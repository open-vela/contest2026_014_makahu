package com.mocharealm.foundation.fabric.buffer.data.device
import org.json.JSONObject
import java.io.IOException

internal data class VelaEnrollmentResult(
    val deviceId: String,
    val requestId: String,
    val state: String,
    val error: Int,
)

/** One frozen status snapshot, paged explicitly over the default ATT MTU.
 * The caller starts each poll with selector 0 and a new reader. A valid
 * network_ready result still requires LAN authentication before adding a peer.
 */
internal class VelaEnrollmentResultReader {
    private val bytes = ByteArray(320)
    private var total = -1
    var received: Int = 0
        private set

    fun selector(): ByteArray = byteArrayOf((received ushr 8).toByte(), received.toByte())

    fun accept(frame: ByteArray): VelaEnrollmentResult? {
        if (received == total || frame.size !in 5..20) throw IOException("配网状态分片无效")
        fun u16(i: Int) = ((frame[i].toInt() and 255) shl 8) or (frame[i+1].toInt() and 255)
        val offset = u16(0)
        val length = u16(2)
        if (length !in 1..bytes.size || offset != received || (total >= 0 && total != length) ||
            frame.size - 4 > length - offset) throw IOException("配网状态分片不连续")
        total = length
        frame.copyInto(bytes, received, 4)
        received += frame.size - 4
        if (received != total) return null
        val encoded = bytes.copyOf(total)
        val text = encoded.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(encoded)) throw IOException("配网状态编码无效")
        try {
            val json = JSONObject(text)
            if (json.length() != 5 || json.opt("protocol") != 1) throw IOException("配网状态协议无效")
            fun string(key: String) = json.opt(key) as? String ?: throw IOException("配网状态字段无效")
            val device = string("device_id")
            val request = string("request_id")
            val state = string("state")
            val error = json.opt("error") as? Int ?: throw IOException("配网错误码无效")
            if (!device.matches(Regex("[A-Za-z0-9._-]{1,79}")) ||
                (request.isNotEmpty() && !request.matches(Regex("[0-9a-f]{32}")))) throw IOException("配网身份或请求标识无效")
            val successStates = setOf("idle", "queued", "connecting", "network_ready")
            val failureStates = setOf("failed", "invalid_request", "request_conflict")
            if (state !in successStates && state !in failureStates) throw IOException("未知配网状态")
            if ((state in successStates && error != 0) || (state in failureStates && error >= 0)) throw IOException("配网结果与错误码不一致")
            if (state !in setOf("idle", "invalid_request") && request.isEmpty()) throw IOException("配网结果缺少请求标识")
            return VelaEnrollmentResult(device, request, state, error)
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("配网状态格式无效", error) }
    }
}
