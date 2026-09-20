package com.mocharealm.foundation.fabric.buffer.domain.model

data class BufferCard(
    val cardId: String,
    val captureId: String?,
    val kind: String,
    val title: String,
    val summary: String,
    val state: String,
    val actions: List<String>,
    val createdAt: Long,
    val answer: String? = null,
    val healthCategory: String? = null,
    val classificationConfirmed: Boolean = false,
    val researchNotes: String? = null,
    val externalLinks: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val relatedCardIds: List<String> = emptyList(),
)

data class BufferCapture(
    val captureId: String,
    val sourceDevice: String,
    val createdAt: Long,
    val mode: String,
    val audioFormat: String?,
    val durationMs: Long,
    val blobPath: String?,
    val transcript: String?,
    val classification: String?,
)

data class WeeklyTheme(val tag: String, val cardIds: List<String>)
data class WeeklyDeferredTask(val card: BufferCard, val times: Int)
data class WeeklySuggestion(val cardId: String, val text: String)

data class WeeklyLifeCategory(
    val category: String,
    val label: String,
    val cardIds: List<String>,
    val recordedDays: Int,
    val examples: List<String>,
)

data class BufferWeeklySummary(
    val sparks: Int,
    val questions: Int,
    val tasksCompleted: Int,
    val careRecords: Int,
    val focusReminders: Int,
    val rabbitHoleCandidates: List<BufferCard>,
    val recurringThemes: List<WeeklyTheme> = emptyList(),
    val deferredTasks: List<WeeklyDeferredTask> = emptyList(),
    val nextWeekSuggestions: List<WeeklySuggestion> = emptyList(),
    val lifeOverview: List<WeeklyLifeCategory> = emptyList(),
    val unconfirmedLifeRecords: Int = 0,
)

internal object BufferConstants {
    const val ability = "app.buffer"
    const val protocolVersion = 1
    const val discoveryPort = 48_886
    const val pairingPort = 48_887
    const val bridgePort = 48_888
    const val focusReminderMs = 50 * 60 * 1000L
    const val focusSnoozeMs = 15 * 60 * 1000L
    const val maxAudioBytes = 512 * 1024
    const val maxPhoneRecordingMs = 30_000
}

/** Answers remain stored across reclassification but belong only on question displays. */
internal val BufferCard.displayAnswer: String?
    get() = answer.takeIf { kind == "question_card" }
