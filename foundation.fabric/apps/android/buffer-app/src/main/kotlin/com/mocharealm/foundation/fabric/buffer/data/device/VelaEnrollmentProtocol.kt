package com.mocharealm.foundation.fabric.buffer.data.device
import org.json.JSONObject

/** Wire encoding for an authenticated GATT enrollment channel.
 * Caller persists requestId/token before sending; retries reuse both values.
 * A GATT write callback acknowledges transport only, never successful enrollment.
 */
internal object VelaEnrollmentProtocol {
    const val MAX_REQUEST_BYTES = 768
    private val identity = Regex("[A-Za-z0-9._-]{1,79}")
    private val requestIdPattern = Regex("[0-9a-f]{32}")
    private val tokenPattern = Regex("[0-9a-f]{64}")

    fun encode(requestId: String, phoneId: String, token: String, ssid: String, password: String): ByteArray {
        require(requestIdPattern.matches(requestId)) { "配置请求标识无效" }
        require(identity.matches(phoneId)) { "手机身份无效" }
        require(tokenPattern.matches(token)) { "配对凭据无效" }
        require(validText(ssid, 1, 32)) { "Wi-Fi 名称应为 1–32 字节，不能包含控制字符" }
        require(validText(password, 8, 64)) { "Wi-Fi 密码应为 8–63 字节或 64 位十六进制密钥" }
        if (password.toByteArray(Charsets.UTF_8).size == 64) {
            require(password.matches(Regex("[0-9a-fA-F]{64}"))) { "64 位 Wi-Fi 密钥必须为十六进制" }
        }
        return JSONObject().put("type", "buffer.configure").put("protocol", 1)
            .put("request_id", requestId).put("phone_device_id", phoneId).put("token", token)
            .put("ssid", ssid).put("password", password).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_REQUEST_BYTES) { "配网请求过长" } }
    }

    fun encodeExisting(requestId: String, phoneId: String, token: String): ByteArray {
        require(requestIdPattern.matches(requestId)) { "配置请求标识无效" }
        require(identity.matches(phoneId)) { "手机身份无效" }
        require(tokenPattern.matches(token)) { "配对凭据无效" }
        return JSONObject().put("type", "buffer.bind").put("protocol", 1)
            .put("request_id", requestId).put("phone_device_id", phoneId).put("token", token)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun validText(text: String, minBytes: Int, maxBytes: Int): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return bytes.size in minBytes..maxBytes && bytes.toString(Charsets.UTF_8) == text &&
            text.none { it.code < 32 || it.code == 127 }
    }

    /** Each ATT write is [offset:u16be][total:u16be][payload], even with MTU 23. */
    fun fragments(request: ByteArray, mtu: Int): List<ByteArray> {
        require(request.size in 1..MAX_REQUEST_BYTES)
        require(mtu in 23..517)
        val capacity = mtu - 3 - 4
        return (request.indices step capacity).map { offset ->
            val length = minOf(capacity, request.size - offset)
            ByteArray(length + 4).also {
                it[0] = (offset ushr 8).toByte(); it[1] = offset.toByte()
                it[2] = (request.size ushr 8).toByte(); it[3] = request.size.toByte()
                request.copyInto(it, 4, offset, offset + length)
            }
        }
    }
}
