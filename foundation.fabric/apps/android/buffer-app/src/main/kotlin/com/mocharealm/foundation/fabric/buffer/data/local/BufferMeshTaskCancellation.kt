package com.mocharealm.foundation.fabric.buffer.data.local

import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import org.json.JSONObject
import java.util.UUID

/** Negative control only: no result, membership list, grant or record data. */
internal object BufferMeshTaskCancellation {
    private val fields = setOf("type", "protocol", "device_id", "nonce", "job_id", "source_revision", "target_revision", "contract_sha256")
    fun validate(value: JSONObject, peer: String) {
        require(value.keys().asSequence().toSet() == fields && value.get("type") == "buffer.mesh.cancel" &&
            value.get("protocol") == 1 && value.get("device_id") == peer)
        (fields - "protocol").forEach { require(value.get(it) is String) }
        listOf("device_id", "job_id", "contract_sha256").forEach { require(value.getString(it).matches(Regex("[0-9a-f]{64}"))) }
        listOf("nonce", "source_revision", "target_revision").forEach { require(UUID.fromString(value.getString(it)).toString() == value.getString(it)) }
    }
    fun reply(local: String, request: JSONObject, status: String = "acknowledged"): JSONObject {
        require(status in setOf("acknowledged", "busy"))
        return JSONObject().put("type", "buffer.mesh.cancel.reply").put("protocol", 1).put("device_id", local)
            .put("nonce", request.getString("nonce")).put("job_id", request.getString("job_id"))
            .put("source_revision", request.getString("source_revision")).put("target_revision", request.getString("target_revision")).put("status", status)
    }
    fun accept(value: JSONObject, request: JSONObject, peer: String): Boolean {
        val status = value.get("status"); require(status is String && status in setOf("acknowledged", "busy"))
        require(BufferCrdt.canonical(value) == BufferCrdt.canonical(reply(peer, request, status)))
        return status == "acknowledged"
    }
}
