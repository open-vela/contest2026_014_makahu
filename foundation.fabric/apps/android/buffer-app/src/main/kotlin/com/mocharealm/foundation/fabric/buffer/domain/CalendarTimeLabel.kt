package com.mocharealm.foundation.fabric.buffer.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object CalendarTimeLabel {
    fun format(event: CalendarEntry, zone: ZoneId): String {
        val start=Instant.ofEpochMilli(event.start).atZone(zone)
        val end=Instant.ofEpochMilli(if(event.allDay && event.end>event.start) event.end-1 else event.end).atZone(zone)
        val sameDay=start.toLocalDate()==end.toLocalDate()
        val date=DateTimeFormatter.ofPattern(if(start.year==end.year) "M月d日" else "yyyy年M月d日")
        val time=DateTimeFormatter.ofPattern("HH:mm")
        return when {
            event.allDay -> if(sameDay) "全天" else "${date.format(start)}–${date.format(end)} · 全天"
            event.start==event.end -> time.format(start)
            sameDay -> "${time.format(start)}–${time.format(end)}"
            else -> "${date.format(start)} ${time.format(start)}–${date.format(end)} ${time.format(end)}"
        }
    }
}
