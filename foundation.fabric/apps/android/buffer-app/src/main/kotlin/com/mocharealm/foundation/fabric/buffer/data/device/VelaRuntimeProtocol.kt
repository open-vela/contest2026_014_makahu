package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import org.json.JSONObject

/** Rhythm state uses the authenticated application session, independently of card replicas. */
internal object VelaModeProtocol {
    fun apply(bytes: ByteArray, repository: BufferRepository, authorize: () -> Boolean = {true}): ByteArray {
        require(bytes.size in 1..4096)
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "mode", "mode_id") +
            (if (value.has("focus_session")) setOf("focus_session") else emptySet()) +
            (if(value.has("coordinator_term")) setOf("coordinator_term") else emptySet()))
        require(value.get("type") == "buffer.mode.change" && value.get("protocol") == 1)
        val mode = value.get("mode")
        val id = value.get("mode_id")
        require(mode is String && mode in setOf("normal", "focus", "bedtime", "rabbit_hole"))
        require(id is String && id.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        val term=value.opt("coordinator_term")
        if(value.has("coordinator_term"))require(term is Number&&term.toDouble().isFinite()&&
            term.toDouble()==term.toLong().toDouble()&&term.toLong() in 0L..9_007_199_254_740_991L)
        // The existing durable receipt makes a retried command idempotent,
        // including after restart or after a subsequent phone-side selection.
        repository.applyVelaFocusMode(mode, id, value.optJSONObject("focus_session")?.let(FocusSession::parse),authorize)
        return JSONObject().put("type", "buffer.mode.reply").put("protocol", 1)
            .put("mode", mode).put("mode_id", id).put("state", "applied")
            .also { if(term is Number)it.put("coordinator_term",term.toLong()) }
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun snapshot(repository: BufferRepository): JSONObject = synchronized(repository) {
        val now = System.currentTimeMillis()
        // Reconciliation can end a research session and return to normal mode.
        val research = repository.rabbitSnapshot(now)
        JSONObject().put("mode", repository.mode()).put("research_session", research)
            .put("focus_reminder", repository.focusReminderSnapshot(now))
            .put("focus_session", repository.focusSession()?.json() ?: JSONObject.NULL)
    }
}

internal class VelaRuntimeSender(
    private val snapshot: () -> JSONObject,
    private val send: (ByteArray) -> Boolean,
) : AutoCloseable {
    private var authorityTerm: (() -> Long?)? = null
    private var domainTerm: Long? = null
    fun requireAuthority(provider: () -> Long?) {
        check(revision==0L&&pending==null) { "authority must be configured before sending" }
        authorityTerm=provider
    }
    private var revision = 0L
    private var pending: ByteArray? = null
    private var deadline = 0L
    private var next = 0L

    fun tick(now: Long) {
        val term=authorityTerm?.invoke()
        if(authorityTerm!=null&&(term==null||term<=0L)) { pending?.fill(0);pending=null;domainTerm=null;next=now;return }
        if(term!=domainTerm){pending?.fill(0);pending=null;domainTerm=term;next=now}
        if (pending == null && now >= next) {
            check(revision < 9_007_199_254_740_991L)
            val value = snapshot().put("type", "buffer.runtime.state").put("protocol", 1)
                .put("revision", ++revision)
                .also { if(term!=null)it.put("coordinator_term",term) }
            pending = value.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 4096) }
            deadline = now
        }
        val bytes = pending ?: return
        if (now >= deadline) deadline = if (send(bytes)) now + 5000 else now + 100
    }

    fun acknowledge(bytes: ByteArray, now: Long) {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "revision", "state") +
            (if(value.has("coordinator_term"))setOf("coordinator_term") else emptySet()))
        require(value.get("type") == "buffer.runtime.reply" && value.get("protocol") == 1)
        val received = value.get("revision")
        require(received is Number && received.toDouble().isFinite() &&
            received.toDouble() == received.toLong().toDouble() && received.toLong() in 1..9_007_199_254_740_991L)
        require(value.get("state") in setOf("applied", "deferred"))
        if(authorityTerm!=null){
            val term=value.opt("coordinator_term")
            if(term !is Number||!term.toDouble().isFinite()||term.toDouble()!=term.toLong().toDouble()||
                term.toLong()!=domainTerm||authorityTerm?.invoke()!=domainTerm)return
        }
        if (pending == null || received.toLong() != revision) return
        pending?.fill(0); pending = null
        next = now + if (value.get("state") == "deferred") 500 else 2000
    }

    override fun close() {
        pending?.fill(0); pending = null; revision = 0; deadline = 0; next = 0;domainTerm=null
    }
}
