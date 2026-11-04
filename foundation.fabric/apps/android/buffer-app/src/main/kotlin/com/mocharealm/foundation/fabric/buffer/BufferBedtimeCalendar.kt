package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.domain.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Read adapter only, outside repository transactions and the UI thread. */
internal class BufferBedtimeCalendar(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val deadline = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "buffer-bedtime-deadline").apply { isDaemon = true } }
    private val changes = AtomicLong()
    private var cachedGeneration = -1L
    private var key = ""
    private var readAt = 0L
    private var cached: BedtimeResolution? = null
    private var observing = false
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { changes.incrementAndGet() }
    }
    fun resolve(policy: BedtimePolicy, now: Long): BedtimeResolution {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val allowed = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (allowed && !observing) observing = runCatching {
            app.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer); true
        }.getOrDefault(false)
        val cacheKey = "${policy.revision}:$zone:$today:$allowed"
        val generation = changes.get()
        cached?.takeIf { cachedGeneration == generation && key == cacheKey && now >= readAt && now - readAt < 60_000 }?.let { return it }
        val spans = mutableListOf<BedtimeBusy>()
        var unavailable: String? = if (!allowed) "calendar_permission_denied" else null
        if (allowed) {
            val signal = CancellationSignal()
            val timeout = deadline.schedule({ signal.cancel() }, 1500, TimeUnit.MILLISECONDS)
            try {
                val start = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = today.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, start); ContentUris.appendId(it, end)
                }.build()
                val columns = arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.STATUS, Instances.CALENDAR_ID)
                val placeholders = policy.calendars.joinToString(",") { "?" }
                val selection = "${Instances.CALENDAR_ID} IN ($placeholders) AND ${Instances.ALL_DAY}=0 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS}!=?)"
                val args = policy.calendars.map(Long::toString).plus(CalendarContract.Events.STATUS_CANCELED.toString()).toTypedArray()
                val rows = app.contentResolver.query(uri, columns, selection, args, "${Instances.BEGIN} ASC", signal)
                    ?: error("calendar provider unavailable")
                rows.use { cursor ->
                    while (cursor.moveToNext()) {
                        check(!signal.isCanceled)
                        if (spans.size == 2048) error("calendar budget exceeded")
                        val begin = cursor.getLong(1); val finish = cursor.getLong(2)
                        if (begin >= 0 && finish > begin && cursor.getInt(3) == 0 && cursor.getInt(4) != CalendarContract.Events.STATUS_CANCELED)
                            spans += BedtimeBusy(begin, finish, "${cursor.getLong(5)}:${cursor.getLong(0)}:$begin")
                    }
                }
            } catch (_: SecurityException) { unavailable = "calendar_permission_denied" }
              catch (_: Exception) { unavailable = "calendar_unavailable" }
              finally { timeout.cancel(false); signal.cancel() }
        }
        // Ignore a partial provider result on failure; the configured fallback
        // is explicit and its reason remains visible to the user.
        val result = BedtimeCalendarPolicy.resolve(policy, now, if (unavailable == null) spans else emptyList(), unavailable, zone)
        key = cacheKey; readAt = now; cachedGeneration = generation; cached = result
        return result
    }
    override fun close() {
        if (observing) runCatching { app.contentResolver.unregisterContentObserver(observer) }
        deadline.shutdownNow()
    }
}
