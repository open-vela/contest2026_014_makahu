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

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioCaptureDatabaseTest {
    private lateinit var first: BufferRepository
    private lateinit var second: BufferRepository
    @Before fun open() {
        first = BufferRepository(RuntimeEnvironment.getApplication())
        second = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { second.close(); first.close() }
    private fun save(repo: BufferRepository, id: String, bytes: ByteArray) =
        repo.createAudioCapture(id, "vela", 1000, "normal", "audio/wav", 1000, bytes)
    private fun blob(id: String) = File(RuntimeEnvironment.getApplication().filesDir, "captures/$id.bin")
    private fun failCommit() = first.writableDatabase.execSQL("""
        CREATE TRIGGER fail_audio BEFORE INSERT ON events WHEN NEW.type = 'CaptureCreated'
        BEGIN SELECT RAISE(ABORT, 'injected failure'); END
    """.trimIndent())
    @Test fun databaseFailureRetainsAudioForRetry() {
        val bytes = byteArrayOf(1, 2, 3)
        failCommit()
        assertThrows(SQLiteException::class.java) { save(first, "retry", bytes) }
        assertArrayEquals(bytes, blob("retry").readBytes())
        assertNull(first.cardForCapture("retry"))
        first.writableDatabase.execSQL("DROP TRIGGER fail_audio")
        save(second, "retry", bytes)
        assertEquals(1, first.pendingAudioCaptures().size)
        assertArrayEquals(bytes, blob("retry").readBytes())
    }
    @Test fun conflictingRetryCannotOverwriteOrphanedAudio() {
        failCommit()
        assertThrows(SQLiteException::class.java) { save(first, "conflict", byteArrayOf(1)) }
        first.writableDatabase.execSQL("DROP TRIGGER fail_audio")
        assertThrows(IllegalStateException::class.java) { save(second, "conflict", byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(1), blob("conflict").readBytes())
        assertNull(first.cardForCapture("conflict"))
    }
    @Test fun concurrentUploadCreatesOneCardWithoutOverwritingWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val uploads = listOf(first to byteArrayOf(1), second to byteArrayOf(2)).map { (repo, bytes) ->
                executor.submit<BufferCard> {
                    check(start.await(5, TimeUnit.SECONDS))
                    save(repo, "same-id", bytes)
                }
            }
            start.countDown()
            val cards = uploads.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(cards[0], cards[1])
            assertEquals(1, first.cards(100).size)
            val original = blob("same-id").readBytes()
            save(first, "same-id", byteArrayOf(3))
            assertArrayEquals(original, blob("same-id").readBytes())
            assertEquals(1, original.size)
            assertTrue(original[0] == 1.toByte() || original[0] == 2.toByte())
        } finally { executor.shutdownNow() }
    }
    @Test fun shortCaptureIdWorksAndLeavesNoTemporaryFile() {
        save(first, "a", byteArrayOf(1))
        assertTrue(blob("a").isFile)
        assertTrue(blob("a").parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun startupRecoversCommittedBlobAfterDatabaseFailure() {
        failCommit()
        val bytes = byteArrayOf(4, 5, 6)
        assertThrows(SQLiteException::class.java) { save(first, "startup", bytes) }
        val metadata = File(blob("startup").parentFile, "startup.pending.json")
        assertTrue(metadata.isFile)
        first.writableDatabase.execSQL("DROP TRIGGER fail_audio")
        first.close()
        first = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(1, first.recoverPendingAudio())
        assertNotNull(first.cardForCapture("startup"))
        assertArrayEquals(bytes, blob("startup").readBytes())
        assertFalse(metadata.exists())
        assertEquals(0, first.recoverPendingAudio())
    }
    @Test fun recoveryRejectsMismatchedMetadataAndRetainsOriginal() {
        failCommit()
        assertThrows(SQLiteException::class.java) { save(first, "mismatch", byteArrayOf(7)) }
        first.writableDatabase.execSQL("DROP TRIGGER fail_audio")
        val metadata = File(blob("mismatch").parentFile, "mismatch.pending.json")
        metadata.writeText(metadata.readText().replace("mismatch", "wrong-id"))
        assertEquals(0, first.recoverPendingAudio())
        assertNull(first.cardForCapture("wrong-id"))
        assertNull(first.cardForCapture("mismatch"))
        assertArrayEquals(byteArrayOf(7), blob("mismatch").readBytes())
        assertTrue(metadata.exists())
    }
    @Test fun failedRecoveryRemainsAvailableForNextAttempt() {
        failCommit()
        assertThrows(SQLiteException::class.java) { save(first, "again", byteArrayOf(8)) }
        assertEquals(0, first.recoverPendingAudio())
        assertTrue(File(blob("again").parentFile, "again.pending.json").exists())
        first.writableDatabase.execSQL("DROP TRIGGER fail_audio")
        assertEquals(1, first.recoverPendingAudio())
    }

    private fun backup(id: String, bytes: ByteArray) {
        val source = File(RuntimeEnvironment.getApplication().cacheDir, "backup-source")
        source.writeBytes(bytes)
        first.preserveAudioForRetry(id, "android", 1000, "normal", "audio/wav", 1000, source)
    }
    @Test fun backupCanBeRetriedAndRecoveredWithoutDuplicateCapture() {
        backup("backup", byteArrayOf(1, 2))
        backup("backup", byteArrayOf(1, 2))
        assertEquals(1, first.recoverPendingAudio())
        assertEquals(0, first.recoverPendingAudio())
        assertArrayEquals(byteArrayOf(1, 2), blob("backup").readBytes())
        assertNotNull(first.cardForCapture("backup"))
    }
    @Test fun conflictingBackupDoesNotDestroyOriginal() {
        backup("backup-conflict", byteArrayOf(3))
        assertThrows(IllegalStateException::class.java) {
            backup("backup-conflict", byteArrayOf(4))
        }
        assertEquals(1, first.recoverPendingAudio())
        assertArrayEquals(byteArrayOf(3), blob("backup-conflict").readBytes())
    }
    @Test fun failedBackupWriteRetainsMetadataAndSourceForRetry() {
        val blocked = File(blob("backup-failure").parentFile, "backup-failure.retry.m4a")
        assertTrue(blocked.mkdir())
        assertThrows(Exception::class.java) { backup("backup-failure", byteArrayOf(5)) }
        assertTrue(File(blocked.parentFile, "backup-failure.retry.json").isFile)
        assertTrue(File(RuntimeEnvironment.getApplication().cacheDir, "backup-source").isFile)
        assertTrue(blocked.delete())
        backup("backup-failure", byteArrayOf(5))
        assertEquals(1, first.recoverPendingAudio())
        assertArrayEquals(byteArrayOf(5), blob("backup-failure").readBytes())
    }
}
