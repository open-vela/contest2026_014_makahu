package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import android.database.sqlite.SQLiteException
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.VelaModeProtocol
import com.mocharealm.foundation.fabric.buffer.domain.FocusPlan
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FocusSessionTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    @Test fun endAndFeedbackPersistWithoutCompletingTaskOrStartingNextSession() {
        val work = repository.startFocus("写作业", now = 1000)
        assertEquals("focus", repository.mode())
        repository.finishFocus(work.id, 61_000)
        assertEquals("normal", repository.mode())
        assertEquals("review_pending", repository.focusSession()!!.status)
        repository.reviewFocus(work.id, "partial", "tired", "完成最后两题", 62_000)
        repository.close(); repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("完成最后两题", repository.focusSession()!!.remaining)
        assertEquals("tired", repository.focusSession()!!.experience)
        assertEquals(1, repository.focusHistory().size)
    }
    @Test fun oldReviewDoesNotReplaceNewActiveSession() {
        val first = repository.startFocus("写作业", now = 1000)
        repository.finishFocus(first.id, 2000)
        val next = repository.startFocus("读书", now = 3000)
        repository.reviewFocus(first.id, "partial", "", "两题", 4000)
        assertEquals(next.id, repository.focusSession()!!.id)
        assertEquals("focus", repository.mode())
        assertThrows(IllegalArgumentException::class.java) { repository.finishFocus(first.id, 5000) }
        assertEquals(next.id, repository.focusSession()!!.id)
    }
    @Test fun storageFailureRollsBackFeedbackAndModeTogether() {
        val work = repository.startFocus("写作业", now = 1000)
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_focus BEFORE INSERT ON events WHEN NEW.type = 'ModeChanged' BEGIN SELECT RAISE(ABORT, 'fail'); END")
        assertThrows(SQLiteException::class.java) { repository.finishFocus(work.id, 2000) }
        assertTrue(repository.focusSession()!!.active)
        assertEquals("focus", repository.mode())
    }
    @Test fun velaRetryDoesNotReopenEndedSession() {
        val work = FocusSession("vela-one", "写作业", 1000)
        val bytes = JSONObject().put("type", "buffer.mode.change").put("protocol", 1)
            .put("mode", "focus").put("mode_id", "voice-start").put("focus_session", work.json()).toString().toByteArray()
        VelaModeProtocol.apply(bytes, repository)
        repository.finishFocus(work.id, 2000)
        VelaModeProtocol.apply(bytes, repository)
        assertEquals("normal", repository.mode())
        assertFalse(repository.focusSession()!!.active)
    }
    @Test fun staleOrMismatchedSessionCannotOverwriteActiveWork() {
        val work = repository.startFocus("写作业", now = 2000)
        assertThrows(IllegalArgumentException::class.java) {
            repository.applyVelaFocusMode("normal", "stale", work.copy(status = "review_pending", endedAt = 2000))
        }
        assertTrue(repository.focusSession()!!.active)
        assertThrows(IllegalArgumentException::class.java) {
            repository.applyVelaFocusMode("focus", "other", FocusSession("other", "其他事情", 3000))
        }
        assertEquals(work.id, repository.focusSession()!!.id)
    }
    @Test fun calendarOccurrenceStartsOnceAndOnlyInsideItsWindow() {
        val plan = FocusPlan("7_1000", 7, "写作业", 1000, 61_000, true)
        repository.saveFocusPlan(plan)
        assertThrows(IllegalArgumentException::class.java) { repository.startPlannedFocus(plan.key, 999) }
        val work = repository.startPlannedFocus(plan.key, 1000)
        assertEquals("7", work.calendarEventId)
        assertEquals(1, work.plannedMinutes)
        repository.finishFocus(work.id, 2000)
        assertThrows(IllegalArgumentException::class.java) { repository.startPlannedFocus(plan.key, 3000) }
    }
    @Test fun calendarNeverInterruptsExistingModeAndExpiredPlansNeverStart() {
        repository.saveFocusPlan(FocusPlan("8_1000", 8, "读书", 1000, 2000, true))
        repository.setMode("bedtime")
        assertThrows(IllegalStateException::class.java) { repository.startPlannedFocus("8_1000", 1500) }
        repository.setMode("normal")
        assertThrows(IllegalArgumentException::class.java) { repository.startPlannedFocus("8_1000", 2000) }
        assertEquals("normal", repository.mode())
    }
    @Test fun remainingTaskIsExplicitAndIdempotent() {
        val work = repository.startFocus("写作业", now = 1000)
        repository.finishFocus(work.id, 2000)
        repository.reviewFocus(work.id, "partial", "", "剩下两题", 3000)
        assertEquals(0, repository.cards(100).size)
        val next = repository.remainingFocusTask(work.id)
        assertTrue(next.classificationConfirmed)
        assertEquals("task_card", next.kind)
        assertEquals(next.cardId, repository.remainingFocusTask(work.id).cardId)
        assertEquals(1, repository.cards(100).size)
    }
    @Test fun calendarSessionKeepsTheOriginalTaskAssociation() {
        val work = repository.startFocus("写作业", now = 1000)
        repository.finishFocus(work.id, 2000)
        repository.reviewFocus(work.id, "partial", "", "剩下两题", 3000)
        val task = repository.remainingFocusTask(work.id)
        repository.commitCalendarChange(task.cardId, task.captureId, "9", deleted = false)
        repository.saveFocusPlan(FocusPlan("9_10000", 9, task.title, 10000, 20000, true))
        val before = repository.findCard(task.cardId)!!.state
        assertEquals(task.cardId, repository.startPlannedFocus("9_10000", 10000).cardId)
        assertEquals(before, repository.findCard(task.cardId)!!.state)
    }
    @Test fun protocolRejectsMalformedNumbersAndInvalidReviewedStatus() {
        val raw = FocusSession("one", "写作业", 1000).json()
        assertThrows(IllegalArgumentException::class.java) { FocusSession.parse(JSONObject(raw.toString()).put("started_at", 1.5)) }
        assertThrows(IllegalArgumentException::class.java) { FocusSession.parse(JSONObject(raw.toString()).put("status", "reviewed")) }
        assertThrows(IllegalArgumentException::class.java) { FocusSession.parse(JSONObject(raw.toString()).put("planned_minutes", Long.MAX_VALUE)) }
    }
}
