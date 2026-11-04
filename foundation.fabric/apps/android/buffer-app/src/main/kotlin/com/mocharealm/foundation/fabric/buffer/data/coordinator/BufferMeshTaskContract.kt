package com.mocharealm.foundation.fabric.buffer.data.coordinator

import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import org.json.JSONObject

/** Task identity describes the immutable input, not the chosen executor or
 * current membership. A configuration change must not invent another task. */
internal object BufferMeshTaskContract {
    private val hash = Regex("[0-9a-f]{64}")
    private val fields = setOf("version", "job_id", "source_device", "source_event_id", "source_event_sha256",
        "stage", "input_revision", "skill_version", "input_sha256", "input_bytes")
    const val CLASSIFICATION_FORMAT = "application/vnd.buffer.classification+json"
    const val ANSWER_FORMAT = "application/vnd.buffer.answer+json"
    const val WEEKLY_FORMAT = "application/vnd.buffer.weekly+json"
    const val MAX_CLASSIFICATION_INPUT_BYTES = 128 * 1024
    fun digest(value: JSONObject): String = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    fun skill(offers: List<JSONObject>, stage: String = "transcribe"): String = when (stage) {
        "transcribe" -> if (offers.all { it.getJSONObject("capabilities").optLong("TRANSCRIBE", 0) == 2L }) "buffer-asr-v2" else "buffer-asr-v1"
        // CLASSIFY v1 declarations predate the remote task protocol. Every
        // member must explicitly support v2 before this protocol is admitted.
        "classify" -> {
            require(offers.isNotEmpty() && offers.all { it.getJSONObject("capabilities").optLong("CLASSIFY", 0) in 2L..3L })
            if (offers.all { it.getJSONObject("capabilities").getLong("CLASSIFY") == 3L }) "buffer-classify-v2" else "buffer-classify-v1"
        }
        "answer" -> {
            require(offers.isNotEmpty() && offers.all { it.getJSONObject("capabilities").optLong("ANSWER", 0) == 2L })
            "buffer-answer-v1"
        }
        "generate" -> {
            require(offers.isNotEmpty() && offers.all { it.getJSONObject("capabilities").optLong("GENERATE", 0) == 2L })
            "buffer-weekly-v1"
        }
        else -> error("unsupported mesh task stage")
    }
    fun capability(stage: String): CoordinatorCapability = when (stage) {
        "transcribe" -> CoordinatorCapability.TRANSCRIBE
        "classify" -> CoordinatorCapability.CLASSIFY
        "answer" -> CoordinatorCapability.ANSWER
        "generate" -> CoordinatorCapability.GENERATE
        else -> error("unsupported mesh task stage")
    }
    fun identity(value: JSONObject): String = digest(JSONObject(BufferCrdt.canonical(value)).apply { remove("job_id") })
    /** The legacy lease codec calls this slot captureId. A weekly target is
     * explicitly a subject in its contract; it never creates a capture row. */
    fun target(value: JSONObject): String = value.getString(if (value.get("stage") == "generate") "subject_id" else "capture_id")
    fun integer(value: JSONObject, field: String, range: LongRange): Long {
        val number = value.get(field)
        require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() in range)
        return number.toLong()
    }
    fun validate(value: JSONObject) {
        val stage = value.get("stage"); require(stage is String)
        capability(stage)
        val formatField = if (stage == "transcribe") "audio_format" else "input_format"
        val targetField = if (stage == "generate") "subject_id" else "capture_id"
        val version = when (stage) { "transcribe" -> 1; "generate" -> 3; else -> 2 }
        require(value.keys().asSequence().toSet() == fields + formatField + targetField && value.get("version") == version)
        (fields + formatField + targetField - setOf("version", "input_revision", "input_bytes")).forEach { require(value.get(it) is String) }
        listOf("job_id", "source_device", "source_event_sha256", "input_sha256").forEach { require(hash.matches(value.getString(it))) }
        require(target(value).matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(value.getString("source_event_id").matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        integer(value, "input_revision", 0L..BufferSourceEvent.MAX_COUNTER)
        if (stage == "transcribe") {
            require(value.get("skill_version") in setOf("buffer-asr-v1", "buffer-asr-v2"))
            integer(value, "input_bytes", 1L..BufferConstants.maxAudioBytes.toLong())
            val format = value.getString(formatField)
            require(format.length in 1..160 && '\u0000' !in format &&
                format.substringBefore(';') in setOf("audio/wav", "audio/x-wav", "pcm16/wav", "audio/mp4"))
        } else if (stage == "classify") {
            require(value.get("skill_version") in setOf("buffer-classify-v1", "buffer-classify-v2") && value.get(formatField) == CLASSIFICATION_FORMAT)
            integer(value, "input_bytes", 1L..MAX_CLASSIFICATION_INPUT_BYTES.toLong())
        } else if (stage == "answer") {
            require(value.get("skill_version") == "buffer-answer-v1" && value.get(formatField) == ANSWER_FORMAT)
            integer(value, "input_bytes", 1L..MAX_CLASSIFICATION_INPUT_BYTES.toLong())
        } else {
            require(stage == "generate" && target(value).matches(Regex("weekly--?[0-9]{1,16}")) &&
                value.get("skill_version") == "buffer-weekly-v1" && value.get(formatField) == WEEKLY_FORMAT)
            integer(value, "input_bytes", 1L..com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyCodec.MAX_INPUT_BYTES.toLong())
        }
        require(identity(value) == value.getString("job_id"))
    }
}
