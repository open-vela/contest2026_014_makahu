package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import org.json.JSONObject
import java.net.URI

/** App-owned OOBE messages carried by an authenticated Fabric ReliableMessages session. */
internal object VelaOobeApplicationProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 12 * 1024
    private const val MAX_REVISION = 9_007_199_254_740_991L
    private val requestIdPattern = Regex("[0-9a-f]{32}")

    fun encodeStateRequest(requestId: String, revision: Long): ByteArray = encode(
        requestId, revision, "oobe.status", JSONObject(),
    )

    fun encodeWifiConfiguration(
        requestId: String,
        revision: Long,
        ssid: String,
        password: String,
    ): ByteArray {
        require(validUtf8Text(ssid, 1, 32)) { "Wi-Fi 名称格式无效" }
        require(validUtf8Text(password, 8, 64)) { "Wi-Fi 密码格式无效" }
        if (password.toByteArray(Charsets.UTF_8).size == 64) {
            require(password.matches(Regex("[0-9a-fA-F]{64}"))) { "64 位 Wi-Fi 密钥必须为十六进制" }
        }
        return encode(requestId, revision, "network.configure", JSONObject()
            .put("ssid", ssid).put("password", password))
    }

    /**
     * Reuses the phone's saved service profile. The returned bytes contain a secret and
     * must be handed only to the authenticated Fabric application-message adapter.
     */
    fun encodeAgentConfiguration(
        requestId: String,
        revision: Long,
        config: BufferLlmConfig,
        timeZone: String,
        languageTag: String,
    ): ByteArray {
        require(config.ready) { "请先保存可用的 AI 服务配置" }
        val uri = URI(config.baseUrl.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) {
            "Vela 服务地址必须使用有效的 HTTPS Base URL"
        }
        require(config.model.isNotBlank() && config.model.length <= 200 &&
            config.model.none { it.code < 0x20 || it.code == 0x7f }) { "模型名称无效" }
        require(validUtf8Text(config.apiKey, 1, 8192)) { "API Key 格式无效" }
        require(validUtf8Text(timeZone, 1, 80) && validUtf8Text(languageTag, 1, 40)) {
            "时区或语言设置无效"
        }

        return encode(requestId, revision, "agent.configure", JSONObject()
            .put("base_url", config.baseUrl.trim().trimEnd('/'))
            .put("completion_model", config.model.trim())
            .put("transcription_model", BufferMiMo.asrModel)
            .put("api_key", config.apiKey)
            .put("time_zone", timeZone)
            .put("language", languageTag))
    }

    fun encodeSkipAgentConfiguration(requestId: String, revision: Long): ByteArray = encode(
        requestId, revision, "agent.skip", JSONObject(),
    )

    fun decodeReply(bytes: ByteArray, expectedRequestId: String, expectedRevision: Long): Reply {
        require(bytes.size in 1..MAX_MESSAGE_BYTES) { "设备回执大小无效" }
        require(requestIdPattern.matches(expectedRequestId) && expectedRevision > 0) {
            "本地配置请求状态无效"
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val allowedFields = setOf(
            "type", "protocol", "request_id", "revision", "state", "error_code", "capabilities",
        )
        val protocol = json.opt("protocol") as? Number
        val revision = json.opt("revision") as? Number
        require(json.length() in 5..7 && json.keys().asSequence().all { it in allowedFields } &&
            json.optString("type") == "vela.oobe.reply" && protocol?.toDouble() == VERSION.toDouble() &&
            json.optString("request_id") == expectedRequestId && revision?.toDouble() == expectedRevision.toDouble()) {
            "设备回执与当前请求不匹配或包含未知字段"
        }
        val state = json.optString("state")
        require(state in STATES) { "设备返回未知初始化状态" }
        require(!json.has("error_code") || json.opt("error_code") is String) {
            "设备错误码类型无效"
        }
        val error = json.optString("error_code").takeIf { it.isNotBlank() }
        require(error == null || error.matches(Regex("[a-z0-9_]{1,64}"))) { "设备错误码无效" }
        require(!json.has("capabilities") || json.opt("capabilities") is JSONObject) {
            "设备能力回执类型无效"
        }
        val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
        require(capabilities.length() <= 12 && capabilities.keys().asSequence().all {
            it.matches(Regex("[a-z][a-z0-9_]{0,39}")) && capabilities.get(it) is Boolean
        }) { "设备能力回执无效" }
        return Reply(expectedRequestId, expectedRevision, state, error,
            capabilities.keys().asSequence().associateWith { capabilities.getBoolean(it) })
    }

    private fun encode(requestId: String, revision: Long, operation: String, payload: JSONObject): ByteArray {
        require(requestIdPattern.matches(requestId)) { "配置请求标识无效" }
        require(revision in 1..MAX_REVISION) { "配置版本超出安全整数范围" }
        val bytes = JSONObject().put("type", "vela.oobe.request").put("protocol", VERSION)
            .put("request_id", requestId).put("revision", revision)
            .put("operation", operation).put("payload", payload)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MESSAGE_BYTES) { "初始化消息超过可靠消息大小限制" }
        return bytes
    }

    private fun validUtf8Text(value: String, minBytes: Int, maxBytes: Int): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in minBytes..maxBytes && bytes.toString(Charsets.UTF_8) == value &&
            value.none { it.code < 0x20 || it.code == 0x7f }
    }

    internal data class Reply(
        val requestId: String,
        val revision: Long,
        val state: String,
        val errorCode: String?,
        val capabilities: Map<String, Boolean>,
    ) {
        // Service readiness is not the user's durable onboarding confirmation.
        val setupComplete: Boolean get() = capabilities["setup_complete"] == true
    }

    private val STATES = setOf(
        "unpaired", "authenticating", "network_pending", "network_ready",
        "agent_pending", "agent_saved", "validating", "ready", "skipped", "failed",
    )
}

/**
 * App boundary for the framework adapter. Implementations must be backed by a
 * Fabric-authorized ReliableMessages session and reject a peer identity mismatch.
 */
internal fun interface VelaReliableMessagesPort {
    fun send(peerDeviceId: String, message: ByteArray): Delivery

    data class Delivery(val accepted: Boolean, val errorCode: String? = null)
}
