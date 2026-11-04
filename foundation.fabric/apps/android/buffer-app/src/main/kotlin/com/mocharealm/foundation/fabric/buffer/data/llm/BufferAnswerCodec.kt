package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** The only answer outputs are text or an explicit unresolved result. */
internal object BufferAnswerCodec {
    fun input(card: String, capture: String, question: String): JSONObject {
        require(card.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) && capture.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(question.isNotBlank() && question.length <= 32_000 && '\u0000' !in question)
        return BufferSourceEvent.parseObject(BufferCrdt.canonical(JSONObject().put("version", 1)
            .put("card_id", card).put("capture_id", capture).put("question", question)), 128 * 1024)
    }
    fun validateInput(contract: JSONObject, value: JSONObject): String {
        BufferMeshTaskContract.validate(contract); require(contract.get("stage") == "answer")
        require(value.keys().asSequence().toSet() == setOf("version", "card_id", "capture_id", "question") && value.get("version") == 1)
        listOf("card_id", "capture_id", "question").forEach { require(value.get(it) is String) }
        val normalized = input(value.getString("card_id"), value.getString("capture_id"), value.getString("question"))
        require(value.get("capture_id") == contract.get("capture_id") && BufferCrdt.canonical(value) == BufferCrdt.canonical(normalized))
        require(BufferMeshTaskContract.digest(normalized) == contract.getString("input_sha256") &&
            BufferCrdt.canonical(normalized).toByteArray(Charsets.UTF_8).size.toLong() == contract.getLong("input_bytes"))
        return value.getString("question")
    }
    fun encode(result: QuickAnswerResult): JSONObject = when (result) {
        is QuickAnswerResult.Answer -> JSONObject().put("answer", result.text)
        is QuickAnswerResult.Unresolved -> JSONObject().put("unanswered", true).put("reason", result.detail)
    }.also { decode(it) }
    fun decode(value: JSONObject): QuickAnswerResult {
        val copy = BufferSourceEvent.parseObject(BufferCrdt.canonical(value), 24 * 1024)
        if (copy.keys().asSequence().toSet() == setOf("answer")) {
            val text = copy.get("answer")
            require(text is String && text.isNotBlank() && text == text.trim() && text.length <= 4000 && '\u0000' !in text)
            return QuickAnswerResult.Answer(text)
        }
        require(copy.keys().asSequence().toSet() == setOf("unanswered", "reason") && copy.get("unanswered") == true)
        val detail = copy.get("reason")
        require(detail is String && detail.isNotBlank() && detail.length <= 500 && '\u0000' !in detail)
        return QuickAnswerResult.Unresolved(detail)
    }
}
