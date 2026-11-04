package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient

import android.app.Application
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenAiCompatibleTest {
    @Test fun baseUrlsNormalizeWithoutDoublingPaths() {
        assertEquals("https://example.com/v1/chat/completions", BufferLlmConfig("https://example.com", "model", "").completionUrl())
        assertEquals("https://example.com/custom/v1/chat/completions", BufferLlmConfig("https://example.com/custom/v1/", "model", "").completionUrl())
        assertEquals("https://example.com/v1/chat/completions", BufferLlmConfig("https://example.com/v1/chat/completions", "model", "").completionUrl())
        assertThrows(IllegalArgumentException::class.java) { BufferLlmConfig("https://user:secret@example.com/v1", "model", "").completionUrl() }
        assertThrows(IllegalArgumentException::class.java) { BufferLlmConfig("https://example.com?key=secret", "model", "").completionUrl() }
    }
    @Test fun usesBearerMessagesAndParsesChatCompletion() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request: JSONObject? = null
        var authorization: String? = null
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            request = JSONObject(exchange.requestBody.bufferedReader().readText())
            val response = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"answer\":\"测试答案\"}"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = BufferLlmClient.complete(BufferLlmConfig("http://127.0.0.1:${server.address.port}/v1", "custom-model", "test-key"),
                "测试指令", JSONObject().put("question", "测试问题"))
            assertEquals("测试答案", result.getString("answer"))
            assertEquals("Bearer test-key", authorization)
            assertEquals("custom-model", request!!.getString("model"))
            assertFalse(request!!.getBoolean("stream"))
            assertEquals("system", request!!.getJSONArray("messages").getJSONObject(0).getString("role"))
            assertTrue(request!!.getJSONArray("messages").getJSONObject(1).getString("content").contains("测试问题"))
            assertFalse(request.toString().contains("test-key"))
        } finally { server.stop(0) }
    }
    @Test fun malformedResultAndRedirectAreNotAcceptedOrFollowed() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect/chat/completions") { exchange ->
            exchange.responseHeaders.add("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        var followed = false
        server.createContext("/target") { exchange -> followed = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.createContext("/bad/chat/completions") { exchange ->
            val bytes = """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for (path in listOf("redirect", "bad")) assertThrows(Exception::class.java) {
                BufferLlmClient.complete(BufferLlmConfig("http://127.0.0.1:${server.address.port}/$path", "model", "test-key"), "test", JSONObject())
            }
            assertFalse(followed)
        } finally { server.stop(0) }
    }
}
