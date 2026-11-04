package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaCardStateProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
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
class VelaCardStateProtocolTest {
    private lateinit var repo: BufferRepository
    private val capture = "state-capture-001"
    private val card get() = "vla_${VelaProcessedCaptureProtocol.fingerprint(capture).take(32)}_0"
    private val device = "device_verified"

    @Before fun open() { repo = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repo.close() }

    private fun importInitial() {
        val segment = JSONObject().put("card_id", card).put("state", "active")
            .put("text", "immutable test record").put("kind", "spark_card")
            .put("title", "Card title").put("summary", "Card summary").put("confidence", 0.9)
            .put("health_category", JSONObject.NULL).put("related_card_ids", JSONArray())
        val raw = JSONObject().put("capture_id", capture).put("transcript", "immutable test record")
            .put("mode", "normal").put("created_at", 1_700_000_000_000L).put("state", "ready")
            .put("segments", JSONArray().put(segment)).toString()
        val receiver = VelaProcessedCaptureReceiver(repo, device) { true }
        assertEquals("imported", JSONObject(receiver.receiveAuthorized(
            VelaProcessedCaptureProtocol.encode(raw)).toString(Charsets.UTF_8)).getString("state"))
    }

    private fun change(revision: Int, previous: String, state: String, reviewAt: Long = 0L): ByteArray =
        JSONObject().put("type", "buffer.card.state.change").put("protocol", 1)
            .put("capture_id", capture).put("card_id", card).put("revision", revision)
            .put("action_id", "${card}_r$revision").put("previous_state", previous)
            .put("state", state).put("deleted", state == "deleted").put("review_at", reviewAt)
            .toString().toByteArray(Charsets.UTF_8)

    private fun apply(bytes: ByteArray): String = JSONObject(
        VelaCardStateProtocol.apply(bytes, device, repo).toString(Charsets.UTF_8),
    ).getString("state")

    @Test fun persistedReceiptsApplyChangesAndDeduplicateExactRetries() {
        importInitial()
        val archive = change(1, "active", "archived")
        assertEquals("applied", apply(archive))
        assertEquals("archived", repo.findCard(card)?.state)
        repo.close(); open()
        assertEquals("duplicate", apply(archive))
        assertEquals("archived", repo.findCard(card)?.state)
        assertEquals("conflict", apply(change(1, "active", "done")))
        assertEquals("archived", repo.findCard(card)?.state)
    }

    @Test fun legacyReceiptMigrationChecksCurrentPhoneStateBeforeApplying() {
        importInitial()
        repo.writableDatabase.delete("vela_card_state_receipts", "capture_id = ?", arrayOf(capture))
        assertEquals("applied", apply(change(1, "active", "done")))
        assertEquals("done", repo.findCard(card)?.state)

        val secondCapture = "state-capture-002"
        val secondCard = "vla_${VelaProcessedCaptureProtocol.fingerprint(secondCapture).take(32)}_0"
        val segment = JSONObject().put("card_id", secondCard).put("state", "active")
            .put("text", "local edit guard").put("kind", "spark_card").put("title", "Guard")
            .put("summary", "Guard").put("confidence", 0.9).put("health_category", JSONObject.NULL)
            .put("related_card_ids", JSONArray())
        val raw = JSONObject().put("capture_id", secondCapture).put("transcript", "local edit guard")
            .put("mode", "normal").put("created_at", 1_700_000_000_001L).put("state", "ready")
            .put("segments", JSONArray().put(segment)).toString()
        VelaProcessedCaptureReceiver(repo, device) { true }
            .receiveAuthorized(VelaProcessedCaptureProtocol.encode(raw))
        repo.writableDatabase.execSQL("UPDATE cards SET state = 'done' WHERE card_id = ?", arrayOf(secondCard))
        val conflicting = JSONObject(change(1, "active", "archived").toString(Charsets.UTF_8))
            .put("capture_id", secondCapture).put("card_id", secondCard)
            .put("action_id", "${secondCard}_r1").toString().toByteArray(Charsets.UTF_8)
        assertEquals("conflict", apply(conflicting))
        assertEquals("done", repo.findCard(secondCard)?.state)
    }

    @Test fun laterAndDeleteUpdateProjectionWithoutDeletingCardData() {
        importInitial()
        val due = System.currentTimeMillis() + 86_400_000L
        assertEquals("applied", apply(change(1, "active", "later", due)))
        assertEquals("later", repo.findCard(card)?.state)
        assertEquals("applied", apply(change(2, "later", "deleted")))
        assertEquals("deleted", repo.findCard(card)?.state)
        assertTrue(repo.cards(100).none { it.cardId == card })
        assertEquals("deleted", JSONObject(requireNotNull(repo.velaReplicaPage("").first()))
            .let { if (it.getString("card_id") == card) "deleted" else "missing" })
        assertEquals("conflict", apply(change(3, "deleted", "active")))
        assertEquals("deleted", repo.findCard(card)?.state)
    }

    @Test fun malformedPayloadsAndDatabaseFailuresNeverReturnApplied() {
        importInitial()
        val valid = change(1, "active", "archived")
        val extra = JSONObject(valid.toString(Charsets.UTF_8)).put("unexpected", true).toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { apply(extra) }
        repo.writableDatabase.execSQL("CREATE TRIGGER fail_vela_state BEFORE INSERT ON vela_card_state_receipts " +
            "WHEN NEW.revision > 0 BEGIN SELECT RAISE(ABORT, 'fixture disk failure'); END")
        assertThrows(android.database.SQLException::class.java) { apply(valid) }
        assertTrue(repo.findCard(card)?.state != "archived")
        repo.writableDatabase.execSQL("DROP TRIGGER fail_vela_state")
        assertEquals("applied", apply(valid))
    }

    @Test fun staleRevisionHasDurableStaleReceipt() {
        importInitial()
        assertEquals("applied", apply(change(2, "active", "done")))
        val old = change(1, "active", "archived")
        assertEquals("stale", apply(old))
        assertEquals("stale", apply(old))
        assertEquals("done", repo.findCard(card)?.state)
    }
}
