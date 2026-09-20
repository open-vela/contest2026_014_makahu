package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants

internal data class FocusReminderConfig(
    val intervalMs: Long = BufferConstants.focusReminderMs,
    val snoozeMs: Long = BufferConstants.focusSnoozeMs,
) {
    init {
        require(intervalMs in 300_000L..10_800_000L) { "提醒间隔需在 5–180 分钟之间" }
        require(snoozeMs in 60_000L..3_600_000L) { "延后时间需在 1–60 分钟之间" }
    }

    fun remainingMs(nextAt: Long, now: Long): Long =
        if (nextAt <= now) 0 else (nextAt - now).coerceAtMost(10_800_000L)

    fun noticeRemainingMs(nextAt: Long, now: Long, clearNotice: Boolean): Long {
        if (clearNotice || nextAt <= 0) return 0
        val firedAt = nextAt - intervalMs
        if (now < firedAt || now - firedAt >= 15_000L) return 0
        return 15_000L - (now - firedAt)
    }
}
