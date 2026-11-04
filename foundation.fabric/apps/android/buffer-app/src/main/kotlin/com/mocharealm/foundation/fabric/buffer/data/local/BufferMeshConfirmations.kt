package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.UUID

internal data class MeshGroupPlan(val binding: JSONObject, val peers: List<String>)

/** Live agreement cache, not a task lease or a global consensus certificate.
 * Freshness uses the requesting process's monotonic clock and random boot tag;
 * stored history cannot renew a confirmation after process/Hub replacement. */
internal object BufferMeshConfirmations {
    const val TTL = 30_000L
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_peer_confirmations (
            peer TEXT PRIMARY KEY, local_revision TEXT NOT NULL, reply TEXT NOT NULL,
            boot TEXT NOT NULL, started_elapsed INTEGER NOT NULL, valid INTEGER NOT NULL, event_id TEXT NOT NULL)""")
    }
    private fun number(value: JSONObject, key: String, range: LongRange): Long {
        val n = value.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in range)
        return n.toLong()
    }
    internal fun validate(peer: String, reply: JSONObject) {
        require(peer.matches(Regex("[0-9a-f]{64}")))
        require(reply.keys().asSequence().toSet() == setOf("type", "protocol", "device_id", "digest", "revision", "generation", "state", "member_count"))
        require(reply.get("type") == "buffer.mesh.binding.reply" && reply.get("protocol") == 1 &&
            reply.get("device_id") == peer && reply.get("state") == "prepared")
        require(reply.getString("digest").matches(Regex("[0-9a-f]{64}")) && UUID.fromString(reply.getString("revision")).toString() == reply.getString("revision"))
        number(reply, "generation", 1L..BufferSourceEvent.MAX_COUNTER); number(reply, "member_count", 1L..65L)
    }
    fun accept(db: SQLiteDatabase, peer: String, plan: MeshGroupPlan, reply: JSONObject, boot: String,
               started: Long, now: Long, emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); require(boot.isNotBlank() && started >= 0 && now >= started && now - started < 12_000)
        validate(peer, reply); require(peer in plan.peers)
        val revision = plan.binding.getString("revision")
        require(reply.getString("digest") == plan.binding.getString("digest") && reply.getInt("member_count") == plan.peers.size + 1) { "成员配置不一致" }
        val prior = db.rawQuery("SELECT local_revision,reply,boot,started_elapsed,event_id FROM mesh_peer_confirmations WHERE peer=?", arrayOf(peer)).use {
            if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4)) else null
        }
        if (prior != null && prior[0] == revision) {
            val old = BufferSourceEvent.parseObject(prior[1], 8192); validate(peer, old)
            if (prior[2] == boot && started < prior[3].toLong()) return
            require(reply.getLong("generation") >= old.getLong("generation")) { "设备配置版本倒退" }
            if (reply.getLong("generation") == old.getLong("generation")) require(BufferCrdt.canonical(reply) == BufferCrdt.canonical(old)) { "同一配置版本存在冲突" }
        }
        val same = prior != null && prior[0] == revision && prior[1] == BufferCrdt.canonical(reply)
        val event = if (same) prior!![4] else emit("MeshConfigurationPeerConfirmed", JSONObject().put("peer", peer)
            .put("local_revision", revision).put("reply", reply))
        db.insertWithOnConflict("mesh_peer_confirmations", null, ContentValues().apply {
            put("peer", peer); put("local_revision", revision); put("reply", BufferCrdt.canonical(reply))
            put("boot", boot); put("started_elapsed", started); put("valid", 1); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    fun failed(db: SQLiteDatabase, peer: String, revision: String, boot: String, started: Long) {
        check(db.inTransaction())
        db.execSQL("UPDATE mesh_peer_confirmations SET valid=0 WHERE peer=? AND local_revision=? AND boot=? AND started_elapsed<=?",
            arrayOf<Any?>(peer, revision, boot, started))
    }
    /** Last authenticated configuration proof, without a liveness claim. */
    fun reply(db: SQLiteDatabase, plan: MeshGroupPlan, peer: String): JSONObject? {
        check(db.inTransaction()); require(peer in plan.peers)
        return db.rawQuery("SELECT reply,event_id FROM mesh_peer_confirmations WHERE peer=? AND local_revision=?",
            arrayOf(peer, plan.binding.getString("revision"))).use {
            if (!it.moveToFirst()) return@use null
            val reply = BufferSourceEvent.parseObject(it.getString(0), 8192); validate(peer, reply)
            val event = BufferEventJournal.localRecord(db, it.getString(1)).event
            val payload = event.getJSONObject("payload")
            check(event.getString("type") == "MeshConfigurationPeerConfirmed" && payload.getString("peer") == peer &&
                payload.getString("local_revision") == plan.binding.getString("revision") &&
                BufferCrdt.canonical(payload.getJSONObject("reply")) == BufferCrdt.canonical(reply))
            check(reply.getString("digest") == plan.binding.getString("digest") && reply.getInt("member_count") == plan.peers.size + 1)
            reply
        }
    }
    fun count(db: SQLiteDatabase, plan: MeshGroupPlan, boot: String, now: Long): Int {
        check(db.inTransaction()); require(now >= 0 && boot.isNotBlank())
        return plan.peers.count { peer -> db.rawQuery(
            "SELECT reply,started_elapsed,event_id FROM mesh_peer_confirmations WHERE peer=? AND local_revision=? AND boot=? AND valid=1",
            arrayOf(peer, plan.binding.getString("revision"), boot)).use {
            if (!it.moveToFirst()) return@use false
            val started = it.getLong(1)
            if (started < 0 || now < started || now - started >= TTL) return@use false
            val reply = BufferSourceEvent.parseObject(it.getString(0), 8192); validate(peer, reply)
            val event = BufferEventJournal.localRecord(db, it.getString(2)).event
            val payload = event.getJSONObject("payload")
            check(event.getString("type") == "MeshConfigurationPeerConfirmed" && payload.getString("peer") == peer &&
                payload.getString("local_revision") == plan.binding.getString("revision") &&
                BufferCrdt.canonical(payload.getJSONObject("reply")) == BufferCrdt.canonical(reply))
            reply.getString("digest") == plan.binding.getString("digest") && reply.getInt("member_count") == plan.peers.size + 1
        } }
    }
    fun receipts(db: SQLiteDatabase, plan: MeshGroupPlan, boot: String, now: Long): org.json.JSONArray? {
        check(db.inTransaction())
        if (plan.peers.isEmpty() || count(db, plan, boot, now) != plan.peers.size) return null
        return org.json.JSONArray().also { proofs -> plan.peers.sorted().forEach { peer ->
            db.rawQuery("SELECT reply,event_id FROM mesh_peer_confirmations WHERE peer=? AND local_revision=? AND boot=? AND valid=1",
                arrayOf(peer, plan.binding.getString("revision"), boot)).use {
                check(it.moveToFirst())
                val reply = BufferSourceEvent.parseObject(it.getString(0), 8192)
                proofs.put(JSONObject().put("peer", peer).put("event_id", it.getString(1)).put("peer_revision", reply.getString("revision")))
            }
        } }
    }
}
