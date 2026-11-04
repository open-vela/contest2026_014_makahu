package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only explicitly requested analysis jobs reach the model. This worker has
 * no executor for proposal actions and cannot enable a capability. */
internal class BufferCapabilityWorker(private val repository: BufferRepository) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    fun start() { executor.scheduleWithFixedDelay(::process, 0, 5, TimeUnit.SECONDS) }
    fun stop() { executor.shutdownNow() }
    fun requestProcessing() { if (!executor.isShutdown) runCatching { executor.execute(::process) } }
    private fun process() {
        try {
            val config = repository.llmConfig()
            if (!config.ready) return
            val job = repository.claimCapabilitySuggestions(System.currentTimeMillis()) ?: return
            try {
                val skill = repository.capabilitySkill()
                check(skill.digest == job.skillDigest) { "能力资源已变化" }
                val result = BufferLlmClient.complete(config, skill.prompt, job.facts)
                repository.completeCapabilitySuggestions(job, result)
            } catch (_: Exception) { repository.failCapabilitySuggestions(job) }
        } catch (_: Exception) { /* Durable job and lease survive storage/configuration failures. */ }
    }
}
