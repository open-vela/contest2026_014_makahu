package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONObject

/** Work performed is distinct from the calendar time reserved for it. */
internal data class FocusSession(
    val id: String, val title: String, val startedAt: Long,
    val endedAt: Long = 0, val updatedAt: Long = startedAt, val status: String = "active",
    val cardId: String = "", val calendarEventId: String = "", val progress: String = "",
    val experience: String = "", val remaining: String = "", val plannedMinutes: Int = 0,
) {
    val active: Boolean get() = status == "active"
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,79}")))
        require(title.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= 160 && '\u0000' !in title)
        require(cardId.isEmpty() || cardId.matches(Regex("[A-Za-z0-9_-]{1,79}")))
        require(calendarEventId.length <= 80 && '\u0000' !in calendarEventId)
        require(startedAt in 1..9_007_199_254_740_991 && updatedAt in startedAt..9_007_199_254_740_991)
        require(status in setOf("active", "review_pending", "reviewed"))
        require(if (active) endedAt == 0L else endedAt in startedAt..updatedAt)
        require(progress in setOf("", "done", "partial", "not_started"))
        require(experience in setOf("", "good", "tired", "hard"))
        require(status != "reviewed" || progress.isNotEmpty())
        require(remaining.toByteArray(Charsets.UTF_8).size <= 1000 && '\u0000' !in remaining)
        require(plannedMinutes in 0..1440)
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title)
        .put("started_at", startedAt).put("ended_at", endedAt).put("updated_at", updatedAt)
        .put("status", status).put("card_id", cardId).put("calendar_event_id", calendarEventId)
        .put("progress", progress).put("experience", experience).put("remaining", remaining)
        .put("planned_minutes", plannedMinutes)
    companion object {
        fun parse(value: JSONObject): FocusSession {
            require(value.keys().asSequence().toSet() == setOf("id", "title", "started_at", "ended_at", "updated_at",
                "status", "card_id", "calendar_event_id", "progress", "experience", "remaining", "planned_minutes"))
            fun text(key: String) = (value.get(key) as? String) ?: error("invalid focus text")
            fun number(key: String): Long {
                val n = value.get(key)
                require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() &&
                    n.toDouble() in 0.0..9_007_199_254_740_991.0)
                return n.toLong()
            }
            val minutes = number("planned_minutes").also { require(it <= 1440) }.toInt()
            return FocusSession(text("id"), text("title"), number("started_at"), number("ended_at"), number("updated_at"),
                text("status"), text("card_id"), text("calendar_event_id"), text("progress"), text("experience"), text("remaining"), minutes)
        }
    }
}

internal data class FocusPlan(val key: String, val eventId: Long, val title: String,
    val begin: Long, val end: Long, val automatic: Boolean, val state: String = "scheduled") {
    fun json() = JSONObject().put("key", key).put("event_id", eventId).put("title", title)
        .put("begin", begin).put("end", end).put("automatic", automatic).put("state", state)
    companion object {
        fun parse(v: JSONObject) = FocusPlan(v.getString("key"), v.getLong("event_id"), v.getString("title"),
            v.getLong("begin"), v.getLong("end"), v.getBoolean("automatic"), v.getString("state"))
    }
}
