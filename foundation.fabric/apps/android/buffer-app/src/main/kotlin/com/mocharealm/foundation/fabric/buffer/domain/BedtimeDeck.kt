package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer

/** Opt-in reading keeps stimulation a user choice, not a keyword guess. */
internal object BedtimeDeck {
    const val SAFE_TAG = "睡前可读"
    private val kinds = setOf("care_card", "question_card", "spark_card", "weekly_card")
    private val excludedStates = setOf("archived", "done", "later", "rabbit_hole", "rabbit_hole_candidate")
    private val gentleActions = setOf("later", "archive", "done")

    fun project(card: BufferCard): BufferCard? {
        if (card.kind !in kinds || card.state in excludedStates) return null
        if (card.kind != "care_card" && SAFE_TAG !in card.tags) return null
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
}
