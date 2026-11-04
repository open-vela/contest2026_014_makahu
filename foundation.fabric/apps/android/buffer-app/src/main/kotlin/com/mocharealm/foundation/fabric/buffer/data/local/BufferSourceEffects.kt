package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdtView
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONArray
import org.json.JSONObject

/** Private before/after images, committed with the actual projection. These
 * are derived recovery metadata, never source facts, grants or executor jobs. */
internal object BufferSourceEffects {
    class CapacityExceeded : RuntimeException("projection recovery capacity exceeded")
    data class Scope(val capture: String? = null, val cards: Set<String> = emptySet(),
                     val document: String? = null, val observations: List<SourceObservation> = emptyList())
    private data class Table(val name: String, val keys: List<String>)
    private val tables = listOf(
        Table("captures", listOf("capture_id")), Table("cards", listOf("card_id")),
        Table("runtime_settings", listOf("key")), Table("card_reminders", listOf("card_id")), Table("card_returns", listOf("card_id")),
        Table("source_projection_captures", listOf("capture_id")), Table("source_projection_cards", listOf("card_id")),
        Table("source_projection_documents", listOf("owner_peer", "source_id", "document_id")),
        Table("source_projection_observations", listOf("owner_peer", "source_id", "view_kind", "entity_id")),
        Table(BufferSourceDeviceObservations.TABLE, listOf("owner_peer", "source_id", "view_kind", "entity_id")),
        Table("source_projection_processed", listOf("owner_peer", "capture_id", "record_sha256")),
        Table("source_projection_retained_rows", listOf("table_name", "row_key")))
    private val settingPrefixes = listOf("crdt:", "task_source:", "task_schedule:", "card_presentation:", "card_conversation:",
        "bedtime_review:", "bedtime_user_review:", "shared_context:")
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_effects (
            effect_sequence INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL,fingerprint TEXT NOT NULL,
            body TEXT NOT NULL,body_sha256 TEXT NOT NULL,native_sequence INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_effect_event ON source_projection_effects(event_id,effect_sequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS cards_source_repair ON cards(state,card_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_effect_targets (
            effect_sequence INTEGER NOT NULL,card_id TEXT NOT NULL,PRIMARY KEY(effect_sequence,card_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_effect_card ON source_projection_effect_targets(card_id,effect_sequence)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_effect_recovery (
            id INTEGER PRIMARY KEY CHECK(id=1),phase TEXT NOT NULL CHECK(phase IN ('ready','undo','replay')),revision INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_projection_effect_recovery VALUES(1,'ready',0)")
        createDamageStorage(db)
        BufferSourceMetadata.create(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_native_overrides (
            document_id TEXT PRIMARY KEY,body TEXT NOT NULL,body_sha256 TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_retained_rows (
            table_name TEXT NOT NULL,row_key TEXT NOT NULL,body TEXT NOT NULL,body_sha256 TEXT NOT NULL,
            PRIMARY KEY(table_name,row_key))""")
    }
    fun createDamageStorage(db: SQLiteDatabase) {
        // Preserve the actual damaged SQLite values, including an unreadable
        // body. Do not serialize them through a parsed/repaired JSON document.
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_effect_damage (
            damage_id INTEGER PRIMARY KEY AUTOINCREMENT,effect_sequence INTEGER,event_id TEXT,fingerprint TEXT,
            body TEXT,body_sha256 TEXT,native_sequence INTEGER)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_effect_damage_targets (
            damage_id INTEGER NOT NULL,card_id TEXT,PRIMARY KEY(damage_id,card_id))""")
    }
    private fun retireDamagedEffect(db: SQLiteDatabase, sequence: Long) {
        val args=arrayOf(sequence.toString())
        db.execSQL("""INSERT INTO source_projection_effect_damage(effect_sequence,event_id,fingerprint,body,body_sha256,native_sequence)
            SELECT effect_sequence,event_id,fingerprint,body,body_sha256,native_sequence FROM source_projection_effects WHERE effect_sequence=?""",args)
        val archive=db.rawQuery("SELECT last_insert_rowid()",null).use { check(it.moveToFirst());it.getLong(0) }
        db.execSQL("""INSERT INTO source_projection_effect_damage_targets(damage_id,card_id)
            SELECT ?,card_id FROM source_projection_effect_targets WHERE effect_sequence=?""",arrayOf(archive,sequence))
        db.delete("source_projection_effect_targets","effect_sequence=?",args)
        check(db.delete("source_projection_effects","effect_sequence=?",args)==1)
        // All mutations join the caller's transaction. The fence and rebuild
        // request cannot commit without the lossless archive, or vice versa.
        BufferSourceLegacyMigration.create(db,start=true)
        BufferEventHistory.requestRebuild(db)
    }
    private fun hash(raw: String) = VelaProcessedCaptureProtocol.fingerprint(raw)
    private fun effectHash(event: String, fingerprint: String, native: Long, body: String) = hash("$event\u0000$fingerprint\u0000$native\u0000$body")
    private fun retainedHash(table: String, key: JSONObject, body: String) = hash(table + "\u0000" + BufferCrdt.canonical(key) + "\u0000" + body)
    private fun equal(a: Any?, b: Any?) = BufferCrdt.canonical(a) == BufferCrdt.canonical(b)
    private fun row(cursor: Cursor): JSONObject = JSONObject().also { value ->
        for (i in 0 until cursor.columnCount) value.put(cursor.getColumnName(i), when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
            else -> error("unsupported projection column")
        })
    }
    private fun key(table: Table, value: JSONObject) = JSONObject().also { out -> table.keys.forEach { out.put(it, value.get(it)) } }
    private fun where(key: JSONObject) = key.keys().asSequence().toList().sorted().joinToString(" AND ") { "\"$it\"=?" }
    private fun args(key: JSONObject) = key.keys().asSequence().toList().sorted().map { key.get(it).toString() }.toTypedArray()
    private fun current(db: SQLiteDatabase, table: Table, key: JSONObject): JSONObject? =
        db.query(table.name, null, where(key), args(key), null, null, null).use { if (it.moveToFirst()) row(it) else null }
    private fun values(value: JSONObject) = ContentValues().apply {
        value.keys().forEach { name -> when (val v = value.get(name)) {
            JSONObject.NULL -> putNull(name)
            is Int -> put(name, v)
            is Long -> put(name, v)
            is Number -> put(name, v.toDouble())
            is String -> put(name, v)
            else -> error("unsupported projection column")
        } }
    }
    private fun snapshot(db: SQLiteDatabase, record: BufferSourceEvent, scope: Scope): Map<String, JSONObject> {
        val cards = scope.cards.toMutableSet(); scope.document?.let(cards::add)
        // Callers name the actual changed segments/document. Sibling cards
        // of the same Capture are not before-images of this projection.
        fun inCards(column: String): Pair<String, Array<String>> = if (cards.isEmpty()) "0" to emptyArray()
            else "$column IN (${cards.joinToString(",") { "?" }})" to cards.sorted().toTypedArray()
        val selections = mutableMapOf<String, Pair<String, Array<String>>>()
        selections["captures"] = "capture_id=?" to arrayOf(scope.capture ?: "")
        selections["source_projection_captures"] = selections.getValue("captures")
        for (name in listOf("cards", "source_projection_cards", "card_reminders", "card_returns")) selections[name] = inCards("card_id")
        val settings = cards.flatMap { id -> settingPrefixes.map { it + id } }
        selections["runtime_settings"] = if (settings.isEmpty()) "0" to emptyArray()
            else "key IN (${settings.joinToString(",") { "?" }})" to settings.toTypedArray()
        selections["source_projection_documents"] = "document_id=?" to arrayOf(scope.document ?: "")
        selections["source_projection_processed"] = "event_id=?" to arrayOf(record.eventId)
        val observationKeys = scope.observations.map { it.kind to it.id }.distinct()
        val retained = cards.flatMap { id ->
            listOf("cards", "source_projection_cards", "quarantine_cards").map { it to BufferCrdt.canonical(JSONObject().put("card_id", id)) } +
                ("crdt" to BufferCrdt.canonical(JSONObject().put("key", "crdt:$id")))
        } + (scope.capture?.let { id -> listOf("captures", "source_projection_captures", "quarantine_captures")
            .map { it to BufferCrdt.canonical(JSONObject().put("capture_id", id)) } } ?: emptyList())
        selections["source_projection_retained_rows"] = if (retained.isEmpty()) "0" to emptyArray() else
            retained.joinToString(" OR ", "(", ")") { "(table_name=? AND row_key=?)" } to retained.flatMap { listOf(it.first, it.second) }.toTypedArray()
        selections["source_projection_observations"] = if (observationKeys.isEmpty()) "0" to emptyArray() else
            ("source_id=? AND (" + observationKeys.joinToString(" OR ") { "(view_kind=? AND entity_id=?)" } + ")") to
                (listOf(record.source) + observationKeys.flatMap { listOf(it.first, it.second) }).toTypedArray()
        selections[BufferSourceDeviceObservations.TABLE]=selections.getValue("source_projection_observations")
        return buildMap {
            for (table in tables) {
                val selection = selections.getValue(table.name)
                BufferSourceMetadata.checkRows(db, table.name, selection.first, selection.second)
                db.query(table.name, null, selection.first, selection.second, null, null, null).use { rows ->
                    while (rows.moveToNext()) {
                        val value = row(rows); val identity = key(table, value)
                        put(table.name + ":" + BufferCrdt.canonical(identity), JSONObject().put("table", table.name).put("key", identity).put("value", value))
                    }
                }
            }
        }
    }
    fun <T> record(db: SQLiteDatabase, record: BufferSourceEvent, scope: Scope, apply: () -> T): T {
        check(db.inTransaction()); check(!undoing(db))
        db.execSQL("SAVEPOINT source_projection_effect")
        try {
            val result = recordEffect(db, record, scope, apply)
            db.execSQL("RELEASE SAVEPOINT source_projection_effect")
            return result
        } catch (error: Exception) {
            db.execSQL("ROLLBACK TO SAVEPOINT source_projection_effect")
            db.execSQL("RELEASE SAVEPOINT source_projection_effect")
            throw error
        }
    }
    private fun <T> recordEffect(db: SQLiteDatabase, record: BufferSourceEvent, scope: Scope, apply: () -> T): T {
        val before = snapshot(db, record, scope)
        val nativeSequence = BufferEventJournal.head(db)
        val result = apply()
        val after = snapshot(db, record, scope)
        val patches = JSONArray()
        for (id in (before.keys + after.keys).sorted()) {
            val b = before[id]; val a = after[id]
            if (equal(b?.get("value"), a?.get("value"))) continue
            val selected = requireNotNull(a ?: b)
            patches.put(JSONObject().put("table", selected.getString("table")).put("key", selected.getJSONObject("key"))
                .put("before", b?.get("value") ?: JSONObject.NULL).put("after", a?.get("value") ?: JSONObject.NULL))
        }
        if (patches.length() > 0) {
            val raw = BufferCrdt.canonical(patches)
            if (raw.toByteArray(Charsets.UTF_8).size > 4 * BufferSourceEvent.MAX_BYTES) throw CapacityExceeded()
            val sequence = db.insertOrThrow("source_projection_effects", null, ContentValues().apply {
                put("event_id", record.eventId); put("fingerprint", record.fingerprint); put("body", raw); put("body_sha256", effectHash(record.eventId, record.fingerprint, nativeSequence, raw)); put("native_sequence", nativeSequence)
            })
            val targets = (0 until patches.length()).mapNotNull { index ->
                val patch = patches.getJSONObject(index)
                when (patch.getString("table")) {
                    "cards" -> patch.getJSONObject("key").getString("card_id")
                    "runtime_settings" -> patch.getJSONObject("key").getString("key").takeIf { it.startsWith("crdt:") }?.removePrefix("crdt:")
                    else -> null
                }
            }.toSet()
            targets.forEach { db.execSQL("INSERT INTO source_projection_effect_targets VALUES(?,?)", arrayOf<Any?>(sequence, it)) }
        }
        return result
    }
    fun undoing(db: SQLiteDatabase): Boolean = db.rawQuery("SELECT phase FROM source_projection_effect_recovery WHERE id=1", null)
        .use { check(it.moveToFirst()); it.getString(0) == "undo" }
    fun invalidated(db: SQLiteDatabase, event: String) {
        check(db.inTransaction())
        val invalid = db.rawQuery("SELECT state FROM source_event_history WHERE event_id=?", arrayOf(event))
            .use { it.moveToFirst() && it.getString(0) !in setOf("linked", "legacy", "missing_parent", "waiting_parent") }
        if (!invalid) return
        val recorded = db.rawQuery("SELECT 1 FROM source_projection_effects WHERE event_id=? LIMIT 1", arrayOf(event)).use { it.moveToFirst() }
        if (recorded) db.execSQL("UPDATE source_projection_effect_recovery SET phase='undo',revision=revision+1 WHERE id=1 AND phase<>'undo'")
    }
    fun revision(db: SQLiteDatabase): String = db.rawQuery("SELECT phase,revision FROM source_projection_effect_recovery WHERE id=1", null)
        .use { check(it.moveToFirst()); it.getString(0) + ":" + it.getLong(1) + ":" + BufferSourceLegacyMigration.active(db) }
    fun fenced(db: SQLiteDatabase, card: String): Boolean = BufferSourceLegacyMigration.fenced(db,card) || db.rawQuery("""SELECT 1 FROM source_projection_effect_targets t
        JOIN source_projection_effect_recovery r ON r.id=1 WHERE t.card_id=? AND r.phase='undo' LIMIT 1""", arrayOf(card)).use { it.moveToFirst() }

    /** Roll back only unchanged registers. Native edits made after a source
     * merge have their own stamps and survive removal of the foreign inputs. */
    private fun undoDocument(before: String?, after: String, current: String, locallyDeleted: Boolean): String? {
        val a = BufferSourceEvent.parseObject(after, 65536); val c = BufferSourceEvent.parseObject(current, 65536)
        val b = before?.let { BufferSourceEvent.parseObject(it, 65536) }
        BufferCrdt.validate(a); BufferCrdt.validate(c); b?.let(BufferCrdt::validate)
        require(a.getString("id") == c.getString("id") && a.getString("kind") == c.getString("kind"))
        val result = JSONObject(c.toString()); val fields = result.getJSONObject("fields")
        val af = a.getJSONObject("fields"); val bf = b?.getJSONObject("fields")
        for (name in (af.keys().asSequence().toSet() + (bf?.keys()?.asSequence()?.toSet() ?: emptySet()))) {
            val av = af.opt(name); val bv = bf?.opt(name)
            if (!equal(av, bv) && equal(fields.opt(name), av)) {
                if (bv == null) fields.remove(name) else fields.put(name, bv)
            }
        }
        if (!locallyDeleted && c.getBoolean("deleted") == a.getBoolean("deleted")) result.put("deleted", b?.getBoolean("deleted") ?: false)
        if (locallyDeleted) result.put("deleted", true)
        BufferCrdt.validate(result)
        return if (fields.length() == 0 && !result.getBoolean("deleted") && b == null) null else BufferCrdt.canonical(result)
    }
    private fun retain(db: SQLiteDatabase, table: Table, key: JSONObject, value: JSONObject) {
        val raw = BufferCrdt.canonical(value)
        check(db.insertWithOnConflict("source_projection_retained_rows", null, ContentValues().apply {
            put("table_name", table.name); put("row_key", BufferCrdt.canonical(key)); put("body", raw); put("body_sha256", retainedHash(table.name, key, raw))
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    private inline fun <T> metadata(table: String, identity: JSONObject, read: () -> T): T = try { read() }
        catch (error: BufferSourceMetadata.Damaged) { throw error }
        catch (_: IllegalArgumentException) { throw BufferSourceMetadata.Damaged(table, identity) }
        catch (_: IllegalStateException) { throw BufferSourceMetadata.Damaged(table, identity) }
        catch (_: org.json.JSONException) { throw BufferSourceMetadata.Damaged(table, identity) }

    internal fun currentDocument(db: SQLiteDatabase, id: String): JSONObject? {
        val identity = JSONObject().put("key", "crdt:$id")
        BufferSourceMetadata.checkRows(db, "runtime_settings", "key=?", arrayOf("crdt:$id"))
        return metadata("runtime_settings", identity) {
            db.rawQuery("SELECT value FROM runtime_settings WHERE key=?", arrayOf("crdt:$id")).use {
                if (!it.moveToFirst()) null else BufferSourceEvent.parseObject(it.getString(0), 65536).also { doc ->
                    BufferCrdt.validate(doc); check(doc.getString("id") == id)
                }
            }
        }
    }
    private fun nativeOverride(db: SQLiteDatabase, id: String): JSONObject? {
        val identity = JSONObject().put("document_id", id)
        BufferSourceMetadata.checkRows(db, "source_projection_native_overrides", "document_id=?", arrayOf(id))
        return metadata("source_projection_native_overrides", identity) {
            db.rawQuery("SELECT body,body_sha256 FROM source_projection_native_overrides WHERE document_id=?", arrayOf(id)).use {
                if (!it.moveToFirst()) null else {
                    check(hash(id+"\u0000"+it.getString(0)) == it.getString(1))
                    BufferSourceEvent.parseObject(it.getString(0), 65536).also { value ->
                        require(value.length() <= 32)
                        BufferCrdtView.validateCardFields(value, id, partial = true)
                    }
                }
            }
        }
    }
    /** Scan only a few rows per idle pass; cursors are scheduling hints, not
     * history progress. A restart safely scans these private caches again. */
    fun inspectMetadata(db: SQLiteDatabase, after: LongArray) {
        val names = listOf("runtime_settings", "source_projection_native_overrides", "source_projection_retained_rows",
            "source_projection_captures", "source_projection_cards", "source_projection_observations", BufferSourceDeviceObservations.TABLE)
        require(after.size == names.size)
        for ((index, table) in names.withIndex()) {
            val keys = when (table) {
                "source_projection_retained_rows" -> listOf("table_name", "row_key")
                "runtime_settings" -> listOf("key")
                "source_projection_captures" -> listOf("capture_id")
                "source_projection_cards" -> listOf("card_id")
                "source_projection_observations", BufferSourceDeviceObservations.TABLE -> listOf("owner_peer", "source_id", "view_kind", "entity_id")
                else -> listOf("document_id")
            }
            // Do not coerce unreachable BLOB/oversized keys into another TEXT
            // identity. Normal protocol keys remain small and addressable.
            val filter = keys.joinToString(" AND ") {
                "typeof($it)='text' AND LENGTH(CAST($it AS BLOB)) BETWEEN 1 AND ${if (it == "row_key") 65536 else 256}"
            } + if (table == "runtime_settings") " AND key GLOB 'crdt:*'" else ""
            val batch = db.rawQuery("SELECT rowid,${keys.joinToString(",")} FROM $table WHERE rowid>? AND $filter ORDER BY rowid LIMIT 4",
                arrayOf(after[index].toString())).use { rows -> buildList {
                    while (rows.moveToNext()) add(rows.getLong(0) to JSONObject().also { identity ->
                        keys.forEachIndexed { field, name -> identity.put(name, rows.getString(field + 1)) }
                    })
                } }
            for ((row, identity) in batch) {
                when (table) {
                    "runtime_settings" -> currentDocument(db, identity.getString("key").removePrefix("crdt:"))
                    "source_projection_native_overrides" -> nativeOverride(db, identity.getString("document_id"))
                    "source_projection_captures", "source_projection_cards" -> BufferSourceProjection.inspectBinding(db, table, identity.getString(keys.single()))
                    "source_projection_observations", BufferSourceDeviceObservations.TABLE -> BufferSourceObservations.inspect(db, table, identity)
                    else -> metadata(table, identity) {
                        val encoded = identity.getString("row_key")
                        val key = BufferSourceEvent.parseObject(encoded, 65536)
                        check(BufferCrdt.canonical(key) == encoded)
                        retainedRow(db, identity.getString("table_name"), key)
                    }
                }
                after[index] = row
            }
            if (batch.size < 4) after[index] = 0
        }
    }
    fun saveNativeOverrides(db: SQLiteDatabase,id: String,changes: JSONObject) {
        if(changes.length()==0)return
        val old=nativeOverride(db,id)?:JSONObject();changes.keys().forEach { old.put(it,changes.get(it)) }
        val raw=BufferCrdt.canonical(old)
        db.insertWithOnConflict("source_projection_native_overrides",null,ContentValues().apply {
            put("document_id",id);put("body",raw);put("body_sha256",hash(id+"\u0000"+raw))
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    fun clearNativeOverrides(db: SQLiteDatabase,id: String) {db.delete("source_projection_native_overrides","document_id=?",arrayOf(id))}
    fun editedNativeOverrides(db: SQLiteDatabase,document: JSONObject) {
        val id=document.getString("id");val old=nativeOverride(db,id)?:return
        if(document.getBoolean("deleted")){clearNativeOverrides(db,id);return}
        val value=BufferCrdt.values(document)
        old.keys().asSequence().toList().forEach { if(value.has(it)&&!equal(old.get(it),value.get(it)))old.remove(it) }
        clearNativeOverrides(db,id);saveNativeOverrides(db,id,old)
    }
    fun projectionChanges(db: SQLiteDatabase,id: String,changes: JSONObject): JSONObject {
        val overlay=nativeOverride(db,id)?:return changes
        return JSONObject(changes.toString()).also { result -> overlay.keys().forEach { field ->
            if(result.has(field)&&equal(result.get(field),overlay.get(field)))result.remove(field)
        } }
    }
    /** A local materialization overlay has no fabricated register stamp. An
     * authenticated live merge or a subsequent native edit can supersede it. */
    fun applyNativeOverrides(db: SQLiteDatabase,id: String) {
        val value=nativeOverride(db,id)?:return
        if(BufferSourceProjection.locallyDeleted(db,id))return
        val deleted=currentDocument(db,id)?.getBoolean("deleted")==true
        if(deleted || db.rawQuery("SELECT 1 FROM cards WHERE card_id=? AND state IN ('deleted','source_unavailable')",arrayOf(id)).use { it.moveToFirst() })return
        val columns=setOf("title","summary","kind","state","actions","answer","research_notes","health_category","classification_confirmed","tags","external_links","related_card_ids")
        val update=ContentValues()
        value.keys().forEach { field -> if(field in columns) {
            val v=value.get(field)
            when {v==JSONObject.NULL->update.putNull(field)
                field=="classification_confirmed"->update.put(field,if(v==true)1 else 0)
                else->update.put(field,if(v is JSONObject||v is JSONArray)v.toString()else v.toString())}
        } }
        if(update.size()>0)db.update("cards",update,"card_id=?",arrayOf(id))
        val prefs=mapOf("text" to "task_source:","schedule" to "task_schedule:","presentation" to "card_presentation:",
            "conversation" to "card_conversation:","capture_context" to "shared_context:")
        for((field,prefix)in prefs)if(value.has(field)){
            val v=value.get(field);val key=prefix+id
            if(v==JSONObject.NULL)db.delete("runtime_settings","key=?",arrayOf(key))
            else db.insertWithOnConflict("runtime_settings",null,ContentValues().apply {put("key",key);put("value",v.toString())},SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    fun archiveLegacyRow(db: SQLiteDatabase, table: String, identity: JSONObject) {
        val schema=tables.single { it.name==table }
        current(db,schema,identity)?.let { row ->
            val archive=Table("legacy_$table",schema.keys)
            if(retainedRow(db,archive.name,identity)==null){retain(db,archive,identity,row);retain(db,schema,identity,row)}
        }
    }
    fun retainLegacyCaptureBaseline(db: SQLiteDatabase,id: String,baseline: JSONObject,changes: JSONObject) {
        val key=JSONObject().put("capture_id",id)
        retain(db,Table("quarantine_captures",listOf("capture_id")),key,
            JSONObject().put("baseline",baseline).put("changes",changes).put("origin","legacy_rebuild"))
    }
    fun retainLegacyDocument(db: SQLiteDatabase, document: JSONObject) {
        BufferCrdt.validate(document)
        retain(db,Table("crdt",listOf("key")),JSONObject().put("key","crdt:"+document.getString("id")),document)
    }
    fun quarantineLegacyCard(db: SQLiteDatabase, id: String, expected: JSONObject) {
        val schema=tables.single { it.name=="cards" };val identity=JSONObject().put("card_id",id)
        val value=current(db,schema,identity)?:return
        val previous=retainedRow(db,"quarantine_cards",identity)?.takeIf { value.optString("state")=="source_unavailable" }
        if(retainedRow(db,"legacy_cards",identity)==null)retain(db,Table("legacy_cards",schema.keys),identity,value)
        if(value.optString("state")=="source_unavailable"&&retainedRow(db,"quarantine_cards",identity)?.optString("origin")=="legacy_rebuild")return
        retain(db,schema,identity,value)
        if(BufferSourceProjection.locallyDeleted(db,id))return
        val native=previous?.getJSONObject("changes")?.let { JSONObject(it.toString()) }?:JSONObject()
        val baseline=JSONObject(expected.toString());val updates=ContentValues()
        val defaults=mapOf<String,Any?>("title" to "来源记录待核对","summary" to "本机修改已保留，等待来源历史恢复。",
            "kind" to "unclassified_card","answer" to null,"research_notes" to null,"health_category" to null,
            "tags" to "[]","external_links" to "[]","related_card_ids" to "[]")
        for(field in value.keys()) if(expected.has(field)&&!equal(value.opt(field),expected.opt(field)) &&
            !(previous?.getJSONObject("baseline")?.let { it.has(field)&&equal(it.get(field),value.get(field)) }==true))native.put(field,value.get(field))
        // Explicit native confirmation owns the existing content, including
        // fields whose values happen to equal the old source proposal.
        if(value.optLong("classification_confirmed")==1L)
            for(field in listOf("kind","title","summary","actions","health_category","related_card_ids","state"))
                if(value.has(field)&&!(field in setOf("state","actions")&&previous!=null))native.put(field,value.get(field))
        for((field,replacement)in defaults)if(expected.has(field)&&equal(value.opt(field),expected.opt(field))){
            if(replacement==null)updates.putNull(field)else updates.put(field,replacement.toString())
        }
        updates.put("state","source_unavailable");updates.put("actions","[]")
        db.update("cards",updates,"card_id=?",arrayOf(id))
        val actual=requireNotNull(current(db,schema,identity));updates.keySet().forEach { baseline.put(it,actual.get(it)) }
        retain(db,Table("quarantine_cards",schema.keys),identity,JSONObject().put("baseline",baseline).put("changes",native).put("origin","legacy_rebuild"))
    }
    fun retainedRow(db: SQLiteDatabase, table: String, key: JSONObject): JSONObject? {
        val own = !db.inTransaction()
        if (own) db.beginTransactionNonExclusive()
        try {
            val value = readRetainedRow(db, table, key)
            if (own) db.setTransactionSuccessful()
            return value
        } finally { if (own) db.endTransaction() }
    }
    private fun readRetainedRow(db: SQLiteDatabase, table: String, key: JSONObject): JSONObject? {
        val identity = JSONObject().put("table_name", table).put("row_key", BufferCrdt.canonical(key))
        BufferSourceMetadata.checkRows(db, "source_projection_retained_rows", "table_name=? AND row_key=?",
            arrayOf(table, identity.getString("row_key")))
        try {
            return db.rawQuery("SELECT body_sha256 FROM source_projection_retained_rows WHERE table_name=? AND row_key=?",
                arrayOf(table, identity.getString("row_key"))).use {
                if (!it.moveToFirst()) null else {
                    val body = BufferSqlText.read(db, "source_projection_retained_rows", "table_name=? AND row_key=?",
                        arrayOf(table, identity.getString("row_key")), 4 * BufferSourceEvent.MAX_BYTES)
                    check(retainedHash(table, key, body) == it.getString(0))
                    BufferSourceEvent.parseStoredObject(body, 4 * BufferSourceEvent.MAX_BYTES).also { value ->
                        if (table == "crdt") {
                            BufferCrdt.validate(value)
                            check(key.getString("key") == "crdt:" + value.getString("id"))
                        }
                        if (table in setOf("quarantine_cards", "quarantine_captures")) {
                            val schema = tables.single { it.name == table.removePrefix("quarantine_") }
                            val baseline = value.getJSONObject("baseline"); val changes = value.getJSONObject("changes")
                            for (fields in listOf(baseline, changes)) fields.keys().forEach { name ->
                                val v = fields.get(name)
                                require(v == JSONObject.NULL || v is String || v is Number)
                            }
                            require(schema.keys.none { changes.has(it) })
                            if (table == "quarantine_cards") {
                                val native = JSONObject(changes.toString())
                                for (name in listOf("actions", "tags", "external_links", "related_card_ids"))
                                    if (native.has(name)) native.put(name, JSONArray(native.getString(name)))
                                if (native.has("classification_confirmed")) {
                                    require(native.get("classification_confirmed") is Number && native.getLong("classification_confirmed") in 0L..1L)
                                    native.put("classification_confirmed", native.getLong("classification_confirmed") == 1L)
                                }
                                BufferCrdtView.validateCardFields(native, key.getString("card_id"), partial = true)
                            }
                        }
                    }
                }
            }
        } catch (error: BufferSourceMetadata.Damaged) { throw error }
          catch (_: IllegalArgumentException) { throw BufferSourceMetadata.Damaged("source_projection_retained_rows", identity) }
          catch (_: IllegalStateException) { throw BufferSourceMetadata.Damaged("source_projection_retained_rows", identity) }
          catch (_: org.json.JSONException) { throw BufferSourceMetadata.Damaged("source_projection_retained_rows", identity) }
    }
    fun comparisonBaseline(db: SQLiteDatabase, table: String, key: JSONObject, original: JSONObject): JSONObject {
        val marker = retainedRow(db, "quarantine_$table", key) ?: return original
        val baseline = marker.getJSONObject("baseline")
        return JSONObject(original.toString()).also { out -> out.keys().asSequence().toList().forEach { field ->
            if (baseline.has(field)) out.put(field, baseline.get(field))
        } }
    }
    fun restoreQuarantine(db: SQLiteDatabase, table: String, key: JSONObject, applied: JSONObject) {
        val marker = retainedRow(db, "quarantine_$table", key) ?: return
        val schema = tables.single { it.name == table }; val current = current(db, schema, key) ?: return
        val changes = marker.getJSONObject("changes"); val updates = JSONObject()
        for (field in changes.keys()) if (field !in schema.keys && current.has(field) && applied.has(field) && equal(current.get(field), applied.get(field)))
            updates.put(field, changes.get(field))
        if (table == "cards" && BufferSourceProjection.locallyDeleted(db, key.getString("card_id"))) {
            updates.put("state", "deleted"); updates.remove("actions")
        }
        if(table=="cards" && (current.optString("state")=="deleted" || applied.optString("state")=="deleted")) {
            updates.put("state","deleted");updates.remove("actions")
        }
        if (updates.length() > 0) db.update(table, values(updates), where(key), args(key))
        if(table=="cards") {
            // These are already retained native edits, not new source facts.
            // Keep them out of later history-generated register updates too.
            val overlay=JSONObject()
            val arrays=setOf("actions","tags","external_links","related_card_ids")
            val columns=arrays+setOf("title","summary","kind","state","answer","research_notes","health_category","classification_confirmed")
            for(field in updates.keys())if(field in columns && changes.has(field) && equal(updates.get(field),changes.get(field))) {
                val value=updates.get(field)
                overlay.put(field,when {
                    value==JSONObject.NULL->JSONObject.NULL
                    field in arrays->JSONArray(value.toString())
                    field=="classification_confirmed"->value.toString()=="1"
                    else->value
                })
            }
            saveNativeOverrides(db,key.getString("card_id"),overlay)
        }
        db.delete("source_projection_retained_rows", "table_name=? AND row_key=?", arrayOf("quarantine_$table", BufferCrdt.canonical(key)))
    }
    fun restoreLegacyCardChanges(db: SQLiteDatabase,id: String) {
        val key=JSONObject().put("card_id",id)
        val actual=current(db,tables.single { it.name=="cards" },key)?:return
        restoreQuarantine(db,"cards",key,actual)
    }
    fun retainedDocument(db: SQLiteDatabase, id: String): JSONObject? =
        retainedRow(db, "crdt", JSONObject().put("key", "crdt:$id"))
    private fun captureClaimed(db: SQLiteDatabase, key: JSONObject, after: Long): Boolean = db.rawQuery("""SELECT 1 FROM source_events s
        JOIN events e ON e.event_id=s.event_id WHERE s.source_id=? AND s.source_sequence>? AND e.capture_id=?
        AND e.type IN ('CaptureCreated','CaptureTranscribed','ClassificationProposed','VelaPendingCaptureImported','VelaProcessedCaptureImported') LIMIT 1""",
        arrayOf(BufferEventJournal.source(db), after.toString(), key.getString("capture_id"))).use { it.moveToFirst() }
    private fun nativeCardActivity(db: SQLiteDatabase, id: String, after: Long): Boolean =
        db.rawQuery("""SELECT e.payload FROM source_events s JOIN events e ON e.event_id=s.event_id
            WHERE s.source_id=? AND s.source_sequence>? AND e.type IN
            ('CardActionPerformed','CardDeleted','CardReminderDeferred','CardReminderPosted','CardResurfaced')""",
            arrayOf(BufferEventJournal.source(db), after.toString())).use { rows ->
            while (rows.moveToNext()) if (JSONObject(rows.getString(0)).optString("card_id") == id) return@use true
            false
        }
    private fun nativeSettingChange(db: SQLiteDatabase, key: String, after: Long): Boolean {
        val prefix = key.substringBefore(':'); val id = key.substringAfter(':', "")
        val types = when (prefix) {
            "shared_context" -> listOf("CaptureContextSharingRevoked", "CaptureContextImported", "CardDeleted")
            "task_schedule" -> listOf("TaskScheduleChanged", "CardActionPerformed", "CardDeleted")
            else -> return false
        }
        return db.rawQuery("""SELECT e.payload FROM source_events s JOIN events e ON e.event_id=s.event_id
            WHERE s.source_id=? AND s.source_sequence>? AND e.type IN (${types.joinToString(",") { "?" }})""",
            (listOf(BufferEventJournal.source(db), after.toString()) + types).toTypedArray()).use { rows ->
            while (rows.moveToNext()) if (JSONObject(rows.getString(0)).optString("card_id") == id) return@use true
            false
        }
    }
    private fun undo(db: SQLiteDatabase, patch: JSONObject, nativeSequence: Long) {
        val table = tables.single { it.name == patch.getString("table") }; val key = patch.getJSONObject("key")
        require(key.keys().asSequence().toSet() == table.keys.toSet())
        val b = patch.optJSONObject("before"); val a = patch.optJSONObject("after"); val c = current(db, table, key)
        if (table.name in setOf("card_reminders", "card_returns") && nativeCardActivity(db, key.getString("card_id"), nativeSequence)) return
        if (table.name == "captures" && captureClaimed(db, key, nativeSequence)) return
        if (table.name == "runtime_settings" && nativeSettingChange(db, key.getString("key"), nativeSequence)) return
        if (table.name == "runtime_settings" && key.getString("key").startsWith("crdt:") && a != null && c != null) {
            val id = key.getString("key").removePrefix("crdt:")
            val value = undoDocument(b?.getString("value") ?: retainedDocument(db, id)?.let(BufferCrdt::canonical), a.getString("value"), c.getString("value"), BufferSourceProjection.locallyDeleted(db, id))
            val document = value?.let(::JSONObject)
            val complete = document?.let { runCatching { BufferCrdtView.validate(it) }.isSuccess } == true
            if (document != null && !complete) retain(db, Table("crdt", listOf("key")), key, document)
            if (!complete) db.delete(table.name, where(key), args(key))
            else db.update(table.name, ContentValues().apply { put("value", value) }, where(key), args(key))
            return
        }
        if (b == null) {
            if (c == null) return
            if (equal(c, a)) {
                if (table.name in setOf("source_projection_cards", "source_projection_captures")) retain(db, table, key, c)
                db.delete(table.name, where(key), args(key)); return
            }
            if (table.name in setOf("cards", "captures")) {
                // Keep the complete edited row privately. Never relabel it as
                // a source fact or silently erase an intervening native edit.
                retain(db, table, key, c)
                if (table.name == "cards" && BufferSourceProjection.locallyDeleted(db, key.getString("card_id"))) return
                if (table.name == "cards" && c.optInt("classification_confirmed") == 1 && a?.optInt("classification_confirmed") != 1) return
                val updates = ContentValues()
                if (table.name == "cards") {
                    val defaults = mapOf<String, Any?>("title" to "来源记录待核对", "summary" to "本机修改已保留，等待来源历史恢复。",
                        "kind" to "unclassified_card", "answer" to null, "research_notes" to null, "health_category" to null,
                        "tags" to "[]", "external_links" to "[]", "related_card_ids" to "[]")
                    for ((field, value) in defaults) if (a != null && equal(c.opt(field), a.opt(field))) {
                        if (value == null) updates.putNull(field) else updates.put(field, value.toString())
                    }
                    updates.put("state", "source_unavailable"); updates.put("actions", "[]")
                    db.execSQL("UPDATE card_reminders SET cancelled=1 WHERE card_id=?", arrayOf(key.getString("card_id")))
                    db.delete("card_returns", "card_id=?", arrayOf(key.getString("card_id")))
                } else for (field in listOf("transcript", "classification"))
                    if (a != null && equal(c.opt(field), a.opt(field))) updates.putNull(field)
                if (updates.size() > 0) {
                    db.update(table.name, updates, where(key), args(key))
                    val baseline = JSONObject((a ?: c).toString()); val actual = requireNotNull(current(db, table, key))
                    updates.keySet().forEach { baseline.put(it, actual.get(it)) }
                    val native = JSONObject(); c.keys().forEach { field -> if (a != null && !equal(c.opt(field), a.opt(field))) native.put(field, c.get(field)) }
                    retain(db, Table("quarantine_${table.name}", table.keys), key,
                        JSONObject().put("baseline", baseline).put("changes", native))
                }
            }
            return
        }
        if (a == null) { if (c == null) db.insertOrThrow(table.name, null, values(b)); return }
        if (c == null) return // a later native removal remains authoritative
        val changes = JSONObject()
        for (name in b.keys()) if (name !in table.keys && !equal(b.opt(name), a.opt(name)) && equal(c.opt(name), a.opt(name))) changes.put(name, b.get(name))
        if (changes.length() > 0) check(db.update(table.name, values(changes), where(key), args(key)) == 1)
    }
    /** Each batch has one durable reverse cursor: the greatest remaining
     * effect sequence. A restart repeats neither a command nor an old lease. */
    fun recover(db: SQLiteDatabase, limit: Int = 4): Boolean {
        check(db.inTransaction()); if (!undoing(db)) return false
        val batch = db.rawQuery("SELECT effect_sequence FROM source_projection_effects ORDER BY effect_sequence DESC LIMIT ?", arrayOf(limit.toString()))
            .use { rows -> buildList { while (rows.moveToNext()) add(rows.getLong(0)) } }
        var bytes = 0
        for (sequence in batch) {
            if (bytes >= 2 * BufferSourceEvent.MAX_BYTES) break
            db.execSQL("SAVEPOINT source_projection_undo_effect")
            var damaged=false
            try {
            val size=db.rawQuery("""SELECT LENGTH(CAST(body AS BLOB)),CASE WHEN
                typeof(event_id)='text' AND LENGTH(CAST(event_id AS BLOB)) BETWEEN 1 AND 160
                AND typeof(fingerprint)='text' AND LENGTH(CAST(fingerprint AS BLOB))=64
                AND typeof(body_sha256)='text' AND LENGTH(CAST(body_sha256 AS BLOB))=64
                AND typeof(native_sequence)='integer' THEN 1 ELSE 0 END
                FROM source_projection_effects WHERE effect_sequence=?""",arrayOf(sequence.toString()))
                .use { check(it.moveToFirst());require(it.getInt(1)==1);it.getLong(0) }
            require(size in 1L..4L*BufferSourceEvent.MAX_BYTES)
            val effect = db.rawQuery("SELECT event_id,fingerprint,body_sha256,native_sequence FROM source_projection_effects WHERE effect_sequence=?", arrayOf(sequence.toString()))
                .use { row -> check(row.moveToFirst())
                    require((0..2).all { row.getType(it)==Cursor.FIELD_TYPE_STRING } && row.getType(3)==Cursor.FIELD_TYPE_INTEGER)
                    val body = BufferSqlText.read(db, "source_projection_effects", "effect_sequence=?", arrayOf(sequence.toString()), 4 * BufferSourceEvent.MAX_BYTES)
                    listOf(row.getString(0),row.getString(1),body,row.getString(2),row.getString(3)) }
            check(effectHash(effect[0], effect[1], effect[4].toLong(), effect[2]) == effect[3]) { "projection recovery image needs review" }
            bytes += effect[2].toByteArray(Charsets.UTF_8).size
            val original = db.rawQuery("SELECT fingerprint FROM source_events WHERE event_id=?", arrayOf(effect[0]))
                .use { check(it.moveToFirst()); it.getString(0) }
            check(original == effect[1]); val nativeSequence = effect[4].toLong()
            check(nativeSequence in 0L..BufferSourceEvent.MAX_COUNTER)
            val patches = JSONArray(effect[2]); for (i in patches.length() - 1 downTo 0) undo(db, patches.getJSONObject(i), nativeSequence)
            db.delete("source_projection_effect_targets", "effect_sequence=?", arrayOf(sequence.toString()))
            check(db.delete("source_projection_effects", "effect_sequence=?", arrayOf(sequence.toString())) == 1)
            } catch (_: IllegalArgumentException) { damaged=true }
              catch (_: IllegalStateException) { damaged=true }
              catch (_: org.json.JSONException) { damaged=true }
            if(damaged)db.execSQL("ROLLBACK TO SAVEPOINT source_projection_undo_effect")
            db.execSQL("RELEASE SAVEPOINT source_projection_undo_effect")
            if(damaged){retireDamagedEffect(db,sequence);return true}
        }
        val more = db.rawQuery("SELECT 1 FROM source_projection_effects LIMIT 1", null).use { it.moveToFirst() }
        if (!more) {
            db.execSQL("UPDATE source_projection_effect_recovery SET phase='replay' WHERE id=1")
            db.execSQL("UPDATE source_projection_scan SET after_event_id='',complete=0 WHERE kind='replay'")
        }
        return true
    }
    fun replayComplete(db: SQLiteDatabase) { db.execSQL("UPDATE source_projection_effect_recovery SET phase='ready' WHERE id=1 AND phase='replay'") }
}
