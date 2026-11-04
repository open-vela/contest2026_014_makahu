package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol as Protocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureReceiver
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaProcessedCaptureTest {
    private lateinit var repo: BufferRepository
    private val capture = "vela-test-001"
    private val cardId get() = "vla_${Protocol.fingerprint(capture).take(32)}_0"
    @Before fun open() { repo = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repo.close() }
    private fun record(state: String = "active", title: String = "设备整理"): String {
        val presentation = JSONObject().put("blocks", JSONArray().put(JSONObject()
            .put("type", "paragraph").put("title", "摘要").put("text", "展示已恢复")))
        val segment = JSONObject().put("card_id", cardId).put("state", state)
            .put("text", "测试记录，请保留原始字节").put("kind", "spark_card").put("title", title)
            .put("summary", "设备生成摘要").put("confidence", 0.9).put("health_category", JSONObject.NULL)
            .put("related_card_ids", JSONArray()).put("presentation", presentation)
        return JSONObject().put("capture_id", capture).put("transcript", "测试记录，请保留原始字节")
            .put("mode", "normal").put("created_at", 1700000000000L).put("state", "ready")
            .put("sync_revision", 1).put("synced_revision", 0)
            .put("segments", JSONArray().put(segment)).toString()
    }
    private fun receiver(authorized: () -> Boolean = { true }) = VelaProcessedCaptureReceiver(repo, "device_verified", authorized)
    private fun receive(raw: String = record()): String = JSONObject(receiver().receiveAuthorized(Protocol.encode(raw)).toString(Charsets.UTF_8)).getString("state")

    @Test fun configurationRetriesKeepIdentityAcrossRestartAndVersionChanges() {
        val device = "a".repeat(64)
        val first = repo.velaConfigurationRequest(device, "agent", "1".repeat(64))
        repo.close(); open()
        assertEquals(first, repo.velaConfigurationRequest(device, "agent", "1".repeat(64)))
        val network = repo.velaConfigurationRequest(device, "network", "2".repeat(64))
        assertEquals(first, repo.velaConfigurationRequest(device, "agent", "1".repeat(64)))
        val skipped = repo.velaConfigurationRequest(device, "skip", "3".repeat(64))
        val restored = repo.velaConfigurationRequest(device, "agent", "1".repeat(64))
        assertTrue(skipped.second > first.second && restored.second > skipped.second)
        assertNotEquals(first.first, restored.first)
        assertEquals(network, repo.velaConfigurationRequest(device, "network", "2".repeat(64)))
    }

    @Test fun importsOncePersistsPresentationAndNeverSchedulesPhoneLlm() {
        assertEquals("imported", receive())
        assertEquals(cardId, repo.findCard(cardId)?.cardId)
        assertTrue(requireNotNull(repo.cardPresentation(cardId)).contains("展示已恢复"))
        assertTrue(repo.pendingClassificationJobs().isEmpty())
        val card = requireNotNull(repo.findCard(cardId))
        repo.editCardContent(card, "用户修改", "保留手机编辑")
        repo.close(); open()
        assertEquals("duplicate", receive())
        assertEquals("用户修改", repo.findCard(cardId)?.title)
        assertTrue(repo.pendingClassificationJobs().isEmpty())
        repo.deleteCards(listOf(cardId))
        repo.close(); open()
        assertEquals("duplicate", receive())
        assertNull(repo.findCard(cardId))
        assertEquals("conflict", receive(record(title = "不能覆盖")))
        assertNull(repo.findCard(cardId))
    }
    @Test fun permissionAndHashFailuresNeverImportOrAck() {
        assertThrows(IllegalStateException::class.java) { receiver { false }.receiveAuthorized(Protocol.encode(record())) }
        val tampered = JSONObject(Protocol.encode(record()).toString(Charsets.UTF_8)).put("record_json", record(title = "篡改"))
        assertThrows(IllegalArgumentException::class.java) { receiver().receiveAuthorized(tampered.toString().toByteArray()) }
        assertNull(repo.findCard(cardId))
        var count = 0
        assertThrows(IllegalStateException::class.java) { receiver { ++count == 1 }.receiveAuthorized(Protocol.encode(record())) }
        assertNull(repo.findCard(cardId))
    }
    @Test fun databaseFailureRollsBackCapturePresentationAndReceipt() {
        repo.writableDatabase.execSQL("CREATE TRIGGER fail_vela BEFORE INSERT ON cards BEGIN SELECT RAISE(ABORT, 'fixture disk failure'); END")
        assertThrows(android.database.SQLException::class.java) { receive() }
        assertNull(repo.findCard(cardId))
        repo.writableDatabase.rawQuery("SELECT count(*) FROM captures WHERE capture_id=?", arrayOf(capture)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        repo.writableDatabase.execSQL("DROP TRIGGER fail_vela")
        assertEquals("imported", receive())
    }
    @Test fun deletionOnDeviceDoesNotCreateVisibleCard() {
        assertEquals("imported", receive(record(state = "deleted")))
        assertNull(repo.findCard(cardId))
        assertEquals("duplicate", receive(record(state = "deleted")))
        assertTrue(repo.pendingClassificationJobs().isEmpty())
    }
    @Test fun boundedPartsPreserveRawBytesAndFinalAckIsAfterCommit() {
        // Whitespace is deliberately significant for the fingerprint.
        val raw = " \n" + record() + "\n "
        val data = raw.toByteArray(Charsets.UTF_8)
        val digest = Protocol.fingerprint(raw)
        val peer = receiver()
        fun part(offset: Int, end: Int) = JSONObject().put("type", "vela.capture.processed.part").put("protocol", 1)
            .put("request_id", digest.take(32)).put("record_sha256", digest).put("offset", offset)
            .put("total", data.size).put("record_base64", java.util.Base64.getEncoder().encodeToString(data.copyOfRange(offset, end)))
            .toString().toByteArray(Charsets.UTF_8)
        val middle = data.size / 2
        val first = part(0, middle)
        val reply = JSONObject(peer.receiveAuthorized(first, 1000).toString(Charsets.UTF_8))
        assertEquals(middle, reply.getInt("next_offset")); assertNull(repo.findCard(cardId))
        assertArrayEquals(peer.receiveAuthorized(first, 1001), reply.toString().toByteArray(Charsets.UTF_8))
        val last = part(middle, data.size)
        assertEquals("imported", JSONObject(peer.receiveAuthorized(last, 1002).toString(Charsets.UTF_8)).getString("state"))
        assertEquals("duplicate", JSONObject(peer.receiveAuthorized(last, 1003).toString(Charsets.UTF_8)).getString("state"))
        assertNotNull(repo.findCard(cardId))
    }
}
