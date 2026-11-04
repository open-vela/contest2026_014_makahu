package com.mocharealm.foundation.fabric.buffer.data.local

import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdtView
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** Parses a saved document observation, never its pending action/executor. */
internal object BufferSourceCrdtProjection {
    data class View(val document: JSONObject?, val capture: String?)
    fun parse(record: BufferSourceEvent): View? {
        val event = record.event; val p = event.getJSONObject("payload")
        val type = event.getString("type")
        if (type in setOf("CardDocumentObserved", "CalendarDocumentObserved", "DocumentMerged") && p.has("document")) {
            require(p.keys().asSequence().toSet() == setOf("id", "clock", "document", "audience"))
            val document = p.getJSONObject("document"); BufferCrdtView.validate(document)
            require(p.get("id") == document.getString("id"))
            val clock = p.get("clock"); require(clock is Number && clock.toDouble().isFinite() && clock.toDouble() == clock.toLong().toDouble() && clock.toLong() == BufferCrdt.clock(document))
            require(p.get("audience") is String && BufferDocumentSource.validAudience(p.getString("audience")))
            require(type != "CardDocumentObserved" || document.getString("kind") == "card")
            require(type != "CalendarDocumentObserved" || document.getString("kind") == "calendar")
            val capture = BufferCrdt.values(document).opt("capture_id")?.takeUnless { it == JSONObject.NULL }?.also {
                require(it is String && it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
            } as String?
            // Audience is a sender boundary, never a receiver-side grant.
            return View(document, capture)
        }
        val kind = p.opt("projection_kind")
        if (type !in setOf("ReplicaDocumentObserved", "CalendarReplicaObserved", "CardActionPerformed", "LegacyProjectionSnapshot") ||
            kind !in setOf("replica", "calendar")) return null
        require(type != "CalendarReplicaObserved" || kind == "calendar")
        require(type !in setOf("ReplicaDocumentObserved", "CardActionPerformed") || kind == "replica")
        require(kind != "calendar" || !p.has("action"))
        require(p.keys().asSequence().toSet() in setOf(setOf("projection_kind", "entity_id", "projection_text"),
            setOf("projection_kind", "entity_id", "projection_text", "action")))
        val id = p.get("entity_id"); require(id is String && id.matches(Regex("[A-Za-z0-9_-]{1,79}")))
        if (p.has("action")) require(p.get("action") is String && p.getString("action").matches(Regex("[A-Za-z0-9_-]{1,79}")))
        if (p.isNull("projection_text")) return View(null, null) // cache removal is not an entity tombstone
        val raw = p.get("projection_text"); require(raw is String)
        val wrapper = BufferSourceEvent.parseObject(raw, 65536)
        require(wrapper.get("card_id") == id)
        if (!wrapper.has("crdt")) return null // old scalar revisions have a different merge contract
        val document = wrapper.getJSONObject("crdt"); BufferCrdtView.validate(document)
        require(document.getString("id") == id)
        require(kind != "calendar" || document.getString("kind") == "calendar")
        if (wrapper.has("deleted")) require(wrapper.get("deleted") == document.getBoolean("deleted"))
        val values = BufferCrdt.values(document)
        val capture = values.opt("capture_id")?.takeUnless { it == JSONObject.NULL }?.also {
            require(it is String && it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        } as String?
        return View(document, capture)
    }
}
