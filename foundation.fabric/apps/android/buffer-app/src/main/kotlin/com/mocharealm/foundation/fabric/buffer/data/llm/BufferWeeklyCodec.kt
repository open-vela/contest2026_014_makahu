package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** Shared local/remote generation; output is a summary, never an action. */
internal object BufferWeeklyCodec {
    const val MAX_FACT_BYTES = 224 * 1024
    const val MAX_INPUT_BYTES = 240 * 1024
    private const val instruction = "根据最近七天的真实记录生成中文周总结，输出 {\"title\":\"标题\",\"summary\":\"总结正文\"}。" +
        "正文最多4000字符，按自然段组织：本周回顾、值得关注的联系、生活与照顾、下周少量建议。" +
        "总结必须来自输入；不编造经历、健康结论或完成事项。区分分类提案与用户已确认事实。" +
        "不要把统计数字拼成模板，不强行制造建议；样本不足或记录截断时明确说明。" +
        "health_quantified只含用户确认的数值；reported和estimated必须区分，缺少数值不能当作0。weight_kg是最近测量而不是合计，不给医学判断。"
    fun input(subject: String, requested: Long, facts: JSONObject): JSONObject {
        require(subject.matches(Regex("weekly--?[0-9]{1,16}")) && requested in 0L..BufferSourceEvent.MAX_COUNTER)
        require(facts.get("task") == "weekly_review" && BufferMeshTaskContract.integer(facts, "period_end", 0L..BufferSourceEvent.MAX_COUNTER) == requested)
        require(BufferMeshTaskContract.integer(facts, "period_start", -604_800_000L..BufferSourceEvent.MAX_COUNTER) == requested - 7L * 86_400_000)
        val copy = BufferSourceEvent.parseObject(BufferCrdt.canonical(facts), MAX_FACT_BYTES)
        return BufferSourceEvent.parseObject(BufferCrdt.canonical(JSONObject().put("version", 1).put("subject_id", subject)
            .put("requested_at", requested).put("facts", copy)), MAX_INPUT_BYTES)
    }
    fun validateInput(contract: JSONObject, value: JSONObject): JSONObject {
        BufferMeshTaskContract.validate(contract); require(contract.get("stage") == "generate")
        require(value.keys().asSequence().toSet() == setOf("version", "subject_id", "requested_at", "facts") && value.get("version") == 1)
        require(value.get("subject_id") is String && value.get("subject_id") == contract.get("subject_id"))
        val requested = BufferMeshTaskContract.integer(value, "requested_at", 0L..BufferSourceEvent.MAX_COUNTER)
        val normalized = input(value.getString("subject_id"), requested, value.getJSONObject("facts"))
        require(BufferCrdt.canonical(normalized) == BufferCrdt.canonical(value) && BufferMeshTaskContract.digest(normalized) == contract.getString("input_sha256") &&
            BufferCrdt.canonical(normalized).toByteArray(Charsets.UTF_8).size.toLong() == contract.getLong("input_bytes"))
        return normalized.getJSONObject("facts")
    }
    fun result(value: JSONObject): JSONObject {
        val copy = BufferSourceEvent.parseObject(BufferCrdt.canonical(value), 24 * 1024)
        require(copy.keys().asSequence().toSet() == setOf("title", "summary"))
        for ((key, maximum) in listOf("title" to 160, "summary" to 4000)) {
            val text = copy.get(key)
            require(text is String && text.isNotBlank() && text.length <= maximum && '\u0000' !in text)
            copy.put(key, text.trim())
        }
        return copy
    }
    fun generate(config: BufferLlmConfig, facts: JSONObject): JSONObject =
        result(BufferLlmClient.complete(config, instruction, facts, maxRequestBytes = 512 * 1024))
}
