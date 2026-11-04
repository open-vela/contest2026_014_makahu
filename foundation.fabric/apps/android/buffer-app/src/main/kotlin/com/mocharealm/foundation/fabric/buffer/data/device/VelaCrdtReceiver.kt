package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64

/** Hash-checked bounded document assembly on the existing authorized channel. */
internal class VelaCrdtReceiver(private val repository: BufferRepository) : AutoCloseable {
    private var bytes: ByteArray? = null
    private var offset = 0
    private var hash = ""
    private var receivingPeer: String? = null
    fun receive(frame: ByteArray, peer: String? = null, authorize: () -> Boolean = { true }): ByteArray {
        if (!authorize()) { close(); error("CRDT channel authorization required") }
        if (peer != null) require(peer.matches(Regex("[0-9a-f]{64}")))
        if (bytes != null && receivingPeer != peer) close()
        require(frame.size in 1..12288)
        val text = frame.toString(Charsets.UTF_8); require(text.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val part = BufferSourceEvent.parseObject(text, 12288)
        require(part.keys().asSequence().toSet() == setOf("type", "protocol", "record_sha256", "offset", "total", "record_base64"))
        require(part.get("type") == "buffer.crdt.part" && part.get("protocol") == 1)
        fun integer(key: String): Int {
            val n = part.get(key); require(n is Number && n.toDouble() == n.toInt().toDouble() && n.toInt() in 0..65536)
            return n.toInt()
        }
        val total = integer("total"); val start = integer("offset"); val digest = part.getString("record_sha256")
        require(total > 0 && digest.matches(Regex("[0-9a-f]{64}")))
        val encoded = part.get("record_base64"); require(encoded is String && encoded.length in 1..8192)
        val data = Base64.getDecoder().decode(encoded)
        try {
        require(data.size in 1..6144 && start <= total - data.size && Base64.getEncoder().encodeToString(data) == encoded)
        if (bytes == null || hash != digest) { require(start == 0); close(); bytes = ByteArray(total); hash = digest; receivingPeer = peer }
        val target = requireNotNull(bytes); require(target.size == total && start <= offset)
        if (start < offset) require(start + data.size <= offset && target.copyOfRange(start, start + data.size).contentEquals(data))
        else { data.copyInto(target, offset); offset += data.size }
        var state = "partial"
        if (offset == total) {
            val raw = target.toString(Charsets.UTF_8)
            require(raw.toByteArray(Charsets.UTF_8).contentEquals(target) && VelaProcessedCaptureProtocol.fingerprint(raw) == hash)
            val record = BufferSourceEvent.parseObject(raw, 65536); val document = record.getJSONObject("crdt")
            require(record.getString("card_id") == document.getString("id"))
            check(authorize())
            repository.mergeCrdtDocument(document, peer, authorize); state = "merged"
        }
        if (!authorize()) { close(); error("CRDT channel authorization revoked") }
        return JSONObject().put("type", "buffer.crdt.reply").put("protocol", 1)
            .put("record_sha256", hash).put("next_offset", offset).put("state", state).toString().toByteArray(Charsets.UTF_8)
        } finally {
            data.fill(0)
            if (!authorize()) { close(); error("CRDT channel authorization revoked") }
        }
    }
    override fun close() { bytes?.fill(0); bytes = null; hash = ""; offset = 0; receivingPeer = null }
}
