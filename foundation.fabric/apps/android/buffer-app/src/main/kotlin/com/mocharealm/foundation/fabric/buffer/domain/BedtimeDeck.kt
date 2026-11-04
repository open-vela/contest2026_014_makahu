package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer
import org.json.JSONObject
import java.security.MessageDigest

/** Opt-in reading keeps stimulation a user choice, not a keyword guess. */
internal object BedtimeDeck {
    const val SAFE_TAG = "睡前可读"
    private val kinds = setOf("care_card", "question_card", "spark_card", "weekly_card")
    const val HIGH_TAG = "白天再研究"
    private val excludedStates = setOf("archived", "done", "deleted", "pending_transcription", "later", "rabbit_hole", "rabbit_hole_candidate", "research_requested")
    private val gentleActions = setOf("later", "archive", "done")

    fun project(card: BufferCard, assessment: BedtimeAssessment? = null, userApproved: Boolean = SAFE_TAG in card.tags): BufferCard? {
        if (card.kind !in kinds || card.state in excludedStates) return null
        if (HIGH_TAG in card.tags) return null
        if (!userApproved) {
            if (assessment?.level == "high") return null
            if (!(card.classificationConfirmed && assessment?.level == "low")) return null
        }
        val answer = card.displayAnswer
        if (card.summary.length > 280 || answer.orEmpty().length > 400) return null
        if (card.kind == "question_card" && answer.isNullOrBlank()) return null
        return card.copy(
            answer = answer,
            actions = card.actions.filter { it in gentleActions },
            researchNotes = null,
            externalLinks = emptyList(),
            relatedCardIds = emptyList(),
        )
    }

    /** Bind the phone's readability projection to the exact transmitted JSON.
     * C uses the same NUL-delimited UTF-8 fields before truncating previews. */
    fun fingerprint(card: JSONObject): String = MessageDigest.getInstance("SHA-256").run {
        val fields = listOf("title", "summary", "answer", "kind", "state")
        for (key in fields) {
            update((if (card.isNull(key)) "" else card.optString(key)).toByteArray(Charsets.UTF_8)); update(0.toByte())
        }
        update((if (card.optBoolean("classification_confirmed", false)) "1" else "0").toByteArray(Charsets.UTF_8)); update(0.toByte())
        card.optJSONArray("tags")?.let { tags ->
            for (i in 0 until tags.length()) { update(tags.getString(i).toByteArray(Charsets.UTF_8)); update(0.toByte()) }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
}
