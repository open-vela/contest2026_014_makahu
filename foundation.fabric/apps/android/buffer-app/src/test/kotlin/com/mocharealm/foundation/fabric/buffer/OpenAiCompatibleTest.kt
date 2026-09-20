package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.DatabaseSettings
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferHttp
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMoAsrClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAsrAudio
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBlePeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPairing
import com.mocharealm.foundation.fabric.buffer.data.device.VelaDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResult
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResultReader
import com.mocharealm.foundation.fabric.buffer.data.device.PendingVelaEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentView
import com.mocharealm.foundation.fabric.buffer.data.device.VelaGattEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.toJson
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlan
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlanner
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeState
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationChange
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncOutcome
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer
import com.mocharealm.foundation.fabric.buffer.domain.RecordingResult
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.WeeklyReflection
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyDeferredTask
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklySuggestion
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyLifeCategory
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer

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
