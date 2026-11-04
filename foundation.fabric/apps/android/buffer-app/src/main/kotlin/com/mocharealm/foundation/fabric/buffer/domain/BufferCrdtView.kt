package com.mocharealm.foundation.fabric.buffer.domain

import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import org.json.JSONObject

/** Pure materialized-view validation, shared by live merges and source replay.
 * A structurally valid register map is not necessarily a usable card. */
internal object BufferCrdtView {
    private val id = Regex("[A-Za-z0-9_.:-]{1,160}")
    private fun text(j: JSONObject, key: String, max: Int, nullable: Boolean = false): String? {
        if (!j.has(key) || j.isNull(key)) { require(nullable); return null }
        val value = j.get(key); require(value is String && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= max)
        return value
    }
    private fun integer(j: JSONObject, key: String): Long {
        val n = j.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in 0L..BufferSourceEvent.MAX_COUNTER)
        return n.toLong()
    }
    private fun strings(j: JSONObject, key: String, maximum: Int, check: (String) -> Boolean) {
        if (!j.has(key)) return
        val a = j.getJSONArray(key); require(a.length() <= maximum)
        (0 until a.length()).forEach { require(a.get(it) is String && check(a.getString(it))) }
    }
    fun validate(document: JSONObject) {
        BufferCrdt.validate(document)
        if (document.getBoolean("deleted")) return
        if (document.getString("kind") == "calendar") { CalendarEntry.fromDocument(document); return }
        val v = BufferCrdt.values(document)
        require(v.get("card_id") == document.getString("id"))
        validateCardFields(v, document.getString("id"))
    }
    /** Validate retained field values without minting CRDT registers. */
    fun validateCardFields(v: JSONObject, documentId: String, partial: Boolean = false) {
        if (!partial || v.has("kind")) require(v.get("kind") in setOf("task_card", "spark_card", "question_card", "care_card", "weekly_card", "rabbit_hole_card",
            "project_card", "article_card", "code_card", ClassificationReview.UNCLASSIFIED))
        if (!partial || v.has("state")) require(!text(v, "state", 40).isNullOrBlank())
        if (!partial || v.has("title")) text(v, "title", 1024)
        if (!partial || v.has("summary")) text(v, "summary", 16384)
        if (!partial || v.has("created_at")) integer(v, "created_at")
        text(v, "capture_id", 160, true)?.let { require(id.matches(it)) }
        listOf("answer", "research_notes", "text").forEach { text(v, it, 24000, true) }
        text(v, "health_category", 96, true)
        if (v.has("classification_confirmed")) require(v.get("classification_confirmed") is Boolean)
        strings(v, "actions", 64) { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }
        strings(v, "tags", 64) { it.isNotBlank() && it.length <= 160 && '\u0000' !in it }
        strings(v, "related_card_ids", 32) { id.matches(it) && it != documentId }
        strings(v, "external_links", 64) {
            val uri = runCatching { java.net.URI(it) }.getOrNull()
            it.length <= 24000 && '\u0000' !in it && uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()
        }
        if (v.has("schedule") && !v.isNull("schedule")) TaskSchedule.parse(v.getJSONObject("schedule"))
        if (v.has("presentation") && !v.isNull("presentation")) BufferCaptureAgent.validatePresentation(v.getJSONObject("presentation"))
        if (v.has("bedtime_review") && !v.isNull("bedtime_review")) BedtimeAssessment.validateReview(v.getJSONObject("bedtime_review"))
        text(v, "bedtime_user_review", 64, true)?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        if (v.has("capture_context") && !v.isNull("capture_context")) CaptureContext.validate(v.getJSONObject("capture_context"))
        if (v.has("conversation")) {
            val turns = v.getJSONArray("conversation"); require(turns.length() <= 8)
            for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                require(id.matches(requireNotNull(text(turn, "capture_id", 160))))
                require(!text(turn, "question", 8192).isNullOrBlank() && !text(turn, "answer", 8192).isNullOrBlank())
                integer(turn, "created_at")
                if (turn.has("card_id")) require(turn.get("card_id") == documentId)
            }
        }
    }
}
