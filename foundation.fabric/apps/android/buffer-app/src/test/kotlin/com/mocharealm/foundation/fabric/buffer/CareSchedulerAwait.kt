package com.mocharealm.foundation.fabric.buffer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Wait for the production worker callback before inspecting durable effects. */
internal fun BufferCareScheduler.awaitAlarm() {
    val completed = CountDownLatch(1)
    onAlarm { completed.countDown() }
    check(completed.await(10, TimeUnit.SECONDS)) { "care reconciliation timed out" }
}

internal fun com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository.finishSourceRecovery() {
    repeat(512) {
        val history = reconcileSourceHistory()
        val projection = reconcileSourceProjections()
        if (!history && !projection) return
    }
    error("source recovery did not finish")
}
