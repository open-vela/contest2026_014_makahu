package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarProviderSeriesTest {
    class Provider : ContentProvider() {
        var events = listOf<Map<String, Any?>>()
        var fail = false
        var instanceQueries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
            if (fail) return null
            requireNotNull(projection)
            val rows = if (uri.path == "/calendars") listOf(
                mapOf("_id" to 1L, "calendar_displayName" to "课程表"),
                mapOf("_id" to 2L, "calendar_displayName" to "铍铜日历"),
            ) else { if (uri.path?.startsWith("/instances") == true) instanceQueries++; events }
            return MatrixCursor(projection).apply { rows.forEach { row -> addRow(projection.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("read-only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("read-only")
    }
    private fun at(date: String) = LocalDateTime.parse(date).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    @Test fun pointEventsAndPreEpochSeriesSurviveProviderImport() {
        val app = RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Provider();provider.attachInfo(app, null)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val oldZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val repository=BufferRepository(app)
        try {
            val start=at("2026-11-04T00:00:00");val end=at("2026-11-05T00:00:00")
            fun point(id: Long, time: Long, allDay: Int=0)=mapOf<String,Any?>(
                "_id" to id,"calendar_id" to 1L,"title" to "Point $id","dtstart" to time,
                "dtend" to null,"duration" to null,"allDay" to allDay,"eventTimezone" to "Asia/Shanghai","eventStatus" to 1)
            provider.events=listOf(point(1,start),point(2,start+3600000),point(3,end),
                point(4,LocalDate.parse("2026-11-04").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),1),
                point(5,at("1964-11-04T09:00:00")) + mapOf("rrule" to "FREQ=YEARLY","duration" to "P3600S"))
            assertTrue(BufferCalendarStore.refresh(app,repository,start,end))
            val entries=repository.calendarEntries(start,end)
            assertEquals(setOf("Point 1","Point 2","Point 4","Point 5"),entries.map{it.title}.toSet())
            assertTrue(entries.filter{it.title!="Point 5"}.all{it.start==it.end})
            assertEquals(3_600_000L,entries.single{it.title=="Point 5"}.let{it.end-it.start})
            assertEquals(5,repository.calendarDocumentIds().size)
            assertEquals(0,provider.instanceQueries)
        } finally {repository.close();TimeZone.setDefault(oldZone)}
    }
    @Test fun allCalendarsMastersAndMovedOrCancelledExceptionsAreOneAtomicSnapshot() {
        val app = RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Provider();provider.attachInfo(app, null)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val oldZone = TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val repository = BufferRepository(app)
        try {
            fun event(id: Long, calendar: Long, title: String, time: String) = mutableMapOf<String, Any?>(
                "_id" to id, "calendar_id" to calendar, "title" to title, "dtstart" to at(time),
                "dtend" to at(time) + 3600000, "allDay" to 0, "eventTimezone" to "Asia/Shanghai", "eventStatus" to 1,
            )
            val master = event(10, 1, "每周课程", "2026-11-02T09:00:00").apply {
                this["dtend"] = null;this["duration"] = "P5700S";this["rrule"] = "FREQ=WEEKLY;COUNT=20"
            }
            val cancelled = event(11, 1, "取消课程", "2026-11-09T09:00:00").apply {
                this["original_id"] = 10L;this["originalInstanceTime"] = at("2026-11-09T09:00:00");this["eventStatus"] = 2
            }
            val moved = event(12, 1, "改期课程", "2026-11-17T14:00:00").apply {
                this["original_id"] = 10L;this["originalInstanceTime"] = at("2026-11-16T09:00:00")
            }
            val ordinary = event(20, 2, "普通事项", "2026-11-02T13:00:00")
            provider.events = listOf(master, cancelled, moved, ordinary)
            val start = at("2026-11-01T00:00:00");val end = at("2026-12-01T00:00:00")
            assertTrue(BufferCalendarStore.refresh(app, repository, start, end))
            assertEquals(0, provider.instanceQueries)
            assertEquals(2, repository.calendarDocumentIds().size)
            val entries = repository.calendarEntries(start, end)
            assertEquals(listOf("每周课程", "普通事项", "改期课程", "每周课程", "每周课程"), entries.map { it.title })
            assertEquals(5_700_000L,entries.first().end-entries.first().start)
            assertEquals("课程表",entries.first().calendarName)
            assertEquals("铍铜日历",entries.single{it.title=="普通事项"}.calendarName)
            assertTrue(entries.filter{it.title!="普通事项"}.all{it.calendarName=="课程表"})
            val records = repository.velaReplicaPage("", 16).map(::JSONObject)
            assertEquals(setOf("课程表", "铍铜日历"), records.map { it.getJSONObject("card").getString("calendar_name") }.toSet())
            val before = records.map { it.toString() }
            master["duration"]="invalid-duration"
            assertThrows(Exception::class.java) { BufferCalendarStore.refresh(app, repository, start, end) }
            assertEquals(before, repository.velaReplicaPage("", 16).map(::JSONObject).map { it.toString() })
            master["duration"]="P5700S"
            provider.fail = true
            assertThrows(Exception::class.java) { BufferCalendarStore.refresh(app, repository, start, end) }
            assertEquals(before, repository.velaReplicaPage("", 16).map(::JSONObject).map { it.toString() })
            provider.fail = false;provider.events = listOf(master, cancelled, moved)
            assertTrue(BufferCalendarStore.refresh(app, repository, start, end))
            assertFalse(repository.calendarEntries(start, end).any { it.title == "普通事项" })
            val priority=repository.velaLiveCalendarReplicaPage("",16)
            assertEquals(1,priority.size);assertFalse(JSONObject(priority.single()).getBoolean("deleted"))
            val normal=repository.velaReplicaPage("",16)
            assertTrue(normal.map(::JSONObject).any{it.getBoolean("deleted")})
            assertTrue(priority.all{it in normal})
            val peer="a".repeat(64);repository.saveVelaReplicaResume(peer,"legacy-progress")
            repository.saveVelaCalendarReplicaResume(peer,JSONObject(priority.single()).getString("card_id"))
            assertEquals("legacy-progress",repository.velaReplicaResume(peer))
            assertEquals(JSONObject(priority.single()).getString("card_id"),repository.velaCalendarReplicaResume(peer))
        } finally { repository.close();TimeZone.setDefault(oldZone) }
    }
}
