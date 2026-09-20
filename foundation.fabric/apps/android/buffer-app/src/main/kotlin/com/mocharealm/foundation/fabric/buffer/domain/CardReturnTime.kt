package com.mocharealm.foundation.fabric.buffer.domain
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal object CardReturnTime {
    fun tomorrow(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    fun weekend(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val candidate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).atTime(9, 0).atZone(zone)
        return (if (candidate.toInstant().toEpochMilli() <= now) candidate.plusWeeks(1) else candidate)
            .toInstant().toEpochMilli()
    }

    fun custom(date: LocalDate, time: LocalTime, zone: ZoneId, now: Long): Long {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "该时间处于夏令时切换区间，请选择其他时间" }
        val result = local.toInstant(offsets.single()).toEpochMilli()
        require(result > now) { "请选择未来的回流时间" }
        return result
    }
}
