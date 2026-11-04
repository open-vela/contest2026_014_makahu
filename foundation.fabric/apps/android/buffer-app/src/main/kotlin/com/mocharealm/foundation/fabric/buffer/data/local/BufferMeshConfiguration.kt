package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local prepared configuration, not a Fabric Session or a global commit.
 * Common manifest hashes can agree across devices; the local revision also
 * fences revoke/reapprove cycles whose common manifest is unchanged. */
internal object BufferMeshConfiguration {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_configuration (
            id INTEGER PRIMARY KEY CHECK(id=1), device TEXT NOT NULL, manifest TEXT NOT NULL,
            digest TEXT NOT NULL, binding TEXT NOT NULL, revision TEXT NOT NULL,
            generation INTEGER NOT NULL, state TEXT NOT NULL, event_id TEXT NOT NULL)""")
    }
    private fun hash(value: JSONObject) = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    private data class Saved(val device: String, val manifest: JSONObject, val digest: String, val binding: JSONObject,
        val revision: String, val generation: Long, val state: String)
    private fun load(db: SQLiteDatabase): Saved? {
        check(db.inTransaction())
        if (!db.rawQuery("SELECT 1 FROM mesh_configuration WHERE id=1", null).use { it.moveToFirst() }) {
            // Only our actual local transition may restore this checkpoint.
            // Imported history cannot establish a local configuration token.
            val id = db.rawQuery("SELECT event_id FROM events WHERE type IN ('MeshConfigurationPrepared','MeshConfigurationInvalidated') ORDER BY logical_clock DESC LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (id != null) {
                val event = BufferEventJournal.localRecord(db, id).event
                val p = event.getJSONObject("payload")
                db.insertOrThrow("mesh_configuration", null, ContentValues().apply {
                    put("id", 1); put("device", p.getString("device_id")); put("manifest", BufferCrdt.canonical(p.getJSONObject("manifest")))
                    put("digest", p.getString("digest")); put("binding", BufferCrdt.canonical(p.getJSONObject("binding")))
                    put("revision", p.getString("revision")); put("generation", p.getLong("generation"))
                    put("state", if (event.getString("type") == "MeshConfigurationPrepared") "prepared" else "invalidated")
                    put("event_id", id)
                })
            }
        }
        return db.rawQuery(
        "SELECT device,manifest,digest,binding,revision,generation,state,event_id FROM mesh_configuration WHERE id=1", null).use {
        if (!it.moveToFirst()) return@use null
        val saved = Saved(it.getString(0), BufferSourceEvent.parseObject(it.getString(1), 128 * 1024), it.getString(2),
            BufferSourceEvent.parseObject(it.getString(3), 128 * 1024), it.getString(4), it.getLong(5), it.getString(6))
        check(hash(saved.manifest) == saved.digest && saved.device.matches(Regex("[0-9a-f]{64}")) &&
            saved.revision.matches(Regex("[0-9a-f-]{36}")) && saved.generation in 1L..BufferSourceEvent.MAX_COUNTER &&
            saved.state in setOf("prepared", "invalidated"))
        val event = BufferEventJournal.localRecord(db, it.getString(7)).event
        val p = event.getJSONObject("payload")
        val expectedType = if (saved.state == "prepared") "MeshConfigurationPrepared" else "MeshConfigurationInvalidated"
        check(event.getString("type") == expectedType)
        check(p.getString("device_id") == saved.device && p.getString("digest") == saved.digest &&
            p.getString("binding_digest") == hash(saved.binding) && p.getString("revision") == saved.revision &&
            p.getLong("generation") == saved.generation)
        saved
        }
    }
    private fun save(db: SQLiteDatabase, prior: Saved?, device: String, manifest: JSONObject, binding: JSONObject,
                     state: String, reason: String, emit: (String, JSONObject) -> String): Saved {
        val generation = (prior?.generation ?: 0L) + 1
        require(generation in 1L..BufferSourceEvent.MAX_COUNTER) { "成员配置版本已达到上限" }
        val digest = hash(manifest); val revision = UUID.randomUUID().toString()
        val event = emit(if (state == "prepared") "MeshConfigurationPrepared" else "MeshConfigurationInvalidated", JSONObject()
            .put("device_id", device).put("digest", digest).put("binding_digest", hash(binding))
            .put("revision", revision).put("generation", generation).put("reason", reason)
            .put("manifest", manifest).put("binding", binding)
            .put("previous_revision", prior?.revision ?: JSONObject.NULL))
        db.insertWithOnConflict("mesh_configuration", null, ContentValues().apply {
            put("id", 1); put("device", device); put("manifest", BufferCrdt.canonical(manifest)); put("digest", digest)
            put("binding", BufferCrdt.canonical(binding)); put("revision", revision); put("generation", generation)
            put("state", state); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return Saved(device, manifest, digest, binding, revision, generation, state)
    }
    fun invalidate(db: SQLiteDatabase, reason: String, emit: (String, JSONObject) -> String) {
        check(db.inTransaction())
        val prior = load(db) ?: return
        if (prior.state != "invalidated") save(db, prior, prior.device, prior.manifest, prior.binding, "invalidated", reason, emit)
    }
    fun snapshot(db: SQLiteDatabase, local: String, self: JSONObject, emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction())
        val own = BufferMeshMembers.descriptor(self, local)
        val approved = BufferMeshMembers.list(db).filter { it.state == "approved" && it.peer != local && BufferMeshMembers.allowed(db, it.peer, local) }.sortedBy { it.peer }
        val offers = (approved.map { it.offer } + own).sortedBy { it.getString("device_id") }
        val manifest = JSONObject().put("protocol", 1).put("members", JSONArray(offers))
        val binding = JSONObject().put("device_id", local).put("self_digest", hash(own)).put("approvals", JSONArray().also { entries ->
            approved.forEach { entries.put(JSONObject().put("peer", it.peer).put("revision", it.revision).put("offer_digest", it.digest)) }
        })
        val prior = load(db)
        val current = if (prior != null && prior.state == "prepared" && prior.device == local && prior.digest == hash(manifest) &&
            BufferCrdt.canonical(prior.binding) == BufferCrdt.canonical(binding)) prior
        else save(db, prior, local, manifest, binding, "prepared", "local_approved_view", emit)
        // The roster itself is local. This endpoint shares only the bounded
        // comparison token with an already approved authenticated caller.
        return JSONObject().put("type", "buffer.mesh.binding.reply").put("protocol", 1).put("device_id", local)
            .put("digest", current.digest).put("revision", current.revision).put("generation", current.generation)
            .put("state", current.state).put("member_count", offers.size)
    }
    fun requireCurrent(db: SQLiteDatabase, local: String, self: JSONObject, digest: String, revision: String,
                       emit: (String, JSONObject) -> String): JSONObject {
        val current = snapshot(db, local, self, emit)
        require(current.getString("digest") == digest && current.getString("revision") == revision) { "成员配置已变化，请重新协商" }
        return current
    }
    fun participants(db: SQLiteDatabase): List<String> {
        check(db.inTransaction())
        val saved = requireNotNull(load(db)); check(saved.state == "prepared")
        val members = saved.manifest.getJSONArray("members")
        return (0 until members.length()).map { members.getJSONObject(it).getString("device_id") }.filter { it != saved.device }
    }
    fun offers(db: SQLiteDatabase): List<JSONObject> {
        check(db.inTransaction())
        val saved = requireNotNull(load(db)); check(saved.state == "prepared")
        val members = saved.manifest.getJSONArray("members")
        return (0 until members.length()).map { members.getJSONObject(it) }
    }
}
