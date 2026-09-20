package com.mocharealm.foundation.fabric.buffer.domain

internal data class CalendarReceipt(val cardId: String?, val eventId: String?, val deleted: Boolean)

internal object CalendarReceipts {
    /** Input is ordered newest first, including successful creates and deletes. */
    fun resolve(cardId: String, receipts: Sequence<CalendarReceipt>, allowLegacy: Boolean): CalendarReceipt? {
        require(cardId.isNotBlank())
        val legacy = linkedMapOf<String, CalendarReceipt>()
        var invalidLegacy = false
        for (receipt in receipts) {
            if (receipt.cardId == cardId) {
                require(receipt.eventId?.toLongOrNull()?.let { it > 0 } == true) {
                    "日历关联记录无效，请检查手机日历"
                }
                return receipt
            }
            if (allowLegacy && receipt.cardId.isNullOrBlank()) {
                val id = receipt.eventId
                if (id?.toLongOrNull()?.let { it > 0 } != true) invalidLegacy = true
                else legacy.putIfAbsent(id, receipt)
            }
        }
        // Never guess between unrelated events, even when only one is still active.
        return if (!invalidLegacy && legacy.size == 1) legacy.values.single() else null
    }
}
