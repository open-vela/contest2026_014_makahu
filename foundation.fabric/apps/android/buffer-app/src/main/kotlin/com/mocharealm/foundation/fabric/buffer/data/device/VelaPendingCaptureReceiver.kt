package com.mocharealm.foundation.fabric.buffer.data.device

import android.os.SystemClock
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class VelaPendingCaptureInput(
    val captureId: String,
    val mode: String,
    val createdAt: Long,
    val durationMs: Long,
    val contentType: String,
    val recordSha256: String,
    val recordBytes: ByteArray,
    val target: String = "buffer",
)

/** Wire contract for raw, still-pending Vela captures. Hash the exact UTF-8 or WAV bytes. */
internal object VelaPendingCaptureProtocol {
    const val MAX_FRAME_BYTES = 12 * 1024
    const val MAX_AUDIO_BYTES = 512 * 1024
    const val MAX_TEXT_BYTES = 8 * 1024
    private const val MAX_PART_BYTES = 3072
    private val captureIdPattern = Regex("[A-Za-z0-9._-]{1,80}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val fabricDeviceIdPattern = Regex("[0-9a-f]{64}")
    private val modes = setOf("normal", "focus", "bedtime", "rabbit_hole")
    private val fields = setOf(
        "type", "protocol", "capture_id", "mode", "created_at", "duration_ms",
        "content_type", "record_sha256", "offset", "total", "record_base64",
    )

    internal data class Part(
        val captureId: String,
        val mode: String,
        val createdAt: Long,
        val durationMs: Long,
        val contentType: String,
        val recordSha256: String,
        val offset: Int,
        val total: Int,
        val bytes: ByteArray,
        val target: String,
    )

    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun deterministicCardId(captureId: String, segmentIndex: Int): String {
        require(captureIdPattern.matches(captureId) && segmentIndex >= 0)
        val captureHash = MessageDigest.getInstance("SHA-256")
            .digest(captureId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "vla_${captureHash}_$segmentIndex"
    }

    fun isFabricDeviceId(deviceId: String): Boolean = fabricDeviceIdPattern.matches(deviceId)

    fun decodePart(payload: ByteArray): Part {
        require(payload.size in 1..MAX_FRAME_BYTES) { "capture input frame exceeds limit" }
        val text = payload.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(payload)) { "invalid UTF-8 frame" }
        val json = JSONObject(text)
        val actualFields = json.keys().asSequence().toSet()
        require(actualFields - "target" == fields) { "unexpected capture input fields" }
        val target = if (json.has("target")) json.get("target") else "buffer"
        require(target is String && target in setOf("auto", "buffer")) { "invalid capture target" }
        require(json.get("type") == "vela.capture.input.part" && integer(json, "protocol") == 1) {
            "unsupported capture input protocol"
        }
        val captureId = string(json, "capture_id", 80).also {
            require(captureIdPattern.matches(it)) { "invalid capture id" }
        }
        val mode = string(json, "mode", 20).also { require(it in modes) { "invalid Buffer mode" } }
        val createdAt = long(json, "created_at").also {
            require(it in 1..9_007_199_254_740_991L) { "invalid capture timestamp" }
        }
        val duration = long(json, "duration_ms").also { require(it in 0..86_400_000L) { "invalid duration" } }
        val contentType = string(json, "content_type", 64)
        val total = integer(json, "total")
        when (contentType) {
            "audio/wav" -> require(total in 44..MAX_AUDIO_BYTES) {
                "invalid WAV capture size"
            }
            "text/plain", "text/plain; charset=utf-8" -> require(total in 1..MAX_TEXT_BYTES) {
                "invalid text capture size"
            }
            else -> error("unsupported capture content type")
        }
        val digest = string(json, "record_sha256", 64).also {
            require(sha256Pattern.matches(it)) { "invalid capture hash" }
        }
        val offset = integer(json, "offset").also { require(it in 0 until total) { "invalid capture offset" } }
        val encoded = string(json, "record_base64", 4096)
        require(encoded.length in 4..4096 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) {
            "invalid capture chunk encoding"
        }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in 1..MAX_PART_BYTES && Base64.getEncoder().encodeToString(bytes) == encoded) {
            "invalid capture chunk size or encoding"
        }
        require(offset + bytes.size <= total) { "capture chunk exceeds declared input" }
        return Part(captureId, mode, createdAt, duration, contentType, digest, offset, total, bytes, target)
    }

