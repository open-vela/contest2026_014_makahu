package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
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
class ClassificationHttpTest {
    private val valid = """{"segments":[{"text":"喝水 300 ml","kind":"care_card","title":"喝水","summary":"喝水 300 ml","health_category":"hydration","confidence":0.8}]}"""

    @Test fun currentOpenAiClientPreservesClassificationContract() {
        val request = withCompletionResponse(valid) { config ->
            val proposal = BufferLlmClient.classify(config, BufferClassificationJob("cap-http", "原始转写")).single()
            assertEquals("hydration", proposal.healthCategory)
            assertEquals("喝水", proposal.title)
            assertEquals(0.8, proposal.confidence, 0.0)
        }
        val content = request.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(content.contains("原始转写"))
        assertTrue(content.contains("allowed_health_categories"))
    }

    @Test fun invalidClassificationTypesAndRangesAreRejected() {
        listOf(
            valid.replace("\"text\":\"喝水 300 ml\"", "\"text\":123"),
            valid.replace("0.8", "\"0.8\""),
            valid.replace("0.8", "1.1"),
            valid.replace("care_card", "unsupported"),
        ).forEach { response ->
            withCompletionResponse(response) { config ->
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                    BufferLlmClient.classify(config, BufferClassificationJob("cap-http", "原始转写"))
                }
            }
        }
    }

    private fun withCompletionResponse(
        content: String,
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
            exchange.sendResponseHeaders(200, response.size.toLong())
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
