package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONObject
import java.util.UUID

internal data class BufferMeshMember(val peer: String, val offer: JSONObject, val digest: String,
    val state: String, val revision: String, val observedAt: Long, val approver: String?)

/** Local application approvals. Imported source events never update this
 * table. A discovery offer is a peer's claim, not an execution lease. */
internal object BufferMeshMembers {
    private const val DAY = 86_400_000L
    private const val POLICY = 4
    private val identity = Regex("[0-9a-f]{64}")
    private val capabilities = setOf("TRANSCRIBE", "CLASSIFY", "ANSWER", "GENERATE", "HISTORY")
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_members (
            peer TEXT PRIMARY KEY, offer TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL,
            revision TEXT NOT NULL, observed_at INTEGER NOT NULL, approved_digest TEXT,
            approval_event TEXT, approver TEXT)""")
    }
    private fun digest(value: JSONObject) = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    fun descriptor(value: JSONObject, peer: String): JSONObject {
        require(identity.matches(peer))
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "device_id", "label", "device_class", "capabilities"))
        require(value.get("type") in setOf("buffer.mesh.hello", "buffer.mesh.hello.reply") && value.get("protocol") == 1 && value.get("device_id") == peer)
        val label = value.get("label"); require(label is String && label.isNotBlank() && label.length <= 80 && '\u0000' !in label)
        require(value.get("device_class") in setOf("PHONE", "TABLET", "VELA", "OTHER"))
        val offered = value.getJSONObject("capabilities")
        require(offered.keys().asSequence().all { it in capabilities } && offered.length() <= 5)
        offered.keys().forEach {
            val version = offered.get(it)
            require(version is Number && version.toDouble().isFinite() && version.toLong() in 1L..65535L &&
                version.toDouble() == version.toLong().toDouble())
        }
        return JSONObject(BufferCrdt.canonical(value)).put("type", "buffer.mesh.hello")
    }
    private fun decode(rows: Cursor): BufferMeshMember {
        val body = descriptor(BufferSourceEvent.parseObject(rows.getString(1), 8192), rows.getString(0))
        check(digest(body) == rows.getString(2) && rows.getString(3) in setOf("pending", "approved", "revoked"))
        return BufferMeshMember(rows.getString(0), body, rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getString(6))
    }
    private fun find(db: SQLiteDatabase, peer: String): BufferMeshMember? = db.rawQuery(
        "SELECT peer,offer,digest,state,revision,observed_at,approver FROM mesh_members WHERE peer=?", arrayOf(peer))
        .use { if (it.moveToFirst()) decode(it) else null }
    fun list(db: SQLiteDatabase): List<BufferMeshMember> = db.rawQuery(
        "SELECT peer,offer,digest,state,revision,observed_at,approver FROM mesh_members ORDER BY observed_at DESC,peer LIMIT 64", null).use { rows ->
        buildList { while (rows.moveToNext()) {
            add(decode(rows))
        } }
    }
    fun observe(db: SQLiteDatabase, peer: String, local: String, value: JSONObject, now: Long,
                emit: (String, JSONObject) -> String): Boolean {
        check(db.inTransaction()); require(identity.matches(local) && peer != local && now in 1L..BufferSourceEvent.MAX_COUNTER)
        val body = descriptor(value, peer); val hash = digest(body)
        val old = find(db, peer)
        check(old != null || list(db).size < 64) { "设备候选已满，请先移除不再使用的候选" }
        val changed = old == null || old.digest != hash
        val state = if (old?.state == "revoked") "revoked" else if (changed) "pending" else old!!.state
        val revision = if (changed) UUID.randomUUID().toString() else old!!.revision
        if (changed && old?.state == "approved") emit("MeshMemberRevoked", JSONObject().put("peer", peer)
            .put("revision", old.revision).put("reason", "offer_changed"))
        if (old == null) db.insertOrThrow("mesh_members", null, ContentValues().apply {
            put("peer", peer); put("offer", BufferCrdt.canonical(body)); put("digest", hash); put("state", state)
            put("revision", revision); put("observed_at", now)
        }) else check(db.update("mesh_members", ContentValues().apply {
            put("offer", BufferCrdt.canonical(body)); put("digest", hash); put("state", state)
            put("revision", revision); put("observed_at", now)
            if (changed) { putNull("approved_digest"); putNull("approval_event"); putNull("approver") }
        }, "peer=?", arrayOf(peer)) == 1)
        if (changed) emit("MeshMemberObserved", JSONObject().put("peer", peer).put("offer_digest", hash).put("offer", body))
        return changed && old?.state == "approved"
    }
    fun decide(db: SQLiteDatabase, peer: String, expected: String, enable: Boolean, local: String?, now: Long,
               emit: (String, JSONObject) -> String): Boolean {
        check(db.inTransaction()); require(now in 1L..BufferSourceEvent.MAX_COUNTER)
        if (enable) require(local != null && identity.matches(local) && peer != local)
        val member = requireNotNull(find(db, peer)?.takeIf { it.digest == expected }) { "设备信息已变化，请刷新后确认" }
        if (!enable && member.state == "revoked") return false
        if (enable) {
            require(member.observedAt <= now && now - member.observedAt < DAY) { "请先重新查询这台设备" }
            if (allowed(db, peer, requireNotNull(local))) return false
        }
        val revision = UUID.randomUUID().toString()
        val event = emit(if (enable) "MeshMemberApproved" else "MeshMemberRevoked", JSONObject()
            .put("peer", peer).put("offer_digest", expected).put("revision", revision).put("approver", local ?: JSONObject.NULL)
            .put("policy", POLICY).put("scope", "buffer_application"))
        check(db.update("mesh_members", ContentValues().apply {
            put("state", if (enable) "approved" else "revoked"); put("revision", revision)
            if (enable) { put("approved_digest", expected); put("approval_event", event); put("approver", local) }
            else { putNull("approved_digest"); putNull("approval_event"); putNull("approver") }
        }, "peer=?", arrayOf(peer)) == 1)
        return enable || member.state == "approved"
    }
    fun allowed(db: SQLiteDatabase, peer: String, local: String): Boolean {
        check(db.inTransaction()); require(identity.matches(peer) && identity.matches(local))
        val member = find(db, peer) ?: return false
        if (member.state != "approved") return false
        return db.rawQuery("SELECT approved_digest,approval_event,approver FROM mesh_members WHERE peer=?", arrayOf(peer)).use {
            check(it.moveToFirst())
            if (it.getString(0) != member.digest || it.getString(2) != local) return@use false
            val event = BufferEventJournal.localRecord(db, it.getString(1)).event
            val p = event.getJSONObject("payload")
            check(event.getString("type") == "MeshMemberApproved" && p.getString("peer") == peer &&
                p.getString("offer_digest") == member.digest && p.getString("revision") == member.revision &&
                p.getString("approver") == local && p.getString("scope") == "buffer_application")
            p.getInt("policy") == POLICY
        }
    }
    fun forget(db: SQLiteDatabase, peer: String, expected: String, emit: (String, JSONObject) -> String) {
        check(db.inTransaction())
        val member = requireNotNull(find(db, peer)?.takeIf { it.digest == expected })
        require(member.state != "approved") { "请先撤销设备授权" }
        check(db.delete("mesh_members", "peer=?", arrayOf(peer)) == 1)
        emit("MeshMemberForgotten", JSONObject().put("peer", peer).put("offer_digest", expected))
    }
}
