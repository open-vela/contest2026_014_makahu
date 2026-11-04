package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository

import android.app.Application
import android.app.NotificationManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RabbitHoleNotificationTest {
    private lateinit var repository: BufferRepository
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun open() { repository = BufferRepository(app) }
    @After fun close() { repository.close() }
    private fun start() = repository.startRabbitSession(repository.createTextCapture("为什么天空是蓝色的？").cardId, 60, System.currentTimeMillis() - 3_600_001)
    private fun count() = repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type = 'RabbitHoleNotificationPosted'", null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun permissionFailureRetriesAfterRestartAndPostsOnce() {
        val session = start(); repository.reconcileRabbitSession(session.endsAt)
        assertEquals(session.endsAt + 60_000, repository.deliverRabbitNotice(session.endsAt, { false }, {}))
        assertEquals(0, count())
        repository.close(); open()
        repository.deliverRabbitNotice(session.endsAt + 59_999, { fail("too soon"); true }, {})
        var posts = 0
        repository.deliverRabbitNotice(session.endsAt + 60_000, { posts++; true }, {})
        repository.deliverRabbitNotice(session.endsAt + 120_000, { posts++; true }, {})
        assertEquals(1, posts); assertEquals(1, count())
    }

    @Test fun bedtimeDefersPendingNoticeAndNewSessionDiscardsIt() {
        val old = start(); repository.reconcileRabbitSession(old.endsAt)
        repository.setMode("bedtime")
        var cleared = 0
        assertNull(repository.deliverRabbitNotice(old.endsAt, { fail("quiet"); true }, { cleared++ }))
        assertEquals(1, cleared); assertEquals(0, count())
        repository.close(); open()
        repository.setMode("normal")
        repository.deliverRabbitNotice(old.endsAt, { true }, {})
        assertEquals(1, count())
        repository.startRabbitSession(old.cardId, 60)
        repository.deliverRabbitNotice(old.endsAt, { fail("stale"); true }, { cleared++ })
        assertEquals(2, cleared)
    }

    @Test fun manualEndDoesNotGenerateCompletionNotice() {
        val session = start()
        repository.finishRabbitSession(session.id)
        repository.deliverRabbitNotice(session.endsAt, { fail("manual"); true }, {})
        assertEquals(0, count())
    }

    @Test fun notificationReceiptFailureKeepsPendingForRetry() {
        val session = start(); repository.reconcileRabbitSession(session.endsAt)
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_notice BEFORE INSERT ON events WHEN NEW.type = 'RabbitHoleNotificationPosted' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(Exception::class.java) { repository.deliverRabbitNotice(session.endsAt, { true }, {}) }
        assertEquals(0, count())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_notice")
        var calls = 0
        repository.deliverRabbitNotice(session.endsAt, { calls++; true }, {})
        assertEquals(1, calls); assertEquals(1, count())
    }

    @Test fun schedulerPostsNotificationAndBedtimeWithdrawsWithoutReannouncing() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        start()
        val scheduler = BufferCareScheduler(app, repository)
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            scheduler.awaitAlarm()
            val notification = shadowOf(manager).getNotification("buffer-research", 1411)
            assertNotNull(notification)
            assertNotNull(notification.contentIntent)
            assertEquals(1, count())
            repository.setMode("bedtime"); scheduler.awaitAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-research", 1411))
            repository.setMode("normal"); scheduler.awaitAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-research", 1411))
            assertEquals(1, count())
        } finally { scheduler.stop() }
    }
}
