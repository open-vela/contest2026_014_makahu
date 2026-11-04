package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusReminderConfigTest {
    @Test fun defaultsMatchExistingSettings() {
        assertEquals(BufferConstants.focusReminderMs, FocusReminderConfig().intervalMs)
        assertEquals(BufferConstants.focusSnoozeMs, FocusReminderConfig().snoozeMs)
    }
    @Test fun overdueDeadlineIsImmediatelyDue() {
        assertEquals(0L, FocusReminderConfig().remainingMs(100, 101))
        assertEquals(0L, FocusReminderConfig().remainingMs(100, 100))
    }
    @Test fun remainingTimeIsBoundedForDevice() {
        assertEquals(500L, FocusReminderConfig().remainingMs(600, 100))
        assertEquals(10_800_000L, FocusReminderConfig().remainingMs(20_000_000, 100))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooShortInterval() { FocusReminderConfig(intervalMs = 299_999) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooLongInterval() { FocusReminderConfig(intervalMs = 10_800_001) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooShortSnooze() { FocusReminderConfig(snoozeMs = 59_999) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooLongSnooze() { FocusReminderConfig(snoozeMs = 3_600_001) }
    @Test fun recentReminderCarriesRemainingDisplayTime() {
        val config = FocusReminderConfig()
        assertEquals(15_000L, config.noticeRemainingMs(1000 + config.intervalMs, 1000, false))
        assertEquals(12_000L, config.noticeRemainingMs(1000 + config.intervalMs, 4000, false))
    }
    @Test fun expiredReminderIsNotShownOnReconnect() {
        val config = FocusReminderConfig()
        assertEquals(0L, config.noticeRemainingMs(1000 + config.intervalMs, 16000, false))
        assertEquals(0L, config.noticeRemainingMs(1000 + config.intervalMs, 16001, false))
    }
    @Test fun snoozeAndClockRollbackDoNotCreateReminders() {
        val config = FocusReminderConfig()
        assertEquals(0L, config.noticeRemainingMs(1000 + config.intervalMs, 1000, true))
        assertEquals(0L, config.noticeRemainingMs(1000 + config.intervalMs, 999, false))
        assertEquals(0L, config.noticeRemainingMs(0, 1000, false))
    }
}
