package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ResearchWeeklyNotificationTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var repository:BufferRepository
    private lateinit var scheduler:BufferCareScheduler
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    @Before fun open(){repository=BufferRepository(app);scheduler=BufferCareScheduler(app,repository)}
    @After fun close(){scheduler.stop();repository.close()}
    private fun duePlan():com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan {
        val time=ZonedDateTime.now().minusMinutes(1).withSecond(0).withNano(0)
        val card=repository.createTextCapture("每周研究一个问题")
        return repository.saveResearchWeeklyPlan(card.cardId,time.dayOfWeek.value,time.format(DateTimeFormatter.ofPattern("HH:mm")),60,
            time.zone.id,now=time.minusMinutes(1).toInstant().toEpochMilli())
    }
    private fun start(intent:Intent) {
        val completed=CountDownLatch(1)
        scheduler.onResearchWeeklyStart(intent.getStringExtra("plan_id")!!,intent.getLongExtra("plan_revision",0),
            intent.getLongExtra("occurrence",0)){completed.countDown()}
        assertTrue(completed.await(10,TimeUnit.SECONDS))
    }
    @Test fun actualNotificationCarriesExactRevisionAndStartsOnlyAfterExplicitAction() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val plan=duePlan();scheduler.awaitAlarm()
        val notice=requireNotNull(shadowOf(manager).getNotification("research-weekly:${plan.id}",1423))
        assertNull(repository.rabbitSession())
        val intent=shadowOf(notice.actions.single().actionIntent).savedIntent
        assertEquals(BufferCareScheduler.ACTION_RESEARCH_WEEKLY_START,intent.action)
        assertEquals(plan.id,intent.getStringExtra("plan_id"));assertEquals(plan.revision,intent.getLongExtra("plan_revision",0))
        assertEquals(plan.nextAt,intent.getLongExtra("occurrence",0));assertEquals(plan.marker+"/${plan.revision}/${plan.nextAt}",intent.data.toString())
        start(intent)
        val session=requireNotNull(repository.rabbitSession())
        assertTrue(session.active);assertEquals(plan.endAt,session.endsAt)
        assertNull(shadowOf(manager).getNotification("research-weekly:${plan.id}",1423))
        start(intent);assertEquals(session.id,repository.rabbitSession()?.id)
    }
    @Test fun editedPlanRejectsOldNotificationAction() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val plan=duePlan();scheduler.awaitAlarm()
        val intent=shadowOf(requireNotNull(shadowOf(manager).getNotification("research-weekly:${plan.id}",1423)).actions.single().actionIntent).savedIntent
        repository.saveResearchWeeklyPlan(plan.cardId,plan.weekday,plan.time,90,plan.zone,id=plan.id)
        start(intent);assertNull(repository.rabbitSession())
        assertEquals("normal",repository.mode())
    }
    @Test fun deniedPermissionRetainsOccurrenceAndGrantAllowsActualNotification() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val plan=duePlan();scheduler.awaitAlarm()
        assertNull(shadowOf(manager).getNotification("research-weekly:${plan.id}",1423))
        assertFalse(repository.researchWeeklyPlans().single().delivered)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);scheduler.awaitAlarm()
        assertNotNull(shadowOf(manager).getNotification("research-weekly:${plan.id}",1423))
        assertTrue(repository.researchWeeklyPlans().single().delivered);assertNull(repository.rabbitSession())
    }
}
