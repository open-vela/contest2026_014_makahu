package com.mocharealm.foundation.fabric.buffer.data.device

import org.json.JSONObject
import java.util.Base64

/** One bounded, acknowledged immutable card snapshot at a time. The complete
 * replica is independent of the small Vela screen projection. */
internal class VelaCardReplicaSender(
    private val page: (String) -> List<String>,
    private val documentMode: Boolean = false,
    private val authorizeSnapshot: (ByteArray) -> Boolean = { true },
    resumeAfter: String = "",
    private val saveProgress: ((String) -> Unit)? = null,
    private val syncFinished: ((String, Boolean) -> Unit)? = null,
    private val priorityPage: ((String) -> List<String>)? = null,
    priorityResumeAfter: String = "",
    private val savePriorityProgress: ((String) -> Unit)? = null,
    private val send: (ByteArray) -> Boolean,
) : AutoCloseable {
    // Resume is a scheduling cursor, never proof that a new scan is complete.
    // Visit the tail and then the prefix before reporting a complete sync.
    private var priorityResumeCursor = priorityResumeAfter
    private var priorityAfter = priorityResumeAfter
    private var priorityStart = priorityResumeAfter
    private var priorityWrapped = false
    private var priorityActive = priorityPage != null
    private var priorityRecord = false
    private var resumeCursor = resumeAfter
    private var scanStart = resumeAfter
    private var wrapped = false
    private var after = resumeAfter
    private val waiting = ArrayDeque<String>()
    private var record: ByteArray? = null
    private var hash = ""
    private var card = ""
    private var offset = 0
    private var end = 0
    private var next = 0L
    private var attempts = 0
    private var peerBusy = false
    private val delivered = mutableMapOf<String, String>()
    var error: String? = null
        private set

    private var syncRequest: String? = null
    private var restartScan = false
    private var syncComplete = false
    private var completionPending = false
    private var syncConflict = false

    /** Retries of the same request must not rewind a scan or discard a fragment. */
    fun requestSync(bytes: ByteArray, now: Long) {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.keys().asSequence().toSet() == setOf("type", "protocol", "request_id") + if (value.has("calendar_range")) setOf("calendar_range") else emptySet())
        require(value.getString("type") == "buffer.sync.request" && value.get("protocol") == 1)
        val id = value.getString("request_id")
        require(id.matches(Regex("[0-9a-f]{32}")))
        if (id == syncRequest) {
            if (syncComplete) completionPending = true
        } else {
            syncRequest = id
            restartScan = true
            syncComplete = false
            syncConflict = false
            completionPending = false
        }
        if (record == null) next = now
    }

    fun tick(now: Long) {
        if (completionPending) {
            val reply = JSONObject().put("type", "buffer.sync.reply").put("protocol", 1)
                .put("request_id", syncRequest).put("state", if (syncConflict) "conflict" else "complete")
                .toString().toByteArray(Charsets.UTF_8)
            if (send(reply)) {
                completionPending = false
                syncRequest?.let { syncFinished?.invoke(it, !syncConflict) }
            }
            return
        }
        if (now < next) return
        if (record == null && restartScan) {
            waiting.clear(); delivered.clear(); after = resumeCursor
            scanStart = resumeCursor; wrapped = false; restartScan = false
            priorityActive = priorityPage != null; priorityAfter = priorityResumeCursor
            priorityStart = priorityResumeCursor; priorityWrapped = false
        }
        if (record == null) {
            if (priorityActive) {
                if (waiting.isEmpty()) waiting.addAll(requireNotNull(priorityPage)(priorityAfter).filter {
                    !priorityWrapped || JSONObject(it).getString("card_id") <= priorityStart
                })
                if (waiting.isEmpty()) {
                    if (priorityStart.isNotEmpty() && !priorityWrapped) {
                        priorityAfter = ""; priorityWrapped = true; next = now; return
                    }
                    priorityActive = false; next = now; return
                }
            }
            if (!priorityActive && waiting.isEmpty()) waiting.addAll(page(after).filter {
                !wrapped || JSONObject(it).getString("card_id") <= scanStart
            })
            if (waiting.isEmpty()) {
                if (scanStart.isNotEmpty() && !wrapped) {
                    after = ""; wrapped = true; next = now; return
                }
                if (syncRequest != null && !syncComplete) {
                    syncComplete = true; completionPending = true
                }
                after = ""; scanStart = ""; wrapped = false
                next = now + 15_000; return
            }
            val raw = waiting.removeFirst()
            val id = JSONObject(raw).getString("card_id")
            priorityRecord = priorityActive
            if (priorityRecord) priorityAfter = id else after = id
            val digest = VelaProcessedCaptureProtocol.fingerprint(raw)
            if (delivered[id] == digest) { next = now; return }
            record = raw.toByteArray(Charsets.UTF_8).also { require(it.size in 1..65_280) }
            hash = digest; card = id; offset = 0; attempts = 0; peerBusy = false
        }
        if (attempts >= 8) {
            error = "卡片副本回执超时，稍后自动重试"
            record?.fill(0); record = null; waiting.clear()
            if (priorityRecord) { priorityAfter = ""; priorityStart = ""; priorityWrapped = false } else after = ""
            next = now + 30_000; return
        }
        val bytes = requireNotNull(record)
        if (!authorizeSnapshot(bytes)) {
            // Skip this document without rewinding the ordered scan. A local
            // or revoked document must not starve later authorized calendars.
            // Every queued fragment is checked again; a later scan can pick up
            // a new snapshot if the sharing policy changes.
            bytes.fill(0); record = null
            hash = ""; card = ""; offset = 0; end = 0; attempts = 0
            next = now
            return
        }
        end = minOf(offset + 3072, bytes.size)
        val message = JSONObject().put("type", if (documentMode) "buffer.crdt.part" else "buffer.card.replica.part").put("protocol", 1)
            .put("record_sha256", hash).put("offset", offset).put("total", bytes.size).put("retry_busy", true)
            .put("record_base64", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
            .toString().toByteArray(Charsets.UTF_8)
        // Fabric owns transport retries. Avoid queueing an application copy
        // while the original multi-fragment record/receipt is still draining.
        if (send(message)) { attempts++; next = now + 30_000 }
        else next = now + 100
    }

    fun acknowledge(bytes: ByteArray, now: Long) {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getString("type") == (if (documentMode) "buffer.crdt.reply" else "buffer.card.replica.reply") && value.getInt("protocol") == 1)
        if (record == null || value.getString("record_sha256") != hash) return
        val position = value.get("next_offset")
        require(position is Number && position.toDouble() == end.toDouble())
        val state = value.getString("state")
        if (state == "busy") {
            require(end == requireNotNull(record).size)
            peerBusy = true; attempts = 0; next = now + 1000
            return
        }
        peerBusy = false
        if (end < requireNotNull(record).size) {
            require(state == "partial")
            offset = end; attempts = 0; next = now
        } else {
            require(state in setOf("stored", "duplicate", "stale"))
            if (state == "stale" && syncRequest != null && !syncComplete && !restartScan) syncConflict = true
            if (state == "stale") error = "设备保留了较新的编辑或删除状态"
            else error = null
            if (priorityRecord) {
                savePriorityProgress?.let { save -> save(card); priorityResumeCursor = card }
            } else saveProgress?.let { save -> save(card); resumeCursor = card }
            delivered[card] = hash
            record?.fill(0); record = null; attempts = 0; next = now
        }
    }

    val prioritizingCurrentCalendar: Boolean get() = priorityActive

    fun diagnosticState(): String = "pending=${record!=null},busy=$peerBusy,priority=$priorityActive,offset=$offset,end=$end,total=${record?.size?:0},attempts=$attempts,waiting=${waiting.size},confirmed=${delivered.size},error=${error!=null}"

    override fun close() {
        record?.fill(0); record = null; waiting.clear(); delivered.clear()
        after = ""; hash = ""; card = ""; offset = 0; end = 0; next = 0; attempts = 0
        syncRequest = null; restartScan = false; syncComplete = false; completionPending = false; syncConflict = false
        priorityActive = priorityPage != null; priorityAfter = priorityResumeCursor
        priorityStart = priorityResumeCursor; priorityWrapped = false; priorityRecord = false; peerBusy = false
    }
}
