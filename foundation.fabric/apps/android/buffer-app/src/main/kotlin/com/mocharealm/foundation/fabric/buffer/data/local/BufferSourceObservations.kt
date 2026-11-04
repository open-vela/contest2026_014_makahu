package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import org.json.JSONObject
import java.util.Locale

internal data class SourceObservation(val kind: String, val id: String, val card: String?, val body: JSONObject)
internal data class SourceObservationEntry(val kind: String, val peer: String, val source: String, val body: JSONObject, val projection: String)
internal data class SourceObservationPage(val entries: List<SourceObservationEntry>, val older: String?, val recovering: Boolean = false)

/** Typed read-only session/answer views. They never become a current session,
 * a mode request, model input, a local executor receipt or a forwarded CRDT. */
internal object BufferSourceObservations {
    private val id = Regex("[A-Za-z0-9_-]{1,79}")
    private val researchId = Regex("[A-Za-z0-9_.-]{1,79}")
    private val recordTypes = setOf("CaptureCreated", "CaptureRecordUpdated", "CaptureTranscribed", "ClassificationProposed",
        "AssistantResultRecorded", "CardRecordChanged", "LegacyCaptureSnapshot")

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_observations (
            owner_peer TEXT NOT NULL,source_id TEXT NOT NULL,view_kind TEXT NOT NULL CHECK(view_kind IN ('focus','research','turn')),entity_id TEXT NOT NULL,
            event_id TEXT NOT NULL,source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990),
            logical_clock INTEGER NOT NULL CHECK(typeof(logical_clock)='integer' AND logical_clock BETWEEN 0 AND 9007199254740991),card_id TEXT,
            body TEXT NOT NULL,binding_sha256 TEXT NOT NULL,page_key TEXT NOT NULL UNIQUE,
            PRIMARY KEY(owner_peer,source_id,view_kind,entity_id))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_projection_session_page ON source_projection_observations(page_key)
            WHERE view_kind IN ('focus','research')""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_projection_turn_page ON source_projection_observations(card_id,page_key)
            WHERE view_kind='turn'""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_observation_card ON source_projection_observations(owner_peer,source_id,card_id)")
        BufferSourceDeviceObservations.create(db)
    }
    private fun text(j: JSONObject, key: String, limit: Int, blank: Boolean = false): String {
        val v = j.get(key); require(v is String && '\u0000' !in v && v.toByteArray(Charsets.UTF_8).size <= limit && (blank || v.isNotBlank()))
        return v
    }
    private fun number(j: JSONObject, key: String, maximum: Long = BufferSourceEvent.MAX_COUNTER): Long {
        val v = j.get(key); require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble() && v.toLong() in 0L..maximum)
        return v.toLong()
    }
    private fun turn(value: JSONObject, card: String? = null, capture: String? = null): SourceObservation {
        require(value.keys().asSequence().toSet() == setOf("card_id", "capture_id", "question", "answer", "created_at"))
        val target = text(value, "card_id", 79); require(id.matches(target) && (card == null || target == card))
        val key = text(value, "capture_id", 79); require(id.matches(key) && (capture == null || key == capture))
        text(value, "question", 4096); text(value, "answer", 4096); number(value, "created_at")
        return SourceObservation("turn", key, target, value)
    }
    private fun focus(value: JSONObject): SourceObservation {
        val session = FocusSession.parse(value)
        return SourceObservation("focus", session.id, session.cardId.ifBlank { null }, session.json())
    }
    private fun research(value: JSONObject): SourceObservation? {
        require(value.keys().asSequence().toSet() == setOf("schema_version", "present", "active", "id", "title", "reason",
            "remaining_ms", "display_expired", "recorded_at", "recorded_mono", "boot_id"))
        require(number(value, "schema_version", 1) == 1L)
        listOf("present", "active", "display_expired").forEach { require(value.get(it) is Boolean) }
        val key = text(value, "id", 79, true); val title = text(value, "title", 1024, true); val reason = text(value, "reason", 32, true)
        val remaining = number(value, "remaining_ms", 7_200_000)
        number(value, "recorded_at"); number(value, "recorded_mono")
        require(text(value, "boot_id", 32).matches(Regex("[0-9a-f]{32}")))
        if (!value.getBoolean("present")) {
            require(!value.getBoolean("active") && remaining == 0L && key.isEmpty() && title.isEmpty() && reason.isEmpty())
            return null
        }
        require(researchId.matches(key))
        if (value.getBoolean("active")) require(reason.isEmpty())
        else require(remaining == 0L && reason in setOf("time_up", "finished", "mode_changed"))
        return SourceObservation("research", key, null, value)
    }

    /** null means another adapter owns this type; an empty list is a valid
     * observation without a completed session/answer, never a deletion. */
    fun parse(record: BufferSourceEvent): List<SourceObservation>? {
        BufferSourceDeviceObservations.parse(record)?.let { return it }
        val event = record.event; val p = event.getJSONObject("payload"); val type = event.getString("type")
        if (type in recordTypes && p.has("record_json")) {
            val raw = p.get("record_json"); require(raw is String)
            val wrapper = BufferSourceEvent.parseObject(raw, 65536)
            if (wrapper.opt("state") != "assistant_complete" || wrapper.opt("target") != "assistant" ||
                (!wrapper.has("followup") && !wrapper.has("focus_session"))) return null
            require(p.keys().asSequence().toSet() == setOf("record_json"))
            val result = VelaProcessedCaptureProtocol.decode(VelaProcessedCaptureProtocol.encode(raw))
            require(event.get("capture_id") == result.captureId)
            return if (result.focusSession != null) listOf(focus(result.focusSession.json()))
                else listOf(turn(JSONObject(requireNotNull(result.followup)), capture = result.captureId))
        }
        val kind = p.opt("projection_kind")
        val allowed = when (kind) {
            "focus" -> setOf("FocusStateObserved", "FocusSessionChanged", "FocusModeRequestRecorded", "LegacyProjectionSnapshot")
            "research" -> setOf("ResearchSessionObserved", "ResearchDisplayDeadlineReached", "LegacyProjectionSnapshot")
            "conversation" -> setOf("ConversationViewUpdated", "LegacyProjectionSnapshot")
            "followup" -> setOf("FollowupContextPrepared", "QuestionAnswered", "FollowupCaptureCancelled", "LegacyProjectionSnapshot")
            else -> return null
        }
        if (type !in allowed) return null
        require(p.keys().asSequence().toSet() == setOf("projection_kind", "entity_id", "projection_text"))
        val entity = text(p, "entity_id", 79); require(id.matches(entity))
        if (kind in setOf("focus", "research")) require(entity == "state")
        if (p.isNull("projection_text")) return emptyList()
        val raw = p.get("projection_text"); require(raw is String)
        val value = BufferSourceEvent.parseObject(raw, 65536)
        return when (kind) {
            "focus" -> {
                if (type in setOf("FocusSessionChanged", "FocusModeRequestRecorded")) require(value.has("session") && !value.isNull("session"))
                if (value.has("session") && !value.isNull("session")) listOf(focus(value.getJSONObject("session"))) else emptyList()
            }
            "research" -> {
                val result = research(value)
                if (type == "ResearchDisplayDeadlineReached") require(value.getBoolean("display_expired"))
                result?.let { listOf(it) } ?: emptyList()
            }
            "conversation" -> {
                require(value.keys().asSequence().toSet() == setOf("turns"))
                val turns = value.getJSONArray("turns"); require(turns.length() <= 8)
                (0 until turns.length()).map { turn(turns.getJSONObject(it), card = entity) }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
            }
            else -> {
                val card = text(value, "card_id", 79); require(id.matches(card))
                text(value, "topic", 24000, true)
                val views = mutableListOf<SourceObservation>()
                if (value.has("history")) {
                    val history = value.getJSONArray("history"); require(history.length() <= 8)
                    (0 until history.length()).forEach { views += turn(history.getJSONObject(it), card = card) }
                }
                if (type == "QuestionAnswered") require(value.has("result"))
                if (value.has("result")) views += turn(value.getJSONObject("result"), card, entity)
                views.groupBy { it.id }.values.map { same ->
                    require(same.all { BufferCrdt.canonical(it.body) == BufferCrdt.canonical(same.first().body) }); same.first()
                }
            }
        }
    }

    private fun pageKey(clock: Long, peer: String, source: String, kind: String, entity: String) =
        String.format(Locale.ROOT, "%016d:%s:%s:%s:%s", clock, peer, source, kind, entity)
    private fun hash(peer: String, source: String, kind: String, entity: String, event: String, sequence: Long, clock: Long, body: JSONObject): String =
        VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(JSONObject().put("peer", peer).put("source", source).put("kind", kind)
            .put("entity", entity).put("event", event).put("sequence", sequence).put("clock", clock).put("body", body)))

    /** Immutable answers and monotonic latest session views. Preflight the
     * whole observation list before changing any row. */
    fun apply(db: SQLiteDatabase, peer: String, record: BufferSourceEvent, values: List<SourceObservation>): String {
        check(db.inTransaction())
        if (values.map { it.kind to it.id }.distinct().size != values.size) return "conflict"
        val updates = mutableListOf<SourceObservation>()
        for (view in values) {
            val table=table(view.kind)
            val old = stored(db, table, peer, record.source, view.kind, view.id)
            if (old != null) {
                val equal = BufferCrdt.canonical(old.second) == BufferCrdt.canonical(view.body)
                if (view.kind == "turn") { if (!equal) return "conflict"; continue }
                if (record.sequence < old.first) continue
                if (record.sequence == old.first && !equal) return "conflict"
                if (view.kind == "focus") {
                    val before = FocusSession.parse(old.second); val after = FocusSession.parse(view.body)
                    if (before.startedAt != after.startedAt || before.cardId != after.cardId || before.calendarEventId != after.calendarEventId ||
                        after.updatedAt < before.updatedAt || (after.updatedAt == before.updatedAt && !equal) ||
                        (!before.active && after.active) || (before.status == "reviewed" && after.status != "reviewed")) return "conflict"
                } else if(view.kind in BufferSourceDeviceObservations.kinds) {
                    if(!BufferSourceDeviceObservations.compatible(view.kind,old.second,view.body))return "conflict"
                } else if (!old.second.getBoolean("active") && view.body.getBoolean("active")) return "conflict"
            }
            updates += view
        }
        for (view in updates) {
            check(db.insertWithOnConflict(table(view.kind), null, ContentValues().apply {
                put("owner_peer", peer); put("source_id", record.source); put("view_kind", view.kind); put("entity_id", view.id)
                put("event_id", record.eventId); put("source_sequence", record.sequence); put("logical_clock", record.logicalClock); put("card_id", view.card)
                put("body", view.body.toString()); put("binding_sha256", hash(peer, record.source, view.kind, view.id, record.eventId, record.sequence, record.logicalClock, view.body))
                put("page_key", pageKey(record.logicalClock, peer, record.source, view.kind, view.id))
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        }
        return if (values.isEmpty()) "observed_only" else "applied"
    }

    private fun table(kind: String): String = if(kind in BufferSourceDeviceObservations.kinds)
        BufferSourceDeviceObservations.TABLE else "source_projection_observations"

    fun inspect(db: SQLiteDatabase, table: String, identity: JSONObject) {
        require(table in setOf("source_projection_observations", BufferSourceDeviceObservations.TABLE))
        stored(db, table, identity.getString("owner_peer"), identity.getString("source_id"),
            identity.getString("view_kind"), identity.getString("entity_id"))
    }
    private fun stored(db: SQLiteDatabase, table: String, peer: String, source: String, kind: String, entity: String): Pair<Long, JSONObject>? {
        val identity = JSONObject().put("owner_peer",peer).put("source_id",source).put("view_kind",kind).put("entity_id",entity)
        val selection = "owner_peer=? AND source_id=? AND view_kind=? AND entity_id=?"
        val args = arrayOf(peer,source,kind,entity)
        BufferSourceMetadata.checkRows(db, table, selection, args)
        try {
            return db.rawQuery("SELECT event_id,source_sequence,logical_clock,body,binding_sha256,page_key,card_id FROM $table WHERE $selection", args).use { row ->
                if (!row.moveToFirst()) null else {
                    val body = checked(peer,source,kind,entity,row.getString(0),row.getLong(1),row.getLong(2),
                        row.getString(3),row.getString(4),row.getString(5),row.getString(6))
                    val original = BufferEventJournal.received(db,peer,source,row.getLong(1)) { true }
                    check(original.eventId == row.getString(0) && original.logicalClock == row.getLong(2))
                    BufferSourceProjection.ensureQueued(db,original.eventId)
                    val expected = parse(original)?.singleOrNull { it.kind == kind && it.id == entity }
                    check(expected != null && expected.card == row.getString(6) && BufferCrdt.canonical(expected.body) == BufferCrdt.canonical(body))
                    check(db.rawQuery("SELECT 1 FROM source_event_projection_details WHERE event_id=? AND fingerprint=?",
                        arrayOf(original.eventId,original.fingerprint)).use { it.moveToFirst() })
                    row.getLong(1) to body
                }
            }
        } catch (error: BufferSourceMetadata.Damaged) { throw error }
          catch (_: IllegalArgumentException) { throw BufferSourceMetadata.Damaged(table,identity) }
          catch (_: IllegalStateException) { throw BufferSourceMetadata.Damaged(table,identity) }
          catch (_: org.json.JSONException) { throw BufferSourceMetadata.Damaged(table,identity) }
    }

    private fun checked(peer: String, source: String, kind: String, entity: String, event: String, sequence: Long, clock: Long,
                        raw: String, digest: String, key: String, card: String?): JSONObject {
        check(peer.matches(Regex("[0-9a-f]{64}")) && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")) &&
            sequence in 1L until BufferSourceEvent.MAX_COUNTER && clock in 0L..BufferSourceEvent.MAX_COUNTER)
        val value = BufferSourceEvent.parseObject(raw, 65536)
        check(digest == hash(peer, source, kind, entity, event, sequence, clock, value) && key == pageKey(clock, peer, source, kind, entity))
        val parsed = when (kind) { "focus" -> focus(value); "research" -> requireNotNull(research(value)); "turn" -> turn(value)
            in BufferSourceDeviceObservations.kinds -> BufferSourceDeviceObservations.checked(kind,value); else -> error("unknown source view") }
        check(parsed.id == entity && parsed.card == card); return value
    }

    fun page(db: SQLiteDatabase, card: String?, before: String? = null): SourceObservationPage {
        if (card != null) require(id.matches(card))
        if (before != null) require(before.matches(Regex("[0-9]{16}:[0-9a-f]{64}:[A-Za-z0-9_.-]{1,96}:(focus|research|turn|timer|alarm|mode|focus_config|focus_reminder):[A-Za-z0-9_.:-]{1,79}")))
        if(BufferSourceEffects.undoing(db)||BufferSourceLegacyMigration.active(db))return SourceObservationPage(emptyList(),null,recovering=true)
        val where = if (card == null) "v.view_kind<>'turn'" else "v.view_kind='turn' AND v.card_id=?"
        val views=if(card==null)"(SELECT * FROM source_projection_observations UNION ALL SELECT * FROM ${BufferSourceDeviceObservations.TABLE})" else "source_projection_observations"
        val args = mutableListOf<String>(); if (card != null) args += card; if (before != null) args += before
        val entries = mutableListOf<SourceObservationEntry>(); var older: String? = null; var damaged = false
        db.rawQuery("""SELECT v.owner_peer,v.source_id,v.view_kind,v.entity_id,v.event_id,v.source_sequence,v.logical_clock,v.body,v.binding_sha256,v.page_key,
            p.state,e.fingerprint,d.fingerprint,r.owner_peer,v.card_id FROM $views v
            LEFT JOIN source_event_projections p ON p.event_id=v.event_id
            LEFT JOIN source_events e ON e.event_id=v.event_id AND e.source_id=v.source_id AND e.source_sequence=v.source_sequence
            LEFT JOIN source_event_projection_details d ON d.event_id=v.event_id
            LEFT JOIN source_event_receivers r ON r.source_id=v.source_id
            JOIN source_event_history h ON h.event_id=v.event_id AND h.fingerprint=e.fingerprint
            WHERE h.state IN ('linked','legacy','missing_parent','waiting_parent') AND v.owner_peer=r.owner_peer
                AND typeof(v.body)='text' AND LENGTH(CAST(v.body AS BLOB)) BETWEEN 1 AND 65536
                AND typeof(v.binding_sha256)='text' AND LENGTH(CAST(v.binding_sha256 AS BLOB))=64 AND $where${if (before == null) "" else " AND v.page_key<?"} ORDER BY v.page_key DESC LIMIT 21""", args.toTypedArray()).use { rows ->
            while (rows.moveToNext()) {
                if (entries.size == 20) break
                val body = try {
                    checked(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getLong(5), rows.getLong(6), rows.getString(7), rows.getString(8), rows.getString(9), rows.getString(14)).also {
                        check(!rows.isNull(10) && !rows.isNull(11) && rows.getString(0) == rows.getString(13) && rows.getString(11) == rows.getString(12))
                        if (card != null) check(it.getString("card_id") == card)
                    }
                } catch (_: IllegalArgumentException) { damaged = true; continue }
                  catch (_: IllegalStateException) { damaged = true; continue }
                  catch (_: org.json.JSONException) { damaged = true; continue }
                entries += SourceObservationEntry(rows.getString(2), rows.getString(0), rows.getString(1), body, rows.getString(10))
                older = rows.getString(9)
            }
            if (rows.isAfterLast) older = null
        }
        return SourceObservationPage(entries, older, recovering = damaged)
    }
}
