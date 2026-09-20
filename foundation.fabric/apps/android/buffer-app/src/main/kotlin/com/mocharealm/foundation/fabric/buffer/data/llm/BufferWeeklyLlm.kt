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
                val result = BufferLlmClient.complete(config,
                    "根据最近七天的真实记录生成中文周总结，输出 {\"title\":\"标题\",\"summary\":\"总结正文\"}。" +
                        "正文最多4000字符，按自然段组织：本周回顾、值得关注的联系、生活与照顾、下周少量建议。" +
                        "总结必须来自输入；不编造经历、健康结论或完成事项。区分分类提案与用户已确认事实。" +
                        "不要把统计数字拼成模板，不强行制造建议；样本不足或记录截断时明确说明。", job.facts)
                repository.completeWeeklyLlm(job, result, config.model)
            } catch (_: Exception) { repository.failWeeklyLlm(job, System.currentTimeMillis()) }
        } catch (_: Exception) { /* Preserve durable work and retry after storage/configuration recovers. */ }
    }
}
