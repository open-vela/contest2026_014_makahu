package com.mocharealm.foundation.fabric.buffer.data.local

/** Ephemeral handle, never reconstructed from events or persisted grants. */
internal data class BufferMeshChannel(val id: String, val binding: String, val current: () -> Boolean)
