package com.mocharealm.foundation.fabric.buffer.domain
import org.json.JSONObject

internal data class RabbitHoleSession(
    val id: String, val cardId: String, val title: String,
    val startedAt: Long, val endsAt: Long, val finishedAt: Long = 0,
    val reason: String = "", val outcome: String = "",
) {
    val active get() = finishedAt == 0L
    fun remainingMinutes(now: Long) = ((endsAt - now).coerceAtLeast(0) + 59_999) / 60_000
    fun json() = JSONObject().put("id", id).put("card_id", cardId).put("title", title)
        .put("started_at", startedAt).put("ends_at", endsAt).put("finished_at", finishedAt)
        .put("reason", reason).put("outcome", outcome)
    companion object {
        fun parse(value: String): RabbitHoleSession = JSONObject(value).let {
            RabbitHoleSession(it.getString("id"), it.getString("card_id"), it.getString("title"),
                it.getLong("started_at"), it.getLong("ends_at"), it.getLong("finished_at"),
                it.getString("reason"), it.getString("outcome"))
        }
    }
}
