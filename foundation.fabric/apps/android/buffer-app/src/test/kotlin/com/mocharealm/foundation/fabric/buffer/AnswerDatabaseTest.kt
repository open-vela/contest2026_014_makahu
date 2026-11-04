package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AnswerDatabaseTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun question(): BufferCard {
        val card = repository.createTextCapture("问题测试")
        return requireNotNull(repository.reviewClassification(card, ClassificationDecision.EDIT, "question_card"))
    }
    @Test fun lateRemoteAnswerCannotReplaceManualAnswer() {
        val snapshot = question()
        val manual = repository.saveQuestionAnswer(snapshot.cardId, "手动答案")
        val count = repository.eventCount()
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveQuestionAnswer(snapshot.cardId, "迟到答案", "remote", snapshot)
        }
        assertEquals(manual, repository.cardForCapture(requireNotNull(snapshot.captureId)))
        assertEquals(count, repository.eventCount())
    }
    @Test fun lateAnswerCannotUndoArchive() {
        val snapshot = question()
        val archived = repository.performCardAction(snapshot.cardId, "archive")
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveQuestionAnswer(snapshot.cardId, "迟到答案", "remote", snapshot)
        }
        assertEquals(archived, repository.cardForCapture(requireNotNull(snapshot.captureId)))
    }
    @Test fun unchangedSnapshotAcceptsAnswerButDuplicateResultDoesNotOverwrite() {
        val snapshot = question()
        assertEquals("答案", repository.saveQuestionAnswer(snapshot.cardId, "答案", "remote", snapshot)?.answer)
        val count = repository.eventCount()
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveQuestionAnswer(snapshot.cardId, "另一个答案", "remote", snapshot)
        }
        assertEquals(count, repository.eventCount())
    }
    @Test fun failedAnswerEventRollsBackCard() {
        val snapshot = question()
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_answer BEFORE INSERT ON events WHEN NEW.type = 'QuestionAnswered'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            repository.saveQuestionAnswer(snapshot.cardId, "答案", "remote", snapshot)
        }
        assertEquals(snapshot, repository.cardForCapture(requireNotNull(snapshot.captureId)))
        repository.writableDatabase.execSQL("DROP TRIGGER fail_answer")
        assertEquals("答案", repository.saveQuestionAnswer(snapshot.cardId, "答案", "remote", snapshot)?.answer)
    }
}
