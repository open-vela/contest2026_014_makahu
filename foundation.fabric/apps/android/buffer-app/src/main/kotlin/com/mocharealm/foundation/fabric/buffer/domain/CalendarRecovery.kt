package com.mocharealm.foundation.fabric.buffer.domain

/** Serializes provider changes across Activity instances; receipts may be retried. */
internal object CalendarRecovery {
    @Synchronized
    fun write(findExisting: () -> String?, insert: () -> String, commit: (String) -> Unit): String {
        val eventId = findExisting() ?: insert()
        commit(eventId)
        return eventId
    }

    @Synchronized
    fun remove(findExisting: () -> String?, delete: (String) -> Int, commit: (String) -> Unit) {
        val eventId = findExisting() ?: return
        check(delete(eventId) in 0..1) { "日历删除返回了无效结果" }
        commit(eventId)
    }
}
