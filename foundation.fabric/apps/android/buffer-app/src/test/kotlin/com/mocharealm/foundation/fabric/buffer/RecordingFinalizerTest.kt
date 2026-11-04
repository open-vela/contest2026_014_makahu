package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RecordingFinalizerTest {
    @Test fun successReleasesBeforeSavingAndOnlyThenDeletesCache() {
        val calls = mutableListOf<String>()
        val result = RecordingFinalizer.finish(
            { calls += "stop" }, { calls += "release" }, { calls += "save" },
            { fail("must not preserve a successful capture") }, { calls += "cleanup"; true },
        )
        assertEquals(listOf("stop", "release", "save", "cleanup"), calls)
        assertEquals(RecordingDestination.CAPTURE, result.destination)
        assertNull(result.error)
        assertFalse(result.cleanupFailed)
    }

    @Test fun stopFailureStillReleasesAndPreservesRawAudio() {
        val calls = mutableListOf<String>()
        val error = IllegalStateException("already stopped")
        val result = RecordingFinalizer.finish(
            { calls += "stop"; throw error }, { calls += "release" },
            { fail("failed stop must use recovery") }, { calls += "preserve" },
            { calls += "cleanup"; true },
        )
        assertEquals(listOf("stop", "release", "preserve", "cleanup"), calls)
        assertEquals(RecordingDestination.RECOVERY, result.destination)
        assertSame(error, result.error)
    }

    @Test fun saveFailureGetsOneRecoveryAttempt() {
        val calls = mutableListOf<String>()
        val result = RecordingFinalizer.finish(
            {}, {}, { calls += "save"; throw IOException("database full") },
            { calls += "preserve" }, { calls += "cleanup"; true },
        )
        assertEquals(listOf("save", "preserve", "cleanup"), calls)
        assertEquals(RecordingDestination.RECOVERY, result.destination)
    }

    @Test fun bothPersistencePathsFailKeepsTheOnlyCopy() {
        val result = RecordingFinalizer.finish(
            {}, {}, { throw IOException("database full") }, { throw IOException("disk full") },
            { fail("must not delete the only audio copy"); false },
        )
        assertEquals(RecordingDestination.CACHE, result.destination)
        assertEquals("disk full", result.error?.message)
    }

    @Test fun releaseFailureUsesRecoveryWithoutEscaping() {
        val result = RecordingFinalizer.finish(
            {}, { throw IllegalStateException("release failed") },
            { fail("must use recovery") }, {}, { fail("failed release must retain cache"); false },
        )
        assertEquals(RecordingDestination.RECOVERY, result.destination)
        assertEquals("release failed", result.error?.message)
        assertTrue(result.cleanupFailed)
    }

    @Test fun cleanupFailureNeverUndoesTheSavedCapture() {
        for (throws in listOf(false, true)) {
            val result = RecordingFinalizer.finish({}, {}, {}, { fail("unexpected recovery") }, {
                if (throws) throw IOException("cannot delete")
                false
            })
            assertEquals(RecordingDestination.CAPTURE, result.destination)
            assertTrue(result.cleanupFailed)
        }
    }
}
