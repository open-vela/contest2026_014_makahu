package com.mocharealm.foundation.fabric.buffer.domain
import org.json.JSONObject

internal data class CalendarEntry(val id: String, val title: String, val start: Long, val end: Long,
    val allDay: Boolean, val location: String, val cardId: String, val done: Boolean, val color: Int, val calendarName: String = "") {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,79}")) && title.isNotBlank() && end >= start) }
    fun overlaps(from: Long, to: Long): Boolean = start < to &&
        (end > from || (end == start && start >= from))
    companion object {
        fun color(id: String): Int {
            var hash = 0x811c9dc5.toInt()
            id.toByteArray(Charsets.UTF_8).forEach { hash = (hash xor (it.toInt() and 255)) * 16777619 }
            hash = hash xor (hash ushr 16)
            return ((64 + (hash and 127)) shl 16) or ((64 + ((hash ushr 8) and 127)) shl 8) or (64 + ((hash ushr 16) and 127))
        }
        fun fromDocument(doc: JSONObject): CalendarEntry {
            val v = BufferCrdt.values(doc); require(doc.getString("kind") == "calendar")
            val schedule = TaskSchedule.parse(v.getJSONObject("schedule"))
            v.optJSONObject("recurrence")?.let(CalendarRecurrence::validate)
            return CalendarEntry(doc.getString("id"), v.getString("title"), schedule.start, schedule.end, schedule.allDay,
                v.optString("location"), v.optString("linked_card_id"), false, color(doc.getString("id")), v.optString("calendar_name"))
        }
    }
}
