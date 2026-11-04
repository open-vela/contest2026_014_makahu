package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshHistory
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuant
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HealthQuantSyncTest {
    // Two actual databases and preference stores; only the transport delivery is
    // in-process. Frames, approvals, originals, reservations and receipts use
    // the production protocol and repository, without a replacement journal.
    private class PeerContext(base:Context,private val prefix:String):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getDatabasePath(name:String):File=baseContext.getDatabasePath(prefix+name)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix+name,mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?):SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix+name,mode,factory,handler)
        override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences(prefix+name,mode)
        override fun getFilesDir():File=File(baseContext.filesDir,prefix).apply {mkdirs()}
    }
    private val aId="a".repeat(64)
    private val bId="b".repeat(64)
    private lateinit var a:BufferRepository
    private lateinit var b:BufferRepository
    private lateinit var bContext:Context
    private val date=LocalDate.now()
    @Before fun open() {
        val base=RuntimeEnvironment.getApplication()
        a=BufferRepository(PeerContext(base,"quant-a-"));bContext=PeerContext(base,"quant-b-");b=BufferRepository(bContext)
        a.finishSourceRecovery();b.finishSourceRecovery()
        a.observeMeshMember(bId,aId,b.meshHello(bId));a.decideMeshMember(bId,a.meshMembers().single().digest,true,aId)
        b.observeMeshMember(aId,bId,a.meshHello(aId));b.decideMeshMember(aId,b.meshMembers().single().digest,true,bId)
        assertTrue(aAuthorized());assertTrue(bAuthorized())
    }
    @After fun close(){a.close();b.close()}
    private fun aAuthorized()=a.withApprovedMeshMember(bId,aId){true}
    private fun bAuthorized()=b.withApprovedMeshMember(aId,bId){true}
    private fun request(action:String,source:String,seq:Long)=JSONObject().put("action",action).put("source_id",source).put("sequence",seq)
    private fun sync(dropHealthAck:Boolean=false,sender:BufferRepository=a,receiver:BufferRepository=b,
                     senderId:String=aId,receiverId:String=bId):String? {
        val sendAuth={sender.withApprovedMeshMember(receiverId,senderId){true}}
        val receiveAuth={receiver.withApprovedMeshMember(senderId,receiverId){true}}
        val manifest=BufferMeshHistory.exchange(sender,receiverId,JSONObject().put("action","manifest"),sendAuth)
        val source=manifest.getString("source_id")
        for(seq in 1..manifest.getLong("head")) {
            val slot=BufferMeshHistory.exchange(sender,receiverId,request("slot",source,seq),sendAuth)
            val token=slot.getString("token")
            receiver.reserveSourceVisibility(senderId,source,seq,token,1,receiveAuth)
            if(!slot.getBoolean("visible"))continue
            val bytes=ByteArrayOutputStream()
            while(bytes.size()<slot.getInt("bytes")) {
                val part=BufferMeshHistory.exchange(sender,receiverId,request("read",source,seq).put("token",token)
                    .put("fingerprint",slot.getString("fingerprint")).put("offset",bytes.size()),sendAuth)
                bytes.write(Base64.decode(part.getString("data_b64"),Base64.NO_WRAP))
            }
            val raw=bytes.toByteArray().toString(Charsets.UTF_8)
            val original=BufferSourceEvent.decode(raw)
            assertEquals(slot.getString("fingerprint"),original.fingerprint)
            assertTrue(receiver.importVisibleSourceEvent(senderId,raw,token,1,receiveAuth).state in setOf("stored","duplicate"))
            if(dropHealthAck && original.event.getString("type")=="HealthQuantRecorded")return raw
            BufferMeshHistory.exchange(sender,receiverId,request("ack",source,seq).put("token",token).put("fingerprint",original.fingerprint),sendAuth)
        }
        receiver.finishSourceRecovery()
        return null
    }
    private fun confirmMeal(longContext:Boolean=true):String {
        val card=a.createTextCapture("我今天午饭吃了200克米饭"+if(longContext)"，保留原始语音上下文".repeat(800)else "")
        a.reviewClassification(requireNotNull(a.findCard(card.cardId)),ClassificationDecision.EDIT,"care_card","nutrition")
        val data=HealthQuant(date.toString(),ZoneId.systemDefault().id,listOf(HealthQuant.Entry("米饭",200.0,"g","estimated","以200克熟米饭估算",
            mapOf("energy_kcal" to 240.0,"protein_g" to 5.0))))
        a.confirmHealthQuant(card.cardId,0,data)
        return card.cardId
    }
    private fun nativeCount()=b.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
    @Test fun confirmedMealCrossesRealHistoryFramesAndRetractionConvergesWithoutExecution() {
        val card=confirmMeal();val before=nativeCount()
        sync()
        val total=b.healthQuantTotals(date,date).single {it.metric=="energy_kcal"}
        assertEquals(240.0,total.estimated!!,0.0)
        assertNull(b.healthQuantRecord(card)) // received confirmation is never local execution authority
        assertFalse(requireNotNull(b.healthQuantView(card)).local)
        assertEquals(0L,b.healthQuantView(card)?.localRevision)
        assertThrows(IllegalStateException::class.java){b.healthQuantForExport(card)}
        assertNull(b.claimHealthQuant());assertEquals(b.readableDatabase.rawQuery("SELECT type FROM events ORDER BY rowid",null).use { rows->buildList {while(rows.moveToNext())add(rows.getString(0))} }.toString(),before,nativeCount())
        assertTrue(b.healthQuantFacts(System.currentTimeMillis()).getJSONArray("source_event_ids").length()>0)
        sync();assertEquals(240.0,b.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
        a.retractHealthQuant(card,1);sync()
        assertTrue(b.healthQuantTotals(date,date).isEmpty());assertEquals(before,nativeCount())
        assertNull(b.claimHealthQuant());assertTrue(b.pendingAudioCaptures().isEmpty())
    }
    @Test fun committedOriginalSurvivesLostAckRestartAndExactDuplicateDelivery() {
        confirmMeal()
        val raw=requireNotNull(sync(dropHealthAck=true));val original=BufferSourceEvent.decode(raw)
        assertEquals(original.sequence,a.sourceEventWithoutReceipt(bId,original.sequence-1,::aAuthorized))
        val before=nativeCount()
        b.close();b=BufferRepository(bContext)
        assertEquals(raw,b.receivedSourceEvent(aId,original.source,original.sequence,::bAuthorized).raw)
        assertEquals(240.0,b.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
        sync()
        assertNotEquals(original.sequence,a.sourceEventWithoutReceipt(bId,original.sequence-1,::aAuthorized))
        assertEquals(before,nativeCount());assertNull(b.claimHealthQuant())
    }
    @Test fun revokedMemberCannotReceiveNewQuantities() {
        confirmMeal()
        b.decideMeshMember(aId,b.meshMembers().single().digest,false,bId)
        assertThrows(SecurityException::class.java){sync()}
        assertTrue(b.healthQuantTotals(date,date).isEmpty());assertNull(b.claimHealthQuant())
    }
    @Test fun explicitEditOnReceivingDeviceConvergesBackToOriginAndRetractionWins() {
        val card=confirmMeal(longContext=false);sync()
        val originalView=requireNotNull(a.healthQuantView(card))
        val received=requireNotNull(b.findCard(card))
        b.reviewClassification(received,ClassificationDecision.EDIT,"care_card","nutrition")
        val corrected=requireNotNull(a.healthQuantRecord(card)).data.copy(entries=listOf(
            HealthQuant.Entry("包装食品",1.0,"份","reported","包装标示",mapOf("energy_kcal" to 180.0))))
        val view=requireNotNull(b.healthQuantView(card))
        b.confirmHealthQuant(card,view.localRevision,corrected,view.eventId)
        sync(sender=b,receiver=a,senderId=bId,receiverId=aId)
        for(repository in listOf(a,b)) {
            val energy=repository.healthQuantTotals(date,date).single {it.metric=="energy_kcal"}
            assertEquals(180.0,energy.reported!!,0.0);assertNull(energy.estimated)
        }
        assertThrows(IllegalArgumentException::class.java){a.confirmHealthQuant(card,originalView.localRevision,originalView.record.data,originalView.eventId)}
        assertFalse(requireNotNull(a.healthQuantView(card)).local)
        assertThrows(IllegalStateException::class.java){a.healthQuantForExport(card)}
        assertNotNull(b.healthQuantForExport(card))
        b.retractHealthQuant(card,1,requireNotNull(b.healthQuantView(card)).eventId)
        sync(sender=b,receiver=a,senderId=bId,receiverId=aId)
        assertTrue(a.healthQuantTotals(date,date).isEmpty());assertTrue(b.healthQuantTotals(date,date).isEmpty())
        assertThrows(IllegalStateException::class.java){a.healthQuantForExport(card)}
        assertThrows(IllegalStateException::class.java){b.healthQuantForExport(card)}
        // Re-sending the older source's history cannot resurrect the value.
        sync();assertTrue(b.healthQuantTotals(date,date).isEmpty())
    }

    @Test fun receivedConfirmedFactCanBeExplicitlyRetractedWithoutBecomingModelAuthority() {
        val card=confirmMeal(longContext=false);sync()
        val view=requireNotNull(b.healthQuantView(card))
        assertEquals(0L,view.localRevision);assertNull(b.claimHealthQuant())
        b.retractHealthQuant(card,view.localRevision,view.eventId)
        sync(sender=b,receiver=a,senderId=bId,receiverId=aId)
        assertTrue(a.healthQuantTotals(date,date).isEmpty());assertTrue(b.healthQuantTotals(date,date).isEmpty())
    }
    @Test fun voiceTranscriptAndQuantitiesReplicateWithoutReopeningSourcePathOrQueuingAsr() {
        val card=a.createAudioCapture("voice-meal","android",System.currentTimeMillis(),"normal","audio/wav",1000,
            "RIFF immutable original".toByteArray(),localOriginal=true)
        a.completeAudioTranscription(requireNotNull(card.captureId),"今天午饭吃了200克米饭")
        a.reviewClassification(requireNotNull(a.findCard(card.cardId)),ClassificationDecision.EDIT,"care_card","nutrition")
        val data=HealthQuant(date.toString(),ZoneId.systemDefault().id,listOf(HealthQuant.Entry("米饭",200.0,"g","estimated","按熟米饭估算",mapOf("energy_kcal" to 240.0))))
        a.confirmHealthQuant(card.cardId,0,data)
        val before=nativeCount();sync()
        val capture=requireNotNull(b.findCapture(requireNotNull(card.captureId)))
        assertEquals("今天午饭吃了200克米饭",capture.transcript)
        assertNull(capture.blobPath);assertTrue(b.pendingAudioCaptures().isEmpty());assertNull(b.claimHealthQuant())
        assertEquals(before,nativeCount())
        assertEquals(240.0,b.healthQuantTotals(date,date).single{it.metric=="energy_kcal"}.estimated!!,0.0)
    }

}
