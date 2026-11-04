package com.mocharealm.foundation.fabric.buffer.data.llm

import java.util.concurrent.atomic.AtomicReference

/** One process-local credential; encrypted settings remain the durable source. */
internal class BufferLlmSecretCache(private val load: (String) -> String) {
    private data class Entry(val encrypted: String, val plain: String)
    private val cached = AtomicReference(Entry("", ""))
    fun remember(encrypted: String, plain: String) { cached.set(Entry(encrypted, plain)) }
    fun decrypt(encrypted: String): String {
        val before = cached.get()
        if (encrypted.isEmpty()) { remember("", ""); return "" }
        if (before.encrypted == encrypted) return before.plain
        val plain = load(encrypted)
        // A concurrent key replacement/clear must not be undone by this read.
        cached.compareAndSet(before, Entry(encrypted, plain))
        return plain
    }
}
