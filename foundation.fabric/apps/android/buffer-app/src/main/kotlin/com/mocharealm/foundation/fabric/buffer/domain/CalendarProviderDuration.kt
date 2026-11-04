package com.mocharealm.foundation.fabric.buffer.domain

import java.time.Duration

/** CalendarProvider also stores elapsed seconds as P5700S, without ISO's T. */
internal object CalendarProviderDuration {
    fun millis(raw: String): Long {
        val value = raw.trim()
        val duration = when {
            value.matches(Regex("P[0-9]+S")) -> Duration.ofSeconds(value.substring(1, value.length - 1).toLong())
            value.matches(Regex("P[0-9]+W")) -> Duration.ofDays(Math.multiplyExact(value.substring(1, value.length - 1).toLong(), 7))
            else -> Duration.parse(value)
        }
        return duration.toMillis().also { require(it >= 0) { "日程时长不能为负数" } }
    }
}