    fun partialReply(part: Part, nextOffset: Int): ByteArray = JSONObject()
        .put("type", "vela.capture.input.part.reply")
        .put("protocol", 1)
        .put("capture_id", part.captureId)
        .put("record_sha256", part.recordSha256)
        .put("next_offset", nextOffset)
        .toString().toByteArray(Charsets.UTF_8)

    fun finalReply(input: VelaPendingCaptureInput, state: String): ByteArray {
        require(state in setOf("imported", "duplicate", "conflict"))
        return JSONObject().put("type", "vela.capture.input.reply")
            .put("protocol", 1)
            .put("capture_id", input.captureId)
            .put("record_sha256", input.recordSha256)
            .put("state", state)
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun validateRecord(input: VelaPendingCaptureInput) {
        require(captureIdPattern.matches(input.captureId) && input.mode in modes)
        require(input.target in setOf("auto", "buffer"))
        require(input.createdAt in 1..9_007_199_254_740_991L)
        require(input.durationMs in 0..86_400_000L)
        val maxBytes = if (input.contentType == "audio/wav") MAX_AUDIO_BYTES else MAX_TEXT_BYTES
        require(input.recordBytes.size in 1..maxBytes)
        require(fingerprint(input.recordBytes) == input.recordSha256) { "capture input hash mismatch" }
        when (input.contentType) {
            "audio/wav" -> require(
                input.recordBytes.size >= 44 &&
                    String(input.recordBytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(input.recordBytes, 8, 4, Charsets.US_ASCII) == "WAVE",
            ) { "invalid WAV header" }
            "text/plain", "text/plain; charset=utf-8" -> {
                val text = input.recordBytes.toString(Charsets.UTF_8)
                require(text.toByteArray(Charsets.UTF_8).contentEquals(input.recordBytes)) { "invalid UTF-8 capture text" }
                require(text.isNotBlank() && '\u0000' !in text) { "empty or invalid capture text" }
            }
            else -> error("unsupported capture content type")
        }
    }

    private fun string(json: JSONObject, key: String, maxBytes: Int): String {
        val value = json.get(key)
        require(value is String && value.isNotBlank() && '\u0000' !in value) { "invalid $key" }
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "$key is too large" }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = long(json, key)
        require(value in 0..Int.MAX_VALUE.toLong()) { "invalid $key" }
        return value.toInt()
    }

    private fun long(json: JSONObject, key: String): Long {
        val number = json.get(key)
        require(number is Number && number.toDouble().isFinite()) { "invalid $key" }
        val value = number.toLong()
        require(number.toDouble() == value.toDouble()) { "invalid $key" }
        return value
    }
}

/**
 * One receiver per authenticated Fabric peer/channel. A final `imported` reply
 * acknowledges the phone-side import only; it never authorizes deleting Vela's
 * source audio. The sender retains that source until classification and card
 * replica completion are acknowledged separately.
 */
internal class VelaPendingCaptureReceiver(
    private val repository: BufferRepository,
    private val trustedDeviceId: String,
    private val authorize: () -> Boolean,
) : AutoCloseable {
    private data class Assembly(
        val captureId: String,
        val mode: String,
        val createdAt: Long,
        val durationMs: Long,
        val contentType: String,
        val recordSha256: String,
        val bytes: ByteArray,
        var received: Int,
        val startedAtElapsedMillis: Long,
        var lastProgressElapsedMillis: Long = startedAtElapsedMillis,
        val target: String,
    )

    private data class Completed(
        val input: VelaPendingCaptureInput,
        val state: String,
        val completedAtElapsedMillis: Long,
    )

    private var assembly: Assembly? = null
    private var completed: Completed? = null

    init { require(VelaPendingCaptureProtocol.isFabricDeviceId(trustedDeviceId)) }

    @Synchronized
    fun receiveAuthenticated(
        authenticatedPeerDeviceId: String,
        payload: ByteArray,
        nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ): ByteArray {
        check(authorize()) { "Fabric receive authorization required" }
        require(authenticatedPeerDeviceId == trustedDeviceId) { "unexpected authenticated Fabric peer" }
        require(nowElapsedRealtimeMillis >= 0L)
        val part = VelaPendingCaptureProtocol.decodePart(payload)

        val last = completed
        if (last != null && nowElapsedRealtimeMillis >= last.completedAtElapsedMillis &&
            nowElapsedRealtimeMillis - last.completedAtElapsedMillis <= ASSEMBLY_TTL_MILLIS &&
            sameMetadata(last.input, part) && part.offset + part.bytes.size <= last.input.recordBytes.size &&
            last.input.recordBytes.copyOfRange(part.offset, part.offset + part.bytes.size).contentEquals(part.bytes)
        ) {
            check(authorize()) { "Fabric authorization revoked before reply" }
            return VelaPendingCaptureProtocol.finalReply(last.input, last.state)
        }

        var current = assembly
        if (current != null) require(nowElapsedRealtimeMillis >= current.startedAtElapsedMillis)
        val expired = current != null &&
            (nowElapsedRealtimeMillis - current.lastProgressElapsedMillis > ASSEMBLY_IDLE_MILLIS ||
                nowElapsedRealtimeMillis - current.startedAtElapsedMillis > ASSEMBLY_TOTAL_MILLIS)
        if (current == null || expired || !sameMetadata(current, part)) {
            discardAssembly()
            discardCompleted()
            if (part.offset != 0) return JSONObject()
                .put("type", "vela.capture.input.restart").put("protocol", 1)
                .put("capture_id", part.captureId).put("record_sha256", part.recordSha256)
                .put("from_offset", part.offset).put("next_offset", 0)
                .toString().toByteArray(Charsets.UTF_8)
            current = Assembly(
                part.captureId, part.mode, part.createdAt, part.durationMs, part.contentType,
                part.recordSha256, ByteArray(part.total), 0, nowElapsedRealtimeMillis, target = part.target,
            )
            assembly = current
        }
        require(current.bytes.size == part.total && part.offset <= current.received) { "noncontiguous capture input" }
        if (part.offset < current.received) {
            require(part.offset + part.bytes.size <= current.received &&
                current.bytes.copyOfRange(part.offset, part.offset + part.bytes.size).contentEquals(part.bytes)
            ) { "conflicting duplicate capture chunk" }
        } else {
            part.bytes.copyInto(current.bytes, part.offset)
            current.received += part.bytes.size
            current.lastProgressElapsedMillis = nowElapsedRealtimeMillis
        }

        if (current.received < current.bytes.size) {
            check(authorize()) { "Fabric authorization revoked before reply" }
            return VelaPendingCaptureProtocol.partialReply(part, current.received)
        }

        val input = VelaPendingCaptureInput(
            current.captureId, current.mode, current.createdAt, current.durationMs,
            current.contentType, current.recordSha256, current.bytes, current.target,
        )
        VelaPendingCaptureProtocol.validateRecord(input)
        check(authorize()) { "Fabric authorization revoked before import" }
        val state = repository.importVelaPendingCapture(trustedDeviceId, input)
        check(authorize()) { "Fabric authorization revoked before reply" }
        assembly = null
        completed = Completed(input, state, nowElapsedRealtimeMillis)
        return VelaPendingCaptureProtocol.finalReply(input, state)
    }

    @Synchronized
    override fun close() {
        discardAssembly()
        discardCompleted()
    }

    private fun sameMetadata(a: Assembly, b: VelaPendingCaptureProtocol.Part): Boolean =
        a.captureId == b.captureId && a.mode == b.mode && a.createdAt == b.createdAt &&
            a.durationMs == b.durationMs && a.contentType == b.contentType &&
            a.recordSha256 == b.recordSha256 && a.target == b.target

    private fun sameMetadata(a: VelaPendingCaptureInput, b: VelaPendingCaptureProtocol.Part): Boolean =
        a.captureId == b.captureId && a.mode == b.mode && a.createdAt == b.createdAt &&
            a.durationMs == b.durationMs && a.contentType == b.contentType &&
            a.recordSha256 == b.recordSha256 && a.target == b.target

    private fun discardAssembly() {
        assembly?.bytes?.fill(0)
        assembly = null
    }

    private fun discardCompleted() {
        completed?.input?.recordBytes?.fill(0)
        completed = null
    }

    private companion object {
        const val ASSEMBLY_TTL_MILLIS = 120_000L
        const val ASSEMBLY_IDLE_MILLIS = 60_000L
        const val ASSEMBLY_TOTAL_MILLIS = 600_000L
    }
}
