package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshHistory
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class MeshHistoryTest {
    private lateinit var repo:BufferRepository
    private val local="a".repeat(64)
    private val peer="b".repeat(64)
    @Before fun open(){repo=BufferRepository(RuntimeEnvironment.getApplication());repo.finishSourceRecovery()}
    @After fun close(){repo.close()}
    private fun approve() {
        repo.observeMeshMember(peer,local,repo.meshHello(peer))
        repo.decideMeshMember(peer,repo.meshMembers().single().digest,true,local)
    }
    private fun authorized():Boolean = repo.withApprovedMeshMember(peer,local){true}
    private fun exchange(request:JSONObject)=BufferMeshHistory.exchange(repo,peer,request,::authorized)
    private fun originals():List<BufferSourceEvent> {
        val result=mutableListOf<BufferSourceEvent>();var after=0L
        while(true){val page=repo.sourceEventPage(after,8){true};if(page.isEmpty())return result
            val decoded=page.map(BufferSourceEvent::decode);result+=decoded;after=decoded.last().sequence}
    }
    private fun request(action:String,record:BufferSourceEvent)=JSONObject().put("action",action).put("source_id",record.source).put("sequence",record.sequence)
    @Test fun discoveryRequiresApprovalAndChangedOfferRevokesOldApprovalAndConfiguration() {
        repo.observeMeshMember(peer,local,repo.meshHello(peer))
        assertThrows(SecurityException::class.java){authorized()}
        repo.decideMeshMember(peer,repo.meshMembers().single().digest,true,local)
        assertTrue(authorized());val binding=repo.meshBinding(local)
        val changed=repo.meshHello(peer).put("label","新的设备声明")
        repo.observeMeshMember(peer,local,changed)
        assertEquals("pending",repo.meshMembers().single().state)
        assertThrows(SecurityException::class.java){authorized()}
        assertThrows(IllegalArgumentException::class.java){repo.meshBinding(local,binding.getString("digest"),binding.getString("revision"))}
    }
    @Test fun originalFragmentsReassembleExactUtf8AndReceiptIsIndependentFromSlot() {
        approve();repo.createTextCapture("午饭记录："+"米饭和蔬菜。".repeat(3000))
        val record=originals().first {it.event.getString("type")=="CaptureCreated"}
        val slot=exchange(request("slot",record));assertTrue(slot.getBoolean("visible"))
        assertEquals(record.sequence,repo.sourceEventWithoutReceipt(peer,record.sequence-1){true})
        val bytes=ByteArrayOutputStream();var offset=0
        while(offset<slot.getInt("bytes")) {
            val part=exchange(request("read",record).put("token",slot.getString("token")).put("fingerprint",record.fingerprint).put("offset",offset))
            assertEquals(offset,part.getInt("offset"))
            val chunk=Base64.decode(part.getString("data_b64"),Base64.NO_WRAP)
            assertTrue(chunk.size in 1..16384);bytes.write(chunk);offset+=chunk.size
        }
        assertEquals(record.raw,bytes.toByteArray().toString(Charsets.UTF_8))
        exchange(request("ack",record).put("token",slot.getString("token")).put("fingerprint",record.fingerprint))
        assertNotEquals(record.sequence,repo.sourceEventWithoutReceipt(peer,record.sequence-1){true})
    }
    @Test fun privateApprovalFactsDiscloseOnlyContentFreeReservations() {
        approve()
        val record=originals().first {it.event.getString("type")=="MeshMemberApproved"}
        val slot=exchange(request("slot",record))
        assertFalse(slot.getBoolean("visible"))
        assertFalse(slot.has("event_id"));assertFalse(slot.has("fingerprint"));assertFalse(slot.has("bytes"))
        assertThrows(IllegalArgumentException::class.java){exchange(request("read",record).put("token",slot.getString("token")).put("fingerprint",record.fingerprint).put("offset",0))}
    }
    @Test fun revokedMemberCannotReadNextFragmentOrAcknowledgeOriginal() {
        approve();repo.createTextCapture("今天午饭吃了什么")
        val record=originals().first {it.event.getString("type")=="CaptureCreated"}
        val slot=exchange(request("slot",record))
        repo.decideMeshMember(peer,repo.meshMembers().single().digest,false,local)
        listOf("read","ack").forEach { action->
            val request=request(action,record).put("token",slot.getString("token")).put("fingerprint",record.fingerprint)
            if(action=="read")request.put("offset",0)
            assertThrows(SecurityException::class.java){exchange(request)}
        }
        assertEquals(record.sequence,repo.sourceEventWithoutReceipt(peer,record.sequence-1){true})
    }
    @Test fun forgedApprovalProjectionNeverEstablishesAuthority() {
        repo.observeMeshMember(peer,local,repo.meshHello(peer))
        repo.writableDatabase.execSQL("UPDATE mesh_members SET state='approved',approved_digest=digest,approver=?,approval_event='missing-proof' WHERE peer=?",arrayOf(local,peer))
        assertThrows(IllegalStateException::class.java){authorized()}
    }
}
