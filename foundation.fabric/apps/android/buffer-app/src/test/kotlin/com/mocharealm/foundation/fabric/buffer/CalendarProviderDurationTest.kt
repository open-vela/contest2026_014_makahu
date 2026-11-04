package com.mocharealm.foundation.fabric.buffer

import com.mocharealm.foundation.fabric.buffer.domain.CalendarProviderDuration
import org.junit.Assert.*
import org.junit.Test

class CalendarProviderDurationTest {
    @Test fun providerSecondsAndStandardDurationsPreserveExactLengths() {
        for(seconds in listOf(0L,1500L,1800L,3000L,3600L,5700L,8700L,9300L,10800L,57600L))
            assertEquals(seconds*1000,CalendarProviderDuration.millis("P${seconds}S"))
        assertEquals(3_600_000L,CalendarProviderDuration.millis("PT1H"))
        assertEquals(86_400_000L,CalendarProviderDuration.millis("P1D"))
        assertEquals(1_209_600_000L,CalendarProviderDuration.millis("P2W"))
    }
    @Test fun invalidOrOverflowingDurationsCannotBecomeInventedEventLengths() {
        for(value in listOf("", "invalid", "P-5S", "P999999999999999999999S"))
            assertThrows(Exception::class.java) { CalendarProviderDuration.millis(value) }
    }
}
