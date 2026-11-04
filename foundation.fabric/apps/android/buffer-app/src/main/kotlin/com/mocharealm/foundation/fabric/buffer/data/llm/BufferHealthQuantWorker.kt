package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuant
import com.mocharealm.foundation.fabric.buffer.domain.HealthQuantJob
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object BufferHealthQuantExtractor {
    fun extract(config:BufferLlmConfig, job:HealthQuantJob):HealthQuant {
        val facts=JSONObject(job.input).put("zone",job.zone)
            .put("captured_local",java.time.Instant.ofEpochMilli(job.createdAt).atZone(java.time.ZoneId.of(job.zone)).toString())
        val value=BufferLlmClient.complete(config,"""
            从原始转写中仅提取 target_title/target_summary 对应的已发生生活记录；计划、问题和建议不算发生事实。
            返回 {"date":"YYYY-MM-DD","zone":"输入的zone","entries":[{"name":"食物或活动","amount":null,"unit":"","basis":"estimated","note":"估算假设或缺失信息","values":{}}]}。
            日期按录音时刻和输入中的今天/昨天等表达解析，不使用当前服务器日期。混合输入只记录目标片段，勿重复其他卡片。
            每种食物独立一项，最多16项。amount为明确份量或null，unit使用g/ml/份/个/碗/杯/分钟/小时/kg。
            values只允许energy_kcal热量、protein_g蛋白质、carbs_g碳水、fat_g脂肪、water_ml饮水、sleep_min睡眠分钟、exercise_min运动分钟、weight_kg体重。
            缺失或无法可靠估算的数值直接省略，不填0。份量未知时不估算该食物营养总量，note说明需补充份量。
            营养推算的basis必须estimated，note说明份量和烹饪假设。仅原文明确报告的测量值可用reported；不编造测量值。
            不做医学判断，不提供诊断，不执行健康平台写入。所有输出都等待用户检查确认。
            """.trimIndent(),facts)
        return HealthQuant.parse(value.toString())
    }
}

internal class BufferHealthQuantWorker(private val repository:BufferRepository) {
    private val executor=Executors.newSingleThreadScheduledExecutor()
    fun start(){executor.scheduleWithFixedDelay(::process,0,5,TimeUnit.SECONDS)}
    fun stop(){executor.shutdownNow()}
    private fun process(){
        try {
            val config=repository.llmConfig();if(!config.ready)return
            val job=repository.claimHealthQuant() ?: return
            try {
                val result=BufferHealthQuantExtractor.extract(config,job)
                val current=repository.llmConfig()
                check(current.baseUrl==config.baseUrl && current.model==config.model && current.apiKey==config.apiKey && !Thread.currentThread().isInterrupted)
                repository.completeHealthQuant(job,result)
            } catch (_:Exception){repository.failHealthQuant(job)}
        } catch (_:Exception){ /* A storage/configuration failure must not stop durable retries. */ }
    }
}
