package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.DatabaseSettings
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferHttp
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMoAsrClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAsrAudio
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBlePeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPairing
import com.mocharealm.foundation.fabric.buffer.data.device.VelaDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResult
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResultReader
import com.mocharealm.foundation.fabric.buffer.data.device.PendingVelaEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentView
import com.mocharealm.foundation.fabric.buffer.data.device.VelaGattEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.toJson
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlan
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlanner
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeState
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationChange
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncOutcome
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer
import com.mocharealm.foundation.fabric.buffer.domain.RecordingResult
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.WeeklyReflection
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyDeferredTask
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklySuggestion
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyLifeCategory
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer

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
