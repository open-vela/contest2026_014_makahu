package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.*
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local grants only. Source replay never writes these tables. All mutations,
 * execution gates and event callbacks share the caller's SQLite transaction. */
internal object BufferCapabilities {
    private const val ID = "cap_focus_care"
    private const val FACT_BYTES = 48 * 1024
    private const val RECORD_BYTES = 64 * 1024
    private fun hash(value: JSONObject) = VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(value))
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_proposals (
            capability_id TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, digest TEXT NOT NULL,
            state TEXT NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY(capability_id,version))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_grants (
            capability_id TEXT PRIMARY KEY, version INTEGER NOT NULL, revision TEXT NOT NULL, device TEXT NOT NULL,
            approved_at INTEGER NOT NULL, valid_until INTEGER NOT NULL, approval_event TEXT NOT NULL, active INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_generation (
            id TEXT PRIMARY KEY, facts TEXT NOT NULL, skill_digest TEXT NOT NULL, state TEXT NOT NULL,
            lease TEXT, lease_until INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_clock (
            capability_id TEXT PRIMARY KEY, revision TEXT NOT NULL, session TEXT NOT NULL, boot TEXT NOT NULL,
            elapsed INTEGER NOT NULL, total INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS capability_tasks (
            task_id TEXT PRIMARY KEY, capability_id TEXT NOT NULL, version INTEGER NOT NULL, revision TEXT NOT NULL,
            session TEXT NOT NULL, boot TEXT NOT NULL, queued_elapsed INTEGER NOT NULL, queued_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, state TEXT NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS capability_tasks_work ON capability_tasks(state,withdrawn,retry_at)")
    }
    private fun fact(db: SQLiteDatabase, id: String): JSONObject {
        val record = BufferEventJournal.localRecord(db, id)
        require(record.event.getString("type") in setOf("FocusSessionUpdated", "FocusEnded", "FocusFeedbackRecorded"))
        val session = FocusSession.parse(record.event.getJSONObject("payload"))
        return JSONObject().put("event_id", id).put("fingerprint", record.fingerprint).put("session", session.json())
    }
    fun request(db: SQLiteDatabase, skill: CapabilitySkill, now: Long, emit: (String, JSONObject) -> String): String {
        check(db.inTransaction())
        require(now in 1L..(BufferSourceEvent.MAX_COUNTER - 30 * CapabilityContract.DAY))
        val running = db.rawQuery("SELECT id FROM capability_generation WHERE state IN ('pending','running') LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (running != null) return running
        val seen = hashSetOf<String>(); val facts = JSONArray()
        db.rawQuery("SELECT event_id FROM events WHERE type IN ('FocusSessionUpdated','FocusEnded','FocusFeedbackRecorded') AND created_at>=? AND created_at<=? ORDER BY logical_clock DESC LIMIT 96",
            arrayOf((now - 30 * CapabilityContract.DAY).toString(),now.toString())).use { rows ->
            while (rows.moveToNext() && facts.length() < 24) {
                // An original that fails verification is a storage error. Old
                // payload schemas are simply ineligible for this bounded Skill.
                val record = BufferEventJournal.localRecord(db, rows.getString(0))
                val session = runCatching { FocusSession.parse(record.event.getJSONObject("payload")) }.getOrNull() ?: continue
                if (!seen.add(session.id) || session.active || session.startedAt < now - 30 * CapabilityContract.DAY || session.endedAt > now) continue
                if (session.endedAt - session.startedAt < 50 * 60_000L && session.experience !in setOf("tired", "hard")) continue
                facts.put(JSONObject().put("event_id", record.eventId).put("fingerprint", record.fingerprint).put("session", session.json()))
                // JSON escaping can expand a valid session considerably. Bound
                // the serialized input before persisting a job the worker reads.
                if (BufferCrdt.canonical(JSONObject().put("events", facts)).toByteArray(Charsets.UTF_8).size > FACT_BYTES)
                    facts.remove(facts.length() - 1)
            }
        }
        require(facts.length() >= 3) { "最近30天至少需要三次长专注或疲劳反馈，暂不生成缺少依据的建议" }
        val input = JSONObject().put("requested_at", now).put("reads", JSONArray(listOf("focus_sessions"))).put("events", facts)
        val id = "capjob_${UUID.randomUUID()}"
        db.insertOrThrow("capability_generation", null, ContentValues().apply {
            put("id", id); put("facts", BufferCrdt.canonical(input)); put("skill_digest", skill.digest); put("state", "pending")
        })
        emit("CapabilitySuggestionsRequested", JSONObject().put("job_id", id).put("skill_digest", skill.digest).put("evidence_count", facts.length()))
        return id
    }
    fun claim(db: SQLiteDatabase, now: Long): CapabilityGeneration? {
        check(db.inTransaction())
        db.execSQL("UPDATE capability_generation SET state='failed',lease=NULL WHERE state='running' AND lease_until<=? AND attempts>=3", arrayOf(now))
        return db.rawQuery("SELECT id,facts,skill_digest FROM capability_generation WHERE (state='pending' OR (state='running' AND lease_until<=?)) AND retry_at<=? AND attempts<3 ORDER BY rowid LIMIT 1",
            arrayOf(now.toString(), now.toString())).use { rows ->
            if (!rows.moveToFirst()) return@use null
            val id = rows.getString(0)
            val stored = rows.getString(1)
            val facts = runCatching {
                BufferSourceEvent.parseObject(stored, RECORD_BYTES).also {
                    CapabilityContract.number(it, "requested_at", 1L..BufferSourceEvent.MAX_COUNTER)
                }
            }.getOrNull()
            if (facts == null) {
                // Only malformed serialized input is terminal here. Database
                // read/write failures still propagate and remain retryable.
                db.execSQL("UPDATE capability_generation SET state='failed',lease=NULL WHERE id=?", arrayOf(id))
                return@use null
            }
            val requestedAt = facts.getLong("requested_at")
            if(now<requestedAt || now-requestedAt>=CapabilityContract.DAY) {
                db.execSQL("UPDATE capability_generation SET state='expired',lease=NULL WHERE id=?",arrayOf(id));return@use null
            }
            val lease = UUID.randomUUID().toString()
            db.execSQL("UPDATE capability_generation SET state='running',lease=?,lease_until=?,attempts=attempts+1 WHERE id=?", arrayOf(lease, now + 180_000, id))
            CapabilityGeneration(id, lease, facts, rows.getString(2))
        }
    }
    private fun owns(db: SQLiteDatabase, job: CapabilityGeneration, now: Long): Boolean = db.rawQuery(
        "SELECT facts FROM capability_generation WHERE id=? AND state='running' AND lease=? AND lease_until>? AND skill_digest=?",
        arrayOf(job.id, job.lease, now.toString(), job.skillDigest)).use {
            if(!it.moveToFirst()) false else {
                check(BufferCrdt.canonical(BufferSourceEvent.parseObject(it.getString(0),64*1024)) == BufferCrdt.canonical(job.facts)) { "建议输入已变化" }
                true
            }
        }
    fun finish(db: SQLiteDatabase, job: CapabilityGeneration, result: JSONObject, skill: CapabilitySkill, now: Long, emit: (String, JSONObject) -> String): Boolean {
        check(db.inTransaction()); if (!owns(db, job, now)) return false
        val requestedAt=job.facts.getLong("requested_at")
        if(now<requestedAt || now-requestedAt>=CapabilityContract.DAY) {
            db.execSQL("UPDATE capability_generation SET state='expired',lease=NULL WHERE id=?",arrayOf(job.id))
            emit("CapabilitySuggestionsExpired",JSONObject().put("job_id",job.id));return false
        }
        require(job.skillDigest == skill.digest && result.keys().asSequence().toSet() == setOf("suggestions"))
        val suggestions = result.getJSONArray("suggestions"); require(suggestions.length() <= 3)
        val checked = (0 until suggestions.length()).map { CapabilityContract.validate(suggestions.getJSONObject(it), job.facts) }
            .distinctBy { BufferCrdt.canonical(it) }
        checked.forEach { proposal ->
            val evidence = proposal.getJSONArray("evidence_ids")
            val originals = JSONArray()
            for (i in 0 until evidence.length()) {
                val original = fact(db, evidence.getString(i))
                val input = job.facts.getJSONArray("events")
                val supplied = (0 until input.length()).map { input.getJSONObject(it) }.single { it.getString("event_id") == evidence.getString(i) }
                check(BufferCrdt.canonical(original) == BufferCrdt.canonical(supplied)) { "历史依据已变化" }
                originals.put(original)
            }
            val version = db.rawQuery("SELECT COALESCE(MAX(version),0)+1 FROM capability_proposals WHERE capability_id=?", arrayOf(ID))
                .use { it.moveToFirst(); it.getInt(0) }
            val body = JSONObject(proposal.toString()).put("id", ID).put("version", version).put("evidence", originals)
                .put("reads", JSONArray(listOf("focus_sessions"))).put("trigger", "focus_elapsed").put("action", "notify_care")
                .put("approval", "explicit_local_version").put("scope", "local_device").put("proposed_at", now)
                .put("expires_at", now + 14 * CapabilityContract.DAY).put("skill_id", skill.id).put("skill_version", skill.version).put("skill_digest", skill.digest)
            require(BufferCrdt.canonical(body).toByteArray(Charsets.UTF_8).size <= RECORD_BYTES) { "能力建议超出可保存范围" }
            db.insertOrThrow("capability_proposals", null, ContentValues().apply {
                put("capability_id", ID); put("version", version); put("body", BufferCrdt.canonical(body)); put("digest", hash(body))
                put("state", "proposed"); put("expires_at", body.getLong("expires_at"))
            })
            emit("CapabilityProposed", JSONObject().put("capability_id", ID).put("version", version).put("digest", hash(body)).put("proposal", body))
        }
        db.execSQL("UPDATE capability_generation SET state='complete',lease=NULL WHERE id=?", arrayOf(job.id))
        emit("CapabilitySuggestionsCompleted", JSONObject().put("job_id", job.id).put("count", checked.size))
        return true
    }
    fun fail(db: SQLiteDatabase, job: CapabilityGeneration, now: Long) {
        if (!owns(db, job, now)) return
        db.execSQL("UPDATE capability_generation SET state=CASE WHEN attempts>=3 THEN 'failed' ELSE 'pending' END,lease=NULL,retry_at=? WHERE id=?", arrayOf<Any?>(now + 60_000, job.id))
    }
    fun cancelGeneration(db: SQLiteDatabase, emit: (String, JSONObject) -> String) {
        check(db.inTransaction())
        val changed=db.update("capability_generation",ContentValues().apply { put("state","cancelled");putNull("lease") },"state IN ('pending','running')",null)
        if(changed>0)emit("CapabilitySuggestionsCancelled", JSONObject().put("scope", "local_device"))
    }
    fun generationState(db: SQLiteDatabase): String = db.rawQuery("SELECT state FROM capability_generation ORDER BY rowid DESC LIMIT 1", null)
        .use { if (it.moveToFirst()) it.getString(0) else "none" }
    fun list(db: SQLiteDatabase): List<CapabilityProposal> = db.rawQuery("""SELECT p.capability_id,p.version,p.body,p.digest,p.state,p.expires_at,
        CASE WHEN g.active=1 AND g.version=p.version THEN g.valid_until ELSE 0 END FROM capability_proposals p
        LEFT JOIN capability_grants g ON g.capability_id=p.capability_id ORDER BY CASE WHEN g.active=1 AND g.version=p.version THEN 0 ELSE 1 END,p.version DESC LIMIT 30""", null)
        .use { rows -> buildList { while (rows.moveToNext()) {
            val body = BufferSourceEvent.parseObject(rows.getString(2), 64 * 1024)
            check(hash(body) == rows.getString(3) && body.getString("id") == rows.getString(0) && body.getInt("version") == rows.getInt(1))
            add(CapabilityProposal(rows.getString(0), rows.getInt(1), rows.getString(3), rows.getString(4), body, rows.getLong(5), rows.getLong(6)))
        } } }
    private fun proposal(db: SQLiteDatabase, id: String, version: Int, digest: String): CapabilityProposal =
        requireNotNull(list(db).firstOrNull { it.id == id && it.version == version && it.digest == digest }) { "能力建议已变化，请刷新" }
    fun decide(db: SQLiteDatabase, id: String, version: Int, digest: String, enable: Boolean, device: String, skill: CapabilitySkill?, now: Long, emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); val p = proposal(db, id, version, digest)
        if (!enable) {
            if(p.state in setOf("revoked","rejected","expired") && p.grantUntil<=0)return
            db.execSQL("UPDATE capability_grants SET active=0 WHERE capability_id=? AND version=?", arrayOf<Any?>(id, version))
            db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE capability_id=? AND version=? AND state!='cancelled'", arrayOf<Any?>(id, version))
            db.execSQL("DELETE FROM capability_clock WHERE capability_id=? AND revision IN (SELECT revision FROM capability_grants WHERE capability_id=? AND version=?)", arrayOf<Any?>(id,id,version))
            val revoked=p.grantUntil>0 || p.state=="approved"
            db.execSQL("UPDATE capability_proposals SET state=? WHERE capability_id=? AND version=?", arrayOf<Any?>(if (revoked) "revoked" else "rejected", id, version))
            emit(if (revoked) "CapabilityRevoked" else "CapabilityRejected", JSONObject().put("capability_id", id).put("version", version).put("digest", digest).put("scope", "local_device"))
            return
        }
        require(now in 1L..(BufferSourceEvent.MAX_COUNTER - 30 * CapabilityContract.DAY))
        if (p.state == "approved" && p.grantUntil > now) return
        require(p.state == "proposed" && now in p.body.getLong("proposed_at") until p.expiresAt) { "建议已过期或撤销，请重新生成" }
        require(p.body.getString("skill_digest") == requireNotNull(skill).digest) { "能力资源已更新，需要重新生成并确认" }
        val evidence = p.body.getJSONArray("evidence")
        for (i in 0 until evidence.length()) { val e = evidence.getJSONObject(i); check(BufferCrdt.canonical(fact(db,e.getString("event_id"))) == BufferCrdt.canonical(e)) }
        val revision = UUID.randomUUID().toString(); val until = now + 30 * CapabilityContract.DAY
        db.rawQuery("SELECT version,revision FROM capability_grants WHERE capability_id=? AND active=1",arrayOf(id)).use {
            if(it.moveToFirst())emit("CapabilityRevoked",JSONObject().put("capability_id",id).put("version",it.getInt(0))
                .put("revision",it.getString(1)).put("reason","new_version_enabled").put("scope","local_device"))
        }
        db.execSQL("UPDATE capability_proposals SET state='revoked' WHERE capability_id=? AND state='approved'", arrayOf(id))
        db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE capability_id=?", arrayOf(id))
        db.execSQL("DELETE FROM capability_clock WHERE capability_id=?", arrayOf(id))
        val event = emit("CapabilityEnabled", JSONObject().put("capability_id", id).put("version", version).put("digest", digest)
            .put("revision", revision).put("device", device).put("approved_at",now).put("valid_until", until).put("scope", "local_device"))
        db.insertWithOnConflict("capability_grants", null, ContentValues().apply {
            put("capability_id", id); put("version", version); put("revision", revision); put("device", device)
            put("approved_at", now); put("valid_until", until); put("approval_event", event); put("active", 1)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        db.execSQL("UPDATE capability_proposals SET state='approved' WHERE capability_id=? AND version=?", arrayOf<Any?>(id,version))
    }
    fun reconcile(db: SQLiteDatabase, device: String, skill: CapabilitySkill?, session: String?, now: Long, elapsed: Long, boot: String,
                  deliver: (CapabilityNotice) -> Boolean, withdraw: (String) -> Unit, emit: (String,JSONObject) -> String): Long? {
        check(db.inTransaction());require(now in 1..(BufferSourceEvent.MAX_COUNTER-30*CapabilityContract.DAY) && elapsed>=0 && boot.isNotBlank()); var next: Long? = null
        val grants = db.rawQuery("SELECT capability_id,version,revision,device,approved_at,valid_until,approval_event FROM capability_grants WHERE active=1", null)
            .use { rows -> buildList { while(rows.moveToNext()) add((0..6).map { rows.getString(it) }) } }
        for(g in grants) {
            val p = list(db).first { it.id==g[0] && it.version==g[1].toInt() }
            check(p.state=="approved") { "能力授权状态需要核对" }
            val approval = BufferEventJournal.localRecord(db,g[6]).event
            val a = approval.getJSONObject("payload")
            check(approval.getString("type")=="CapabilityEnabled" && a.getString("capability_id")==p.id && a.getInt("version")==p.version &&
                a.getString("digest")==p.digest && a.getString("revision")==g[2] && a.getString("device")==g[3] && a.getLong("approved_at")==g[4].toLong() && a.getLong("valid_until")==g[5].toLong() && a.getString("scope")=="local_device")
            if(g[3]!=device || now>=g[5].toLong() || skill==null || p.body.getString("skill_digest")!=skill.digest) {
                db.execSQL("UPDATE capability_grants SET active=0 WHERE capability_id=?",arrayOf(p.id))
                db.execSQL("UPDATE capability_proposals SET state='expired' WHERE capability_id=? AND version=?",arrayOf<Any?>(p.id,p.version))
                db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE capability_id=?",arrayOf(p.id))
                db.execSQL("DELETE FROM capability_clock WHERE capability_id=?",arrayOf(p.id))
                emit("CapabilityExpired",JSONObject().put("capability_id",p.id).put("version",p.version)); continue
            }
            next=minOf(next?:Long.MAX_VALUE,g[5].toLong())
            if(session==null || now<g[4].toLong()) {
                db.execSQL("DELETE FROM capability_clock WHERE capability_id=?",arrayOf(p.id))
                db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE capability_id=?",arrayOf(p.id));continue
            }
            db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE capability_id=? AND (session!=? OR boot!=?)",arrayOf(p.id,session,boot))
            val old = db.rawQuery("SELECT revision,session,boot,elapsed,total FROM capability_clock WHERE capability_id=?",arrayOf(p.id))
                .use { if(it.moveToFirst()) (0..4).map { i->it.getString(i) } else null }
            val total = if(old!=null && old[0]==g[2] && old[1]==session && old[2]==boot && old[3].toLong() in 0L..elapsed && old[4].toLong() in 0L..86_400_000L)
                (old[4].toLong()+(elapsed-old[3].toLong()).coerceAtMost(86_400_000L)).coerceAtMost(86_400_000L) else 0L
            db.insertWithOnConflict("capability_clock",null,ContentValues().apply {
                put("capability_id",p.id);put("revision",g[2]);put("session",session);put("boot",boot);put("elapsed",elapsed);put("total",total)
            },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
            val due = p.minutes*60_000L
            if(total<due) {next=minOf(next?:Long.MAX_VALUE,now+due-total);continue}
            val key=VelaProcessedCaptureProtocol.fingerprint("${p.id}:${p.version}:${g[2]}:$session")
            db.insertWithOnConflict("capability_tasks",null,ContentValues().apply {
                put("task_id",key);put("capability_id",p.id);put("version",p.version);put("revision",g[2]);put("session",session)
                put("boot",boot);put("queued_elapsed",elapsed);put("queued_at",now);put("expires_at",now+900_000);put("state","pending")
            },SQLiteDatabase.CONFLICT_IGNORE)
            val task=db.rawQuery("SELECT state,queued_elapsed,queued_at,expires_at,retry_at,capability_id,version,revision,session,boot FROM capability_tasks WHERE task_id=?",arrayOf(key))
                .use { check(it.moveToFirst());(0..9).map { i->it.getString(i) } }
            if(task[0]!="pending")continue
            check(task[5]==p.id && task[6].toInt()==p.version && task[7]==g[2] && task[8]==session && task[9]==boot) { "能力任务身份不匹配" }
            if(elapsed<task[1].toLong()||elapsed-task[1].toLong()>=900_000||now<task[2].toLong()||now>=task[3].toLong()) {
                db.execSQL("UPDATE capability_tasks SET state='cancelled' WHERE task_id=?",arrayOf(key));continue
            }
            if(now<task[4].toLong()){next=minOf(next?:Long.MAX_VALUE,task[4].toLong());continue}
            val ids=p.body.getJSONArray("message_ids");val message=(0 until ids.length()).joinToString("；") { CapabilityContract.messages.getValue(ids.getString(it)) }
            // The write transaction serializes this final grant check and the
            // bounded local notification with revoke, including other helpers.
            if(deliver(CapabilityNotice(key,p.id,p.version,g[2],session,message))) {
                db.execSQL("UPDATE capability_tasks SET state='delivered' WHERE task_id=?",arrayOf(key))
                emit("CapabilityNotificationPosted",JSONObject().put("capability_id",p.id).put("version",p.version).put("revision",g[2]).put("task_id",key))
            }else {db.execSQL("UPDATE capability_tasks SET retry_at=? WHERE task_id=?",arrayOf<Any?>(now+60_000,key));next=minOf(next?:Long.MAX_VALUE,now+60_000)}
        }
        val cancelled=db.rawQuery("SELECT task_id FROM capability_tasks WHERE state='cancelled' AND withdrawn=0 LIMIT 32",null)
            .use { rows->buildList {while(rows.moveToNext())add(rows.getString(0))} }
        cancelled.forEach { key->
            val removed=try {withdraw(key);true}catch (_:Exception){false}
            if(removed)db.execSQL("UPDATE capability_tasks SET withdrawn=1 WHERE task_id=?",arrayOf(key))
            else next=minOf(next?:Long.MAX_VALUE,now+60_000)
        }
        if(cancelled.size==32)next=minOf(next?:Long.MAX_VALUE,now+5000)
        return next
    }
}
