package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorWireCodec
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Runs only on the authenticated connection's worker. Inventory is observation,
 * never Fabric trust; only a matching device acknowledgment activates its view. */
internal class VelaControllerView(
    private val repository: BufferRepository,
    private val local: String,
    private val device: String,
    private val send: (ByteArray) -> Boolean,
    private val pause: () -> Unit,
    private val install: (List<String>, String) -> Unit,
) {
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private var candidate: JSONObject? = null
    private var installed = false
    private var modern = repository.knownVelaControllerView(device)
    private var nextSend = 0L
    private val legacyMembers = listOf(local, device).sorted()
    private val legacyHash = digest(legacyMembers.joinToString("\u0000"))

    fun tick(now: Long) {
        if (installed || now < nextSend) return
        val value = JSONObject().put("protocol", 1).put("nonce", nonce)
        val view = candidate
        if(view!=null&&view.opt("owner_protocol")!=1){candidate=null;nextSend=now+30_000L;return}
        if (view == null) value.put("type", "buffer.vela.members.get")
        else value.put("type", "buffer.vela.members.accept").put("revision", view.getLong("revision"))
            .put("membership_hash", view.getString("membership_hash")).put("owner_protocol",1)
        if (send(value.toString().toByteArray(Charsets.UTF_8))) { nextSend = now + 2000 }
    }

    fun legacyHeartbeat(bytes: ByteArray) {
        if (modern || installed) return
        val heartbeat = CoordinatorWireCodec.decodeHeartbeat(bytes) ?: return
        if (heartbeat.deviceId != device || heartbeat.membershipVersion != legacyHash ||
            heartbeat.sessionId != "buffer-v1-$legacyHash") return
        // Legacy heartbeats offer no bounded owner lease. Keep execution
        // paused while retaining the authenticated history connection.
        pause()
    }

    fun receive(bytes: ByteArray) {
        require(bytes.size in 1..12288)
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "nonce", "device_id", "revision", "members", "membership_hash") +
            (if(value.has("owner_protocol"))setOf("owner_protocol") else emptySet()))
        require(value.get("protocol") == 1 && value.get("nonce") == nonce && value.get("device_id") == device)
        val revision = value.get("revision")
        require(revision is Number && revision.toDouble().isFinite() && revision.toDouble() == revision.toLong().toDouble() && revision.toLong() in 0L..9007199254740991L)
        val array = value.getJSONArray("members")
        require(array.length() in 2..65)
        val members = (0 until array.length()).map { array.get(it).also { id -> require(id is String && id.matches(Regex("[0-9a-f]{64}"))) } as String }
        require(members == members.distinct().sorted() && local in members && device in members)
        val hash = digest(members.joinToString("\u0000") + "\u0000roster_revision:${revision.toLong()}")
        require(value.get("membership_hash") == hash)
        when (value.getString("type")) {
            "buffer.vela.members" -> {
                // Revoke all previous ownership before persisting or accepting.
                pause(); installed = false; modern = true
                repository.observeVelaControllerView(device, value)
                candidate = value; nextSend = 0L
            }
            "buffer.vela.members.accepted" -> {
                val expected = candidate ?: return
                if (installed) return
                require(value.opt("owner_protocol")==1&&expected.opt("owner_protocol")==1)
                require(expected.getLong("revision") == revision.toLong() && expected.getString("membership_hash") == hash)
                repository.activateVelaControllerView(device, value)
                install(members, hash); installed = true
            }
            else -> error("unexpected device membership response")
        }
    }

    companion object {
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
