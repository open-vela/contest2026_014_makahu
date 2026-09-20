package com.mocharealm.foundation.fabric.buffer.domain
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal object WeeklySchedule {
    private fun sunday(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(20, 0).atZone(zone)
    fun next(now: Long, zone: ZoneId): Long {
        val candidate = sunday(now, zone)
        return (if (candidate.toInstant().toEpochMilli() <= now) candidate.plusWeeks(1) else candidate).toInstant().toEpochMilli()
    }
    fun latest(now: Long, zone: ZoneId): Long {
        val candidate = sunday(now, zone)
        return (if (candidate.toInstant().toEpochMilli() > now) candidate.minusWeeks(1) else candidate).toInstant().toEpochMilli()
    }
}
