package com.mocharealm.foundation.fabric.buffer.domain
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal enum class CalendarPriority(val wireName: String, val label: String) {
    LOW("low", "低"), NORMAL("normal", "普通"), HIGH("high", "高"),
}

internal data class CalendarWriteSelection(
    val calendarId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val priority: CalendarPriority,
) {
    fun description(summary: String): String = "$summary\n\n优先级：${priority.label}"

    companion object {
        fun confirm(
            calendarId: Long,
            date: LocalDate?,
            time: LocalTime?,
            durationMinutes: Int,
            priority: CalendarPriority,
            zone: ZoneId,
            nowMillis: Long,
        ): CalendarWriteSelection {
            require(calendarId > 0) { "请选择有效的日历" }
            require(date != null && time != null) { "请明确选择日期和时间" }
            require(durationMinutes in 1..1440) { "时长需在 1 分钟到 24 小时之间" }
            val local = date.atTime(time)
            val offsets = zone.rules.getValidOffsets(local)
            require(offsets.isNotEmpty()) { "该时间处于夏令时跳转空档，请重新选择" }
            require(offsets.size == 1) { "该时间在时区切换时重复，请选择其他时间" }
            val start = local.toInstant(offsets.single()).toEpochMilli()
            require(start > nowMillis) { "请选择未来的开始时间" }
            return CalendarWriteSelection(
                calendarId, start, Math.addExact(start, durationMinutes * 60_000L),
                zone.id, priority,
            )
        }
    }
}
