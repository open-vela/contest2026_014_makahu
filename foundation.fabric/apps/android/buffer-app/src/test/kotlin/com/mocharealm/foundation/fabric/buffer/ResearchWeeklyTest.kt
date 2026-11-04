package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ResearchWeeklyTest {
    private lateinit var repo: BufferRepository
    private lateinit var card: String
    private val now=Instant.parse("2026-10-26T09:00:00Z").toEpochMilli()
    @Before fun open(){repo=BufferRepository(RuntimeEnvironment.getApplication());card=repo.createTextCapture("一个研究灵感").cardId}
    @After fun close(){repo.close()}
    private fun plan()=repo.saveResearchWeeklyPlan(card,1,"10:00",90,"UTC",now=now)
    @Test fun restartsRetainWeeklyPlanAndOfferOnlyOnce() {
        val p=plan();repo.close();repo=BufferRepository(RuntimeEnvironment.getApplication())
        var posts=0
        repeat(2){repo.reconcileResearchWeekly(p.nextAt,{posts++;true},{})}
        assertEquals(1,posts);assertNull(repo.rabbitSession());assertTrue(repo.researchWeeklyPlans().single().delivered)
    }
    @Test fun notificationFailureRetriesWithoutAdvancingOccurrence() {
        val p=plan();var attempts=0
        repo.reconcileResearchWeekly(p.nextAt,{attempts++;false},{})
        assertEquals(p.nextAt,repo.researchWeeklyPlans().single().nextAt)
        repo.reconcileResearchWeekly(p.nextAt+60_000,{attempts++;true},{})
        assertEquals(2,attempts)
    }
    @Test fun missedWeeksSkipExpiredSessionsRatherThanStartingOldResearch() {
        val p=plan();val after=p.endAt+21*86_400_000L
        val wake=repo.reconcileResearchWeekly(after,{fail("expired slot");true},{})
        assertTrue(requireNotNull(wake)>after);assertNull(repo.rabbitSession())
        assertEquals(p.next(after),repo.researchWeeklyPlans().single().nextAt)
    }
    @Test fun focusAndBedtimeDeferUntilNormalWithoutStartingAnything() {
        val p=plan();var posts=0
        for(mode in listOf("focus","bedtime")){repo.setMode(mode);repo.reconcileResearchWeekly(p.nextAt,{posts++;true},{})}
        assertEquals(0,posts);assertNull(repo.rabbitSession())
        repo.setMode("normal");repo.reconcileResearchWeekly(p.nextAt+60_000,{posts++;true},{})
        assertEquals(1,posts)
    }
    @Test fun explicitLateStartUsesAppointmentEndAndDuplicateDoesNotRestart() {
        val p=plan();val started=repo.startResearchWeekly(p.id,p.revision,p.nextAt,p.nextAt+20*60_000)
        assertEquals(p.endAt,started.endsAt);assertEquals("rabbit_hole",repo.mode())
        assertEquals(started,repo.startResearchWeekly(p.id,p.revision,p.nextAt,p.nextAt+21*60_000))
    }
    @Test fun cancellationAndEditsFenceOldNotificationActions() {
        val p=plan();val edited=repo.saveResearchWeeklyPlan(card,1,"11:00",60,"UTC",id=p.id,now=now)
        assertThrows(IllegalArgumentException::class.java){repo.startResearchWeekly(p.id,p.revision,p.nextAt,p.nextAt)}
        repo.cancelResearchWeeklyPlan(edited.id,now)
        assertThrows(IllegalArgumentException::class.java){repo.startResearchWeekly(edited.id,edited.revision,edited.nextAt,edited.nextAt)}
        var cleared=0;repo.reconcileResearchWeekly(edited.nextAt,{fail("cancelled");true},{cleared++})
        assertEquals(1,cleared);assertTrue(repo.researchWeeklyPlans().isEmpty())
    }
    @Test fun eventFailureRollsBackPlanAndAllowsRetry() {
        repo.writableDatabase.execSQL("CREATE TRIGGER fail_plan BEFORE INSERT ON events WHEN NEW.type='ResearchWeeklyPlanSaved' BEGIN SELECT RAISE(ABORT,'injected'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java){plan()}
        assertTrue(repo.researchWeeklyPlans().isEmpty())
        repo.writableDatabase.execSQL("DROP TRIGGER fail_plan");assertNotNull(plan())
    }
    @Test fun cacheCannotAuthorizeAChangedCalendarOrTime() {
        val p=plan();val forged=p.copy(time="12:00",calendarId=3)
        repo.writableDatabase.execSQL("UPDATE research_weekly_plans SET body=? WHERE id=?",arrayOf(forged.json().toString(),p.id))
        assertFalse(repo.researchPlanApproved(forged))
        repo.reconcileResearchWeekly(p.nextAt,{fail("forged plan");true},{})
        assertNull(repo.rabbitSession())
    }
    @Test fun daylightSavingGapsAndOverlapsAreNotSilentlyAdjusted() {
        fun stamp(value:String)=Instant.parse(value).toEpochMilli()
        val gap=ResearchWeeklyPlan.nextOccurrence(7,"02:30","America/New_York",stamp("2026-03-07T00:00:00Z"))
        assertEquals(stamp("2026-03-15T06:30:00Z"),gap)
        val overlap=ResearchWeeklyPlan.nextOccurrence(7,"01:30","America/New_York",stamp("2026-10-31T00:00:00Z"))
        assertEquals(stamp("2026-11-08T06:30:00Z"),overlap)
    }
}
