package com.mocharealm.foundation.fabric.buffer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecurrence
import com.mocharealm.foundation.fabric.buffer.domain.TaskSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** A complete provider snapshot of masters and exceptions, never a window of
 * materialized occurrences. Read failures leave the previous snapshot intact. */
internal object BufferCalendarStore {
    private data class Event(val id: Long, val calendar: Long, val title: String, val start: Long,
        val end: Long?, val duration: String, val allDay: Boolean, val zone: String,
        val rrule: String, val rdate: String, val exrule: String, val exdate: String,
        val original: Long?, val originalTime: Long?, val cancelled: Boolean,
        val location: String, val marker: String, val syncId: String, val originalSyncId: String)
    private fun bounded(text: String, max: Int): String {
        var end = text.length
        while (text.substring(0, end).toByteArray(Charsets.UTF_8).size > max) end -= Character.charCount(text.codePointBefore(end))
        return text.substring(0, end)
    }
    fun refresh(context: Context, repository: BufferRepository, start: Long, end: Long): Boolean {
        require(start >= 0 && end > start && end - start <= 100L * 86_400_000)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        val names = mutableMapOf<Long, String>()
        requireNotNull(context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)).use { c ->
            while (c.moveToNext()) names[c.getLong(0)] = bounded(c.getString(1).orEmpty(), 160)
        }
        val columns = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXRULE, CalendarContract.Events.EXDATE, CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME, CalendarContract.Events.STATUS, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.CUSTOM_APP_URI, CalendarContract.Events._SYNC_ID, CalendarContract.Events.ORIGINAL_SYNC_ID)
        val events = mutableListOf<Event>()
        requireNotNull(context.contentResolver.query(CalendarContract.Events.CONTENT_URI, columns, "${CalendarContract.Events.DELETED} = 0", null, "${CalendarContract.Events._ID} ASC")).use { c ->
            while (c.moveToNext()) events += Event(c.getLong(0), c.getLong(1), bounded(c.getString(2)?.trim().orEmpty().ifBlank { "未命名日程" }, 160),
                c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(5).orEmpty(), c.getInt(6) != 0,
                c.getString(7).orEmpty().ifBlank { ZoneId.systemDefault().id }, c.getString(8).orEmpty(), c.getString(9).orEmpty(),
                c.getString(10).orEmpty(), c.getString(11).orEmpty(), if (c.isNull(12)) null else c.getLong(12),
                if (c.isNull(13)) null else c.getLong(13), !c.isNull(14) && c.getInt(14) == CalendarContract.Events.STATUS_CANCELED,
                bounded(c.getString(15).orEmpty(), 160), c.getString(16).orEmpty(), c.getString(17).orEmpty(), c.getString(18).orEmpty())
        }
        fun schedule(event: Event): TaskSchedule {
            val zone = if (event.allDay) ZoneId.systemDefault() else ZoneId.of(event.zone)
            // Some providers store point events with neither DTEND nor DURATION.
            // Preserve their zero duration rather than inventing an end or losing the snapshot.
            val rawEnd = event.end ?: if (event.duration.isBlank()) event.start else Math.addExact(event.start,
                com.mocharealm.foundation.fabric.buffer.domain.CalendarProviderDuration.millis(event.duration))
            require(rawEnd >= event.start)
            return if (event.allDay) {
                val from = Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
                val to = Instant.ofEpochMilli(rawEnd).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)
                TaskSchedule(from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli(), true, zone.id)
            } else TaskSchedule(event.start, rawEnd, false, zone.id)
        }
        fun parent(event: Event): Long? = event.original ?: event.originalSyncId.takeIf { it.isNotBlank() }?.let { sync ->
            requireNotNull(events.singleOrNull { it.calendar == event.calendar && it.syncId == sync && it.original == null && it.originalSyncId.isBlank() }) { "日历例外缺少系列主事件" }.id
        }
        val exceptions = events.filter { it.original != null || it.originalSyncId.isNotBlank() }.groupBy(::parent)
        val staged = linkedMapOf<String, JSONObject>()
        val offsetCache = mutableMapOf<String, JSONArray>()
        for (event in events.filter { it.original == null && it.originalSyncId.isBlank() && !it.cancelled }) {
            val value = schedule(event);val zone = ZoneId.of(value.zone)
            val marker = event.marker.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)
            val linked = marker?.takeIf { it.scheme == "buffer" && it.host == "calendar" }?.lastPathSegment.orEmpty()
            val body = JSONObject().put("title", event.title).put("location", event.location).put("schedule", value.json())
                .put("linked_card_id", linked).put("source", "android_calendar")
                .put("calendar_name", names[event.calendar].orEmpty()).put("calendar_id", event.calendar.toString())
                .put("provider_event_id", event.id.toString())
            if (event.rrule.isNotBlank() || event.rdate.isNotBlank()) {
                val overrides = JSONArray()
                for (exception in exceptions[event.id].orEmpty()) {
                    val rawOriginal = requireNotNull(exception.originalTime)
                    val original = if (event.allDay) Instant.ofEpochMilli(rawOriginal).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() else rawOriginal
                    val item = JSONObject().put("original", original).put("cancelled", exception.cancelled)
                    if (!exception.cancelled) item.put("schedule", schedule(exception).json()).put("title", exception.title).put("location", exception.location)
                    overrides.put(item)
                }
                val local = Instant.ofEpochMilli(value.start).atZone(zone)
                val days = if (value.allDay) java.time.temporal.ChronoUnit.DAYS.between(local.toLocalDate(), Instant.ofEpochMilli(value.end).atZone(zone).toLocalDate()) else 0L
                val series = JSONObject().put("version", 1).put("zone", zone.id)
                    .put("local_start", local.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")))
                    .put("days", days).put("rrule", CalendarRecurrence.providerRule(event.rrule, zone, value.allDay)).put("exrule", CalendarRecurrence.providerRule(event.exrule, zone, value.allDay))
                    .put("rdates", CalendarRecurrence.dates(event.rdate, zone, value.allDay)).put("exdates", CalendarRecurrence.dates(event.exdate, zone, value.allDay))
                    .put("offsets", offsetCache.getOrPut(zone.id) { CalendarRecurrence.offsets(zone) }).put("exceptions", overrides)
                CalendarRecurrence.validate(series);body.put("recurrence", series)
            } else body.put("recurrence", JSONObject.NULL)
            staged["cal_series_" + VelaProcessedCaptureProtocol.fingerprint("${event.calendar}:${event.id}").take(32)] = body
        }
        // Permission or query errors never turn absence into a deletion.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        repository.replaceCalendarSourceSnapshot(staged)
        return true
    }
}
