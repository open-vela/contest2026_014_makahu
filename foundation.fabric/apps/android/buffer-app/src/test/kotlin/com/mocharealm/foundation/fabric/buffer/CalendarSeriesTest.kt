package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.CalendarEntry
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecurrence
import com.mocharealm.foundation.fabric.buffer.domain.TaskSchedule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarSeriesTest {
    private fun at(date: String, zone: String = "Asia/Shanghai") = LocalDateTime.parse(date).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun series(start: Long, rule: String, zone: String = "Asia/Shanghai") = JSONObject()
        .put("version", 1).put("zone", zone).put("local_start", Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")))
        .put("rrule", rule).put("exrule", "").put("rdates", JSONArray()).put("exdates", JSONArray())
        .put("offsets", CalendarRecurrence.offsets(ZoneId.of(zone))).put("exceptions", JSONArray()).put("days", 0)
    private fun base(start: Long, end: Long = start + 3600000, allDay: Boolean = false) = CalendarEntry("cal_series_one", "课程", start, end, allDay, "教室", "", false, 123)
    @Test fun weeklyIntervalByDayAndFiniteCount() {
        val start = at("2026-11-02T09:00:00")
        val result = CalendarRecurrence.expand(base(start), series(start, "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=4"), start, at("2026-12-01T00:00:00"))
        assertEquals(listOf(2, 4, 16, 18), result.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of("Asia/Shanghai")).dayOfMonth })
    }
    @Test fun monthlyLastWeekdayAndUntil() {
        val start = at("2026-01-30T09:00:00")
        val result = CalendarRecurrence.expand(base(start), series(start, "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;UNTIL=20260430T010000Z"), start, at("2026-06-01T00:00:00"))
        assertEquals(listOf("2026-01-30", "2026-02-27", "2026-03-31", "2026-04-30"), result.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString() })
    }
    @Test fun exceptionsRemoveOriginalAndPreserveMovedIdentity() {
        val start = at("2026-11-01T09:00:00");val recurrence = series(start, "FREQ=DAILY;COUNT=4")
        recurrence.getJSONArray("exdates").put(at("2026-11-02T09:00:00"))
        recurrence.getJSONArray("rdates").put(at("2026-11-10T09:00:00"))
        val original = at("2026-11-03T09:00:00")
        recurrence.getJSONArray("exceptions").put(JSONObject().put("original", original).put("cancelled", false)
            .put("title", "改到周五").put("location", "二楼").put("schedule", TaskSchedule(at("2026-11-06T14:00:00"), at("2026-11-06T15:00:00"), false, "Asia/Shanghai").json()))
        val result = CalendarRecurrence.expand(base(start), recurrence, start, at("2026-12-01T00:00:00"))
        assertEquals(listOf(1, 4, 6, 10), result.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of("Asia/Shanghai")).dayOfMonth })
        assertEquals("cal_series_one_$original", result.single { it.title == "改到周五" }.id)
    }
    @Test fun wallClockAndAllDaySurviveDst() {
        val zone = "America/New_York";val start = at("2026-03-07T09:00:00", zone)
        val result = CalendarRecurrence.expand(base(start), series(start, "FREQ=DAILY;COUNT=3", zone), start, at("2026-03-11T00:00:00", zone))
        assertEquals(listOf(9, 9, 9), result.map { Instant.ofEpochMilli(it.start).atZone(ZoneId.of(zone)).hour })
        assertEquals(23 * 3600000L, result[1].start - result[0].start)
        val midnight = at("2026-03-08T00:00:00", zone)
        val allDay = series(midnight, "FREQ=DAILY;COUNT=2", zone).put("days", 1)
        val dates = CalendarRecurrence.expand(base(midnight, at("2026-03-09T00:00:00", zone), true), allDay, midnight, at("2026-03-11T00:00:00", zone))
        assertEquals(23 * 3600000L, dates[0].end - dates[0].start)
        assertEquals(24 * 3600000L, dates[1].end - dates[1].start)
    }
    @Test fun androidDateEncodingAndAllDayUntilKeepTheNominalDate() {
        val zone = ZoneId.of("America/New_York")
        val expected = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, CalendarRecurrence.dates("20260308T000000Z", zone, true).getLong(0))
        assertEquals(expected, CalendarRecurrence.dates("America/New_York;20260308T000000", zone).getLong(0))
        assertEquals(Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(), CalendarRecurrence.dates("20260308T000000", zone).getLong(0))
        assertEquals("FREQ=DAILY;UNTIL=20260308T050000Z", CalendarRecurrence.providerRule("FREQ=DAILY;UNTIL=20260308T000000Z", zone, true))
    }
    @Test fun snapshotMigratesInstancesAndRollsBackFailure() {
        val repository = BufferRepository(RuntimeEnvironment.getApplication())
        try {
            val start = at("2026-11-02T09:00:00")
            fun body(title: String) = JSONObject().put("title", title).put("location", "").put("linked_card_id", "")
                .put("source", "android_calendar").put("schedule", TaskSchedule(start, start + 3600000, false, "Asia/Shanghai").json())
            repository.upsertCalendarDocument("cal_old_instance", body("旧实例"))
            repository.replaceCalendarSourceSnapshot(mapOf("cal_series_one" to body("课程").put("recurrence", series(start, "FREQ=WEEKLY;COUNT=100"))))
            fun document(id: String) = repository.velaReplicaPage("", 16).map(::JSONObject).single { it.getString("card_id") == id }.getJSONObject("crdt")
            assertTrue(document("cal_old_instance").getBoolean("deleted"))
            assertEquals(1, repository.calendarDocumentIds().count { !document(it).getBoolean("deleted") })
            assertEquals(5, repository.calendarEntries(start, at("2026-12-01T00:00:00")).size)
            val before = document("cal_series_one").toString()
            assertThrows(Exception::class.java) { repository.replaceCalendarSourceSnapshot(linkedMapOf("cal_series_one" to body("错误更改"), "bad" to JSONObject())) }
            assertEquals(before, document("cal_series_one").toString())
        } finally { repository.close() }
    }
}
