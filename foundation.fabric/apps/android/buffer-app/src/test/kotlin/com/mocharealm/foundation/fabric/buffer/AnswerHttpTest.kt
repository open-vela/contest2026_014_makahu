package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AnswerHttpTest {
    private val card = BufferCard(
        "q-1", "cap-1", "question_card", "问题", "为什么？", "unanswered_gray", emptyList(), 1000,
    )

    @Test fun openAiCompatibleAnswerRequestUsesCurrentClient() {
        val request = withCompletionResponse("{\"answer\":\"  简短答案  \"}") { config ->
            assertEquals(QuickAnswerResult.Answer("简短答案"), BufferLlmClient.answer(config, card))
        }
        assertTrue(request.getJSONArray("messages").getJSONObject(1).getString("content").contains("为什么？"))
    }

    @Test fun unresolvedAndMalformedAnswerPayloadsStaySafe() {
        withCompletionResponse("{\"unanswered\":true,\"reason\":\"证据不足\"}") { config ->
            assertEquals(QuickAnswerResult.Unresolved("证据不足"), BufferLlmClient.answer(config, card))
        }
        withCompletionResponse("{\"answer\":null}") { config ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                BufferLlmClient.answer(config, card)
            }
        }
        withCompletionResponse("{\"answer\":123}") { config ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                BufferLlmClient.answer(config, card)
            }
        }
    }

    @Test fun answerLengthAndBooleanValidationRemainOnCurrentInterface() {
        withCompletionResponse("{\"answer\":\"${"a".repeat(4001)}\"}") { config ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                BufferLlmClient.answer(config, card)
            }
        }
        withCompletionResponse("{\"unanswered\":\"true\",\"answer\":\"文本\"}") { config ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                BufferLlmClient.answer(config, card)
            }
        }
    }

    private fun withCompletionResponse(
        content: String,
        status: Int = 200,
        test: (BufferLlmConfig) -> Unit,
    ): JSONObject {
        var request = JSONObject()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = JSONObject()
                .put("choices", JSONArray().put(JSONObject()
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", content))))
                .toString().toByteArray()
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            test(BufferLlmConfig("http://127.0.0.1:${server.address.port}/v1", "test-model", "test-key"))
        } finally {
            server.stop(0)
        }
        return request
    }
}
