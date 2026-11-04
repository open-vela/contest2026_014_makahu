package com.mocharealm.foundation.fabric.buffer.data.local

import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** Own-source originals over an approved application Session. Reservations
 * disclose no event metadata; original receipts remain separate from coverage.
 * The repository holds its monitor and configuration transaction throughout. */
internal object BufferMeshHistory {
    const val BLOB_CHUNK = 64 * 1024
    private fun keys(value: JSONObject, vararg names: String) =
        require(value.keys().asSequence().toSet() == names.toSet())

    fun reference(ref: SourceBlobTransfer): JSONObject = JSONObject().put("event_id", ref.eventId).put("source_id", ref.source)
        .put("sequence", ref.sequence).put("record_hash", ref.recordHash).put("blob_hash", ref.blobHash)
        .put("total", ref.total).put("content_type", ref.contentType)

    private fun reference(value: JSONObject): SourceBlobTransfer {
        keys(value, "event_id", "source_id", "sequence", "record_hash", "blob_hash", "total", "content_type")
        fun text(name: String, pattern: String): String = (value.get(name) as? String)?.also { require(it.matches(Regex(pattern))) }
            ?: error("invalid blob reference")
        return SourceBlobTransfer(text("event_id", "[A-Za-z0-9_.:-]{1,160}"), text("source_id", "[A-Za-z0-9_.-]{1,96}"),
            BufferMeshTaskContract.integer(value, "sequence", 1L until BufferSourceEvent.MAX_COUNTER),
            text("record_hash", "[0-9a-f]{64}"), text("blob_hash", "[0-9a-f]{64}"),
            BufferMeshTaskContract.integer(value, "total", 1L..BufferSourceBlobs.MAX_BYTES.toLong()).toInt(),
            text("content_type", "audio/wav|audio/mp4|application/octet-stream"))
    }

    fun exchange(repository: BufferRepository, peer: String, request: JSONObject, authorize: () -> Boolean): JSONObject {
        check(authorize())
        val action = request.getString("action")
        if (action == "observe") {
            keys(request, "action", "after")
            val after = request.get("after"); require(after is String)
            return repository.observeMeshDocuments(peer, after, authorize)
        }
        if (action == "parent") {
            keys(request, "action", "event_id")
            val id = request.get("event_id")
            require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
            val sequence = repository.sourceParentToSend(peer, id, authorize)
            // Do not reveal the location of a withheld or foreign original.
            if (sequence == null) return JSONObject().put("event_id", id).put("state", "not_found")
            val raw = repository.sourceEventPage(sequence - 1, 1, authorize).single()
            val original = BufferSourceEvent.decode(raw)
            check(original.eventId == id && original.sequence == sequence && repository.mayShareSourceEvent(original, peer))
            val manifest = repository.sourceEventManifest(authorize)
            check(manifest.getString("source_id") == original.source)
            check(authorize())
            return JSONObject().put("event_id", id).put("state", "available").put("source_id", original.source)
                .put("sequence", sequence).put("fingerprint", original.fingerprint).put("head", manifest.getLong("last_sequence"))
        }
        if (action == "blob_read" || action == "blob_ack") {
            require(request.keys().asSequence().toSet() == setOf("action", "ref") + (if (action == "blob_read") setOf("offset") else emptySet()))
            val ref = reference(request.getJSONObject("ref"))
            repository.prepareOutgoingSourceBlob(peer, ref, authorize)
            require(repository.mayShareOutgoingSourceBlob(peer, ref, authorize)) { "audio is withheld" }
            if (action == "blob_ack") {
                repository.outgoingSourceBlobDelivered(peer, ref, authorize)
                return JSONObject().put("ref", reference(ref)).put("state", "received")
            }
            val offset = BufferMeshTaskContract.integer(request, "offset", 0L until ref.total.toLong()).toInt()
            val bytes = repository.readOutgoingSourceBlob(peer, ref, authorize)
            try {
                require(bytes.size == ref.total)
                val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + BLOB_CHUNK))
                val encoded = try { Base64.encodeToString(part, Base64.NO_WRAP) } finally { part.fill(0) }
                check(authorize() && repository.mayShareOutgoingSourceBlob(peer, ref, authorize))
                return JSONObject().put("ref", reference(ref)).put("offset", offset).put("data_b64", encoded)
            } finally { bytes.fill(0) }
        }
        val manifest = repository.sourceEventManifest(authorize)
        val source = manifest.getString("source_id")
        val head = manifest.getLong("last_sequence")
        if (action == "manifest") {
            keys(request, "action")
            return JSONObject().put("source_id", source).put("head", head).put("visibility_version", 1)
        }
        require(request.get("source_id") == source)
        if (action == "held") {
            keys(request, "action", "source_id", "after")
            val after = BufferMeshTaskContract.integer(request, "after", 0L until BufferSourceEvent.MAX_COUNTER)
            val slot = repository.nextSourceVisibilityFill(peer, after, 1, authorize)
            return JSONObject().put("source_id", source).put("sequence", slot?.sequence ?: 0L)
        }
        val sequence = BufferMeshTaskContract.integer(request, "sequence", 1L..head)
        val raw = repository.sourceEventPage(sequence - 1, 1, authorize).single()
        val original = BufferSourceEvent.decode(raw)
        check(original.source == source && original.sequence == sequence)
        val slot = repository.prepareSourceVisibility(peer, raw, 1, authorize)
        if (action == "slot") {
            keys(request, "action", "source_id", "sequence")
            repository.sourceVisibilityReserved(peer, source, sequence, slot.token, 1, authorize)
            val visible = repository.mayShareSourceEvent(original, peer)
            check(authorize())
            return JSONObject().put("source_id", source).put("sequence", sequence).put("token", slot.token)
                .put("visible", visible).also {
                    if (visible) it.put("event_id", original.eventId).put("fingerprint", original.fingerprint)
                        .put("bytes", raw.toByteArray(Charsets.UTF_8).size)
                }
        }
        require(request.get("token") == slot.token && request.get("fingerprint") == original.fingerprint)
        require(repository.mayShareSourceEvent(original, peer)) { "original is withheld" }
        if (action == "ack") {
            keys(request, "action", "source_id", "sequence", "token", "fingerprint")
            repository.sourceEventDelivered(peer, raw, authorize)
            check(authorize())
            return JSONObject().put("source_id", source).put("sequence", sequence).put("token", slot.token)
                .put("fingerprint", original.fingerprint).put("state", "received")
        }
        require(action == "read")
        keys(request, "action", "source_id", "sequence", "token", "fingerprint", "offset")
        val bytes = raw.toByteArray(Charsets.UTF_8)
        try {
            val offset = BufferMeshTaskContract.integer(request, "offset", 0L until bytes.size.toLong()).toInt()
            val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + 16 * 1024))
            val encoded = try { Base64.encodeToString(part, Base64.NO_WRAP) } finally { part.fill(0) }
            check(authorize() && repository.mayShareSourceEvent(original, peer))
            return JSONObject().put("source_id", source).put("sequence", sequence).put("token", slot.token)
                .put("fingerprint", original.fingerprint).put("offset", offset).put("total", bytes.size).put("data_b64", encoded)
        } finally { bytes.fill(0) }
    }
}
