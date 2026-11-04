package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class WeeklyLlmJob(val id: String, val lease: String, val requestedAt: Long, val facts: JSONObject)

internal class BufferWeeklyLlmWorker(private val repository: BufferRepository) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    fun start() { executor.scheduleWithFixedDelay(::process, 0, 5, TimeUnit.SECONDS) }
    fun stop() { executor.shutdownNow() }
    private fun process() {
        try {
            val config = repository.llmConfig()
            if (!config.ready) return
            val job = repository.claimWeeklyLlm(System.currentTimeMillis()) ?: return
            try {
                val result = BufferWeeklyCodec.generate(config, job.facts)
                val current = repository.llmConfig()
                check(current.baseUrl == config.baseUrl && current.model == config.model && current.apiKey == config.apiKey && !Thread.currentThread().isInterrupted)
                repository.completeWeeklyLlm(job, result, config.model)
            } catch (_: Exception) { repository.failWeeklyLlm(job, System.currentTimeMillis()) }
        } catch (_: Exception) { /* Preserve durable work and retry after storage/configuration recovers. */ }
    }
}
