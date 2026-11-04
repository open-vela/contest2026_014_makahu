package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import java.security.MessageDigest

internal data class VelaCardStateChange(
    val captureId: String,
    val cardId: String,
    val revision: Long,
    val actionId: String,
    val previousState: String,
    val state: String,
    val deleted: Boolean,
    val reviewAt: Long,
    val fingerprint: String,
)

/** State changes are separate from immutable processed-capture imports. The
 * caller supplies the identity of the currently authorized Fabric peer. */
internal object VelaCardStateProtocol {
    private val id = Regex("[A-Za-z0-9_-]{1,79}")
    private val cardId = Regex("[A-Za-z0-9_-]{1,79}")
    private val actionId = Regex("[A-Za-z0-9_-]{1,79}")
    private val states = setOf("active", "done", "later", "archived", "deleted",
        "reminder_pending", "research_requested")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun decode(bytes: ByteArray): VelaCardStateChange {
        require(bytes.size in 1..1024) { "invalid card state message size" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "invalid UTF-8" }
        val value = JSONObject(text)
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "capture_id",
            "card_id", "revision", "action_id", "previous_state", "state", "deleted", "review_at"))
        require(value.get("type") == "buffer.card.state.change" && value.get("protocol") == 1)
        val capture = value.get("capture_id")
        val card = value.get("card_id")
        val revision = safeInteger(value.get("revision"), 1)
        val action = value.get("action_id")
        val previous = value.get("previous_state")
        val state = value.get("state")
        val deleted = value.get("deleted")
        val reviewAt = safeInteger(value.get("review_at"), 0)
        require(capture is String && id.matches(capture))
        require(card is String && cardId.matches(card) && card.startsWith("vla_"))
        require(action is String && actionId.matches(action) && action == "${card}_r$revision")
        require(previous is String && previous in states && state is String && state in states)
        require(deleted is Boolean && deleted == (state == "deleted"))
        require(if (state == "later") reviewAt > 0 else reviewAt == 0L)
        return VelaCardStateChange(capture, card, revision, action, previous, state, deleted,
            reviewAt, MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) })
    }

    fun apply(bytes: ByteArray, trustedDeviceId: String, repository: BufferRepository,authorize: ()->Boolean = {true}): ByteArray {
        val change = decode(bytes)
        require(trustedDeviceId.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val state = repository.applyAuthenticatedVelaCardState(trustedDeviceId, change,authorize)
        return JSONObject().put("type", "buffer.card.state.reply").put("protocol", 1)
            .put("capture_id", change.captureId).put("card_id", change.cardId)
            .put("revision", change.revision).put("action_id", change.actionId).put("state", state)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun safeInteger(value: Any?, minimum: Long): Long {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number >= minimum.toDouble() &&
            number <= MAX_SAFE_INTEGER.toDouble() && number == number.toLong().toDouble())
        return number.toLong()
    }
}
