package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationParser
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class VelaProcessedSegment(
    val cardId: String,
    val state: String,
    val proposal: BufferClassificationProposal,
)

internal data class VelaProcessedCapture(
    val requestId: String,
    val fingerprint: String,
    val captureId: String,
    val transcript: String,
    val mode: String,
    val createdAt: Long,
    val segments: List<VelaProcessedSegment>,
    val toolReceipts: String?,
    val followup: String? = null,
    val focusSession: FocusSession? = null,
)

/** Application payload only. Caller must authenticate and authorize its Fabric
 * peer/channel before parsing, importing or acknowledging these private records.
 * Hash the exact UTF-8 record_json, avoiding cross-language JSON canonicalization.
 * The v1 payload is an immutable processed snapshot; later edits are separate events.
 */
internal object VelaProcessedCaptureProtocol {
    // Internal complete envelope only; every actual carrier frame stays <=12KiB.
    const val MAX_MESSAGE_BYTES = 132 * 1024
    private const val MAX_RECORD_BYTES = 64 * 1024
    private val id = Regex("[A-Za-z0-9_-]{1,79}")
    private val hash = Regex("[0-9a-f]{64}")

    fun fingerprint(recordJson: String): String = MessageDigest.getInstance("SHA-256")
        .digest(recordJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun encode(recordJson: String): ByteArray {
        require(recordJson.toByteArray(Charsets.UTF_8).size in 1..MAX_RECORD_BYTES)
        val digest = fingerprint(recordJson)
        return JSONObject().put("type", "vela.capture.processed").put("protocol", 1)
            .put("request_id", digest.take(32)).put("record_sha256", digest)
            .put("record_json", recordJson).toString().toByteArray(Charsets.UTF_8).also {
                require(it.size <= MAX_MESSAGE_BYTES)
            }
    }

    fun decode(bytes: ByteArray): VelaProcessedCapture {
        require(bytes.size in 1..MAX_MESSAGE_BYTES) { "processed capture message exceeds limit" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "invalid UTF-8" }
        val message = JSONObject(text)
        require(message.keys().asSequence().toSet() == setOf("type", "protocol", "request_id", "record_sha256", "record_json"))
        require(message.get("type") == "vela.capture.processed" && message.get("protocol") == 1)
        val recordJson = string(message, "record_json", MAX_RECORD_BYTES)
        val digest = string(message, "record_sha256", 64)
        require(hash.matches(digest) && digest == fingerprint(recordJson)) { "record fingerprint mismatch" }
        val requestId = string(message, "request_id", 32)
        require(requestId == digest.take(32)) { "request does not match immutable snapshot" }
        val record = JSONObject(recordJson)
        require(record.get("state") == "ready" ||
            (record.get("state") == "assistant_complete" && record.optString("target") == "assistant" && (record.has("followup") || record.has("focus_session")))) {
            "only completed captures may be imported"
        }
        val captureId = string(record, "capture_id", 79).also { require(id.matches(it)) }
        val transcript = string(record, "transcript", 8192)
        val mode = string(record, "mode", 20).also {
            require(it in setOf("normal", "focus", "bedtime", "rabbit_hole"))
        }
        val timestamp = record.get("created_at")
        require(timestamp is Number && timestamp.toDouble().isFinite() &&
            timestamp.toDouble() in 0.0..9_007_199_254_740_991.0 &&
            timestamp.toDouble() == timestamp.toLong().toDouble()) { "invalid creation timestamp" }
        if (record.get("state") == "assistant_complete" && record.has("focus_session")) {
            val session = FocusSession.parse(record.getJSONObject("focus_session"))
            require(!record.has("followup"))
            return VelaProcessedCapture(requestId, digest, captureId, transcript, mode,
                timestamp.toLong(), emptyList(), null, focusSession = session)
        }
        if (record.get("state") == "assistant_complete") {
            val turn = record.getJSONObject("followup")
            require(turn.keys().asSequence().toSet() == setOf("card_id", "capture_id", "question", "answer", "created_at"))
            require(id.matches(string(turn, "card_id", 79)))
            require(string(turn, "capture_id", 79) == captureId)
            require(string(turn, "question", 4096) == transcript)
            require(string(turn, "answer", 4096).isNotBlank())
            require(turn.get("created_at") is Number && turn.getLong("created_at") == timestamp.toLong())
            return VelaProcessedCapture(requestId, digest, captureId, transcript, mode,
                timestamp.toLong(), emptyList(), null, turn.toString())
        }
        val proposals = BufferClassificationParser.parse(record)
        val parts = record.getJSONArray("segments")
        val expectedPrefix = "vla_${fingerprint(captureId).take(32)}_"
        val segments = proposals.mapIndexed { index, proposal ->
            val part = parts.getJSONObject(index)
            val cardId = string(part, "card_id", 79)
            require(cardId == "$expectedPrefix$index") { "unexpected derived card ID" }
            val state = string(part, "state", 40)
            require(state in setOf("active", "done", "later", "archived", "deleted", "reminder_pending", "research_requested")) { "unsupported card state" }
            val related = part.optJSONArray("related_card_ids") ?: JSONArray()
            require(related.length() <= 8)
            val relatedIds = (0 until related.length()).map {
                val value = related.get(it)
                require(value is String && id.matches(value) && value != cardId)
                value
            }
            require(relatedIds.distinct().size == relatedIds.size)
            val view = if (part.has("presentation") && !part.isNull("presentation")) {
                part.getJSONObject("presentation").also(BufferCaptureAgent::validatePresentation).toString()
            } else null
            VelaProcessedSegment(cardId, state, proposal.copy(relatedCardIds = relatedIds, presentation = view))
        }
        val receipts = record.optJSONArray("tool_receipts")?.also { values ->
            require(values.length() <= 8)
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                require(value.get("tool") in setOf("search_records", "read_record"))
                require(value.get("success") is Boolean)
                val count = value.get("count")
                require(count is Number && count.toDouble() in 0.0..256.0 && count.toDouble() == count.toInt().toDouble())
            }
        }?.toString()
        return VelaProcessedCapture(requestId, digest, captureId, transcript, mode,
            timestamp.toLong(), segments, receipts)
    }

