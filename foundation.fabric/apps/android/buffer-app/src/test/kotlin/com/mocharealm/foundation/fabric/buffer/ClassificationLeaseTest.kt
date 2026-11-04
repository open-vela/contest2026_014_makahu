package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClassificationLeaseTest {
    private lateinit var first: BufferRepository
    private lateinit var second: BufferRepository
    @Before fun open() {
        first = BufferRepository(RuntimeEnvironment.getApplication())
        second = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { second.close(); first.close() }
    @Test fun twoRepositoriesCanOnlyClaimOnce() {
        val card = first.createTextCapture("一次领取")
        val id = requireNotNull(card.captureId)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(first, second).map { repo -> executor.submit<String?> { repo.claimClassificationRequest(id) } }
            val leases = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, leases.count { it != null })
        } finally { executor.shutdownNow() }
    }
    @Test fun expiredRequestCannotOverwriteNewLeaseOrResult() {
        val card = first.createTextCapture("旧请求隔离")
        val id = requireNotNull(card.captureId)
        val old = requireNotNull(first.claimClassificationRequest(id))
        first.writableDatabase.execSQL("UPDATE classification_jobs SET updated_at = 0 WHERE capture_id = ?", arrayOf(id))
        val fresh = requireNotNull(second.claimClassificationRequest(id))
        assertNotEquals(old, fresh)
        val count = first.eventCount()
        first.markClassificationRequestFailed(id, old, "old failure")
        val proposal = BufferClassificationProposal("文本", "question_card", "新结果", "摘要", null, 0.9)
        first.applyRemoteClassification(id, listOf(proposal.copy(title = "过期结果")), old)
        assertEquals(count, first.eventCount())
        assertEquals(card, first.cardForCapture(id))
        second.applyRemoteClassification(id, listOf(proposal), fresh)
        val complete = first.eventCount()
        first.markClassificationRequestFailed(id, old, "late failure")
        first.applyRemoteClassification(id, listOf(proposal.copy(title = "重复结果")), fresh)
        assertEquals("新结果", first.cardForCapture(id)?.title)
        assertEquals(complete, first.eventCount())
    }
    @Test fun failedClaimReceiptRollsBackLease() {
        val id = requireNotNull(first.createTextCapture("领取回滚").captureId)
        first.writableDatabase.execSQL("""
            CREATE TRIGGER fail_claim BEFORE INSERT ON events WHEN NEW.type = 'ClassificationRequested'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) { first.claimClassificationRequest(id) }
        first.writableDatabase.execSQL("DROP TRIGGER fail_claim")
        assertNotNull(second.claimClassificationRequest(id))
    }
}
