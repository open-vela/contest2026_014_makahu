package com.mocharealm.foundation.fabric.buffer.domain
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

internal enum class ClassificationDecision { ACCEPT, EDIT, REJECT }
internal data class ClassificationChange(val card: BufferCard, val eventType: String?)

internal object ClassificationReview {
    const val UNCLASSIFIED = "unclassified_card"
    val kinds = linkedMapOf(
        "spark_card" to "灵感", "question_card" to "问题", "task_card" to "待办",
        "care_card" to "照顾自己", "weekly_card" to "生活记录",
    )
    val healthCategories = LifeLogCategories.labels.keys
    private val reviewActions = listOf("accept_classification", "edit_classification", "reject_classification")
    private val retainedStates = setOf("archived", "done", "later")

    fun initialState(kind: String): String = when (kind) {
        "question_card" -> "unanswered_gray"
        "task_card" -> "inbox"
        "care_card" -> "active"
        "weekly_card" -> "logged"
        else -> "cooling"
    }

    fun proposalActions(kind: String, healthCategory: String?): List<String> {
        val base = when (kind) {
            "task_card" -> listOf("done", "later", "remind", "calendar")
            "question_card" -> listOf("mark_answered", "mark_researched", "rabbit_hole", "later", "archive")
            "care_card" -> listOf("done", "later")
            "spark_card" -> listOf("create_project", "create_article", "create_code_task", "rabbit_hole", "ferment", "archive", "later")
            else -> listOf("archive", "later")
        }
        return base + reviewActions + if (healthCategory in LifeLogCategories.exportable && kind != "question_card") listOf("health_sync") else emptyList()
    }

    /** Adds new review choices to existing persisted proposals without a migration. */
    fun normalizeActions(card: BufferCard): BufferCard {
        val base = card.actions.filterNot { it in reviewActions || (it == "health_sync" && card.healthCategory !in LifeLogCategories.exportable) }
        val actions = when {
            card.classificationConfirmed || card.state == "pending_transcription" -> base
            card.kind == UNCLASSIFIED -> base + "edit_classification"
            card.actions.any { it in reviewActions } -> base + reviewActions
            else -> base
        }
        return card.copy(actions = actions.distinct())
    }

    fun preserveActions(card: BufferCard, actions: List<String>): List<String> =
        normalizeActions(card.copy(actions = actions + "accept_classification")).actions

    fun decide(
        old: BufferCard, decision: ClassificationDecision,
        kind: String? = null, healthCategory: String? = null,
        expected: BufferCard = old,
    ): ClassificationChange {
        if (old.classificationConfirmed) {
            require(decision == ClassificationDecision.ACCEPT ||
                (decision == ClassificationDecision.EDIT && kind == old.kind && healthCategory == old.healthCategory)) {
                "分类已确认，请刷新后操作"
            }
            return ClassificationChange(old, null)
        }
        if (decision == ClassificationDecision.REJECT && old.kind == UNCLASSIFIED) {
            return ClassificationChange(old, null)
        }
        require(old == expected) { "分类提案已更新，请刷新后重新确认" }
        require(old.state != "pending_transcription") { "请先完成语音转写" }
        if (decision == ClassificationDecision.REJECT) {
            return ClassificationChange(old.copy(
                kind = UNCLASSIFIED, title = "待分类：${old.summary.takeCodePoints(32)}",
                state = old.state.takeIf { it in retainedStates } ?: "classification_rejected",
                actions = listOf("edit_classification", "archive", "later"),
                classificationConfirmed = false, healthCategory = null,
            ), "ClassificationRejected")
        }
        if (decision == ClassificationDecision.ACCEPT) {
            require(old.kind in kinds) { "请先编辑并选择分类" }
            return ClassificationChange(normalizeActions(old.copy(classificationConfirmed = true)), "ClassificationAccepted")
        }
        require(kind in kinds) { "请选择有效的分类" }
        require(healthCategory == null || healthCategory in healthCategories) { "请选择有效的健康类别" }
        require(kind != "question_card" || healthCategory == null) { "问题不直接作为健康记录，请选择“不是健康记录”" }
        val selectedKind = requireNotNull(kind)
        val state = when {
            old.state in retainedStates -> old.state
            selectedKind == "question_card" && !old.answer.isNullOrBlank() -> "quick_answered"
            else -> initialState(selectedKind)
        }
        val edited = old.copy(
            kind = selectedKind,
            title = if (old.kind == selectedKind) old.title else "${kinds.getValue(selectedKind)}：${old.summary.takeCodePoints(32)}",
            state = state, actions = proposalActions(selectedKind, healthCategory),
            healthCategory = healthCategory, classificationConfirmed = true,
        )
        return ClassificationChange(normalizeActions(edited), "ClassificationEdited")
    }
}
