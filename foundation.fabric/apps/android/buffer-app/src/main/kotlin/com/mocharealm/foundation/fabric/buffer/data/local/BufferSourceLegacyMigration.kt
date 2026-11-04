package com.mocharealm.foundation.fabric.buffer.data.local

import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONObject

/** Upgrade recovery uses actual originals and explicitly retained caches.
 * It never invents an old before-image, native event, permission or job lease. */
internal object BufferSourceLegacyMigration {
    fun create(db: SQLiteDatabase, start: Boolean = false) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_legacy_state (
            id INTEGER PRIMARY KEY CHECK(id=1),phase TEXT NOT NULL,after_id TEXT NOT NULL,scanned INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_projection_legacy_state VALUES(1,'done','',0)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_legacy_work(event_id TEXT PRIMARY KEY)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_legacy_targets (
            kind TEXT NOT NULL,entity_id TEXT NOT NULL,native_after INTEGER NOT NULL DEFAULT 0,baseline TEXT,overrides TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY(kind,entity_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_legacy_foreign_fields (
            document_id TEXT NOT NULL,field_name TEXT NOT NULL,register_sha256 TEXT NOT NULL,
            PRIMARY KEY(document_id,field_name,register_sha256))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_legacy_native_documents (
            document_id TEXT NOT NULL,event_id TEXT NOT NULL,fingerprint TEXT NOT NULL,source_sequence INTEGER NOT NULL,
            PRIMARY KEY(document_id,event_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_legacy_native_order ON source_projection_legacy_native_documents(document_id,source_sequence)")
        if (start) {
            db.execSQL("UPDATE source_projection_legacy_state SET phase='prepare',after_id='',scanned=0 WHERE id=1")
            db.execSQL("UPDATE source_projection_effect_recovery SET phase='undo',revision=revision+1 WHERE id=1")
        }
    }
    fun active(db: SQLiteDatabase): Boolean = phase(db) !in setOf("done", "cleanup")
    fun cleanupPending(db: SQLiteDatabase): Boolean = phase(db) == "cleanup"
    private fun phase(db: SQLiteDatabase): String = db.rawQuery("SELECT phase FROM source_projection_legacy_state WHERE id=1", null)
        .use { check(it.moveToFirst()); it.getString(0) }
    fun enqueue(db: SQLiteDatabase, id: String) {
        if (active(db)) db.execSQL("INSERT OR IGNORE INTO source_projection_legacy_work VALUES(?)", arrayOf(id))
    }
    fun fenced(db: SQLiteDatabase, id: String): Boolean {
        if (!active(db)) return false
        return db.rawQuery("""SELECT 1 FROM source_projection_legacy_targets WHERE kind='document' AND entity_id=?
            UNION ALL SELECT 1 FROM source_projection_cards WHERE card_id=?
            UNION ALL SELECT 1 FROM source_projection_documents WHERE document_id=?
            UNION ALL SELECT 1 FROM source_projection_metadata_damage d WHERE d.pending=1 AND
                ((d.kind='document' AND d.entity_id=?) OR (d.kind='capture' AND EXISTS(
                    SELECT 1 FROM cards c WHERE c.card_id=? AND c.capture_id=d.entity_id))) LIMIT 1""", arrayOf(id,id,id,id,id)).use { it.moveToFirst() }
    }
    fun captureFenced(db: SQLiteDatabase, id: String): Boolean = active(db) && db.rawQuery("""SELECT 1
        FROM source_projection_legacy_targets WHERE kind='capture' AND entity_id=?
        UNION ALL SELECT 1 FROM source_projection_captures WHERE capture_id=?
        UNION ALL SELECT 1 FROM source_projection_metadata_damage WHERE pending=1 AND kind='capture' AND entity_id=? LIMIT 1""",arrayOf(id,id,id)).use { it.moveToFirst() }
    private fun historyReady(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT complete FROM source_event_history_scan WHERE id=1",null).use { it.moveToFirst() && it.getInt(0)==1 } &&
        !db.rawQuery("SELECT 1 FROM source_event_history_work UNION ALL SELECT 1 FROM source_event_history_changes LIMIT 1",null).use { it.moveToFirst() }
    private fun target(db: SQLiteDatabase, kind: String, id: String) {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        db.execSQL("INSERT OR IGNORE INTO source_projection_legacy_targets(kind,entity_id) VALUES(?,?)", arrayOf(kind,id))
    }
    private fun original(db: SQLiteDatabase, id: String): BufferSourceEvent {
        val location = db.rawQuery("SELECT e.source_id,e.source_sequence,r.owner_peer FROM source_events e LEFT JOIN source_event_receivers r ON r.source_id=e.source_id WHERE e.event_id=?", arrayOf(id))
            .use { check(it.moveToFirst()); Triple(it.getString(0),it.getLong(1),if(it.isNull(2)) null else it.getString(2)) }
        return if (location.first == BufferEventJournal.source(db)) BufferEventJournal.localRecord(db,id)
            else BufferEventJournal.received(db,requireNotNull(location.third),location.first,location.second) { true }
    }
    private fun document(record: BufferSourceEvent): JSONObject? =
        if (record.source.isNotBlank()) BufferSourceCrdtProjection.parse(record)?.document else null
    private fun registerHash(value: JSONObject) = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    private fun index(db: SQLiteDatabase, id: String) {
        // History recovery has already verified the original. A corrupt
        // original remains archived and fenced out of materialization; it
        // must not keep unrelated upgrade work in an endless retry loop.
        val corrupt=db.rawQuery("SELECT state FROM source_event_history WHERE event_id=?",arrayOf(id))
            .use { check(it.moveToFirst());it.getString(0)=="corrupt" }
        if(corrupt){db.delete("source_projection_legacy_work","event_id=?",arrayOf(id));return}
        val record = original(db,id)
        val doc = try { document(record) } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null } catch (_: org.json.JSONException) { null }
        if (doc != null) {
            val key = doc.getString("id")
            if (record.source == BufferEventJournal.source(db)) {
                db.execSQL("INSERT OR IGNORE INTO source_projection_legacy_native_documents VALUES(?,?,?,?)",
                    arrayOf(key,id,record.fingerprint,record.sequence))
            } else {
                val fields = doc.getJSONObject("fields")
                for (field in fields.keys()) db.execSQL("INSERT OR IGNORE INTO source_projection_legacy_foreign_fields VALUES(?,?,?)",
                    arrayOf(key,field,registerHash(fields.getJSONObject(field))))
            }
        }
        db.delete("source_projection_legacy_work","event_id=?",arrayOf(id))
    }
    private fun scan(db: SQLiteDatabase): Boolean {
        val state = db.rawQuery("SELECT after_id,scanned FROM source_projection_legacy_state WHERE id=1",null)
            .use { check(it.moveToFirst()); it.getString(0) to (it.getInt(1)==1) }
        if (!state.second) {
            val ids = db.rawQuery("SELECT event_id FROM source_events WHERE event_id>? ORDER BY event_id LIMIT 16",arrayOf(state.first))
                .use { rows -> buildList { while(rows.moveToNext()) add(rows.getString(0)) } }
            for (id in ids) enqueue(db,id)
            db.execSQL("UPDATE source_projection_legacy_state SET after_id=?,scanned=? WHERE id=1",
                arrayOf(ids.lastOrNull() ?: state.first,if(ids.size<16) 1 else 0))
        }
        val work = db.rawQuery("SELECT event_id FROM source_projection_legacy_work ORDER BY event_id LIMIT 8",null)
            .use { rows -> buildList { while(rows.moveToNext()) add(rows.getString(0)) } }
        var bytes=0
        for(id in work) {
            if(bytes>=2*BufferSourceEvent.MAX_BYTES) break
            val size=db.rawQuery("SELECT LENGTH(CAST(envelope AS BLOB)) FROM source_events WHERE event_id=?",arrayOf(id))
                .use { check(it.moveToFirst());it.getInt(0) }
            index(db,id);bytes+=size
        }
        val pending=db.rawQuery("SELECT 1 FROM source_projection_legacy_work LIMIT 1",null).use { it.moveToFirst() }
        val complete=db.rawQuery("SELECT scanned FROM source_projection_legacy_state WHERE id=1",null).use { check(it.moveToFirst());it.getInt(0)==1 }
        return !complete||pending
    }
    private fun enumerate(db: SQLiteDatabase): Boolean {
        val after=db.rawQuery("SELECT after_id FROM source_projection_legacy_state WHERE id=1",null).use { check(it.moveToFirst());it.getString(0) }
        val rows=db.rawQuery("""SELECT kind,entity_id FROM (
            SELECT 'document' AS kind,card_id AS entity_id FROM source_projection_cards
            UNION SELECT 'document',document_id FROM source_projection_documents
            UNION SELECT 'capture',capture_id FROM source_projection_captures
            UNION SELECT kind,entity_id FROM source_projection_metadata_damage WHERE pending=1 AND kind IN ('capture','document'))
            WHERE kind||':'||entity_id>? ORDER BY kind,entity_id LIMIT 16""",arrayOf(after)).use { rows ->
            buildList { while(rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
        }
        rows.forEach { target(db,it.first,it.second) }
        db.execSQL("UPDATE source_projection_legacy_state SET after_id=? WHERE id=1",
            arrayOf(rows.lastOrNull()?.let { it.first+":"+it.second } ?: after))
        return rows.size==16
    }
    private fun changedByParent(db: SQLiteDatabase, record: BufferSourceEvent, id: String, field: String, value: Any): Boolean {
        for(parent in record.parents){
            val local=db.rawQuery("""SELECT 1 FROM source_events e JOIN source_event_history h ON h.event_id=e.event_id
                AND h.fingerprint=e.fingerprint WHERE e.event_id=? AND e.source_id=? AND h.state IN ('linked','legacy')""",
                arrayOf(parent,BufferEventJournal.source(db))).use { it.moveToFirst() }
            if(!local)continue
            val original=BufferEventJournal.localRecord(db,parent);val event=original.event;val p=event.getJSONObject("payload")
            if(p.optString("card_id")!=id)continue
            when(event.getString("type")){
                "CardContentEdited" -> if(field in setOf("title","summary")&&p.has(field)&&p.has("previous_$field")&&
                    BufferCrdt.canonical(p.get(field))!=BufferCrdt.canonical(p.get("previous_$field"))&&BufferCrdt.canonical(p.get(field))==BufferCrdt.canonical(value))return true
                "TaskScheduleChanged" -> if(field=="schedule"&&p.has("schedule")&&BufferCrdt.canonical(p.get("schedule"))==BufferCrdt.canonical(value))return true
                "CaptureContextSharingRevoked" -> if((field=="capture_context"&&value==JSONObject.NULL)||(field=="context_summary"&&value==""))return true
                "CardActionPerformed" -> if(field=="state"&&p.opt("new_state")==value&&p.opt("old_state")!=value)return true
                "ClassificationProposed","ClassificationEdited" -> if(p.has(field)&&BufferCrdt.canonical(p.get(field))==BufferCrdt.canonical(value))return true
            }
        }
        return false
    }
    private fun nativeBaseline(db: SQLiteDatabase, id: String, actor: String): Boolean {
        val target=db.rawQuery("SELECT native_after,baseline FROM source_projection_legacy_targets WHERE kind='document' AND entity_id=?",arrayOf(id))
            .use { check(it.moveToFirst());it.getLong(0) to if(it.isNull(1)) null else JSONObject(it.getString(1)) }
        var base=target.second
        val overrides=db.rawQuery("SELECT overrides FROM source_projection_legacy_targets WHERE kind='document' AND entity_id=?",arrayOf(id))
            .use { check(it.moveToFirst());JSONObject(it.getString(0)) }
        val current=BufferSourceEffects.currentDocument(db,id)
        val events=db.rawQuery("SELECT event_id,fingerprint,source_sequence FROM source_projection_legacy_native_documents WHERE document_id=? AND source_sequence>? ORDER BY source_sequence LIMIT 8",arrayOf(id,target.first.toString()))
            .use { rows -> buildList { while(rows.moveToNext()) add(Triple(rows.getString(0),rows.getString(1),rows.getLong(2))) } }
        for ((event,hash,sequence) in events) {
            val original=BufferEventJournal.localRecord(db,event);check(original.fingerprint==hash)
            val doc=requireNotNull(document(original))
            val linked=db.rawQuery("SELECT 1 FROM source_event_history WHERE event_id=? AND fingerprint=? AND state IN ('linked','legacy')",arrayOf(event,hash)).use { it.moveToFirst() }
            val accepted=JSONObject(doc.toString());val fields=accepted.getJSONObject("fields")
            val scalar=BufferSourceProjection.legacyScalarFields(db,id)
            for(name in fields.keys()){
                val reg=fields.getJSONObject(name)
                if(reg.getString("actor")==actor&&changedByParent(db,original,id,name,reg.get("value"))&&
                    current?.getJSONObject("fields")?.optJSONObject(name)?.let { BufferCrdt.canonical(it)==BufferCrdt.canonical(reg) }==true)
                    overrides.put(name,reg.get("value"))
            }
            if(!linked) {
                fields.keys().asSequence().toList().forEach { name ->
                    val register=fields.getJSONObject(name)
                    val foreign=db.rawQuery("SELECT 1 FROM source_projection_legacy_foreign_fields WHERE document_id=? AND field_name=? AND register_sha256=?",arrayOf(id,name,registerHash(register))).use { it.moveToFirst() }
                    val copiedScalar=scalar?.has(name)==true&&BufferCrdt.canonical(scalar.get(name))==BufferCrdt.canonical(register.get("value"))
                    val changed=changedByParent(db,original,id,name,register.get("value"))
                    val unknownScalar=scalar==null&&BufferSourceMetadata.pending(db,"document",id)
                    if(register.getString("actor")!=actor||(!changed&&(foreign||copiedScalar||unknownScalar)))fields.remove(name)
                }
                accepted.put("deleted",false)
            }
            if (base==null) base=accepted else base=BufferCrdt.merge(base,accepted)
            db.execSQL("UPDATE source_projection_legacy_targets SET native_after=?,baseline=?,overrides=? WHERE kind='document' AND entity_id=?",
                arrayOf<Any?>(sequence,BufferCrdt.canonical(requireNotNull(base)),BufferCrdt.canonical(overrides),id))
        }
        return events.size==8
    }
    /** True means the migration owns this turn. All cursors and mutations
     * join the surrounding source worker transaction. */
    fun step(db: SQLiteDatabase, actor: String, applyNative: (JSONObject) -> Unit): Boolean {
        check(db.inTransaction())
        when(phase(db)) {
            "done" -> return false
            "prepare" -> {
                var left=false
                for(table in listOf("source_projection_legacy_targets","source_projection_legacy_foreign_fields",
                    "source_projection_legacy_native_documents","source_projection_legacy_work")) {
                    db.execSQL("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table LIMIT 128)")
                    if(db.rawQuery("SELECT 1 FROM $table LIMIT 1",null).use { it.moveToFirst() })left=true
                }
                if(!left)db.execSQL("UPDATE source_projection_legacy_state SET phase='undo' WHERE id=1")
            }
            "cleanup" -> {
                // These are temporary indexes, not source facts. Drain them
                // alongside normal projection work after the fence is lifted.
                var left=false
                for(table in listOf("source_projection_legacy_foreign_fields","source_projection_legacy_native_documents","source_projection_legacy_work")) {
                    db.execSQL("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table LIMIT 128)")
                    if(db.rawQuery("SELECT 1 FROM $table LIMIT 1",null).use { it.moveToFirst() })left=true
                }
                if(!left)db.execSQL("UPDATE source_projection_legacy_state SET phase='done' WHERE id=1")
                return false
            }
            "undo" -> {
                if(BufferSourceEffects.recover(db))return true
                db.execSQL("UPDATE source_projection_legacy_state SET phase='targets',after_id='' WHERE id=1")
            }
            "targets" -> {
                if(!enumerate(db))db.execSQL("UPDATE source_projection_legacy_state SET phase='scan',after_id='',scanned=0 WHERE id=1")
            }
            "scan" -> {
                if(!historyReady(db))return true
                if(!scan(db) && historyReady(db))db.execSQL("UPDATE source_projection_legacy_state SET phase='strip' WHERE id=1")
            }
            "strip" -> {
                // New native edits are indexed before their retained baseline
                // can be materialized; new received history still cannot execute.
                val pending=db.rawQuery("SELECT 1 FROM source_projection_legacy_work LIMIT 1",null).use { it.moveToFirst() }
                if(!historyReady(db))return true
                if(pending){scan(db);return true}
                val target=db.rawQuery("SELECT kind,entity_id FROM source_projection_legacy_targets ORDER BY kind,entity_id LIMIT 1",null)
                    .use { if(it.moveToFirst())it.getString(0) to it.getString(1) else null }
                if(target==null) {
                    db.execSQL("UPDATE source_projection_legacy_state SET phase='clear_views' WHERE id=1")
                    return true
                }
                val (kind,id)=target
                if(kind=="document") {
                    if(nativeBaseline(db,id,actor))return true
                    val base=db.rawQuery("SELECT baseline FROM source_projection_legacy_targets WHERE kind='document' AND entity_id=?",arrayOf(id))
                        .use { check(it.moveToFirst());if(it.isNull(0))null else JSONObject(it.getString(0)) }
                    val overrides=db.rawQuery("SELECT overrides FROM source_projection_legacy_targets WHERE kind='document' AND entity_id=?",arrayOf(id))
                        .use { check(it.moveToFirst());JSONObject(it.getString(0)) }
                    BufferSourceEffects.saveNativeOverrides(db,id,overrides)
                    if(BufferSourceProjection.resetLegacyDocument(db,id,base,applyNative))return true
                } else BufferSourceProjection.resetLegacyCapture(db,id)
                db.delete("source_projection_legacy_targets","kind=? AND entity_id=?",arrayOf(kind,id))
            }
            "clear_views" -> {
                db.execSQL("DELETE FROM source_projection_observations WHERE rowid IN (SELECT rowid FROM source_projection_observations LIMIT 32)")
                db.execSQL("DELETE FROM ${BufferSourceDeviceObservations.TABLE} WHERE rowid IN (SELECT rowid FROM ${BufferSourceDeviceObservations.TABLE} LIMIT 32)")
                db.execSQL("DELETE FROM source_projection_processed WHERE rowid IN (SELECT rowid FROM source_projection_processed LIMIT 32)")
                val left=db.rawQuery("SELECT 1 FROM source_projection_observations UNION ALL SELECT 1 FROM ${BufferSourceDeviceObservations.TABLE} UNION ALL SELECT 1 FROM source_projection_processed LIMIT 1",null).use { it.moveToFirst() }
                if(!left){
                    db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
                    db.execSQL("UPDATE source_projection_effect_recovery SET phase='replay',revision=revision+1 WHERE id=1")
                    db.execSQL("UPDATE source_projection_metadata_damage SET pending=0 WHERE pending=1")
                    db.execSQL("UPDATE source_projection_legacy_state SET phase='cleanup' WHERE id=1")
                }
            }
            else -> error("invalid source projection migration phase")
        }
        return true
    }
}
