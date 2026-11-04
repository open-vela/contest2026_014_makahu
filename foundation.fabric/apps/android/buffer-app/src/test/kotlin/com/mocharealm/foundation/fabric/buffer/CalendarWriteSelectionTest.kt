package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarWriteSelectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun confirm(date: LocalDate? = LocalDate.of(2026, 9, 20),
                        time: LocalTime? = LocalTime.of(23, 30),
                        duration: Int = 60, calendar: Long = 42,
                        priority: CalendarPriority = CalendarPriority.NORMAL) =
        CalendarWriteSelection.confirm(calendar, date, time, duration, priority, zone, now)

    @Test fun explicitChoicePreservesCalendarZoneAndCrossMidnightDuration() {
        val selection = confirm()
        assertEquals(42, selection.calendarId)
        assertEquals("Asia/Shanghai", selection.timeZone)
        assertEquals(Instant.parse("2026-09-20T15:30:00Z").toEpochMilli(), selection.startMillis)
        assertEquals(Instant.parse("2026-09-20T16:30:00Z").toEpochMilli(), selection.endMillis)
    }

    @Test fun missingTimeStaysUnconfirmedInsteadOfGuessing() {
        assertThrows(IllegalArgumentException::class.java) { confirm(date = null) }
        assertThrows(IllegalArgumentException::class.java) { confirm(time = null) }
    }

    @Test fun invalidCalendarAndDurationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { confirm(calendar = 0) }
        assertThrows(IllegalArgumentException::class.java) { confirm(duration = 0) }
        assertThrows(IllegalArgumentException::class.java) { confirm(duration = 1441) }
        assertEquals(86_400_000L, confirm(duration = 1440).let { it.endMillis - it.startMillis })
    }

    @Test fun pastTimeCannotBeAccidentallyScheduled() {
        assertThrows(IllegalArgumentException::class.java) { confirm(date = LocalDate.of(2025, 12, 31)) }
    }

    @Test fun priorityIsIncludedWithoutLosingTaskDescription() {
        for (priority in CalendarPriority.entries) {
            val selection = confirm(priority = priority)
            assertEquals(priority, selection.priority)
            assertEquals("任务原文\n\n优先级：${priority.label}", selection.description("任务原文"))
        }
        assertEquals(listOf("low", "normal", "high"), CalendarPriority.entries.map { it.wireName })
    }

    @Test fun daylightSavingGapAndRepeatedHourRequireAnotherChoice() {
        val newYork = ZoneId.of("America/New_York")
        for (dateTime in listOf(LocalDate.of(2026, 3, 8) to LocalTime.of(2, 30),
                                LocalDate.of(2026, 11, 1) to LocalTime.of(1, 30))) {
            assertThrows(IllegalArgumentException::class.java) {
                CalendarWriteSelection.confirm(42, dateTime.first, dateTime.second, 60,
                    CalendarPriority.NORMAL, newYork, now)
            }
        }
    }
}
