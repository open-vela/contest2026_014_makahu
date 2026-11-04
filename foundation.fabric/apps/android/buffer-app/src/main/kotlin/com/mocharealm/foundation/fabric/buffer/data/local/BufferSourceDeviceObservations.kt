package com.mocharealm.foundation.fabric.buffer.data.local

import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.math.BigInteger

/** Source device state is an observation, never a local alarm or command. */
internal object BufferSourceDeviceObservations {
    const val TABLE = "source_projection_device_observations"
    val kinds = setOf("timer", "alarm", "mode", "focus_config", "focus_reminder")
    private val assistantTypes = setOf("AssistantStateInitialized", "AssistantStateRecovered", "AssistantStateChanged",
        "AssistantReminderTriggered", "AssistantOperationCommitted", "AssistantDisplayRequested", "AssistantDisplayFailed", "LegacyProjectionSnapshot")
    private val reminderTypes=setOf("FocusReminderStateChanged","FocusReminderConfigObserved","FocusReminderScheduleChanged",
        "FocusReminderNoticeExpired","FocusReminderTriggered","FocusReminderModeChanged","FocusReminderModeObserved","LegacyProjectionSnapshot")
    private val modes=setOf("normal","focus","bedtime","rabbit_hole")
    private fun types(kind: String): Set<String> = when(kind) {
        "timer","alarm"->assistantTypes
        "mode"->setOf("ModeStateChanged","LegacyProjectionSnapshot")
        "focus_config"->setOf("FocusReminderConfigChanged","LegacyProjectionSnapshot")
        "focus_reminder"->reminderTypes
        else->emptySet()
    }
    private val unsignedMax = BigInteger("18446744073709551615")
    private const val DAY_MS = 86_400_000L

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE (
            owner_peer TEXT NOT NULL,source_id TEXT NOT NULL,view_kind TEXT NOT NULL CHECK(view_kind IN ('timer','alarm','mode','focus_config','focus_reminder')),entity_id TEXT NOT NULL,
            event_id TEXT NOT NULL,source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990),
            logical_clock INTEGER NOT NULL CHECK(typeof(logical_clock)='integer' AND logical_clock BETWEEN 0 AND 9007199254740991),card_id TEXT,
            body TEXT NOT NULL,binding_sha256 TEXT NOT NULL,page_key TEXT NOT NULL UNIQUE,
            PRIMARY KEY(owner_peer,source_id,view_kind,entity_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_device_page ON $TABLE(page_key)")
    }

