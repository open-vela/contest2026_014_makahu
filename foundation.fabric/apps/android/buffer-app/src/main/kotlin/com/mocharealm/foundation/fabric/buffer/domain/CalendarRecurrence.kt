package com.mocharealm.foundation.fabric.buffer.domain

import biweekly.Biweekly
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.TimeZone

/** Rules and exceptions are facts; occurrences are bounded, read-only views. */
internal object CalendarRecurrence {
    private val localFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    fun offsets(zone: ZoneId): JSONArray {
        val result = JSONArray(); val rules = zone.rules
        var at = Instant.EPOCH
        result.put(JSONArray().put(0L).put(rules.getOffset(at).totalSeconds))
        val end = Instant.parse("2200-01-01T00:00:00Z")
        while (true) {
            val change = rules.nextTransition(at) ?: break
            if (change.instant >= end) break
            check(result.length() < 1024)
            result.put(JSONArray().put(change.instant.epochSecond).put(change.offsetAfter.totalSeconds))
            at = change.instant.plusSeconds(1)
        }
        return result
    }
    fun dates(encoded: String, zone: ZoneId, allDay: Boolean = false): JSONArray {
        val result = JSONArray()
        if (encoded.isBlank()) return result
        val chunks = encoded.trim().split(Regex("[\\r\\n]+"))
        chunks.forEach { line ->
            val prefix = line.substringBefore(':', "")
            // Android RecurrenceSet stores timezone;date,date, while an
            // iCalendar property uses TZID=timezone:date,date.
            val androidZone = if (prefix.isEmpty() && ';' in line) line.substringBefore(';') else ""
            val values = when { prefix.isNotEmpty() -> line.substringAfter(':');androidZone.isNotEmpty() -> line.substringAfter(';');else -> line }
            val selected = when { androidZone.isNotEmpty() -> ZoneId.of(androidZone);"TZID=" in prefix -> ZoneId.of(prefix.substringAfter("TZID=").substringBefore(';'));else -> ZoneOffset.UTC }
            values.split(',').filter { it.isNotBlank() }.forEach { value ->
                val instant = when {
                    allDay -> LocalDate.parse(value.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant()
                    value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(selected).toInstant()
                    value.endsWith('Z') -> LocalDateTime.parse(value.dropLast(1), localFormat).toInstant(ZoneOffset.UTC)
                    else -> LocalDateTime.parse(value, localFormat).atZone(selected).toInstant()
                }
                result.put(instant.toEpochMilli())
            }
        }
        return result
    }
    fun providerRule(encoded: String, zone: ZoneId, allDay: Boolean): String {
        val unfolded = encoded.replace(Regex("\\r?\\n[ \\t]"), "")
        if (!allDay) return unfolded
        return Regex("UNTIL=(\\d{8})T\\d{6}Z").replace(unfolded) {
            val last = LocalDate.parse(it.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant()
            "UNTIL=" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(last)
        }
    }
    fun validate(series: JSONObject) {
        require(series.getInt("version") == 1)
        ZoneId.of(series.getString("zone"))
        LocalDateTime.parse(series.getString("local_start"), localFormat)
        require(series.getLong("days") in 0..84006)
        for (key in listOf("rrule", "exrule")) {
            val rule = series.getString(key)
            require(rule.length <= 8192 && rule.lineSequence().count() <= 32)
            for (part in rule.lineSequence().filter { it.isNotBlank() }) requireNotNull(Biweekly.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20000101T000000Z\r\nRRULE:$part\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").first()?.events?.firstOrNull()?.recurrenceRule)
        }
        require(series.getJSONArray("offsets").length() in 1..1024)
        require(series.getJSONArray("exceptions").length() <= 256)
        for (key in listOf("rdates", "exdates")) {
            val dates = series.getJSONArray(key);require(dates.length() <= 16384)
            for (i in 0 until dates.length()) require(dates.getLong(i) in -9_007_199_254_740_991..9_007_199_254_740_991)
        }
    }
    fun expand(base: CalendarEntry, series: JSONObject, from: Long, to: Long): List<CalendarEntry> {
        validate(series)
        val zone = ZoneId.of(series.getString("zone"));val timezone = TimeZone.getTimeZone(zone)
        val duration = base.end - base.start
        val lookback = if (base.allDay) (series.getLong("days") + 1) * 86_400_000L else duration
        val included = sortedSetOf(base.start); val excluded = mutableSetOf<Long>()
        fun rule(key: String, target: MutableSet<Long>) {
            val raw = series.getString(key);if (raw.isBlank()) return
            for (part in raw.lineSequence().filter { it.isNotBlank() }) {
            val recurrence = requireNotNull(Biweekly.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nDTSTART:20000101T000000Z\r\nRRULE:$part\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n").first()?.events?.firstOrNull()?.recurrenceRule)
            val iterator = recurrence.getDateIterator(Date(base.start), timezone)
            iterator.advanceTo(Date(from - lookback))
            var work = 0
            while (iterator.hasNext()) {
                check(++work <= 16384) { "日历窗口中的重复实例过多" }
                val at = iterator.next().time;if (at >= to) break
                target += at
            }
            }
        }
        rule("rrule", included);rule("exrule", excluded)
        for ((key, target) in listOf("rdates" to included, "exdates" to excluded)) {
            val values = series.getJSONArray(key);for (i in 0 until values.length()) target += values.getLong(i)
        }
        val exceptions = series.getJSONArray("exceptions")
        val overrides = (0 until exceptions.length()).map(exceptions::getJSONObject)
        overrides.forEach { excluded += it.getLong("original") }
        val result = included.filter { it !in excluded }.map { at ->
            val end = if (base.allDay) Instant.ofEpochMilli(at).atZone(zone).plusDays(series.getLong("days")).toInstant().toEpochMilli() else at + duration
            base.copy(id = "${base.id}_$at", start = at, end = end)
        }.toMutableList()
        overrides.filterNot { it.getBoolean("cancelled") }.forEach {
            val schedule = TaskSchedule.parse(it.getJSONObject("schedule"))
            result += base.copy(id = "${base.id}_${it.getLong("original")}", title = it.getString("title"), location = it.getString("location"), start = schedule.start, end = schedule.end, allDay = schedule.allDay)
        }
        return result.filter { it.overlaps(from, to) }.sortedWith(compareBy<CalendarEntry> { it.start }.thenBy { it.id })
    }
}
