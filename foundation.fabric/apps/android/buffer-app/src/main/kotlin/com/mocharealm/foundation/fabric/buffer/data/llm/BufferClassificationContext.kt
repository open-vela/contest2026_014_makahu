package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject

/** Data access needed by one classification. Implementations must authorize
 * every read; recording a tool observation grants no execution authority.
 * Device assistant routing remains an explicit, separate owner callback. */
internal interface BufferClassificationContext {
    fun checkActive()
    val focusSession: JSONObject?
    fun searchRecords(query: String): List<BufferCard>
    fun readRecord(id: String): BufferCard?
    fun recordTool(name: String, success: Boolean, count: Int)
}

/** Local reads stay serialized with task ownership changes. A mesh owner can
 * additionally supply a local lease/membership predicate; it must not perform
 * network I/O while the repository monitor is held. */
internal class BufferLocalClassificationContext(
    private val repository: BufferRepository,
    private val job: BufferClassificationJob,
    private val request: String,
    private val authorize: () -> Boolean = { true },
) : BufferClassificationContext {
    private val capture: String get() = job.captureId
    private val observed = linkedSetOf<String>()
    override fun checkActive() = synchronized(repository) {
        check(!Thread.currentThread().isInterrupted) { "分类任务已取消" }
        check(authorize() && repository.classificationRequestCurrent(capture, request, job.transcript)) { "分类任务已失效" }
    }
    private fun <T> read(block: () -> T): T = synchronized(repository) {
        checkActive(); val value = block(); checkActive(); value
    }
    override val focusSession: JSONObject? get() = read { repository.focusSession()?.json() }
    override fun searchRecords(query: String): List<BufferCard> = read {
        require(query.isNotBlank() && query.length <= 120)
        repository.searchLocalRecords(query, excludeCapture = capture).also { cards -> observed.addAll(cards.map { it.cardId }) }
    }
    override fun readRecord(id: String): BufferCard? = read {
        require(id in observed)
        repository.findCard(id)?.takeIf { it.state !in setOf("pending_transcription", "deleted") && it.captureId != capture }
    }
    override fun recordTool(name: String, success: Boolean, count: Int) = read {
        require(count in 0..12)
        val tool = if (name in setOf("search_records", "read_record", "route_to_device_assistant")) name else "unknown_tool"
        repository.recordClassificationTool(capture, request, tool, success, count)
    }
}

/** Observed IDs describe this execution, not transferable authorization.
 * A source must validate associations against its own served-record evidence. */
internal data class BufferClassificationOutcome(
    val proposals: List<BufferClassificationProposal>,
    val observedRecordIds: Set<String>,
    val handedOff: Boolean = false,
)
