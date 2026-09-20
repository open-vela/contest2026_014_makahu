package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the focus care reminder independent from the foreground UI.  The
 * short in-process poll handles mode changes from either the phone or Vela;
 * AlarmManager wakes the app when it is backgrounded.
 */
class BufferCareScheduler(
    context: Context,
    private val repository: BufferRepository,
    private val reminderDelivery: (() -> Boolean)? = null,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    fun start() {
        createNotificationChannel()
        executor.scheduleWithFixedDelay(::reconcile, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
        synchronized(lock) {
            cancelAlarm()
            cancelBedtimeAlarm()
            cancelResearchAlarm()
            cancelWeeklyAlarm()
            cancelReturnAlarm()
            cancelCardReminderAlarm()
        }
    }

    fun onAlarm() {
        reconcile()
    }

    fun onBedtimeAlarm() {
        reconcile()
    }

    fun onReminderAction(action: String) {
        if (action != ACTION_SNOOZE && action != ACTION_SKIP) return
        synchronized(lock) {
            try {
                if (repository.mode() != "focus") return
                val now = System.currentTimeMillis()
                repository.deferFocusReminder(
                    now,
                    if (action == ACTION_SNOOZE) repository.focusReminderConfig().snoozeMs
                    else repository.focusReminderConfig().intervalMs,
                    if (action == ACTION_SNOOZE) "snooze" else "skip",
                )
                // Do not dismiss the current reminder until its action is durable.
                notificationManager.cancel(NOTIFICATION_ID)
                scheduleAlarm(repository.focusNextReminderAt())
            } catch (error: Exception) {
                Log.w("BufferCareScheduler", "Reminder action failed", error)
            }
            reconcileBedtime(System.currentTimeMillis())
        }
    }

    private fun reconcile() {
        synchronized(lock) {
            try {
                if (repository.mode() == "focus") {
                    val now = System.currentTimeMillis()
                    var next = repository.focusNextReminderAt()
                    if (next <= 0L) {
                        next = repository.ensureFocusSession(now)
                    }
                    if (next > 0L && now >= next) {
                        next = repository.fireFocusReminder(now)
                    }
                    scheduleAlarm(next)
                    repository.deliverPendingFocusReminder { reminderDelivery?.invoke() ?: postReminder() }
                } else {
                    repository.clearFocusSession()
                    cancelAlarm()
                    notificationManager.cancel(NOTIFICATION_ID)
                }
            } catch (error: Exception) {
                // An exception escaping a periodic Runnable suppresses later runs.
                Log.w("BufferCareScheduler", "Focus reconciliation failed; will retry", error)
            }
            reconcileBedtime(System.currentTimeMillis())
            reconcileResearch(System.currentTimeMillis())
            reconcileWeekly(System.currentTimeMillis())
            reconcileReturns(System.currentTimeMillis())
            reconcileCardReminders(System.currentTimeMillis())
        }
    }

    private fun reconcileBedtime(now: Long) {
        try {
            val next = repository.reconcileBedtime(now)
            if (next == null) cancelBedtimeAlarm() else scheduleBedtimeAlarmAt(next)
        } catch (error: Exception) {
            // Keep the periodic worker alive so transient storage/alarm errors retry.
            Log.w("BufferCareScheduler", "Bedtime reconciliation failed", error)
        }
    }

    private fun cardReminderIntent(): PendingIntent = PendingIntent.getBroadcast(appContext, 1409,
        Intent(appContext, BufferCareAlarmReceiver::class.java).setAction(ACTION_CARD_REMINDER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelCardReminderAlarm() {
        val intent = cardReminderIntent()
        alarmManager.cancel(intent); intent.cancel()
    }

    private fun reconcileCardReminders(now: Long) {
        try {
            val next = repository.reconcileCardReminders(now, ::postCardReminder) { id ->
                notificationManager.cancel("buffer-card-$id", 1410)
            }
            if (next == null) cancelCardReminderAlarm()
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, cardReminderIntent())
        } catch (error: Exception) { Log.w("BufferCareScheduler", "Card reminder failed; will retry", error) }
    }

    private fun postCardReminder(card: BufferCard): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(appContext,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val channelId = "buffer-card-reminders"
        notificationManager.createNotificationChannel(NotificationChannel(channelId, "Buffer 事项提醒", NotificationManager.IMPORTANCE_DEFAULT))
        if (!notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(appContext, 1410,
            CardReminderNavigation.intent(appContext, card.cardId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            notificationManager.notify("buffer-card-${card.cardId}", 1410,
                Notification.Builder(appContext, channelId).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(card.title.take(120)).setContentText(card.summary.take(240))
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }

    private fun returnIntent(): PendingIntent = PendingIntent.getBroadcast(appContext, 1408,
        Intent(appContext, BufferCareAlarmReceiver::class.java).setAction(ACTION_RETURN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelReturnAlarm() {
        val intent = returnIntent()
        alarmManager.cancel(intent)
        intent.cancel()
    }

    private fun reconcileReturns(now: Long) {
        try {
            val next = repository.reconcileCardReturns(now)
            if (next == null) cancelReturnAlarm()
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, returnIntent())
        } catch (error: Exception) { Log.w("BufferCareScheduler", "Card return failed; will retry", error) }
    }

    private fun researchIntent(): PendingIntent = PendingIntent.getBroadcast(appContext, 1411,
        Intent(appContext, BufferCareAlarmReceiver::class.java).setAction(ACTION_RESEARCH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelResearchAlarm() {
        val intent = researchIntent()
        alarmManager.cancel(intent); intent.cancel()
    }

    private fun reconcileResearch(now: Long) {
        try {
            val deadline = repository.reconcileRabbitSession(now)
            val noticeRetry = repository.deliverRabbitNotice(now, ::postResearchNotice) {
                notificationManager.cancel("buffer-research", 1411)
            }
            val next = listOfNotNull(deadline, noticeRetry).minOrNull()
            if (next == null) cancelResearchAlarm()
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, researchIntent())
        } catch (error: Exception) { Log.w("BufferCareScheduler", "Research timer failed; will retry", error) }
    }

    private fun postResearchNotice(session: RabbitHoleSession): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(appContext,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val channel = "buffer-research"
        notificationManager.createNotificationChannel(NotificationChannel(channel, "Buffer 研究计时", NotificationManager.IMPORTANCE_DEFAULT))
        if (!notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(appContext, 1411,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            notificationManager.notify("buffer-research", 1411,
                Notification.Builder(appContext, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("研究时间到：${session.title.take(100)}")
                    .setContentText("打开 Buffer，记录这次发现；也可以稍后再写。")
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }

    private fun weeklyIntent(): PendingIntent = PendingIntent.getBroadcast(appContext, 1407,
        Intent(appContext, BufferCareAlarmReceiver::class.java).setAction(ACTION_WEEKLY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelWeeklyAlarm() {
        val intent = weeklyIntent()
        alarmManager.cancel(intent)
        intent.cancel()
    }

    private fun reconcileWeekly(now: Long) {
        try {
            val next = repository.reconcileWeekly(now)
            if (next == null) cancelWeeklyAlarm()
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, weeklyIntent())
        } catch (error: Exception) {
            Log.w("BufferCareScheduler", "Weekly reconciliation failed; will retry", error)
        }
    }

    private fun scheduleAlarm(nextAt: Long) {
        if (nextAt <= 0L) return
        val delay = (nextAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            Intent(appContext, BufferCareAlarmReceiver::class.java)
                .setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delay,
            pendingIntent,
        )
    }

    private fun cancelAlarm() {
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            Intent(appContext, BufferCareAlarmReceiver::class.java)
                .setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun scheduleBedtimeAlarmAt(at: Long) {
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            BEDTIME_ALARM_REQUEST_CODE,
            Intent(appContext, BufferCareAlarmReceiver::class.java)
                .setAction(ACTION_BEDTIME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at,
            pendingIntent,
        )
    }

    private fun cancelBedtimeAlarm() {
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            BEDTIME_ALARM_REQUEST_CODE,
            Intent(appContext, BufferCareAlarmReceiver::class.java)
                .setAction(ACTION_BEDTIME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun postReminder(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return false
        createNotificationChannel()
        if (!notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val contentIntent = PendingIntent.getActivity(
            appContext,
            CONTENT_REQUEST_CODE,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            appContext,
            SNOOZE_REQUEST_CODE,
            reminderActionIntent(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipIntent = PendingIntent.getBroadcast(
            appContext,
            SKIP_REQUEST_CODE,
            reminderActionIntent(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle("专注照顾提醒")
            .setContentText("喝水、看远处或活动一下肩颈")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "延后 ${repository.focusReminderConfig().snoozeMs / 60_000} 分钟", snoozeIntent).build())
            .addAction(Notification.Action.Builder(null, "跳过本次", skipIntent).build())
            .build()
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            return true
        } catch (_: SecurityException) {
            return false
        }
    }

    private fun reminderActionIntent(action: String): Intent =
        Intent(appContext, BufferCareAlarmReceiver::class.java).setAction(action)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Buffer 专注照顾",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "长时间专注后的低摩擦身体照顾提醒"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CARD_REMINDER = "com.mocharealm.foundation.fabric.buffer.CARD_REMINDER"
        const val ACTION_RESEARCH = "com.mocharealm.foundation.fabric.buffer.RESEARCH_ALARM"
        const val ACTION_RETURN = "com.mocharealm.foundation.fabric.buffer.CARD_RETURN"
        const val ACTION_WEEKLY = "com.mocharealm.foundation.fabric.buffer.WEEKLY_ALARM"
        const val ACTION_ALARM = "com.mocharealm.foundation.fabric.buffer.FOCUS_ALARM"
        const val ACTION_BEDTIME = "com.mocharealm.foundation.fabric.buffer.BEDTIME_ALARM"
        const val ACTION_SNOOZE = "com.mocharealm.foundation.fabric.buffer.FOCUS_SNOOZE"
        const val ACTION_SKIP = "com.mocharealm.foundation.fabric.buffer.FOCUS_SKIP"
        private const val CHANNEL_ID = "buffer-care"
        private const val NOTIFICATION_ID = 1402
        private const val ALARM_REQUEST_CODE = 1402
        private const val BEDTIME_ALARM_REQUEST_CODE = 1406
        private const val CONTENT_REQUEST_CODE = 1403
        private const val SNOOZE_REQUEST_CODE = 1404
        private const val SKIP_REQUEST_CODE = 1405
        private const val POLL_SECONDS = 15L
    }
}

class BufferCareAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? BufferApplication ?: return
        when (intent.action) {
            BufferCareScheduler.ACTION_CARD_REMINDER,
            BufferCareScheduler.ACTION_RESEARCH,
            BufferCareScheduler.ACTION_RETURN,
            BufferCareScheduler.ACTION_WEEKLY,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            BufferCareScheduler.ACTION_ALARM -> application.careScheduler.onAlarm()
            BufferCareScheduler.ACTION_BEDTIME -> application.careScheduler.onBedtimeAlarm()
            BufferCareScheduler.ACTION_SNOOZE,
            BufferCareScheduler.ACTION_SKIP,
            -> application.careScheduler.onReminderAction(intent.action.orEmpty())
            Intent.ACTION_BOOT_COMPLETED -> {
                application.careScheduler.onAlarm()
                if (Build.VERSION.SDK_INT < 37 ||
                    ContextCompat.checkSelfPermission(
                        context,
                        LOCAL_NETWORK_PERMISSION,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, BufferBridgeService::class.java),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
