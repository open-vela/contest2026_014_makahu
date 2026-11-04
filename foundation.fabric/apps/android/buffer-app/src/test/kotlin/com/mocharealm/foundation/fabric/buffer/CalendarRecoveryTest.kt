package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CalendarRecoveryTest {
    @Test fun writeRetryRecoversAfterReceiptFailure() {
        var external: String? = null
        var inserts = 0
        val insert = { inserts++; "event-1".also { external = it } }
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.write({ external }, insert) { error("disk full") }
        }
        var receipt: String? = null
        CalendarRecovery.write({ external }, insert) { receipt = it }
        assertEquals(1, inserts)
        assertEquals(external, receipt)
    }

    @Test fun retryRecoversAnInsertThatCommittedBeforeThrowing() {
        var external: String? = null
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.write({ external }, {
                external = "event-1"
                error("connection lost after insert")
            }) { fail("must not commit") }
        }
        assertEquals("event-1", CalendarRecovery.write({ external }, { error("duplicate insert") }) {})
    }

    @Test fun failedLookupNeverInserts() {
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.write({ error("permission denied") }, { error("must not insert") }) {}
        }
    }

    @Test fun concurrentActivitiesCreateOneEvent() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            var external: String? = null
            var inserts = 0
            val futures = (1..20).map {
                executor.submit<String> {
                    CalendarRecovery.write({ external }, {
                        inserts++
                        "event-1".also { external = it }
                    }) {}
                }
            }
            futures.forEach { assertEquals("event-1", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, inserts)
        } finally { executor.shutdownNow() }
    }

    @Test fun deleteRetryCommitsAfterExternalEventIsGone() {
        var present = true
        val delete: (String) -> Int = { if (present) { present = false; 1 } else 0 }
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.remove({ "event-1" }, delete) { error("disk full") }
        }
        assertFalse(present)
        var committed = false
        CalendarRecovery.remove({ "event-1" }, delete) { committed = true }
        assertTrue(committed)
    }

    @Test fun deleteFailureLeavesLocalStateAlone() {
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.remove({ "event-1" }, { error("permission denied") }) { fail("must not commit") }
        }
        assertThrows(IllegalStateException::class.java) {
            CalendarRecovery.remove({ "event-1" }, { 2 }) { fail("must not commit") }
        }
    }
}
