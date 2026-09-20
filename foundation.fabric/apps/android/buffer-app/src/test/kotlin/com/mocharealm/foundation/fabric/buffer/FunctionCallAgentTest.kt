package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
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
class FunctionCallAgentTest {
    @Test fun processingDistinguishesFallbackFailureAndIgnoresExpiredTools() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repository = BufferRepository(context)
        try {
            val card = repository.createTextCapture("明天整理项目")
            val captureId = requireNotNull(card.captureId)
            assertEquals("pending", repository.classificationProcessing(captureId).state)
            assertTrue(repository.classificationProcessing(captureId).steps.isEmpty())
            val lease = requireNotNull(repository.claimClassificationRequest(captureId))
            repository.recordClassificationTool(captureId, "expired", "read_record", true, 0)
            assertEquals(1, repository.classificationProcessing(captureId).steps.size)
            repository.recordClassificationTool(captureId, lease, "read_record", false, 0)
            repository.markClassificationRequestFailed(captureId, lease, "test error")
            val failed = repository.classificationProcessing(captureId)
            assertEquals("pending", failed.state)
            assertEquals(1, failed.attempts)
            assertTrue(!failed.steps[1].success)
            assertEquals("ClassificationFailed", failed.steps.last().type)
            repository.recordClassificationTool(captureId, lease, "search_records", true, 4)
            assertEquals(failed, repository.classificationProcessing(captureId))
        } finally {
            repository.close()
            context.deleteDatabase("buffer.db")
        }
    }

    @Test fun multiRoundToolReceiptPreservesReasoningAndSearchResult() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repository = BufferRepository(context)
        val old = repository.createTextCapture("旧项目：咖啡研究")
        val fresh = repository.createTextCapture("继续咖啡项目")
        val job = repository.pendingClassificationJobs().single { it.captureId == fresh.captureId }
        val lease = requireNotNull(repository.claimClassificationRequest(job.captureId))
        val requests = mutableListOf<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            synchronized(requests) { requests += request }
            val response = if (requests.size == 1) {
                JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("finish_reason", "tool_calls")
                    .put("message", JSONObject()
                        .put("role", "assistant")
                        .put("reasoning_content", "mimo internal reasoning")
                        .put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "call-search")
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", "search_records")
                                .put("arguments", "{\"query\":\"咖啡\"}")))))))
            } else {
                val content = JSONObject().put("segments", JSONArray().put(JSONObject()
                    .put("text", "继续咖啡项目")
                    .put("kind", "spark_card")
                    .put("title", "咖啡项目进展")
                    .put("summary", "继续旧项目研究")
                    .put("health_category", JSONObject.NULL)
                    .put("confidence", 0.9)
                    .put("related_card_ids", JSONArray().put(old.cardId))
                    .put("presentation", JSONObject().put("blocks", JSONArray().put(JSONObject()
                        .put("type", "paragraph").put("title", "整理要点").put("text", "继续旧项目研究"))))))
                JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", content.toString()))))
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = BufferCaptureAgent.classify(
                BufferLlmConfig("http://127.0.0.1:${server.address.port}/v1", "test-model", "test-key"),
                job,
                repository,
                lease,
            )
            assertEquals(listOf(old.cardId), result.single().relatedCardIds)
            repository.applyRemoteClassification(job.captureId, result, lease)
            assertEquals(listOf(old.cardId), repository.findCard(fresh.cardId)!!.relatedCardIds)
            assertTrue(repository.cardPresentation(fresh.cardId)!!.contains("整理要点"))
            assertTrue(repository.pendingClassificationJobs().none { it.captureId == fresh.captureId })
            val processing = repository.classificationProcessing(job.captureId)
            assertEquals("complete", processing.state)
            assertEquals("继续咖啡项目", processing.original)
            assertEquals(listOf("ClassificationRequested", "ClassificationToolExecuted", "ClassificationProposed"), processing.steps.map { it.type })
            assertEquals("search_records", processing.steps[1].tool)
            assertEquals(1, processing.steps[1].count)
            assertTrue(processing.steps[1].success)
            assertEquals(2, requests.size)
            val secondMessages = requests[1].getJSONArray("messages")
            val assistant = (0 until secondMessages.length()).map { secondMessages.getJSONObject(it) }
                .first { it.optString("role") == "assistant" && it.has("tool_calls") }
            assertEquals("mimo internal reasoning", assistant.getString("reasoning_content"))
            val tool = (0 until secondMessages.length()).map { secondMessages.getJSONObject(it) }
                .first { it.optString("role") == "tool" }
            assertTrue(tool.getString("content").contains(old.cardId))
        } finally {
            server.stop(0)
            repository.close()
            context.deleteDatabase("buffer.db")
        }
    }
}
