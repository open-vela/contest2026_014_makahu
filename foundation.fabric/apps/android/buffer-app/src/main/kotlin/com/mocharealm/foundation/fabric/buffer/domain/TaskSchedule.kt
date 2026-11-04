package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONObject
import java.time.*

internal data class TaskSchedule(val start: Long, val end: Long, val allDay: Boolean, val zone: String) {
    init { require(start in -9_007_199_254_740_991..9_007_199_254_740_991 && end >= start && end <= 9_007_199_254_740_991); ZoneId.of(zone) }
    fun json() = JSONObject().put("start", start).put("end", end).put("all_day", allDay).put("zone", zone)
    companion object {
        fun parse(v: JSONObject): TaskSchedule {
            require(v.keys().asSequence().toSet() == setOf("start", "end", "all_day", "zone"))
            fun instant(key: String): Long {
                val n = v.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble())
                return n.toLong()
            }
            require(v.get("all_day") is Boolean && v.get("zone") is String)
            return TaskSchedule(instant("start"), instant("end"), v.getBoolean("all_day"), v.getString("zone"))
        }
        /** Resolve explicit dates against capture time, not delayed processing time.
         * Ambiguous dates stay unscheduled. No guessed clock time for date-only tasks. */
        fun fromText(text: String, capturedAt: Long, zone: ZoneId = ZoneId.systemDefault()): TaskSchedule? = runCatching {
            val today = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDate()
            var date: LocalDate? = null
            Regex("(20\\d{2})[-年/](\\d{1,2})[-月/](\\d{1,2})日?").find(text)?.let {
                date = LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }
            if (date == null) Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(text)?.let {
                date = LocalDate.of(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            if (date == null) date = when {
                "大后天" in text -> today.plusDays(3)
                "后天" in text -> today.plusDays(2)
                "明天" in text -> today.plusDays(1)
                "昨天" in text -> today.minusDays(1)
                "今天" in text -> today
                else -> null
            }
            if (date == null) Regex("(下周|本周|这周|周|星期)([一二三四五六日天])").find(text)?.let {
                val wanted = "一二三四五六日".indexOf(it.groupValues[2].replace('天', '日')) + 1
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                var candidate = monday.plusDays((wanted - 1).toLong())
                if (it.groupValues[1] == "下周") candidate = candidate.plusWeeks(1)
                else if (it.groupValues[1] in setOf("周", "星期") && candidate < today) candidate = candidate.plusWeeks(1)
                date = candidate
            }
            var hour: Int? = null; var minute = 0
            Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)").find(text)?.let {
                var h = it.groupValues[1].toInt(); minute = it.groupValues[2].toInt()
                val period = text.substring(0, it.range.first).trimEnd().takeLast(2)
                if (period in setOf("下午", "傍晚", "晚上") && h < 12) h += 12
                if (period == "中午" && h < 11) h += 12
                if (period in setOf("凌晨", "早上", "上午") && h == 12) h = 0
                hour = h
            }
            if (hour == null) Regex("(凌晨|早上|上午|中午|下午|傍晚|晚上)?([0-9]{1,2}|[零一二两三四五六七八九十]{1,3})[点时](半|[0-5]?\\d分?)?").find(text)?.let {
                fun chinese(s: String): Int {
                    s.toIntOrNull()?.let { n -> return n }
                    fun digit(c: Char) = "零一二三四五六七八九".indexOf(if (c == '两') '二' else c)
                    val split = s.split('十')
                    return if (split.size == 1) digit(s.single()) else (if (split[0].isEmpty()) 1 else digit(split[0].single())) * 10 +
                        (if (split[1].isEmpty()) 0 else digit(split[1].single()))
                }
                var h = chinese(it.groupValues[2]); val period = it.groupValues[1]
                require(h in 0..23)
                if (period in setOf("下午", "傍晚", "晚上") && h < 12) h += 12
                if (period == "中午" && h < 11) h += 12
                if (period in setOf("凌晨", "早上", "上午") && h == 12) h = 0
                hour = h; minute = if (it.groupValues[3] == "半") 30 else it.groupValues[3].removeSuffix("分").toIntOrNull() ?: 0
            }
            if (date == null && hour == null) return null
            if (date == null) date = today
            val day = requireNotNull(date)
            val start = if (hour == null) day.atStartOfDay(zone) else day.atTime(requireNotNull(hour), minute).atZone(zone)
            val end = if (hour == null) day.plusDays(1).atStartOfDay(zone) else start.plusHours(1)
            TaskSchedule(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli(), hour == null, zone.id)
        }.getOrNull()
    }
}
