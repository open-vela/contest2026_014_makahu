package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SourceJournalTest {
    private lateinit var repo:BufferRepository
    private val peer="b".repeat(64)
    @Before fun open(){repo=BufferRepository(RuntimeEnvironment.getApplication());repo.finishSourceRecovery()}
    @After fun close(){repo.close()}
    private fun record(source:String="foreign",sequence:Long=1,id:String="event1",clock:Long=10,parents:List<String> = emptyList(),
        type:String="ModeChanged",capture:String?=null,payload:JSONObject=JSONObject().put("mode","focus")):BufferSourceEvent =
        BufferSourceEvent.encode(source,sequence,JSONObject().put("event_id",id).put("app_namespace","app.buffer").put("actor_id","user")
            .put("schema_version",1).put("parent_event_ids",JSONArray(parents)).put("logical_clock",clock).put("type",type)
            .put("capture_id",capture ?: JSONObject.NULL).put("created_at",1000).put("payload",payload))
    private fun nativeCount()=repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {it.moveToFirst();it.getLong(0)}
    @Test fun originalReceiptsSurviveReopenAndPinExactAuthenticatedOwner() {
        val raw=record().raw
        assertEquals("stored",repo.importSourceEvent(peer,raw){true}.state)
        assertEquals("duplicate",repo.importSourceEvent(peer,raw){true}.state)
        repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(2L,repo.sourceEventResume(peer,"foreign"){true})
        assertEquals(raw,repo.receivedSourceEvent(peer,"foreign",1){true}.raw)
        assertThrows(IllegalArgumentException::class.java){repo.importSourceEvent("c".repeat(64),raw){true}}
        assertEquals("conflict",repo.importSourceEvent(peer,record(payload=JSONObject().put("mode","bedtime")).raw){true}.state)
        assertEquals("gap",repo.importSourceEvent(peer,record(sequence=3,id="gap",clock=12).raw){true}.state)
    }
    @Test fun authorizationRevokedBeforeCommitRollsBackOriginalAndProgress() {
        var calls=0
        assertThrows(IllegalStateException::class.java){repo.importSourceEvent(peer,record().raw){++calls<3}}
        assertEquals(1L,repo.sourceEventResume(peer,"foreign"){true})
        assertEquals("stored",repo.importSourceEvent(peer,record().raw){true}.state)
        assertThrows(IllegalStateException::class.java){repo.receivedSourceEvent(peer,"foreign",1){false}}
    }
    @Test fun insertionFailureRollsBackReceiveCursorAndCanRetrySameBytes() {
        repo.writableDatabase.execSQL("CREATE TRIGGER fail_source BEFORE INSERT ON source_events WHEN NEW.source_id='foreign' BEGIN SELECT RAISE(ABORT,'injected');END")
        assertThrows(android.database.sqlite.SQLiteException::class.java){repo.importSourceEvent(peer,record().raw){true}}
        assertEquals(1L,repo.sourceEventResume(peer,"foreign"){true})
        repo.writableDatabase.execSQL("DROP TRIGGER fail_source")
        assertEquals("stored",repo.importSourceEvent(peer,record().raw){true}.state)
    }
    @Test fun reservationsDoNotConfirmOriginalsAndSparseFillChecksTokens() {
        val t1="1".repeat(32);val t2="2".repeat(32)
        repo.reserveSourceVisibility(peer,"visible",1,t1,1){true}
        val covered=repo.reserveSourceVisibility(peer,"visible",2,t2,1){true}.progress
        assertEquals(3L,covered.coveredNext);assertEquals(1L,covered.originalNext)
        val second=record("visible",2,"second",20,listOf("first"))
        assertEquals("stored",repo.importVisibleSourceEvent(peer,second.raw,t2,1){true}.state)
        assertEquals(1L,repo.sourceVisibilityProgress(peer,"visible",1){true}.originalNext)
        val first=record("visible",1,"first",19)
        assertEquals("conflict",repo.importVisibleSourceEvent(peer,first.raw,"3".repeat(32),1){true}.state)
        assertEquals("stored",repo.importVisibleSourceEvent(peer,first.raw,t1,1){true}.state)
        assertEquals(3L,repo.sourceVisibilityProgress(peer,"visible",1){true}.originalNext)
        assertEquals(first.raw,repo.receivedSourceEvent(peer,"visible",1){true}.raw)
    }
    @Test fun lateParentReevaluatesChildWithoutExecutingHistoricalModeOrCreatingNewFacts() {
        val before=nativeCount()
        val child=record(parents=listOf("parent"))
        repo.importSourceEvent(peer,child.raw){true};repo.finishSourceRecovery()
        assertEquals("missing_parent",repo.sourceJournal().entries.single {it.eventId==child.eventId}.state)
        repo.importSourceEvent(peer,record("parent-source",1,"parent",5).raw){true};repo.finishSourceRecovery()
        assertEquals("linked",repo.sourceJournal().entries.single {it.eventId==child.eventId}.state)
        assertEquals("normal",repo.mode());assertEquals(before,nativeCount())
        assertNull(repo.claimHealthQuant());assertTrue(repo.pendingAudioCaptures().isEmpty())
    }
    @Test fun originalReceiptDoesNotImplyAudioReceiptAndCorruptAudioRequiresRedelivery() {
        val bytes="RIFF immutable audio bytes".toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val raw=record(type="CaptureCreated",capture="voice1",payload=JSONObject().put("blob",JSONObject()
            .put("sha256",hash).put("bytes",bytes.size).put("content_type","audio/wav").put("created_at",1000)
            .put("duration_ms",100).put("mode","normal"))).raw
        repo.importSourceEvent(peer,raw){true};repo.finishSourceRecovery()
        val candidate=requireNotNull(repo.nextSourceBlob(peer,System.currentTimeMillis(),null){true})
        assertEquals(2L,repo.sourceEventResume(peer,"foreign"){true})
        assertFalse(repo.resumeSourceBlob(peer,candidate.transfer){true})
        assertThrows(IllegalArgumentException::class.java){repo.storeSourceBlob(peer,candidate.transfer,byteArrayOf(9)){true}}
        repo.storeSourceBlob(peer,candidate.transfer,bytes){true};repo.confirmSourceBlob(peer,candidate.transfer){true}
        repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        assertTrue(repo.resumeSourceBlob(peer,candidate.transfer){true})
        val archive=File(RuntimeEnvironment.getApplication().filesDir,"source-blobs/$hash.wav")
        assertTrue(archive.isFile);archive.writeBytes(byteArrayOf(1))
        assertFalse(repo.resumeSourceBlob(peer,candidate.transfer){true})
        assertEquals(raw,repo.receivedSourceEvent(peer,"foreign",1){true}.raw)
        assertTrue(repo.pendingAudioCaptures().isEmpty())
    }
    @Test fun corruptedOriginalFailsClosedRatherThanReconstructingBytesFromProjection() {
        val event=record();repo.importSourceEvent(peer,event.raw){true}
        repo.writableDatabase.execSQL("UPDATE source_events SET envelope=? WHERE event_id=?",arrayOf(event.raw.replace("focus","bedtime"),event.eventId))
        assertThrows(IllegalStateException::class.java){repo.receivedSourceEvent(peer,"foreign",1){true}}
    }
}
