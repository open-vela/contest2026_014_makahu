package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdtView
import org.json.JSONObject

/** Local source observations of actual register documents. A peer scope is a
 * return boundary, never a transferable claim about another source's history. */
internal object BufferDocumentSource {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_document_scopes (
            document_id TEXT PRIMARY KEY,audience TEXT NOT NULL,binding_sha256 TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_document_observations (
            document_id TEXT PRIMARY KEY,event_id TEXT NOT NULL)""")
    }
    fun validAudience(value: String) = value in setOf("local", "unknown") || value.matches(Regex("[0-9a-f]{64}"))
    private fun scopeHash(id: String, value: String) = VelaProcessedCaptureProtocol.fingerprint(
        BufferCrdt.canonical(JSONObject().put("document_id", id).put("audience", value)))
    fun audience(db: SQLiteDatabase, id: String, fallback: String): String {
        require(validAudience(fallback))
        return db.rawQuery("SELECT audience,binding_sha256 FROM source_document_scopes WHERE document_id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) fallback else {
                val value = it.getString(0)
                check(validAudience(value) && it.getString(1) == scopeHash(id, value)) { "document scope needs review" }
                value
            }
        }
    }
    fun compatible(audience: String, peer: String?) = audience in setOf("local", "unknown") || audience == peer
    fun claim(db: SQLiteDatabase, id: String, value: String) {
        check(db.inTransaction()); require(validAudience(value))
        check(db.insertWithOnConflict("source_document_scopes", null, ContentValues().apply {
            put("document_id", id); put("audience", value); put("binding_sha256", scopeHash(id, value))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    /** Caller owns the fact transaction. Persisting a document and its source
     * observation cannot be split by an editor's nested transaction. */
    fun observe(db: SQLiteDatabase, document: JSONObject, audience: String, preferredType: String? = null, append: (String, JSONObject) -> Unit) {
        check(db.inTransaction()); require(validAudience(audience)); BufferCrdtView.validate(document)
        val id = document.getString("id")
        val previous = db.rawQuery("SELECT event_id FROM source_document_observations WHERE document_id=?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (previous != null) {
            val saved = BufferEventJournal.localRecord(db, previous)
            check(saved.event.getString("type") in setOf("CardDocumentObserved", "CalendarDocumentObserved", "DocumentMerged"))
            val payload = saved.event.getJSONObject("payload")
            check(payload.keys().asSequence().toSet() == setOf("id", "clock", "document", "audience") && payload.get("id") == id)
            val old = payload.getJSONObject("document"); BufferCrdtView.validate(old)
            val clock = payload.get("clock")
            check(clock is Number && clock.toDouble().isFinite() && clock.toDouble() == clock.toLong().toDouble() &&
                old.getString("id") == id && clock.toLong() == BufferCrdt.clock(old) && validAudience(payload.getString("audience")))
            if (BufferCrdt.canonical(old) == BufferCrdt.canonical(document) && payload.getString("audience") == audience) return
        }
        val type = preferredType ?: if (document.getString("kind") == "card") "CardDocumentObserved" else "CalendarDocumentObserved"
        require(type in setOf("CardDocumentObserved", "CalendarDocumentObserved", "DocumentMerged"))
        val payload = JSONObject().put("id", id).put("clock", BufferCrdt.clock(document)).put("document", document).put("audience", audience)
        append(type, payload)
        val head = BufferEventJournal.head(db)
        val saved = BufferEventJournal.page(db, head - 1, 1).single()
        val record = com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent.decode(saved)
        check(record.event.getString("type") == type && BufferCrdt.canonical(record.event.getJSONObject("payload")) == BufferCrdt.canonical(payload))
        check(db.insertWithOnConflict("source_document_observations", null, ContentValues().apply {
            put("document_id", id); put("event_id", record.eventId)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
}
