package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ResearchWeeklyPlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Desired-state calendar adapter. Stable markers recover an insert whose receipt was lost.
 * Replayed history never enters this local approved-plan table. */
internal class BufferResearchCalendar(private val context: Context, private val repository: BufferRepository) {
    data class Calendar(val id: Long,val title: String)
    fun calendars(): List<Calendar> {
        check(allowed()) { "需要日历读写权限" }
        return requireNotNull(context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${CalendarContract.Calendars.VISIBLE}=1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),null)).use { rows ->
            buildList { while(rows.moveToNext()) { check(size<128);add(Calendar(rows.getLong(0),rows.getString(1).orEmpty())) } }
        }
    }
    private fun allowed() = listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED
    }
    fun reconcile() {
        for(p in repository.researchWeeklyPlans()) {
            if(p.calendarId==null || !repository.researchPlanApproved(p)) continue
            if(!p.enabled && p.calendarState=="deleted")continue
            if(!allowed()) { repository.researchCalendarResult(p.id,p.revision,p.eventId,"failed","日历权限未授权；预约保留待重试");continue }
            try { sync(p) }
            catch (_: SecurityException) { repository.researchCalendarResult(p.id,p.revision,p.eventId,"failed","日历权限已撤销；等待恢复后重试") }
            catch (_: Exception) { repository.researchCalendarResult(p.id,p.revision,p.eventId,"failed","日历暂不可用；等待重试") }
        }
    }
    private fun sync(plan: ResearchWeeklyPlan) {
        val actual=repository.researchWeeklyPlans().find { it.id==plan.id && it.revision==plan.revision } ?: return
        check(repository.researchPlanApproved(actual))
        val resolver=context.contentResolver
        val ids=requireNotNull(resolver.query(CalendarContract.Events.CONTENT_URI,arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_URI}=? AND ${CalendarContract.Events.CALENDAR_ID}=?",
            arrayOf(plan.marker,plan.calendarId.toString()),null)).use { c -> buildList { while(c.moveToNext()){check(size<64);add(c.getLong(0))} } }
        // Never delete an event identified solely by an unverified cache ID.
        if(!plan.enabled) {
            ids.forEach { id -> resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),
                "${CalendarContract.Events.CUSTOM_APP_URI}=?",arrayOf(plan.marker)) }
            // A provider can decline a deletion without throwing. Confirm the
            // desired state before recording success or discarding retry work.
            requireNotNull(resolver.query(CalendarContract.Events.CONTENT_URI,arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CUSTOM_APP_URI}=? AND ${CalendarContract.Events.CALENDAR_ID}=?",
                arrayOf(plan.marker,plan.calendarId.toString()),null)).use { check(!it.moveToFirst()) { "日历预约尚未删除" } }
            repository.researchCalendarResult(plan.id,plan.revision,null,"deleted");return
        }
        val weekdays=listOf("MO","TU","WE","TH","FR","SA","SU")
        val start=plan.nextAt
        val values=ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,plan.calendarId)
            put(CalendarContract.Events.TITLE,"Buffer 研究：${plan.title}")
            put(CalendarContract.Events.DESCRIPTION,"每周研究预约；到点提醒后由你确认开始。时区：${plan.zone}")
            put(CalendarContract.Events.DTSTART,start)
            put(CalendarContract.Events.DURATION,"PT${plan.minutes}M")
            put(CalendarContract.Events.RRULE,"FREQ=WEEKLY;BYDAY=${weekdays[plan.weekday-1]}")
            put(CalendarContract.Events.EVENT_TIMEZONE,plan.zone)
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE,context.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI,plan.marker)
            put(CalendarContract.Events.STATUS,CalendarContract.Events.STATUS_CONFIRMED)
        }
        var id=ids.firstOrNull()
        if(id==null) {
            if(plan.calendarRevision==plan.revision) {
                repository.researchCalendarResult(plan.id,plan.revision,null,"failed","日历中的预约已被删除；请在 Buffer 重新确认预约")
                return
            }
            id=ContentUris.parseId(requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI,values)))
        } else {
            // Only update when the user changed the plan. Advancing an occurrence
            // must not rewrite the whole recurring series on every 30s poll.
            val same=requireNotNull(resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),
                arrayOf(CalendarContract.Events.TITLE,CalendarContract.Events.RRULE,CalendarContract.Events.DURATION,CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.DTSTART,CalendarContract.Events.DESCRIPTION),null,null,null)).use { c ->
                check(c.moveToFirst())
                val local=Instant.ofEpochMilli(c.getLong(4)).atZone(ZoneId.of(plan.zone))
                c.getString(0)==values.getAsString(CalendarContract.Events.TITLE) && c.getString(1)==values.getAsString(CalendarContract.Events.RRULE) &&
                    c.getString(2)==values.getAsString(CalendarContract.Events.DURATION) && c.getString(3)==plan.zone &&
                    local.dayOfWeek.value==plan.weekday && local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))==plan.time
            }
            if(!same && plan.calendarRevision==plan.revision) {
                repository.researchCalendarResult(plan.id,plan.revision,id,"failed","日历中的预约已被修改；请在 Buffer 重新确认预约")
                return
            }
            if(!same) check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),values,
                "${CalendarContract.Events.CUSTOM_APP_URI}=?",arrayOf(plan.marker))==1)
        }
        // Clean duplicate marker rows left by a provider without idempotent insert.
        ids.drop(1).forEach { extra -> resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,extra),
            "${CalendarContract.Events.CUSTOM_APP_URI}=?",arrayOf(plan.marker)) }
        repository.researchCalendarResult(plan.id,plan.revision,id,"synced")
    }
}
