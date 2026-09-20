package com.mocharealm.foundation.fabric.buffer.domain
import java.util.Calendar
import java.util.TimeZone

internal data class BedtimeState(
    val pendingDay: Long = 0,
    val retryAt: Long = 0,
    val scheduledFor: Long = 0,
)

internal data class BedtimePlan(
    val state: BedtimeState,
    val nextAlarmAt: Long? = null,
    val triggerDay: Long? = null,
)

/** Calendar policy only; the repository persists the decision before scheduling. */
internal object BedtimePlanner {
    private const val RETRY_MS = 15 * 60 * 1000L

    fun plan(
        schedule: String,
        now: Long,
        focusActive: Boolean,
        lastTriggeredDay: Long,
        previous: BedtimeState,
        zone: TimeZone = TimeZone.getDefault(),
    ): BedtimePlan {
        if (!schedule.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {
            return BedtimePlan(BedtimeState())
        }
        fun dayOf(at: Long): Long = Calendar.getInstance(zone).apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val parts = schedule.split(':')
        val target = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            set(Calendar.MINUTE, parts[1].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = dayOf(now)
        val targetToday = target.timeInMillis
        if (targetToday <= now) target.add(Calendar.DATE, 1)
        val nextDaily = target.timeInMillis

        var pending = previous.pendingDay.takeIf { it > 0 && it != lastTriggeredDay } ?: 0
        // A delayed alarm or process restart may miss the original calendar day.
        if (previous.scheduledFor in 1..now) {
            val missedDay = dayOf(previous.scheduledFor)
            if (missedDay != lastTriggeredDay) pending = maxOf(pending, missedDay)
        }
        if (now >= targetToday && today != lastTriggeredDay) pending = today

        if (pending == 0L) {
            return BedtimePlan(BedtimeState(scheduledFor = nextDaily), nextDaily)
        }
        if (!focusActive) {
            return BedtimePlan(BedtimeState(scheduledFor = nextDaily), nextDaily, pending)
        }
        val retry = if (previous.pendingDay == pending && previous.retryAt > now) {
            previous.retryAt
        } else {
            now + RETRY_MS
        }
        return BedtimePlan(BedtimeState(pending, retry, nextDaily), minOf(retry, nextDaily))
    }
}
