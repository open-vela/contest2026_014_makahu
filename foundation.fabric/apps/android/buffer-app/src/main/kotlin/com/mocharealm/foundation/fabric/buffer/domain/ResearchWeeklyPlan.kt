package com.mocharealm.foundation.fabric.buffer.domain

import org.json.JSONObject
import java.time.*
import java.time.temporal.TemporalAdjusters

/** Recurrence uses the user's displayed timezone. DST gaps/overlaps skip that occurrence. */
internal data class ResearchWeeklyPlan(
    val id: String, val revision: Long, val cardId: String, val title: String,
    val weekday: Int, val time: String, val zone: String, val minutes: Int,
    val nextAt: Long, val enabled: Boolean = true, val offeredAt: Long = 0,
    val delivered: Boolean = false, val startedAt: Long = 0,
    val calendarId: Long? = null, val eventId: Long? = null,
    val calendarState: String = "local", val problem: String = "", val calendarRevision: Long = 0,
) {
    init {
        require(id.matches(Regex("research_[0-9a-f]{32}")) && revision > 0)
        require(cardId.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) && title.isNotBlank() && title.length <= 500)
        require(weekday in 1..7 && time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
        LocalTime.parse(time); ZoneId.of(zone)
        require(minutes in setOf(60,90,120) && nextAt > 0 && nextAt <= BufferSourceEvent.MAX_COUNTER - 7_200_000)
        require(offeredAt >= 0 && startedAt >= 0 && (calendarId == null || calendarId >= 0) && (eventId == null || eventId >= 0))
        require(calendarState in setOf("local","pending","synced","delete_pending","deleted","failed") && problem.length <= 200)
    }
    val endAt get() = nextAt + minutes * 60_000L
    val marker get() = "buffer://research/$id"
    fun next(after: Long): Long = nextOccurrence(weekday,time,zone,after)
    fun json(): JSONObject = JSONObject().put("id",id).put("revision",revision).put("card_id",cardId).put("title",title)
        .put("weekday",weekday).put("time",time).put("zone",zone).put("minutes",minutes).put("next_at",nextAt)
        .put("enabled",enabled).put("offered_at",offeredAt).put("delivered",delivered).put("started_at",startedAt)
        .put("calendar_id",calendarId ?: JSONObject.NULL).put("event_id",eventId ?: JSONObject.NULL)
        .put("calendar_state",calendarState).put("problem",problem).put("calendar_revision",calendarRevision)
    companion object {
        fun nextOccurrence(day: Int, time: String, zoneName: String, after: Long): Long {
            require(day in 1..7 && after >= 0)
            val zone=ZoneId.of(zoneName);val localTime=LocalTime.parse(time)
            var date=Instant.ofEpochMilli(after).atZone(zone).toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)))
            repeat(54) {
                val local=LocalDateTime.of(date,localTime);val offsets=zone.rules.getValidOffsets(local)
                if(offsets.size==1) {
                    val stamp=local.toInstant(offsets.single()).toEpochMilli()
                    if(stamp>after) return stamp
                }
                date=date.plusWeeks(1)
            }
            error("没有有效的预约时间")
        }
        fun parse(raw: String): ResearchWeeklyPlan {
            val j=BufferSourceEvent.parseObject(raw,16384)
            return ResearchWeeklyPlan(j.getString("id"),j.getLong("revision"),j.getString("card_id"),j.getString("title"),
                j.getInt("weekday"),j.getString("time"),j.getString("zone"),j.getInt("minutes"),j.getLong("next_at"),
                j.getBoolean("enabled"),j.getLong("offered_at"),j.getBoolean("delivered"),j.getLong("started_at"),
                if(j.isNull("calendar_id"))null else j.getLong("calendar_id"),if(j.isNull("event_id"))null else j.getLong("event_id"),
                j.getString("calendar_state"),j.getString("problem"),j.optLong("calendar_revision",0))
        }
    }
}
