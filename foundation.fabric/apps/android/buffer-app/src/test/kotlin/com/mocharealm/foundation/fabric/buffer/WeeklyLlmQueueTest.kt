package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository

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
class WeeklyLlmQueueTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun result() = JSONObject().put("title", "模型标题").put("summary", "这是测试服务返回的周总结原文。")
    @Test fun requestCreatesNoSummaryAndSurvivesReopen() {
        val now = System.currentTimeMillis()
        val id = repository.generateWeeklySummary(now)
        assertNull(repository.findCard(id))
        assertNull(repository.findCard("$id-llm"))
        repository.close(); open()
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        assertEquals(id, job.id)
        assertTrue(repository.completeWeeklyLlm(job, result(), "test-model"))
        assertEquals(result().getString("summary"), repository.findCard("$id-llm")?.summary)
        assertFalse(repository.completeWeeklyLlm(job, result(), "test-model"))
    }
    @Test fun expiredLeaseRejectsLateResponseAndFailureRemainsRetryable() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val old = requireNotNull(repository.claimWeeklyLlm(now))
        assertNull(repository.claimWeeklyLlm(now + 1000))
        val current = requireNotNull(repository.claimWeeklyLlm(now + 180001))
        assertFalse(repository.completeWeeklyLlm(old, result(), "old"))
        repository.failWeeklyLlm(current, now + 180002)
        assertNull(repository.findCard("${old.id}-llm"))
        assertNull(repository.claimWeeklyLlm(now + 180003))
        repository.generateWeeklySummary(now)
        assertNotNull(repository.claimWeeklyLlm(now + 180004))
    }
    @Test fun resultReceiptFailureRollsBackCardAndKeepsLease() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_weekly BEFORE INSERT ON events WHEN NEW.type = 'WeeklySummaryGenerated' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository.completeWeeklyLlm(job, result(), "test") }
        assertNull(repository.findCard("${job.id}-llm"))
        repository.writableDatabase.execSQL("DROP TRIGGER fail_weekly")
        assertTrue(repository.completeWeeklyLlm(job, result(), "test"))
    }
    @Test fun deletedLlmSummaryIsNotRegeneratedByScheduleOrReplayedResponse() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        repository.completeWeeklyLlm(job, result(), "test")
        repository.deleteCards(listOf("${job.id}-llm"))
        repository.generateWeeklySummary(now)
        assertNull(repository.claimWeeklyLlm(now + 200000))
        assertFalse(repository.completeWeeklyLlm(job, result(), "test"))
        assertNull(repository.findCard("${job.id}-llm"))
    }
}
