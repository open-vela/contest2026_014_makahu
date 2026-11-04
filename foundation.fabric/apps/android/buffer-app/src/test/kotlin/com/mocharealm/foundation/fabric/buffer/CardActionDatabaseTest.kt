package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardActionDatabaseTest {
    private lateinit var first: BufferRepository
    private lateinit var second: BufferRepository
    @Before fun open() {
        first = BufferRepository(RuntimeEnvironment.getApplication())
        second = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { second.close(); first.close() }
    @Test fun duplicateConcurrentActionsCommitOnce() {
        val card = first.createTextCapture("操作去重测试")
        val count = first.eventCount()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val pending = listOf(first, second).map { repo ->
                executor.submit<BufferCard?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    repo.performCardAction(card.cardId, "later", "action-shared")
                }
            }
            start.countDown()
            val results = pending.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals("later", results[0]?.state)
            assertEquals(count + 2, first.eventCount()) // Action plus its immutable document observation.
            first.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='CardActionPerformed' AND json_extract(payload,'$.action_id')='action-shared'", null)
                .use { check(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            assertEquals("later", first.actionResultState("action-shared"))
        } finally { executor.shutdownNow() }
    }
    @Test fun receiptFailureRollsBackActionAndRetrySucceeds() {
        val card = first.createTextCapture("失败重试测试")
        val count = first.eventCount()
        first.writableDatabase.execSQL("""
            CREATE TRIGGER fail_action BEFORE INSERT ON action_receipts
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            first.performCardAction(card.cardId, "later", "action-retry")
        }
        assertEquals(card, first.cardForCapture(requireNotNull(card.captureId)))
        assertEquals(count, first.eventCount())
        assertNull(first.actionResultState("action-retry"))
        first.writableDatabase.execSQL("DROP TRIGGER fail_action")
        assertEquals("later", second.performCardAction(card.cardId, "later", "action-retry")?.state)
    }
    @Test fun operationIdCannotBeReusedForDifferentActionOrCard() {
        val card = first.createTextCapture("第一张")
        val other = first.createTextCapture("第二张")
        first.performCardAction(card.cardId, "later", "action-bound")
        val count = first.eventCount()
        assertThrows(IllegalArgumentException::class.java) {
            second.performCardAction(card.cardId, "archive", "action-bound")
        }
        assertThrows(IllegalArgumentException::class.java) {
            second.performCardAction(other.cardId, "later", "action-bound")
        }
        assertEquals(count, first.eventCount())
        assertEquals(other, first.cardForCapture(requireNotNull(other.captureId)))
    }
    @Test fun oldRetryDoesNotReapplyStateAfterNewerOperation() {
        val card = first.createTextCapture("较新的操作优先")
        first.performCardAction(card.cardId, "later", "old-action")
        first.performCardAction(card.cardId, "archive", "new-action")
        val count = first.eventCount()
        assertEquals("archived", second.performCardAction(card.cardId, "later", "old-action")?.state)
        assertEquals("later", second.actionResultState("old-action"))
        assertEquals(count, first.eventCount())
    }
}
