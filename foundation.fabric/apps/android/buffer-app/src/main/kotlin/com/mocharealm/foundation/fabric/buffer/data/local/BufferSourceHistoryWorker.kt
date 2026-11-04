package com.mocharealm.foundation.fabric.buffer.data.local

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Recover derived history independently of the connection, screen and model.
 * Busy history drains in bounded batches; failure preserves the durable queue. */
internal class BufferSourceHistoryWorker(private val repository: BufferRepository) : AutoCloseable {
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val retryAt = LongArray(3)
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "buffer-source-history") }

    fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) schedule(0)
    }

    private fun schedule(delay: Long) {
        if (!closed.get()) runCatching { executor.schedule(Runnable { reconcile() }, delay, TimeUnit.MILLISECONDS) }
    }

    private fun reconcile() {
        if (closed.get()) return
        var more = false
        var failed = false
        val stages = listOf<() -> Boolean>(
            { repository.reconcileSourceHistory() },
            { repository.reconcileLocalAudio() },
            { repository.reconcileSourceProjections() },
        )
        for ((index, stage) in stages.withIndex()) {
            if (closed.get()) break
            if (android.os.SystemClock.elapsedRealtime() < retryAt[index]) { failed = true; continue }
            try { if (stage()) more = true; retryAt[index] = 0 }
            catch (_: Exception) {
                // Exception text can contain original records. An unavailable
                // audio file must not prevent unrelated view/history recovery.
                failed = true
                retryAt[index] = android.os.SystemClock.elapsedRealtime() + 30_000
                Log.w("BufferSourceHistory", "History recovery deferred; original records retained")
            }
        }
        schedule(if (more) 100L else if (failed) 30_000L else 5000L)
    }

    override fun close() { if (closed.compareAndSet(false, true)) executor.shutdownNow() }
}
