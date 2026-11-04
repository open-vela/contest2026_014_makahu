package com.mocharealm.foundation.fabric.buffer.data.device

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class VelaAssistantInput(
    val deviceId: String,
    val captureId: String,
    val recordSha256: String,
    val transcript: String,
)

/** Peer-provided diagnostic text is never copied to the UI, events, or exception causes. */
internal class VelaAssistantRejection private constructor(
    val state: String,
    val errorCode: String,
) : IOException("设备未受理助手任务（$errorCode），原记录已保留") {
    companion object {
        // The assistant handoff protocol's finite error vocabulary, not a format/length filter.
        private val allowedCodes = setOf(
            "invalid_request", "unauthorized", "setup_incomplete", "invalid_capture",
            "storage_error", "capture_payload_mismatch", "capture_state_conflict",
            "not_coordinator", "capture_source_mismatch", "capture_target_conflict",
            "capture_already_processed", "hash_failed", "source_missing",
            "source_hash_mismatch", "record_limit",
        )

        fun fromReply(state: String, reply: JSONObject): VelaAssistantRejection {
            require(state == "conflict" || state == "failed") { "invalid assistant receipt" }
            val code = (reply.opt("error_code") as? String)?.takeIf { it in allowedCodes }
                ?: "unknown_error"
            return VelaAssistantRejection(state, code)
        }
    }
}

/** Only a live authenticated Enrollment can install a destination. No discovery fallback. */
internal object VelaAssistantHandoff {
    private val connections=java.util.concurrent.ConcurrentHashMap<String,Connection>()

    @Synchronized fun attach(deviceId: String): Connection {
        require(VelaPendingCaptureProtocol.isFabricDeviceId(deviceId))
        check(connections.containsKey(deviceId) || connections.size<64) { "device connection capacity exceeded" }
        connections.remove(deviceId)?.close()
        return Connection(deviceId).also { connections[deviceId]=it }
    }
    @Synchronized fun detach(connection: Connection?) {
        connection?.close()
        if(connection!=null)connections.remove(connection.deviceId,connection)
    }
    fun isCurrent(connection: Connection?): Boolean = connection!=null && connections[connection.deviceId]===connection
    fun route(input: VelaAssistantInput, term: () -> Long?) {
        val endpoint = connections[input.deviceId]
            ?: throw IOException("设备助手暂未连接，原记录已保留")
        endpoint.route(input, term)
    }

    internal fun encode(input: VelaAssistantInput, term: Long): ByteArray {
        require(VelaPendingCaptureProtocol.isFabricDeviceId(input.deviceId))
        require(input.captureId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(input.recordSha256.matches(Regex("[0-9a-f]{64}")))
        require(input.transcript.isNotBlank() && '\u0000' !in input.transcript)
        require(input.transcript.codePointCount(0, input.transcript.length) <= 4000)
        require(input.transcript.toByteArray(Charsets.UTF_8).size <= 8192 && term in 0..9_007_199_254_740_991L)
        val requestId = VelaPendingCaptureProtocol.fingerprint(
            (input.captureId + '\u0000' + input.recordSha256 + '\u0000' + input.transcript).toByteArray(Charsets.UTF_8),
        ).take(32)
        return JSONObject().put("type", "vela.capture.assistant.request").put("protocol", 1)
            .put("request_id", requestId).put("capture_id", input.captureId)
            .put("record_sha256", input.recordSha256).put("transcript", input.transcript)
            .put("coordinator_term", term).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= VelaPendingCaptureProtocol.MAX_FRAME_BYTES) }
    }

    class Connection internal constructor(val deviceId: String) {
        private data class Pending(val payload: ByteArray, val request: JSONObject,
            val result: CompletableFuture<Unit>, var sendAt: Long = 0)
        private var pending: Pending? = null
        private var closed = false

        fun route(input: VelaAssistantInput, term: () -> Long?) {
            require(input.deviceId == deviceId)
            val epoch = term() ?: throw IOException("任务归属已改变，请稍后重试")
            val payload = encode(input, epoch)
            val entry = Pending(payload, JSONObject(payload.toString(Charsets.UTF_8)), CompletableFuture())
            synchronized(this) {
                check(!closed && pending == null) { "设备助手连接不可用" }
                pending = entry
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            try {
                while (System.nanoTime() < until) {
                    check(term() == epoch) { "任务归属已改变，请稍后重试" }
                    try { entry.result.get(250, TimeUnit.MILLISECONDS); return }
                    catch (_: TimeoutException) { }
                    catch (error: ExecutionException) {
                        // Keep the safe protocol diagnosis intact in classification failure events.
                        throw (error.cause as? IOException
                            ?: IOException("设备助手受理失败，原记录已保留"))
                    }
                }
                throw IOException("设备助手受理超时，原记录已保留")
            } finally {
                synchronized(this) { if (pending === entry) pending = null }
                payload.fill(0)
            }
        }

        @Synchronized fun tick(now: Long, send: (ByteArray) -> Boolean) {
            val entry = pending ?: return
            if (!closed && !entry.result.isDone && now >= entry.sendAt)
                entry.sendAt = now + if (send(entry.payload)) 3000 else 100
        }

        @Synchronized fun receive(payload: ByteArray) {
            require(payload.size in 1..VelaPendingCaptureProtocol.MAX_FRAME_BYTES)
            val reply = JSONObject(payload.toString(Charsets.UTF_8))
            require(reply.get("type") == "vela.capture.assistant.reply" && reply.get("protocol") == 1)
            val entry = pending ?: return
            if (reply.optString("request_id") != entry.request.getString("request_id") ||
                reply.opt("coordinator_term") != entry.request.get("coordinator_term")) return
            for (field in listOf("capture_id", "record_sha256"))
                require(reply.get(field) == entry.request.get(field)) { "unexpected assistant receipt" }
            when (val state = reply.getString("state")) {
                "accepted", "duplicate" -> entry.result.complete(Unit)
                "conflict", "failed" -> entry.result.completeExceptionally(VelaAssistantRejection.fromReply(state, reply))
                else -> error("invalid assistant receipt")
            }
        }

        @Synchronized fun close() {
            closed = true
            pending?.result?.completeExceptionally(IOException("设备助手连接已中断，原记录已保留"))
        }
    }
}
