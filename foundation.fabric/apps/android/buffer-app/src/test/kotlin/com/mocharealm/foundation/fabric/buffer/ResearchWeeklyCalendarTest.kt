package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ResearchWeeklyCalendarTest {
    private lateinit var repo: BufferRepository
    private lateinit var provider: CalendarProvider
    private lateinit var adapter: BufferResearchCalendar
    private lateinit var card: String
    @Before fun open(){
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        repo=BufferRepository(app);card=repo.createTextCapture("探索一个问题").cardId
        provider=CalendarProvider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        adapter=BufferResearchCalendar(app,repo)
    }
    @After fun close(){repo.close()}
    private fun plan()=repo.saveResearchWeeklyPlan(card,7,"14:00",90,"Asia/Shanghai",7)
    @Test fun writesOneRecurringCalendarEventAndCancellationDeletesIt() {
        val p=plan();adapter.reconcile();adapter.reconcile()
        assertEquals(1,provider.rows.size);assertEquals("synced",repo.researchWeeklyPlans().single().calendarState)
        val event=provider.rows.values.single()
        assertEquals("FREQ=WEEKLY;BYDAY=SU",event.getAsString(CalendarContract.Events.RRULE))
        assertEquals("PT90M",event.getAsString(CalendarContract.Events.DURATION))
        assertEquals("Asia/Shanghai",event.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        repo.cancelResearchWeeklyPlan(p.id);adapter.reconcile();assertTrue(provider.rows.isEmpty())
        assertEquals("deleted",repo.researchWeeklyPlans().single().calendarState)
    }
    @Test fun lostLocalReceiptReusesMarkerAndDoesNotInsertAgain() {
        plan();repo.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON events WHEN NEW.type='ResearchCalendarResult' BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java){adapter.reconcile()}
        assertEquals(1,provider.rows.size)
        repo.writableDatabase.execSQL("DROP TRIGGER fail_receipt");repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        adapter=BufferResearchCalendar(RuntimeEnvironment.getApplication(),repo);adapter.reconcile()
        assertEquals(1,provider.rows.size);assertEquals("synced",repo.researchWeeklyPlans().single().calendarState)
    }
    @Test fun permissionRevocationPreservesCancellationForLaterRetry() {
        val p=plan();adapter.reconcile();repo.cancelResearchWeeklyPlan(p.id)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        adapter.reconcile();assertEquals(1,provider.rows.size);assertFalse(repo.researchWeeklyPlans().single().enabled)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        adapter.reconcile();assertTrue(provider.rows.isEmpty())
    }
    @Test fun externalCalendarEditsRequireFreshConfirmationRatherThanBeingOverwritten() {
        val p=plan();adapter.reconcile();provider.rows.values.single().put(CalendarContract.Events.TITLE,"我自己修改了主题")
        repeat(2){adapter.reconcile()}
        assertEquals("我自己修改了主题",provider.rows.values.single().getAsString(CalendarContract.Events.TITLE))
        assertEquals("failed",repo.researchWeeklyPlans().single().calendarState)
        repo.saveResearchWeeklyPlan(card,p.weekday,p.time,p.minutes,p.zone,p.calendarId,p.id);adapter.reconcile()
        assertEquals("Buffer 研究：${p.title}",provider.rows.values.single().getAsString(CalendarContract.Events.TITLE))
    }
    @Test fun forgedPlanNeverCallsCalendarProvider() {
        val p=plan();repo.writableDatabase.execSQL("UPDATE research_weekly_plans SET body=? WHERE id=?",arrayOf(p.copy(calendarId=9).json().toString(),p.id))
        adapter.reconcile();assertTrue(provider.rows.isEmpty())
    }
    @Test fun externallyDeletedAppointmentIsNotRecreatedWithoutFreshConfirmation() {
        val p=plan();adapter.reconcile();provider.rows.clear()
        repeat(2){adapter.reconcile()}
        assertTrue(provider.rows.isEmpty())
        assertEquals("failed",repo.researchWeeklyPlans().single().calendarState)
        repo.saveResearchWeeklyPlan(card,p.weekday,p.time,p.minutes,p.zone,p.calendarId,p.id)
        adapter.reconcile()
        assertEquals(1,provider.rows.size)
        assertEquals("synced",repo.researchWeeklyPlans().single().calendarState)
    }
    @Test fun declinedDeletionRetainsRetryUntilProviderActuallyRemovesEvent() {
        val p=plan();adapter.reconcile();repo.cancelResearchWeeklyPlan(p.id)
        provider.declineDeletion=true;adapter.reconcile()
        assertEquals(1,provider.rows.size)
        assertEquals("failed",repo.researchWeeklyPlans().single().calendarState)
        repo.reconcileResearchWeekly(System.currentTimeMillis(),{false},{})
        assertEquals(1,repo.researchWeeklyPlans().size)
        provider.declineDeletion=false;adapter.reconcile()
        assertTrue(provider.rows.isEmpty())
        assertEquals("deleted",repo.researchWeeklyPlans().single().calendarState)
    }
    class CalendarProvider: ContentProvider() {
        var declineDeletion=false
        val rows=linkedMapOf<Long,ContentValues>();private var next=10L
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
            val columns=requireNotNull(projection)
            val selected=uri.lastPathSegment?.toLongOrNull()?.let { id -> rows.filterKeys {it==id} }
                ?: rows.filter { (_,row)->args==null || (row.getAsString(CalendarContract.Events.CUSTOM_APP_URI)==args[0] && row.getAsString(CalendarContract.Events.CALENDAR_ID)==args[1]) }
            return MatrixCursor(columns).apply {selected.forEach { (id,row)->addRow(columns.map { if(it==CalendarContract.Events._ID)id else row[it] }.toTypedArray())}}
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri {val id=next++;rows[id]=ContentValues(requireNotNull(values));return ContentUris.withAppendedId(uri,id)}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
            val row=rows[ContentUris.parseId(uri)] ?: return 0;row.putAll(requireNotNull(values));return 1
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=if(!declineDeletion && rows.remove(ContentUris.parseId(uri))!=null)1 else 0
    }
}
