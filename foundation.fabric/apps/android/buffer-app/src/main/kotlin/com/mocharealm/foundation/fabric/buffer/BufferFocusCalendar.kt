package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.FocusPlan

/** Only user-selected calendar occurrences participate. Recheck the provider
 * before delivery/start; deleted or moved occurrences never start from stale data. */
internal class BufferFocusCalendar(private val context: Context, private val repository: BufferRepository) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notices = context.getSystemService(NotificationManager::class.java)
    private fun alarm() = PendingIntent.getBroadcast(context, 1420,
        Intent(context, BufferCareAlarmReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun upcoming(now: Long = System.currentTimeMillis()): List<FocusPlan> {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) { "请允许读取日历" }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000); ContentUris.appendId(it, now + 7 * 86_400_000L)
        }.build()
        return requireNotNull(context.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            "${CalendarContract.Instances.ALL_DAY} = 0 AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)",
            arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()), "${CalendarContract.Instances.BEGIN} ASC"))
            .use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0); val begin = cursor.getLong(2); val end = cursor.getLong(3)
                    val title = cursor.getString(1)?.trim().orEmpty()
                    if (end > now && title.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= 160)
                        add(FocusPlan("${id}_$begin", id, title, begin, end, false))
                }
            } }
    }
    fun start(key: String) {
        val now = System.currentTimeMillis()
        val plan = repository.focusPlans().first { it.key == key }
        check(upcoming(now).any { it.key == key && it.end == plan.end }) { "日程已变更，请重新选择" }
        repository.startPlannedFocus(key, now)
        notices.cancel("focus:$key", 1420)
    }
    fun cancelAlarm() { alarms.cancel(alarm()) }

    fun reconcile(now: Long = System.currentTimeMillis()) {
        val work = repository.focusSession()
        if (work?.active == true && work.calendarEventId.isNotEmpty()) {
            val plan = repository.focusPlans().firstOrNull { it.state == "started" &&
                it.eventId.toString() == work.calendarEventId && work.startedAt in it.begin until it.end }
            if (plan != null && now >= plan.end) repository.finishFocus(work.id, now)
        }
        if (repository.focusSession()?.status != "review_pending") notices.cancel("focus-review", 1421)
        repository.focusSession()?.takeIf { it.status == "review_pending" && !repository.focusReviewNotified(it.id) }?.let { review ->
            if (notices.areNotificationsEnabled()) {
                val channel = "buffer-focus-calendar"
                notices.createNotificationChannel(NotificationChannel(channel, "日程专注", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(context, 1421, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notices.notify("focus-review", 1421, Notification.Builder(context, channel)
                    .setSmallIcon(R.drawable.ms_alarm).setContentTitle(review.title)
                    .setContentText("专注结束了，记录一下进展和感受？").setContentIntent(open).setAutoCancel(true).build())
                repository.markFocusReviewNotified(review.id)
            }
        }
        repository.focusPlans().filter { it.state in setOf("cancelled", "expired", "started") }
            .forEach { notices.cancel("focus:${it.key}", 1420) }
        val pending = repository.focusPlans().filter { it.state in setOf("scheduled", "offered") }
        val activeEnd = repository.focusSession()?.takeIf { it.active }?.let { active ->
            repository.focusPlans().firstOrNull { it.state == "started" && it.eventId.toString() == active.calendarEventId &&
                active.startedAt in it.begin until it.end }?.end
        }
        if (pending.isEmpty()) {
            if (activeEnd != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activeEnd, alarm()) else cancelAlarm()
            return
        }
        // Permission/provider failures leave plans intact for a later retry.
        val actual = upcoming(now).associateBy { it.key }
        for (plan in pending) {
            val current = actual[plan.key]
            if (now >= plan.end) {
                repository.saveFocusPlan(plan.copy(state = "expired")); notices.cancel("focus:${plan.key}", 1420)
            } else if (current == null || current.end != plan.end || current.title != plan.title) {
                repository.saveFocusPlan(plan.copy(state = "cancelled")); notices.cancel("focus:${plan.key}", 1420)
            } else if (now >= plan.begin) {
                if (plan.automatic && repository.mode() == "normal" && repository.focusSession()?.active != true) {
                    repository.startPlannedFocus(plan.key, now)
                    notices.cancel("focus:${plan.key}", 1420)
                } else if (plan.state == "scheduled" && notify(plan)) repository.saveFocusPlan(plan.copy(state = "offered"))
            }
        }
        val next = repository.focusPlans().filter { it.state in setOf("scheduled", "offered") }
            .minOfOrNull { if (it.begin > now) it.begin else minOf(it.end, now + 60_000) }
        val ending = repository.focusSession()?.takeIf { it.active }?.let { active ->
            repository.focusPlans().firstOrNull { it.state == "started" && it.eventId.toString() == active.calendarEventId &&
                active.startedAt in it.begin until it.end }?.end
        }
        val wake = listOfNotNull(next, ending).minOrNull()
        if (wake == null) cancelAlarm() else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake, alarm())
    }

    private fun notify(plan: FocusPlan): Boolean {
        if (!notices.areNotificationsEnabled()) return false
        val channel = "buffer-focus-calendar"
        notices.createNotificationChannel(NotificationChannel(channel, "日程专注", NotificationManager.IMPORTANCE_DEFAULT))
        val start = PendingIntent.getBroadcast(context, plan.key.hashCode(),
            Intent(context, BufferCareAlarmReceiver::class.java).setAction(ACTION_START)
                .setData(android.net.Uri.parse("buffer://focus/${plan.key}")).putExtra("focus_plan", plan.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, plan.key.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notices.notify("focus:${plan.key}", 1420, Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ms_alarm).setContentTitle(plan.title)
            .setContentText("日程时间到了，现在开始专注吗？").setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "开始专注", start).build()).build())
        return true
    }
    companion object {
        const val ACTION_TICK = "com.mocharealm.buffer.FOCUS_CALENDAR"
        const val ACTION_START = "com.mocharealm.buffer.FOCUS_CALENDAR_START"
    }
}
