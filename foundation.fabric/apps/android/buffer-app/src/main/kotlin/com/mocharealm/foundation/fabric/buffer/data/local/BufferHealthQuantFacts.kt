package com.mocharealm.foundation.fabric.buffer.data.local

import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuant
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuantRecord

/** Read-only facts from verified originals. Received confirmations never become
 * local approval, model work, CalendarProvider or Health Connect authority.
 * The index accelerates selection; every selected original is checked again. */
internal object BufferHealthQuantFacts {
    private const val predicate = "CASE WHEN json_valid(envelope) THEN json_extract(envelope,'$.event.type') END='HealthQuantRecorded'"
    data class Fact(val original: BufferSourceEvent, val record: HealthQuantRecord, val category: String?)

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS source_health_quant_facts ON source_events(source_id,source_sequence) WHERE $predicate")
    }

    fun observes(original: BufferSourceEvent): Boolean {
        if(original.event.getString("type")!="HealthQuantRecorded")return false
        parse(original)
        return true
    }

    private fun parse(original: BufferSourceEvent): Fact {
        val event=original.event
        require(event.getLong("schema_version")==1L && event.getString("type")=="HealthQuantRecorded")
        val p=event.getJSONObject("payload")
        require(p.keys().asSequence().toSet() in setOf(setOf("card_id","revision","state","data"),
            setOf("card_id","revision","state","data","category")))
        val id=p.get("card_id");require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val revision=p.get("revision");require(revision is Number && revision.toDouble()==revision.toLong().toDouble() && revision.toLong() in 1 until BufferSourceEvent.MAX_COUNTER)
        val state=p.get("state");require(state is String && state in setOf("proposed","confirmed","retracted"))
        val category=if(p.has("category"))p.getString("category").also {
            require(it in setOf("nutrition","hydration","sleep","exercise","symptom"))
        }else null
        return Fact(original,HealthQuantRecord(id,revision.toLong(),state,HealthQuant.parse(p.getJSONObject("data").toString())),category)
    }

    fun local(db: SQLiteDatabase, card: String): Fact? {
        val id=db.rawQuery("SELECT event_id FROM source_events WHERE $predicate AND source_id=? AND json_extract(envelope,'$.event.payload.card_id')=? ORDER BY source_sequence DESC LIMIT 1",
            arrayOf(BufferEventJournal.source(db),card)).use { if(it.moveToFirst())it.getString(0)else null } ?: return null
        return parse(BufferEventJournal.localRecord(db,id)).also { check(it.record.cardId==card) }
    }

    /** One latest revision per source/card, then deterministic event-clock
     * ordering for edits to the same shared card. A tombstone/proposal wins over
     * an older confirmation; source-local revision counters are never compared
     * across different devices. Only accepted owner-pinned originals are read. */
    fun latest(db: SQLiteDatabase): List<Fact> {
        val local=BufferEventJournal.source(db)
        val locations=db.rawQuery("""WITH latest AS (
            SELECT source_id,MAX(source_sequence) AS seq FROM source_events WHERE $predicate
            GROUP BY source_id,json_extract(envelope,'$.event.payload.card_id'))
            SELECT e.event_id,e.source_id,e.source_sequence,r.owner_peer FROM latest l
            JOIN source_events e ON e.source_id=l.source_id AND e.source_sequence=l.seq
            LEFT JOIN source_event_receivers r ON r.source_id=e.source_id
            WHERE e.source_id=? OR r.owner_peer IS NOT NULL""",arrayOf(local)).use { rows -> buildList {
                while(rows.moveToNext())add(arrayOf(rows.getString(0),rows.getString(1),rows.getLong(2).toString(),rows.getString(3)))
            } }
        val selected=linkedMapOf<String,Fact>()
        val order=compareBy<Fact>({if(it.record.state=="proposed")0 else 1},{it.original.logicalClock},{it.original.source},{it.original.sequence})
        locations.forEach { location ->
            val original=if(location[1]==local)BufferEventJournal.localRecord(db,location[0]) else
                BufferEventJournal.received(db,requireNotNull(location[3]),location[1],location[2].toLong()){true}
            check(original.eventId==location[0])
            val fact=parse(original)
            val old=selected[fact.record.cardId]
            if(old==null || order.compare(fact,old)>0)selected[fact.record.cardId]=fact
        }
        return selected.values.sortedWith(compareBy({it.record.data.date},{it.original.createdAtForHealth()},{it.original.source},{it.original.sequence}))
    }

    private fun BufferSourceEvent.createdAtForHealth()=event.getLong("created_at")
}