    private fun integer(value: JSONObject, field: String, max: Long): Long {
        val n=value.get(field)
        require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toLong().toDouble() && n.toLong() in 0L..max)
        return n.toLong()
    }
    private fun wide(value: JSONObject, field: String, max: BigInteger = unsignedMax): BigInteger {
        val raw=value.get(field)
        require(raw is String && raw.matches(Regex("0|[1-9][0-9]{0,19}")))
        return BigInteger(raw).also { require(it<=max) }
    }
    private fun item(kind: String, value: JSONObject, next: BigInteger = unsignedMax): String {
        require(kind in kinds)
        if(kind=="mode") {
            require(value.keys().asSequence().toSet()==setOf("mode") && value.get("mode") in modes)
            return "state"
        }
        if(kind=="focus_config") {
            require(value.keys().asSequence().toSet()==setOf("interval_ms"))
            require(integer(value,"interval_ms",10_800_000)>=300_000)
            return "state"
        }
        if(kind=="focus_reminder") {
            require(value.keys().asSequence().toSet()==setOf("schema_version","mode","mode_id","revision","interval_ms",
                "elapsed_ms","remaining_ms","notice_ms","recorded_at","recorded_mono","boot_id"))
            require(integer(value,"schema_version",1)==1L && value.get("mode") in modes)
            for(field in listOf("mode_id","revision")) {
                val raw=value.get(field);require(raw is String && '\u0000' !in raw && raw.toByteArray(Charsets.UTF_8).size<80)
            }
            val boot=value.get("boot_id");require(boot is String && boot.matches(Regex("[0-9a-f]{32}")))
            require(integer(value,"interval_ms",10_800_000)>=300_000)
            integer(value,"remaining_ms",10_800_000);integer(value,"notice_ms",15_000)
            for(field in listOf("elapsed_ms","recorded_at","recorded_mono"))integer(value,field,BufferSourceEvent.MAX_COUNTER)
            return "state"
        }
        val expected=if(kind=="timer")setOf("id","label","duration_ms","remaining_ms","wall_deadline_ms","sequence","state")
            else setOf("id","label","scheduled_at_unix_ms","sequence","state")
        require(value.keys().asSequence().toSet()==expected)
        val id=value.get("id");require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,63}")))
        val label=value.get("label");require(label is String && '\u0000' !in label && label.toByteArray(Charsets.UTF_8).size<96)
        require(integer(value,"state",4)>=1)
        wide(value,"sequence",next)
        if(kind=="timer") {
            val duration=integer(value,"duration_ms",DAY_MS);require(duration>0)
            integer(value,"remaining_ms",duration)
            wide(value,"wall_deadline_ms",BigInteger.valueOf(Long.MAX_VALUE))
        } else require(wide(value,"scheduled_at_unix_ms",BigInteger.valueOf(Long.MAX_VALUE))>BigInteger.ZERO)
        return id
    }

    fun parse(record: BufferSourceEvent): List<SourceObservation>? {
        val event=record.event;val p=event.getJSONObject("payload");val type=event.getString("type")
        val projection=p.opt("projection_kind")
        val kind=when(projection){"assistant"->"timer";"focus_runtime"->"focus_reminder";"mode"->"mode";"focus_config"->"focus_config";else->return null}
        if(type !in types(kind))return null
        require(p.keys().asSequence().toSet()==setOf("projection_kind","entity_id","projection_text") && p.get("entity_id")=="state")
        if(p.isNull("projection_text"))return emptyList()
        val raw=p.get("projection_text");require(raw is String)
        if(projection!="assistant") {
            val value=when(kind) {
                "mode"->{val match=Regex("mode=(normal|focus|bedtime|rabbit_hole)\\r?\\n?").matchEntire(raw)
                    require(match!=null);JSONObject().put("mode",match.groupValues[1])}
                "focus_config"->{val text=raw.trim();require(text.matches(Regex("[0-9]{1,8}")))
                    JSONObject().put("interval_ms",text.toLong())}
                else->BufferSourceEvent.parseObject(raw,65536)
            }
            item(kind,value)
            return listOf(SourceObservation(kind,"state",null,JSONObject().put("item",value)
                .put("recorded_at",event.getLong("created_at")).put("event_type",type)))
        }
        val state=BufferSourceEvent.parseObject(raw,65536)
        require(state.keys().asSequence().toSet()==setOf("timers","alarms","operations","request","schema_version","next_sequence","operation_next"))
        require(integer(state,"schema_version",1)==1L)
        val next=wide(state,"next_sequence")
        val operationNext=integer(state,"operation_next",31)
        val operations=state.getJSONArray("operations");require(operations.length()<=32)
        if(operations.length()<32)require(operationNext==operations.length().toLong())
        require(state.isNull("request") || state.get("request") is JSONObject)
        val views=mutableListOf<SourceObservation>()
        for(kind in listOf("timer","alarm")) {
            val values=state.getJSONArray(if(kind=="timer")"timers" else "alarms");require(values.length()<=8)
            val ids=mutableSetOf<String>()
            for(i in 0 until values.length()) {
                val value=values.getJSONObject(i);val id=item(kind,value,next);require(ids.add(id))
                val body=JSONObject().put("item",value).put("recorded_at",event.getLong("created_at")).put("event_type",type)
                views+=SourceObservation(kind,id,null,body)
            }
        }
        return views
    }

    fun checked(kind: String, body: JSONObject): SourceObservation {
        require(body.keys().asSequence().toSet()==setOf("item","recorded_at","event_type"))
        integer(body,"recorded_at",BufferSourceEvent.MAX_COUNTER)
        require(body.get("event_type") in types(kind))
        return SourceObservation(kind,item(kind,body.getJSONObject("item")),null,body)
    }
    fun compatible(kind: String, before: JSONObject, after: JSONObject): Boolean {
        if(kind !in setOf("timer","alarm"))return true
        val old=before.getJSONObject("item");val next=after.getJSONObject("item")
        if(old.getString("label")!=next.getString("label"))return false
        val immutable=if(kind=="timer")"duration_ms" else "scheduled_at_unix_ms"
        if(old.get(immutable)!=next.get(immutable))return false
        if(wide(next,"sequence")!=wide(old,"sequence"))return false
        // Cancelling a fired item dismisses its source-device notice. Neither
        // fired nor cancelled items can become running/paused again.
        return when(old.getInt("state")) {3->next.getInt("state") in setOf(3,4);4->next.getInt("state")==4;else->true}
    }
}
