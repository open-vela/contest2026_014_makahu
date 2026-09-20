package com.mocharealm.foundation.fabric.buffer.domain

internal enum class RecordingDestination { CAPTURE, RECOVERY, CACHE }
internal data class RecordingResult(
    val destination: RecordingDestination,
    val error: Exception? = null,
    val cleanupFailed: Boolean = false,
)

/** Stop/release before persistence; delete cache only after successful release and persistence. */
internal object RecordingFinalizer {
    fun finish(
        stop: () -> Unit,
        release: () -> Unit,
        save: () -> Unit,
        preserve: () -> Unit,
        cleanup: () -> Boolean,
    ): RecordingResult {
        var failure: Exception? = null
        var releaseFailed = false
        try {
            stop()
        } catch (error: Exception) {
            failure = error
        } finally {
            try {
                release()
            } catch (error: Exception) {
                releaseFailed = true
                if (failure == null) failure = error
            }
        }
        var destination = RecordingDestination.CACHE
        if (failure == null) {
            try {
                save()
                destination = RecordingDestination.CAPTURE
            } catch (error: Exception) {
                failure = error
            }
        }
        if (destination == RecordingDestination.CACHE) {
            try {
                preserve()
                destination = RecordingDestination.RECOVERY
            } catch (error: Exception) {
                return RecordingResult(RecordingDestination.CACHE, error)
            }
        }
        // A failed release can leave the native writer alive; retain its original file.
        if (releaseFailed) return RecordingResult(destination, failure, cleanupFailed = true)
        val cleaned = try { cleanup() } catch (_: Exception) { false }
        return RecordingResult(destination, failure, cleanupFailed = !cleaned)
    }
}
