package com.mocharealm.foundation.fabric.buffer.domain

import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import org.json.JSONObject
import java.security.MessageDigest

/** A semantic suggestion bound to the exact content, never a clinical label. */
internal data class BedtimeAssessment(val level: String, val reason: String) {
    fun json(): JSONObject = JSONObject().put("level", level).put("reason", reason)
    fun review(card: BufferCard): JSONObject = json().put("content_sha256", contentHash(card))
    companion object {
        fun parse(value: JSONObject): BedtimeAssessment {
            require(value.keys().asSequence().toSet() == setOf("level", "reason"))
            require(value.get("level") in setOf("low", "high", "unknown"))
            val reason = value.get("reason")
            require(reason is String && reason.isNotBlank() && reason.codePointCount(0, reason.length) <= 160 && '\u0000' !in reason)
            return BedtimeAssessment(value.getString("level"), reason)
        }
        fun validateReview(value: JSONObject): JSONObject {
            require(value.keys().asSequence().toSet() == setOf("level", "reason", "content_sha256"))
            parse(JSONObject().put("level", value.get("level")).put("reason", value.get("reason")))
            require(value.get("content_sha256") is String && value.getString("content_sha256").matches(Regex("[0-9a-f]{64}")))
            return value
        }
        fun fromReview(value: JSONObject?, card: BufferCard): BedtimeAssessment? {
            if (value == null) return null
            validateReview(value)
            if (value.getString("content_sha256") != contentHash(card)) return null
            return parse(JSONObject().put("level", value.get("level")).put("reason", value.get("reason")))
        }
        fun contentHash(card: BufferCard): String = MessageDigest.getInstance("SHA-256").run {
            for (text in listOf(card.title, card.summary, card.answer.orEmpty(), card.kind)) {
                update(text.toByteArray(Charsets.UTF_8)); update(0.toByte())
            }
            digest().joinToString("") { "%02x".format(it) }
        }
    }
}
