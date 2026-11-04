package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantHandoff
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobLease
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeAssessment
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import android.util.Log
import android.os.SystemClock
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
    val bedtime: String? = null,
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
            for (job in repository.pendingClassificationJobs(eligible = {
                !repository.meshManagesLocalClassification(it.captureId) && (config.ready || repository.assistantDispositionPending(it.captureId))
            })) {
                val capture = repository.findCapture(job.captureId) ?: continue
                val needsCoordinatorFence = BufferCoordinatorJobGate.requiresFence(capture.sourceDevice)
                val coordinatorLease = if (needsCoordinatorFence) {
                    BufferCoordinatorJobGate.acquire(
                        captureId = job.captureId,
                        sourceDeviceId = capture.sourceDevice,
                        stage = "classify",
                        inputRevision = capture.createdAt,
                        capability = CoordinatorCapability.CLASSIFY,
                        nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    ) ?: continue
                } else null
                try {
                    val requestLease = repository.claimClassificationRequest(job.captureId, expectedTranscript = job.transcript) ?: continue
                    try {
                        val assistantInput = if (coordinatorLease != null)
                            repository.velaAssistantInput(job.captureId, requestLease) else null
                        val handoff: (() -> Unit)? = if (assistantInput != null && coordinatorLease != null) ({
                            repository.selectAssistantHandoff(job.captureId, requestLease)
                            VelaAssistantHandoff.route(assistantInput) {
                                BufferCoordinatorJobGate.termIfCurrent(coordinatorLease) { SystemClock.elapsedRealtime() }
                            }
                        }) else null
                        val proposals = if (repository.assistantHandoffSelected(job.captureId, requestLease)) {
                            requireNotNull(handoff).invoke()
                            emptyList()
                        } else BufferCaptureAgent.classifyAuthorized(config, job, repository, requestLease, authorize = {
                            coordinatorLease == null || BufferCoordinatorJobGate.termIfCurrent(coordinatorLease) { SystemClock.elapsedRealtime() } != null
                        }, assistantHandoff = handoff)
                        if (coordinatorLease == null) {
                            repository.applyRemoteClassification(job.captureId, proposals, requestLease, expectedTranscript = job.transcript)
                        } else {
                            commitVelaClassificationIfCurrent(
                                repository = repository,
                                captureId = job.captureId,
                                requestLease = requestLease,
                                coordinatorLease = coordinatorLease,
                            ) {
                                if (proposals.isEmpty()) repository.applyAssistantHandoff(job.captureId, requestLease)
                                else repository.applyRemoteClassification(job.captureId, proposals, requestLease, expectedTranscript = job.transcript)
                            }
                        }
                    } catch (error: Exception) {
                        val markFailed = {
                            repository.markClassificationRequestFailed(
                                job.captureId,
                                requestLease,
                                error.message ?: "分类服务不可用",
                            )
                        }
                        if (coordinatorLease == null) markFailed()
                        else commitVelaClassificationIfCurrent(
                            repository = repository,
                            captureId = job.captureId,
                            requestLease = requestLease,
                            coordinatorLease = coordinatorLease,
                            commit = markFailed,
                        )
                    }
                } finally {
                    coordinatorLease?.let(BufferCoordinatorJobGate::release)
                }
            }
        } catch (error: Exception) {
            // Storage failures leave the lease recoverable and must not cancel polling.
            Log.w("BufferClassification", "Classification pass failed; will retry", error)
        }
    }
}

/** Commit under the current Vela coordinator fence, or release our DB request lease for retry. */
internal fun commitVelaClassificationIfCurrent(
    repository: BufferRepository,
    captureId: String,
    requestLease: String,
    coordinatorLease: BufferCoordinatorJobLease,
    commit: () -> Unit,
): Boolean {
    // Repository methods synchronize on this same object. Taking it first keeps the
    // lock order Repository -> Gate -> election -> DB and prevents reverse acquisition.
    val committed = synchronized(repository) {
        BufferCoordinatorJobGate.commitIfCurrent(
            coordinatorLease,
            SystemClock.elapsedRealtime(),
            commit,
        )
    }
    if (committed == null) repository.releaseClassificationRequest(captureId, requestLease)
    return committed != null
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
                        bedtime = if (!segment.has("bedtime") || segment.isNull("bedtime")) null
                            else BedtimeAssessment.parse(segment.getJSONObject("bedtime")).json().toString(),
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
