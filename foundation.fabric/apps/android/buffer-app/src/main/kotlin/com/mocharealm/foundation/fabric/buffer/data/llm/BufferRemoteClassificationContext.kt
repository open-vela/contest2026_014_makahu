package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshClassificationSource
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import org.json.JSONObject

/** A single immutable input plus data-only reads from its authenticated owner.
 * The transport callback supplies the current job/lease and validates the Hub
 * peer, membership, nonce and connection generation on every exchange. */
internal class BufferRemoteClassificationContext(
    contract: JSONObject,
    input: JSONObject,
    private val active: () -> Unit,
    private val exchange: (String, String) -> JSONObject,
) : BufferClassificationContext {
    val job = BufferMeshClassificationSource.validateInput(contract, input)
    val allowAssistantDecision = input.opt("allow_device_assistant") == true
    private val focus = if (input.isNull("focus_session")) null else BufferCrdt.canonical(input.getJSONObject("focus_session"))
    private val observed = linkedSetOf<String>()
    override fun checkActive() {
        check(!Thread.currentThread().isInterrupted) { "分类任务已取消" }
        active()
    }
    override val focusSession: JSONObject? get() { checkActive(); return focus?.let(::JSONObject) }
    private fun records(action: String, argument: String, detail: Boolean): List<BufferCard> {
        checkActive()
        val reply = exchange(action, argument)
        checkActive()
        require(reply.keys().asSequence().toSet() == setOf("records"))
        val values = reply.getJSONArray("records"); require(values.length() <= (if (detail) 1 else 12))
        return (0 until values.length()).map { BufferClassificationCodec.readRecord(values.getJSONObject(it), detail) }.also { cards ->
            require(cards.map { it.cardId }.distinct().size == cards.size && cards.all { it.captureId != job.captureId })
        }
    }
    override fun searchRecords(query: String): List<BufferCard> {
        require(query.isNotBlank() && query.length <= 120)
        return records("search_records", query, false).also { cards -> observed.addAll(cards.map { it.cardId }) }
    }
    override fun readRecord(id: String): BufferCard? {
        require(id in observed)
        return records("read_record", id, true).singleOrNull()?.also { require(it.cardId == id) }
    }
    override fun recordTool(name: String, success: Boolean, count: Int) {
        checkActive(); require(count in 0..12)
        // Actual source reads already have durable, source-owned disclosure
        // events. No duplicate RPC and no model reasoning/content is recorded.
        require(name != "route_to_device_assistant" || (allowAssistantDecision && success && count == 1))
    }
}
