package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TranscriptionDatabaseTest {
    private lateinit var manual: BufferRepository
    private lateinit var background: BufferRepository
    @Before fun open() {
        manual = BufferRepository(RuntimeEnvironment.getApplication())
        background = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { background.close(); manual.close() }
    private fun audio(id: String) = manual.createAudioCapture(id, "test", 1000, "normal", "audio/wav", 1000, byteArrayOf(1, 2))
    private fun transcriptionCount(id: String) = manual.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE type = 'CaptureTranscribed' AND capture_id = ?", arrayOf(id),
    ).use { it.moveToFirst(); it.getInt(0) }

    @Test fun concurrentResultsCommitOnlyOneTranscript() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { round ->
                val id = "audio-$round"
                audio(id)
                val start = CountDownLatch(1)
                val results = listOf(manual to "手动文本", background to "后台文本").map { (repo, text) ->
                    executor.submit<BufferCard> {
                        check(start.await(5, TimeUnit.SECONDS))
                        repo.completeAudioTranscription(id, text)
                    }
                }
                start.countDown()
                val cards = results.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(cards[0], cards[1])
                assertEquals(1, transcriptionCount(id))
                assertEquals(1, manual.cards(100).count { it.captureId == id })
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun failedCommitRollsBackTranscriptAndAllowsRetry() {
        val card = audio("audio-failure")
        manual.writableDatabase.execSQL("""
            CREATE TRIGGER fail_proposal BEFORE INSERT ON events
            WHEN NEW.type = 'ClassificationProposed'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            manual.completeAudioTranscription("audio-failure", "待办测试")
        }
        assertEquals(0, transcriptionCount("audio-failure"))
        assertEquals(card, manual.cardForCapture("audio-failure"))
        assertEquals(1, manual.pendingAudioCaptures().size)
        manual.writableDatabase.execSQL("DROP TRIGGER fail_proposal")
        manual.completeAudioTranscription("audio-failure", "重试文本")
        assertEquals(1, transcriptionCount("audio-failure"))
        assertTrue(manual.pendingAudioCaptures().isEmpty())
    }
    @Test fun lateTranscriptionDoesNotReplaceReviewedCard() {
        audio("audio-reviewed")
        val transcribed = manual.completeAudioTranscription("audio-reviewed", "原始文本")
        val reviewed = manual.reviewClassification(transcribed, ClassificationDecision.EDIT, "task_card")
        assertEquals(reviewed, background.completeAudioTranscription("audio-reviewed", "迟到文本"))
        assertEquals(1, transcriptionCount("audio-reviewed"))
    }
}
