package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class BufferClassificationJob(
    val captureId: String,
    val transcript: String,
)

data class BufferClassificationProposal(
    val text: String,
    val kind: String,
    val title: String,
    val summary: String,
    val healthCategory: String?,
    val confidence: Double,
    val relatedCardIds: List<String> = emptyList(),
    val presentation: String? = null,
)

/**
 * Runs optional Coordinator/LLM classification after the immutable capture and
 * the local fallback proposal already exist. The worker only applies proposals;
 * user confirmation remains a separate event in BufferRepository.
 */
class BufferClassificationWorker(
    private val repository: BufferRepository,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        executor.scheduleWithFixedDelay(::process, 0, 5, TimeUnit.SECONDS)
    }

    fun requestProcessing() {
        if (!executor.isShutdown) executor.execute(::process)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun process() {
        try {
            val config = repository.llmConfig()
            if (!config.ready) return
            for (job in repository.pendingClassificationJobs()) {
                val lease = repository.claimClassificationRequest(job.captureId) ?: continue
                try {
                    val proposals = BufferCaptureAgent.classify(config, job, repository, lease)
                    repository.applyRemoteClassification(job.captureId, proposals, lease)
                } catch (error: Exception) {
                    repository.markClassificationRequestFailed(job.captureId, lease,
                        error.message ?: "分类服务不可用")
                }
            }
        } catch (error: Exception) {
            // Storage failures leave the lease recoverable and must not cancel polling.
            Log.w("BufferClassification", "Classification pass failed; will retry", error)
        }
    }
}

internal object BufferClassificationParser {
    private val allowedKinds = setOf(
        "spark_card",
        "question_card",
        "task_card",
        "care_card",
        "weekly_card",
    )
    private val allowedHealthCategories = LifeLogCategories.labels.keys

    internal fun parse(response: JSONObject): List<BufferClassificationProposal> {
        val segments = response.optJSONArray("segments")
            ?: throw IOException("classification response has no segments")
        require(segments.length() in 1..MAX_SEGMENTS) {
            "classification response has an invalid segment count"
        }
        return buildList {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index)
                    ?: throw IOException("classification segment $index is invalid")
                val text = stringField(segment, "text").trim()
                val kind = stringField(segment, "kind").trim()
                val summary = stringField(segment, "summary").trim()
                require(text.isNotEmpty() && text.length <= MAX_TEXT_LENGTH) {
                    "classification segment text is invalid"
                }
                require(kind in allowedKinds) { "unsupported classification kind: $kind" }
                require(summary.isNotEmpty() && summary.length <= MAX_SUMMARY_LENGTH) {
                    "classification summary is invalid"
                }
                val title = stringField(segment, "title", optional = true).trim().ifBlank { summary.takeCodePoints(32) }
                require(title.length <= MAX_TITLE_LENGTH) { "classification title is too long" }
                val rawCategory = if (segment.isNull("health_category")) {
                    ""
                } else {
                    stringField(segment, "health_category", optional = true).trim()
                }
                val healthCategory = rawCategory.ifBlank { null }?.also {
                    require(it in allowedHealthCategories) {
                        "unsupported health category: $it"
                    }
                }
                require(kind != "question_card" || healthCategory == null) {
                    "question cards cannot be health records"
                }
                val confidence = if (segment.has("confidence")) {
                    val value = segment.get("confidence")
                    require(value is Number) { "classification confidence must be a number" }
                    value.toDouble().also {
                        require(it.isFinite() && it in 0.0..1.0) { "classification confidence is invalid" }
                    }
                } else 0.5
                add(
                    BufferClassificationProposal(
                        text = text,
                        kind = kind,
                        title = title,
                        summary = summary,
                        healthCategory = healthCategory,
                        confidence = confidence,
                    ),
                )
            }
        }
    }

    private fun stringField(value: JSONObject, name: String, optional: Boolean = false): String {
        if (optional && (!value.has(name) || value.isNull(name))) return ""
        val field = value.get(name)
        require(field is String) { "classification $name must be a string" }
        return field
    }

    private const val MAX_SEGMENTS = 8
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private const val MAX_TEXT_LENGTH = 4_000
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_SUMMARY_LENGTH = 1_000
}
