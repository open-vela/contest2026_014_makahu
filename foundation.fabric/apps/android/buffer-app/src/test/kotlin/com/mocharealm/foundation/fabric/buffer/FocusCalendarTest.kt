package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.FocusPlan
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FocusCalendarTest {
    private lateinit var repo: BufferRepository
    private lateinit var calendar: BufferFocusCalendar
    private lateinit var provider: CalendarProvider
    private val plan = FocusPlan("7_1000", 7, "写作业", 1000, 61_000, true)
    @Before fun open() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        repo = BufferRepository(app)
        calendar = BufferFocusCalendar(app, repo)
        provider = CalendarProvider().also { it.rows = listOf(plan) }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        repo.saveFocusPlan(plan)
    }
    @After fun close() { calendar.cancelAlarm(); repo.close() }
    @Test fun automaticOccurrenceStartsAndEndsWithPendingReview() {
        calendar.reconcile(1000)
        assertEquals("focus", repo.mode())
        assertEquals("写作业", repo.focusSession()!!.title)
        calendar.reconcile(61_000)
        assertEquals("normal", repo.mode())
        assertEquals("review_pending", repo.focusSession()!!.status)
        assertEquals(61_000L, repo.focusSession()!!.endedAt)
        assertEquals("started", repo.focusPlans().single().state)
    }
    @Test fun deletedOrMovedCalendarOccurrenceDoesNotStart() {
        provider.rows = listOf(plan.copy(begin = 2000))
        calendar.reconcile(1000)
        assertEquals("normal", repo.mode())
        assertNull(repo.focusSession())
        assertEquals("cancelled", repo.focusPlans().single().state)
    }
    @Test fun reminderPolicyDoesNotAutomaticallyStartWork() {
        repo.saveFocusPlan(plan.copy(automatic = false))
        calendar.reconcile(1000)
        assertEquals("normal", repo.mode())
        assertNull(repo.focusSession())
        assertTrue(repo.focusPlans().single().state in setOf("scheduled", "offered"))
    }
    @Test fun revokedCalendarPermissionPreservesPlanForRetry() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertThrows(IllegalStateException::class.java) { calendar.reconcile(1000) }
        assertEquals("scheduled", repo.focusPlans().single().state)
        assertEquals("normal", repo.mode())
    }
    class CalendarProvider : ContentProvider() {
        internal var rows = emptyList<FocusPlan>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
            MatrixCursor(projection).apply { rows.forEach { addRow(arrayOf<Any>(it.eventId, it.title, it.begin, it.end)) } }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
