package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery

import org.junit.Assert.*
import org.junit.Test

class CalendarReceiptsTest {
    private fun resolve(vararg receipts: CalendarReceipt, legacy: Boolean = false) =
        CalendarReceipts.resolve("card-a", receipts.asSequence(), legacy)

    @Test fun deletionPreventsResurrectingOldCreation() {
        val deleted = CalendarReceipt("card-a", "10", true)
        assertEquals(deleted, resolve(deleted, CalendarReceipt("card-a", "10", false)))
    }
    @Test fun readdUsesNewestEvent() {
        val created = CalendarReceipt("card-a", "11", false)
        assertEquals(created, resolve(created, CalendarReceipt("card-a", "10", true)))
    }
    @Test fun otherCardsNeverSupplyAnEvent() {
        assertNull(resolve(CalendarReceipt("card-b", "10", false), legacy = true))
    }
    @Test fun explicitAssociationWinsOverLegacy() {
        val explicit = CalendarReceipt("card-a", "11", false)
        assertEquals(explicit, resolve(CalendarReceipt(null, "10", false), explicit, legacy = true))
    }
    @Test fun legacyRequiresUniqueCaptureOwnership() {
        assertNull(resolve(CalendarReceipt(null, "10", false)))
        assertEquals("10", resolve(CalendarReceipt(null, "10", false), legacy = true)?.eventId)
    }
    @Test fun multipleLegacyEventsAreAmbiguous() {
        assertNull(resolve(CalendarReceipt(null, "10", true), CalendarReceipt(null, "11", false), legacy = true))
    }
    @Test fun repeatedLegacyReceiptsKeepLatestDeletion() {
        assertEquals(true, resolve(CalendarReceipt(null, "10", true), CalendarReceipt(null, "10", false), legacy = true)?.deleted)
    }
    @Test fun invalidExplicitReceiptCannotFallBackToOlderOne() {
        assertThrows(IllegalArgumentException::class.java) {
            resolve(CalendarReceipt("card-a", "null", false), CalendarReceipt("card-a", "10", false))
        }
    }
    @Test fun invalidLegacyReceiptPreventsGuessing() {
        assertNull(resolve(CalendarReceipt(null, "null", false), CalendarReceipt(null, "10", false), legacy = true))
    }
    @Test fun alreadyDeletedDoesNotCallProviderOrCommitAgain() {
        CalendarRecovery.remove({ null }, { error("must not delete") }) { fail("must not commit") }
    }
}
