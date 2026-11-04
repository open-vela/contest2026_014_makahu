package com.mocharealm.foundation.fabric.buffer.domain

/** Read-only source history. Linked means its causal metadata was checked;
 * it does not mean a business projector or an external action has run. */
internal data class BufferSourceHistoryEntry(
    val eventId: String,
    val type: String,
    val createdAt: Long,
    val local: Boolean,
    val state: String,
    val audio: BufferSourceAudioState? = null,
    val projection: String? = null,
    val source: String = "",
    val captureId: String? = null,
)

internal data class BufferSourceOriginalPage(val eventId: String, val source: String, val sequence: Long,
    val text: String, val offset: Int, val total: Int, val previous: Int?, val next: Int?)

internal data class BufferSourceAudioState(val stage: String, val problem: String)

internal data class BufferSourceHistoryCursor(val clock: Long, val source: String, val sequence: Long)

internal data class BufferSourceHistoryPage(val entries: List<BufferSourceHistoryEntry>, val older: BufferSourceHistoryCursor?, val hasHeld: Boolean = false)
