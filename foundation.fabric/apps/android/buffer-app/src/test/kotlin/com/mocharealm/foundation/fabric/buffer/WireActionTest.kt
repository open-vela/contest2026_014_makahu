package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WireActionTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun request(card: BufferCard, action: String, id: Any) = JSONObject()
        .put("type", "card.action").put("card_id", card.cardId).put("action", action).put("action_id", id)
    @Test fun retryAckContainsOriginalResultWithoutReapplyingIt() {
        val card = repository.createTextCapture("协议测试")
        val original = request(card, "later", "wire-old")
        val ack = BufferWire.handle(repository, original)
        assertEquals("ack", ack.getString("type"))
        assertEquals("wire-old", ack.getString("action_id"))
        BufferWire.handle(repository, request(card, "archive", "wire-new"))
        val count = repository.eventCount()
        val retry = BufferWire.handle(repository, original)
        assertEquals("ack", retry.getString("type"))
        assertEquals("later", retry.getString("state"))
        assertEquals("archived", repository.cardForCapture(requireNotNull(card.captureId))?.state)
        assertEquals(count, repository.eventCount())
    }
    @Test fun malformedOperationIdsAreRejectedWithoutMutation() {
        val card = repository.createTextCapture("非法 ID 测试")
        val count = repository.eventCount()
        listOf(JSONObject.NULL, 123, true, "", " ", "x".repeat(81)).forEach { id ->
            assertEquals("error", BufferWire.handle(repository, request(card, "later", id)).getString("type"))
            val mode = JSONObject().put("type", "mode.changed").put("mode", "focus").put("mode_id", id)
            assertEquals("error", BufferWire.handle(repository, mode).getString("type"))
        }
        assertEquals(count, repository.eventCount())
        assertEquals("normal", repository.mode())
    }
    @Test fun receiptFailureReturnsErrorAndRetryCanAck() {
        val card = repository.createTextCapture("回执失败")
        val message = request(card, "later", "wire-retry")
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_wire BEFORE INSERT ON action_receipts
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertEquals("error", BufferWire.handle(repository, message).getString("type"))
        assertEquals(card, repository.cardForCapture(requireNotNull(card.captureId)))
        repository.writableDatabase.execSQL("DROP TRIGGER fail_wire")
        assertEquals("ack", BufferWire.handle(repository, message).getString("type"))
    }
    @Test fun reusedIdForAnotherActionDoesNotAck() {
        val card = repository.createTextCapture("操作绑定")
        BufferWire.handle(repository, request(card, "later", "bound"))
        val count = repository.eventCount()
        assertEquals("error", BufferWire.handle(repository, request(card, "archive", "bound")).getString("type"))
        assertEquals(count, repository.eventCount())
    }
}
