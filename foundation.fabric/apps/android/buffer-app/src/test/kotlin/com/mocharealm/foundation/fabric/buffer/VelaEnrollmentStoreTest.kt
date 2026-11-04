package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.PendingVelaEnrollment

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaEnrollmentStoreTest {
    private lateinit var repository: BufferRepository
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = repository.velaEnrollments
    private fun begin() = store.begin("vela-test", "AA:BB:CC:DD:EE:FF")
    @Before fun open() { repository = BufferRepository(app) }
    @After fun close() { repository.close() }

    @Test fun persistsBeforeSendAndDoesNotExposePendingAsPaired() {
        val pending = begin()
        assertNull(repository.pairedVelaToken(pending.deviceId))
        assertTrue(repository.pairedVelaIds().isEmpty())
        assertEquals(pending.token, repository.velaAuthenticationToken(pending.deviceId))
        assertFalse(pending.toString().contains(pending.token))
        repository.close(); repository = BufferRepository(app)
        assertEquals(pending.token, begin().token)
        assertEquals(pending.requestId, begin().requestId)
    }
    @Test fun requiresBothSignalsInEitherOrderAndAddsExactlyOnce() {
        for ((i, lanFirst) in listOf(true, false).withIndex()) {
            val row = store.begin("vela-$i", "AA:BB:CC:DD:EE:FF")
            val count = repository.eventCount()
            if (lanFirst) {
                assertFalse(store.lanVerified(row.deviceId, row.token))
                assertNull(repository.pairedVelaToken(row.deviceId))
                assertTrue(store.networkReady(row.deviceId, row.requestId))
            } else {
                assertFalse(store.networkReady(row.deviceId, row.requestId))
                assertNull(repository.pairedVelaToken(row.deviceId))
                assertTrue(store.lanVerified(row.deviceId, row.token))
            }
            assertEquals(row.token, repository.pairedVelaToken(row.deviceId))
            assertNull(store.pending(row.deviceId))
            assertFalse(store.networkReady(row.deviceId, row.requestId))
            assertFalse(store.lanVerified(row.deviceId, row.token))
            assertEquals(count + 1, repository.eventCount())
        }
    }
    @Test fun staleResultAndWrongTokenCannotConfirm() {
        val old = begin()
        val retry = store.retry(old.deviceId, old.requestId)
        assertEquals(old.token, retry.token)
        assertNotEquals(old.requestId, retry.requestId)
        assertFalse(store.networkReady(old.deviceId, old.requestId))
        assertFalse(store.lanVerified(old.deviceId, "0".repeat(64)))
        assertFalse(requireNotNull(store.pending(old.deviceId)).networkReady)
        assertNull(repository.pairedVelaToken(old.deviceId))
    }
    @Test fun cancelRevokesPendingAuthenticationWithoutLosingRecoverySecret() {
        val row = begin()
        store.networkReady(row.deviceId, row.requestId)
        assertTrue(store.cancel(row.deviceId, row.requestId))
        assertNull(repository.velaAuthenticationToken(row.deviceId))
        assertFalse(store.lanVerified(row.deviceId, row.token))
        assertFalse(store.networkReady(row.deviceId, row.requestId))
        val resumed = begin()
        assertEquals(row.token, resumed.token)
        assertEquals(row.requestId, resumed.requestId)
        assertFalse(resumed.lanVerified)
        assertTrue(store.lanVerified(row.deviceId, row.token))
    }
    @Test fun eventFailureRollsBackPeerAndPreservesPendingRequest() {
        val row = begin()
        store.networkReady(row.deviceId, row.requestId)
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_peer BEFORE INSERT ON events WHEN NEW.type = 'PeerPaired'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) { store.lanVerified(row.deviceId, row.token) }
        assertNull(repository.pairedVelaToken(row.deviceId))
        assertNotNull(store.pending(row.deviceId))
        assertFalse(requireNotNull(store.pending(row.deviceId)).lanVerified)
        repository.writableDatabase.execSQL("DROP TRIGGER fail_peer")
        assertTrue(store.lanVerified(row.deviceId, row.token))
    }
    @Test fun concurrentRepositoryInstancesReuseOneAttempt() {
        val second = BufferRepository(app)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<PendingVelaEnrollment> { begin() }
            val b = executor.submit<PendingVelaEnrollment> { second.velaEnrollments.begin("vela-test", "AA:BB:CC:DD:EE:FF") }
            val first=a.get(10, TimeUnit.SECONDS);val other=b.get(10,TimeUnit.SECONDS)
            assertEquals(first.requestId,other.requestId);assertEquals(first.token,other.token)
        } finally { executor.shutdownNow();second.close() }
    }
    @Test fun upgradesVersion16WithoutChangingExistingPeers() {
        repository.pairVela("existing", "old-token")
        val db=repository.writableDatabase
        val currentVersion = db.version
        db.execSQL("DROP TABLE vela_enrollments");db.version=16
        repository.close();repository=BufferRepository(app)
        assertEquals("old-token", repository.pairedVelaToken("existing"))
        assertNotNull(begin())
        assertEquals(currentVersion,repository.readableDatabase.version)
    }
    @Test fun doesNotOverwritePeerCommittedByAnotherPath() {
        val row=begin()
        repository.pairVela(row.deviceId,"other-token")
        store.networkReady(row.deviceId,row.requestId)
        assertThrows(SQLiteException::class.java){store.lanVerified(row.deviceId,row.token)}
        assertEquals("other-token",repository.pairedVelaToken(row.deviceId))
        assertNotNull(store.pending(row.deviceId))
    }
}