    fun reply(capture: VelaProcessedCapture, result: String): ByteArray {
        require(result in setOf("imported", "duplicate", "conflict"))
        return JSONObject().put("type", "vela.capture.processed.reply").put("protocol", 1)
            .put("request_id", capture.requestId).put("capture_id", capture.captureId)
            .put("record_sha256", capture.fingerprint).put("state", result)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun string(value: JSONObject, field: String, maxBytes: Int): String {
        val text = value.get(field)
        require(text is String && text.isNotBlank() && '\u0000' !in text)
        val encoded = text.toByteArray(Charsets.UTF_8)
        require(encoded.size <= maxBytes && encoded.toString(Charsets.UTF_8) == text)
        return text
    }
}

/** One receiver per authenticated peer/session. Partial bytes never enter the
 * repository. Teardown discards assembly; sender replays from offset zero.
 * Only the final application receipt acknowledges a database commit. */
internal class VelaProcessedCaptureReceiver(
    private val repository: com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository,
    private val trustedDeviceId: String,
    private val authorize: () -> Boolean,
) {
    private var digest: String? = null
    private var bytes: ByteArray? = null
    private var received = 0
    private var started = 0L

    @Synchronized
    fun receiveAuthorized(payload: ByteArray, now: Long = android.os.SystemClock.elapsedRealtime()): ByteArray {
        check(authorize()) { "Fabric receive authorization required" }
        require(payload.size in 1..12288) { "message exceeds application limit" }
        val text = payload.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(payload))
        val message = JSONObject(text)
        if (message.optString("type") == "vela.capture.processed") {
            val value = VelaProcessedCaptureProtocol.decode(payload)
            check(authorize())
            return VelaProcessedCaptureProtocol.reply(value, repository.importVelaProcessedCapture(trustedDeviceId, value))
        }
        require(message.keys().asSequence().toSet() == setOf("type", "protocol", "request_id", "record_sha256", "offset", "total", "record_base64"))
        require(message.get("type") == "vela.capture.processed.part" && message.get("protocol") == 1)
        val hash = message.getString("record_sha256")
        require(hash.matches(Regex("[0-9a-f]{64}")) && message.get("request_id") == hash.take(32))
        fun integer(name: String): Int {
            val number = message.get(name)
            require(number is Number && number.toDouble() in 0.0..65536.0 && number.toDouble() == number.toInt().toDouble())
            return number.toInt()
        }
        val total = integer("total").also { require(it in 1..65536) }
        val offset = integer("offset")
        val encoded = message.getString("record_base64")
        require(encoded.length in 4..4096 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val part = java.util.Base64.getDecoder().decode(encoded)
        require(part.size in 1..3072 && offset + part.size <= total)
        if (digest != hash || now - started > 120000) {
            require(offset == 0) { "new snapshot must begin at zero" }
            bytes?.fill(0)
            digest = hash; bytes = ByteArray(total); received = 0; started = now
        }
        val target = requireNotNull(bytes)
        require(target.size == total && offset <= received) { "noncontiguous snapshot" }
        if (offset < received) {
            require(offset + part.size <= received && target.copyOfRange(offset, offset + part.size).contentEquals(part))
        } else {
            part.copyInto(target, offset); received += part.size
        }
        if (received == total) {
            val raw = target.toString(Charsets.UTF_8)
            require(raw.toByteArray(Charsets.UTF_8).contentEquals(target) && VelaProcessedCaptureProtocol.fingerprint(raw) == hash)
            val value = VelaProcessedCaptureProtocol.decode(VelaProcessedCaptureProtocol.encode(raw))
            check(authorize()) { "Fabric authorization revoked before import" }
            return VelaProcessedCaptureProtocol.reply(value, repository.importVelaProcessedCapture(trustedDeviceId, value))
        }
        check(authorize())
        return JSONObject().put("type", "vela.capture.processed.part.reply").put("protocol", 1)
            .put("request_id", hash.take(32)).put("record_sha256", hash).put("next_offset", received)
            .toString().toByteArray(Charsets.UTF_8)
    }

    @Synchronized fun close() { bytes?.fill(0); bytes = null; digest = null; received = 0 }
}
