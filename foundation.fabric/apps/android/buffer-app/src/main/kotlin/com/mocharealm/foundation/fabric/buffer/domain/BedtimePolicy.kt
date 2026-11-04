package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class BedtimePolicy(
    val method: String,
    val fallback: String,
    val sleepTime: String = "23:00",
    val calendars: List<Long> = emptyList(),
    val revision: String = "legacy",
) {
    init {
        require(method in setOf("off", "fixed", "calendar"))
        val clock = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
        require(method == "off" || fallback.matches(clock))
        require(sleepTime.matches(clock))
        require(calendars.size <= 16 && calendars.distinct().size == calendars.size && calendars.all { it > 0 })
        require(method != "calendar" || calendars.isNotEmpty()) { "请选择用于睡前计划的日历" }
        require(revision.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }
    fun json(): JSONObject = JSONObject().put("method", method).put("fallback", fallback)
        .put("sleep_time", sleepTime).put("calendars", JSONArray(calendars)).put("revision", revision)
    companion object {
        fun parse(value: JSONObject): BedtimePolicy {
            require(value.keys().asSequence().toSet() == setOf("method", "fallback", "sleep_time", "calendars", "revision"))
            val ids = value.getJSONArray("calendars")
            require(ids.length() <= 16)
            return BedtimePolicy(value.getString("method"), value.getString("fallback"), value.getString("sleep_time"),
                (0 until ids.length()).map { index ->
                    val n = ids.get(index)
                    require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble())
                    n.toLong()
                }, value.getString("revision"))
        }
    }
}

internal data class BedtimeBusy(val begin: Long, val end: Long, val source: String)
internal data class BedtimeNight(val day: String, val start: Long, val expires: Long, val source: String, val sources: List<String>)
internal data class BedtimeResolution(val revision: String, val zone: String, val nights: List<BedtimeNight>, val status: String)

/** Calendar days own their evening and the following early morning. Absolute
 * instants follow ZoneRules, including gap/overlap and non-24-hour days. */
internal object BedtimeCalendarPolicy {
    fun resolve(policy: BedtimePolicy, now: Long, events: List<BedtimeBusy>, unavailable: String? = null,
                zone: ZoneId = ZoneId.systemDefault()): BedtimeResolution {
        require(policy.method == "calendar")
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun clock(day: LocalDate, value: String): Long {
            val local = LocalTime.parse(value)
            val date = if (local.hour < 12) day.plusDays(1) else day
            return date.atTime(local).atZone(zone).toInstant().toEpochMilli()
        }
        val sorted = events.sortedWith(compareBy<BedtimeBusy> { it.begin }.thenBy { it.end }.thenBy { it.source })
        val nights = (-1L..2L).mapNotNull { offset ->
            val day = today.plusDays(offset)
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val cutoff = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            val sleep = clock(day, policy.sleepTime)
            val hasAgenda = sorted.any { it.begin < dayEnd && it.end > dayStart }
            // Pick the actual base before checking obligations. A late fallback
            // can overlap tomorrow's early agenda even when this day is empty.
            var target = if (unavailable != null || !hasAgenda) clock(day, policy.fallback) else sleep - 3_600_000L
            val used = linkedSetOf<String>()
            var conflict = false
            if (unavailable == null) for (event in sorted) {
                // Include the day's agenda and later occurrences overlapping
                // the resulting wind-down hour; do not pull in tomorrow's
                // unrelated morning appointments.
                if (event.end <= dayStart || event.begin >= maxOf(dayEnd, sleep, target + 3_600_000L)) continue
                if (event.end > cutoff - 3_600_000L) { conflict = true; break }
                if (event.end > target) { target = event.end; used += event.source }
            }
            if (conflict) return@mapNotNull null // Never enter bedtime over an overnight obligation.
            val reason = when {
                unavailable != null -> unavailable
                used.isNotEmpty() -> "calendar_end"
                !hasAgenda -> "no_agenda_fallback"
                else -> "no_late_agenda"
            }
            val end = maxOf(sleep, target + 3_600_000L)
            if (end > cutoff) return@mapNotNull null
            BedtimeNight(day.toString(), target, end, reason, used.toList().takeLast(16))
        }
        return BedtimeResolution(policy.revision, zone.id, nights, unavailable ?: if (nights.size < 4) "calendar_conflict" else "available")
    }
}
