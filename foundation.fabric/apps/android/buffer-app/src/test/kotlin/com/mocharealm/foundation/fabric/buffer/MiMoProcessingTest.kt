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
import android.util.Base64
import org.robolectric.RuntimeEnvironment
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MiMoProcessingTest {
    @Test fun wavHasCorrectPcmHeaderAndLength() {
        val pcm = ByteArray(32000)
        val wav = BufferAsrAudio.wrapPcm(pcm, 16000, 1)
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals(32036, bytes.getInt(4))
        assertEquals(16000, bytes.getInt(24)); assertEquals(32000, bytes.getInt(28))
        assertEquals(32000, bytes.getInt(40)); assertEquals(32044, wav.size)
        assertThrows(IllegalArgumentException::class.java) { BufferAsrAudio.wrapPcm(ByteArray(3), 16000, 1) }
    }
    @Test fun silentOrNearSilentAudioNeverReachesAsrButQuietSignalIsRetained() {
        for (level in listOf(0, 9, 16)) {
            val pcm = ByteArray(320) { if (it % 2 == 0) level.toByte() else 0 }
            val wav = BufferAsrAudio.wrapPcm(pcm, 16000, 1)
            assertThrows(IllegalArgumentException::class.java) {
                BufferMiMoAsrClient.transcribe(BufferLlmConfig("http://127.0.0.1:1/v1", "mimo-v2.5", ""), wav)
            }
        }
        BufferAsrAudio.requireAudioSignal(BufferAsrAudio.wrapPcm(ByteArray(320) { if (it % 2 == 0) 32 else 0 }, 16000, 1))
        assertThrows(IllegalArgumentException::class.java) {
            BufferAsrAudio.requireAudioSignal(BufferAsrAudio.wrapPcm(ByteArray(320), 16000, 1).copyOf(45))
        }
    }

    @Test fun asrSendsAudioAndReadsPlainText() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request: JSONObject? = null
        var auth: String? = null
        server.createContext("/v1/chat/completions") { exchange ->
            request = JSONObject(exchange.requestBody.bufferedReader().readText()); auth = exchange.requestHeaders.getFirst("Authorization")
            val response = """{"choices":[{"finish_reason":"stop","message":{"content":"明天继续研究旧项目。"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val wav = BufferAsrAudio.wrapPcm(ByteArray(320) { if (it % 2 == 0) 64 else 0 }, 16000, 1)
            assertEquals("明天继续研究旧项目。", BufferMiMoAsrClient.transcribe(BufferLlmConfig("http://127.0.0.1:${server.address.port}/v1", "mimo-v2.5", "test-key"), wav))
            assertEquals("mimo-v2.5-asr", request!!.getString("model")); assertEquals("Bearer test-key", auth)
            val data = request!!.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data")
            assertArrayEquals(wav, Base64.decode(data.substringAfter(','), Base64.DEFAULT))
            assertEquals("auto", request!!.getJSONObject("asr_options").getString("language"))
        } finally { server.stop(0) }
    }
    @Test fun rejectsInventedRelationsAndExecutableLayouts() {
        val result = JSONObject("""{"segments":[{"text":"项目","kind":"spark_card","title":"项目","summary":"记录","related_card_ids":["old"],"presentation":{"blocks":[{"type":"paragraph","title":"摘要","text":"相关内容"}]}}]}""")
        assertThrows(IllegalArgumentException::class.java) { BufferCaptureAgent.parse(result, emptySet()) }
        assertEquals(listOf("old"), BufferCaptureAgent.parse(result, setOf("old")).single().relatedCardIds)
        result.getJSONArray("segments").getJSONObject(0).getJSONObject("presentation").getJSONArray("blocks").getJSONObject(0).put("type", "html")
        assertThrows(IllegalStateException::class.java) { BufferCaptureAgent.parse(result, setOf("old")) }
    }
    @Test fun savesClassificationRelationsAndLayoutTogetherAndSurvivesReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        var repo = BufferRepository(context)
        val old = repo.createTextCapture("旧项目：咖啡研究")
        val fresh = repo.createTextCapture("继续咖啡项目")
        val layout = """{"blocks":[{"type":"paragraph","title":"下一步","text":"继续已有研究"}]}"""
        repo.applyRemoteClassification(fresh.captureId!!, listOf(BufferClassificationProposal("继续咖啡项目", "spark_card", "咖啡", "继续研究", null, 0.9, listOf(old.cardId), layout)))
        repo.close(); repo = BufferRepository(context)
        assertEquals(listOf(old.cardId), repo.findCard(fresh.cardId)!!.relatedCardIds)
        assertEquals(layout, repo.cardPresentation(fresh.cardId))
        repo.deleteCards(listOf(fresh.cardId))
        assertNull(repo.cardPresentation(fresh.cardId))
        repo.close(); context.deleteDatabase("buffer.db")
    }
}
