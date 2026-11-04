package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable local adoption of a configuration each peer actually confirmed.
 * This is an application membership intent, not a Fabric election certificate
 * or proof that every peer is still online. Task leases remain separate. */
internal object BufferMeshActivations {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_configuration_activation (
            id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL, digest TEXT NOT NULL, event_id TEXT NOT NULL)""")
    }
    private fun hash(value: JSONObject) = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    private fun write(db: SQLiteDatabase, body: JSONObject, event: String) {
        db.insertWithOnConflict("mesh_configuration_activation", null, ContentValues().apply {
            put("id", 1); put("body", BufferCrdt.canonical(body)); put("digest", hash(body)); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    private fun load(db: SQLiteDatabase): JSONObject? {
        check(db.inTransaction())
        if (!db.rawQuery("SELECT 1 FROM mesh_configuration_activation WHERE id=1", null).use { it.moveToFirst() }) {
            val id = db.rawQuery("SELECT event_id FROM events WHERE type='MeshConfigurationActivated' ORDER BY logical_clock DESC LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (id != null) {
                val event = BufferEventJournal.localRecord(db, id).event
                check(event.getString("type") == "MeshConfigurationActivated")
                write(db, event.getJSONObject("payload").getJSONObject("activation"), id)
            }
        }
        return db.rawQuery("SELECT body,digest,event_id FROM mesh_configuration_activation WHERE id=1", null).use {
            if (!it.moveToFirst()) return@use null
            val body = BufferSourceEvent.parseObject(it.getString(0), 128 * 1024)
            check(hash(body) == it.getString(1))
            val event = BufferEventJournal.localRecord(db, it.getString(2)).event
            check(event.getString("type") == "MeshConfigurationActivated" &&
                BufferCrdt.canonical(event.getJSONObject("payload").getJSONObject("activation")) == BufferCrdt.canonical(body))
            body
        }
    }
    fun current(db: SQLiteDatabase, plan: MeshGroupPlan): Boolean {
        check(db.inTransaction())
        val body = load(db) ?: return false
        check(body.keys().asSequence().toSet() == setOf("id", "local_revision", "digest", "peers", "confirmations"))
        check(UUID.fromString(body.getString("id")).toString() == body.getString("id"))
        if (body.getString("local_revision") != plan.binding.getString("revision") || body.getString("digest") != plan.binding.getString("digest")) return false
        val peers = body.getJSONArray("peers")
        check((0 until peers.length()).map { peers.getString(it) } == plan.peers.sorted() && plan.peers.isNotEmpty())
        val receipts = body.getJSONArray("confirmations"); check(receipts.length() == plan.peers.size)
        val checked = hashSetOf<String>()
        for (i in 0 until receipts.length()) {
            val receipt = receipts.getJSONObject(i)
            check(receipt.keys().asSequence().toSet() == setOf("peer", "event_id", "peer_revision"))
            val peer = receipt.getString("peer"); check(peer in plan.peers && checked.add(peer))
            val event = BufferEventJournal.localRecord(db, receipt.getString("event_id")).event
            val p = event.getJSONObject("payload")
            check(event.getString("type") == "MeshConfigurationPeerConfirmed" && p.getString("peer") == peer &&
                p.getString("local_revision") == plan.binding.getString("revision"))
            val reply = p.getJSONObject("reply"); BufferMeshConfirmations.validate(peer, reply)
            check(reply.getString("digest") == plan.binding.getString("digest") &&
                reply.getString("revision") == receipt.getString("peer_revision") && reply.getInt("member_count") == plan.peers.size + 1)
        }
        return true
    }
    fun advance(db: SQLiteDatabase, plan: MeshGroupPlan, boot: String, now: Long, emit: (String, JSONObject) -> String): Boolean {
        check(db.inTransaction())
        if (current(db, plan)) return true
        val receipts = BufferMeshConfirmations.receipts(db, plan, boot, now) ?: return false
        val body = JSONObject().put("id", UUID.randomUUID().toString()).put("local_revision", plan.binding.getString("revision"))
            .put("digest", plan.binding.getString("digest")).put("peers", JSONArray(plan.peers.sorted())).put("confirmations", receipts)
        val event = emit("MeshConfigurationActivated", JSONObject().put("activation", body))
        write(db, body, event)
        check(current(db, plan))
        return true
    }
}
