package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small LAN data plane used by the Gemini-S1 light client.
 *
 * Each request and response is one UTF-8 JSON object terminated by LF. The
 * control messages stay small; a completed capture carries its bounded audio
 * blob as `audio_b64`. The first version deliberately keeps this separate from
 * the Fabric Hub control plane so Vela never has to implement Hub discovery or
 * QUIC.
 */
object BufferWire {
    fun handle(repository: BufferRepository, request: JSONObject): JSONObject {
        return try {
            when (request.optString("type")) {
                "hello" -> {
                    val protocol = request.optInt("protocol", -1)
                    if (protocol != BufferConstants.protocolVersion) {
                        error("unsupported_protocol", protocol.toString())
                    } else {
                        JSONObject()
                            .put("type", "hello_ack")
                            .put("protocol", BufferConstants.protocolVersion)
                            .put("ability", BufferConstants.ability)
                            .put("device_id", repository.deviceId())
                    }
                }
                "capture.created" -> {
                    val captureId = request.getString("capture_id")
                    val audio = Base64.decode(request.getString("audio_b64"), Base64.DEFAULT)
                    val card = repository.createAudioCapture(
                        captureId = captureId,
                        sourceDevice = request.optString("device_id", "vela-gemini-s1"),
                        createdAt = request.optLong("created_at", System.currentTimeMillis()),
                        mode = request.optString("mode", repository.mode()),
                        audioFormat = request.optString(
                            "audio_format",
                            "audio/wav;codec=pcm_s16le;rate=16000;channels=1",
                        ),
                        durationMs = request.optLong("duration_ms", 0),
                        audio = audio,
                    )
                    if (request.optString("input") == "button") {
                        repository.recordVelaButtonPressed(
                            request.optString("device_id", "vela-gemini-s1"),
                            captureId,
                        )
                    }
                    JSONObject()
                        .put("type", "ack")
                        .put("capture_id", captureId)
                        .put("card_id", card.cardId)
                        .put("received_at", System.currentTimeMillis())
                }
                "cards.get" -> cardsUpdated(repository)
                "card.action" -> {
                    val cardId = request.getString("card_id")
                    val action = request.getString("action")
                    val actionId = optionalOperationId(request, "action_id")
                    val card = repository.performCardAction(cardId, action, actionId)
                        ?: return error("card_not_found", cardId)
                    val resultingState = actionId?.let(repository::actionResultState) ?: card.state
                    JSONObject()
                        .put("type", "ack")
                        .put("card_id", cardId)
                        .put("action_id", actionId ?: JSONObject.NULL)
                        .put("action", action)
                        .put("state", resultingState)
                }
                "device.status" -> {
                    val deviceId = request.optString("device_id").trim()
                    require(deviceId.isNotEmpty()) { "device id is missing" }
                    repository.recordVelaStatus(
                        deviceId = deviceId,
                        mode = request.optString("mode", repository.mode()),
                        pendingCaptures = request.optInt("pending_captures", 0),
                        cardsStale = request.optBoolean("cards_stale", false),
                        recording = request.optBoolean("recording", false),
                    )
                    JSONObject()
                        .put("type", "ack")
                        .put("event", "DeviceStatusUpdated")
                        .put("device_id", deviceId)
                }
                "upload.retry" -> {
                    val deviceId = request.optString("device_id").trim()
                    val captureId = request.getString("capture_id")
                    require(deviceId.isNotEmpty()) { "device id is missing" }
                    repository.recordVelaUploadRetry(
                        deviceId,
                        captureId,
                        request.optString("detail", "upload retry requested"),
                    )
                    JSONObject()
                        .put("type", "ack")
                        .put("event", "UploadRetryRequested")
                        .put("capture_id", captureId)
                }
                "mode.changed" -> {
                    val mode = request.getString("mode")
                    val modeId = optionalOperationId(request, "mode_id")
                    repository.setMode(mode, modeId)
                    JSONObject()
                        .put("type", "ack")
                        .put("mode", mode)
                        .put("mode_id", modeId ?: JSONObject.NULL)
                }
                "status" -> status(repository)
                "ping" -> JSONObject().put("type", "pong")
                else -> error("unsupported_message", request.optString("type"))
            }
        } catch (error: Exception) {
            error("bad_request", error.message ?: "invalid Buffer message")
        }
    }

    private fun optionalOperationId(request: JSONObject, field: String): String? {
        if (!request.has(field)) return null // Older clients omit operation IDs.
        val value = request.get(field)
        require(value is String && value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "invalid $field"
        }
        return value
    }

    fun cardsUpdated(repository: BufferRepository): JSONObject = synchronized(repository) {
        val research = repository.rabbitSnapshot(System.currentTimeMillis())
        val selectedMode = repository.mode()
        val array = JSONArray()
        repository.cardsForMode(selectedMode).forEach { card -> array.put(card.toJson()) }
        JSONObject()
            .put("type", "display_cards.updated")
            .put("protocol", BufferConstants.protocolVersion)
            .put("mode", selectedMode)
            .put("cards", array)
            .put("research_session", research)
            .put("focus_reminder", repository.focusReminderSnapshot(System.currentTimeMillis()))
    }

    fun status(repository: BufferRepository): JSONObject = JSONObject()
        .put("type", "status")
        .put("mode", repository.mode())
        .put("pending_captures", repository.pendingCaptureCount())
        .put("event_count", repository.eventCount())
        .put("paired_velas", repository.pairedVelaIds().size)

    private fun error(code: String, detail: String): JSONObject = JSONObject()
        .put("type", "error")
        .put("code", code)
        .put("detail", detail)
}

fun BufferCard.toJson(): JSONObject = JSONObject()
    .put("card_id", cardId)
    .put("capture_id", captureId ?: JSONObject.NULL)
    .put("kind", kind)
    .put("title", title)
    .put("summary", summary)
    .put("state", state)
    .put("answer", displayAnswer ?: JSONObject.NULL)
    .put("health_category", healthCategory ?: JSONObject.NULL)
    .put("classification_confirmed", classificationConfirmed)
    .put("created_at", createdAt)
    // Vela exposes the small physical action set. Phone-only actions
    // (answers, calendar/health writes, classification, and Spark derivation)
    // stay in the Android card view.
    .put("actions", JSONArray(actions.filter {
        it in setOf("later", "done", "archive", "remind", "rabbit_hole")
    }))
