package com.mocharealm.foundation.fabric.buffer.data.local

import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** Damaged derived bindings are preserved as SQLite values. They cannot be
 * before-images, native edits or authority to execute an imported record. */
internal object BufferSourceMetadata {
    class Damaged(val table: String, val identity: JSONObject) : RuntimeException("source metadata needs recovery")
    private val observationColumns = listOf("owner_peer", "source_id", "view_kind", "entity_id", "event_id", "source_sequence",
        "logical_clock", "card_id", "body", "binding_sha256", "page_key")
    private fun observation(table: String) = table in setOf("source_projection_observations", BufferSourceDeviceObservations.TABLE)
    private fun keys(table: String): List<String> = when {
        observation(table) -> listOf("owner_peer", "source_id", "view_kind", "entity_id")
        table == "source_projection_retained_rows" -> listOf("table_name", "row_key")
        else -> listOf(requireNotNull(columns[table]).first())
    }
    private val columns = mapOf(
        "source_projection_observations" to observationColumns,
        BufferSourceDeviceObservations.TABLE to observationColumns,
        "source_projection_captures" to listOf("capture_id", "owner_peer", "source_id", "source_sequence", "baseline", "binding_sha256"),
        "source_projection_cards" to listOf("card_id", "capture_id", "owner_peer", "source_id", "source_sequence", "baseline", "binding_sha256"),
        "source_projection_native_overrides" to listOf("document_id", "body", "body_sha256"),
        "runtime_settings" to listOf("key", "value"),
        "source_projection_retained_rows" to listOf("table_name", "row_key", "body", "body_sha256"))

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_metadata_damage (
            damage_id INTEGER PRIMARY KEY AUTOINCREMENT,table_name TEXT NOT NULL,row_key TEXT NOT NULL,
            kind TEXT,entity_id TEXT,pending INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_metadata_target ON source_projection_metadata_damage(pending,kind,entity_id)")
        for ((table, names) in columns) {
            // Untyped archive columns retain even unexpected BLOB/NULL values.
            db.execSQL("CREATE TABLE IF NOT EXISTS ${table}_damage (damage_id INTEGER NOT NULL,${names.joinToString(",")})")
        }
        // Earlier recovery archives did not copy this derived association.
        // Leave old entries explicitly unknown; never invent its old value.
        val captureColumn = db.rawQuery("PRAGMA table_info(source_projection_cards_damage)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "capture_id") found = true
            found
        }
        if (!captureColumn) db.execSQL("ALTER TABLE source_projection_cards_damage ADD COLUMN capture_id")
    }

    /** Check SQL types and lengths before loading an untrusted large body. */
    fun checkRows(db: SQLiteDatabase, table: String, selection: String, args: Array<String>) {
        val names = columns[table] ?: return
        val identity = keys(table)
        val body = when (table) {
            "runtime_settings" -> "value"
            "source_projection_retained_rows", "source_projection_native_overrides", "source_projection_observations", BufferSourceDeviceObservations.TABLE -> "body"
            else -> "baseline"
        }
        val integers = names.filter { it in setOf("source_sequence", "logical_clock") }
        val text = names - integers.toSet()
        val limit = when { observation(table) -> 65536L; table == "source_projection_retained_rows" -> 4L * BufferSourceEvent.MAX_BYTES; else -> BufferSourceEvent.MAX_BYTES.toLong() }
        val bad = text.joinToString(" OR ") {
            if (observation(table) && it == "card_id") "typeof(card_id) NOT IN ('null','text')" else "typeof($it)<>'text'"
        } +
            text.filter { it != body }.joinToString("") { name ->
                val maximum = when { name.endsWith("sha256") || name == "owner_peer" -> 64; name == "source_id" -> 96; else -> 65536 }
                " OR LENGTH(CAST($name AS BLOB)) NOT BETWEEN 1 AND $maximum"
            } +
            integers.joinToString("") { " OR typeof($it)<>'integer'" } +
            " OR LENGTH(CAST($body AS BLOB)) NOT BETWEEN 1 AND $limit"
        db.rawQuery("SELECT ${identity.joinToString(",")} FROM $table WHERE ($selection) AND ${if (table == "runtime_settings") "key GLOB 'crdt:*' AND " else ""}($bad) LIMIT 1", args).use {
            if (it.moveToFirst()) throw Damaged(table, JSONObject().also { key ->
                identity.forEachIndexed { index, name -> key.put(name, it.getString(index)) }
            })
        }
    }

    private fun target(table: String, identity: JSONObject): Pair<String, String>? {
        if (observation(table)) return null // Read-only rows are rebuilt by clear_views.
        if (table == "source_projection_captures") return "capture" to identity.getString("capture_id")
        if (table == "source_projection_cards") return "document" to identity.getString("card_id")
        if (table == "source_projection_native_overrides") return "document" to identity.getString("document_id")
        val key = if (table == "runtime_settings") identity else try { JSONObject(identity.getString("row_key")) }
            catch (_: org.json.JSONException) { return null }
        return when {
            key.has("card_id") -> "document" to key.getString("card_id")
            key.has("capture_id") -> "capture" to key.getString("capture_id")
            key.has("key") && key.getString("key").startsWith("crdt:") -> "document" to key.getString("key").removePrefix("crdt:")
            else -> null
        }
    }

    private fun archive(db: SQLiteDatabase, table: String, identity: JSONObject, kind: String?, id: String?) {
        val names = requireNotNull(columns[table])
        val keyNames = keys(table)
        require(identity.keys().asSequence().toSet() == keyNames.toSet())
        val where = keyNames.joinToString(" AND ") { "$it=?" }
        val args = keyNames.map { identity.getString(it) }.toTypedArray()
        if (!db.rawQuery("SELECT 1 FROM $table WHERE $where", args).use { it.moveToFirst() }) return
        db.execSQL("INSERT INTO source_projection_metadata_damage(table_name,row_key,kind,entity_id) VALUES(?,?,?,?)",
            arrayOf(table, BufferCrdt.canonical(identity), kind, id))
        val archive = db.rawQuery("SELECT last_insert_rowid()", null).use { check(it.moveToFirst()); it.getLong(0) }
        db.execSQL("INSERT INTO ${table}_damage(damage_id,${names.joinToString(",")}) SELECT ?,${names.joinToString(",")} FROM $table WHERE $where",
            arrayOf(archive.toString(), *args))
        check(db.delete(table, where, args) == 1)
    }

    fun recover(db: SQLiteDatabase, error: Damaged) {
        check(db.inTransaction())
        // A malformed private key is archived too; it cannot identify a
        // business entity or prevent unrelated valid originals rebuilding.
        val target = target(error.table, error.identity)?.takeIf {
            it.second.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))
        }
        archive(db, error.table, error.identity, target?.first, target?.second)
        if (error.table in setOf("source_projection_captures", "source_projection_cards")) {
            // An old private copy of the same broken binding must not be
            // selected as a fallback during the subsequent rebuild.
            for (name in listOf(error.table, "legacy_${error.table}")) archive(db, "source_projection_retained_rows",
                JSONObject().put("table_name", name).put("row_key", BufferCrdt.canonical(error.identity)), target?.first, target?.second)
        }
        BufferSourceLegacyMigration.create(db, start = true)
        BufferEventHistory.requestRebuild(db)
    }

    fun pending(db: SQLiteDatabase, kind: String, id: String): Boolean = db.rawQuery(
        "SELECT 1 FROM source_projection_metadata_damage WHERE pending=1 AND kind=? AND entity_id=? LIMIT 1",
        arrayOf(kind, id)).use { it.moveToFirst() }
}
