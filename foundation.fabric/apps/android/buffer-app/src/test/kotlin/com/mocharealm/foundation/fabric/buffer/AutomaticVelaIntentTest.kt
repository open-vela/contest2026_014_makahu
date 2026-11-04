package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.*
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.llm.*
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutomaticVelaIntentTest {
    private val device = "a".repeat(64)

    @Test fun modelToolChoiceRoutesCommandsWhileTimerDesignRemainsARecord() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repo = BufferRepository(context)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val text = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content")).getString("text")
            val assistant = requests.incrementAndGet() == 1
            val message = if (assistant) JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
                JSONObject().put("id", "route-1").put("type", "function").put("function", JSONObject()
                    .put("name", "route_to_device_assistant").put("arguments", "{}"))))
            else JSONObject().put("role", "assistant").put("content", JSONObject().put("segments", JSONArray().put(
                JSONObject().put("text", text).put("kind", "spark_card").put("title", "计时器设计")
                    .put("summary", "保存设计想法").put("confidence", 0.9).put("health_category", JSONObject.NULL))).toString())
            val reply = JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", if (assistant) "tool_calls" else "stop").put("message", message))).toString().toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            listOf("帮我倒计时三分钟", "记下一个计时器设计想法").forEachIndexed { index, text ->
                val bytes = text.toByteArray()
                val input = VelaPendingCaptureInput("auto-model-$index", "normal", System.currentTimeMillis(),
                    0, "text/plain", VelaPendingCaptureProtocol.fingerprint(bytes), bytes, target = "auto")
                repo.importVelaPendingCapture(device, input)
                val job = repo.pendingClassificationJobs().single { it.captureId == input.captureId }
                val lease = requireNotNull(repo.claimClassificationRequest(job.captureId))
                var handoffs = 0
                val proposals = BufferCaptureAgent.classify(BufferLlmConfig(
                    "http://127.0.0.1:${server.address.port}/v1", "fixture", "fixture-key"), job, repo, lease) {
                    repo.selectAssistantHandoff(job.captureId, lease); handoffs++
                }
                if (index == 0) {
                    assertEquals(1, handoffs); assertTrue(proposals.isEmpty())
                    repo.applyAssistantHandoff(job.captureId, lease)
                    assertNull(repo.cardForCapture(job.captureId))
                } else {
                    assertEquals(0, handoffs)
                    repo.applyRemoteClassification(job.captureId, proposals, lease)
                    assertEquals("计时器设计", repo.cardForCapture(job.captureId)?.title)
                }
            }
        } finally { server.stop(0); repo.close(); context.deleteDatabase("buffer.db") }
    }

    @Test fun selectedIntentSurvivesLostReplyAndOnlyCurrentLeaseCanCompleteHandoff() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repo = BufferRepository(context)
        try {
            val text = "帮我计时三分钟".toByteArray()
            val input = VelaPendingCaptureInput("auto-intent-1", "normal", System.currentTimeMillis(),
                0, "text/plain", VelaPendingCaptureProtocol.fingerprint(text), text, target = "auto")
            assertEquals("imported", repo.importVelaPendingCapture(device, input))
            assertEquals("pending_transcription", repo.cardForCapture(input.captureId)!!.state)
            val lease = requireNotNull(repo.claimClassificationRequest(input.captureId))
            assertNull(repo.velaAssistantInput(input.captureId, "stale"))
            val original = requireNotNull(repo.velaAssistantInput(input.captureId, lease))
            assertEquals(String(text), original.transcript)
            assertEquals(input.recordSha256, original.recordSha256)
            repo.selectAssistantHandoff(input.captureId, lease)
            repo.markClassificationRequestFailed(input.captureId, lease, "lost reply")
            val next = requireNotNull(repo.claimClassificationRequest(input.captureId, System.currentTimeMillis() + 600_000))
            assertTrue(repo.assistantHandoffSelected(input.captureId, next))
            repo.applyAssistantHandoff(input.captureId, lease)
            assertNotNull(repo.cardForCapture(input.captureId))
            repo.applyAssistantHandoff(input.captureId, next)
            assertNull(repo.cardForCapture(input.captureId))
            assertEquals("complete", repo.classificationProcessing(input.captureId).state)
            assertEquals(String(text), repo.captures().single().transcript)
            assertTrue(repo.velaReplicaPage("", 4).all { JSONObject(it).getBoolean("deleted") })
        } finally { repo.close(); context.deleteDatabase("buffer.db") }
    }

    @Test fun pendingInputCannotBeDeletedAsFavoriteAndReceiptRemovesIt() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repo = BufferRepository(context)
        try {
            val bytes = "帮我计时三分钟".toByteArray()
            val input = VelaPendingCaptureInput("auto-delete", "normal", System.currentTimeMillis(),
                0, "text/plain", VelaPendingCaptureProtocol.fingerprint(bytes), bytes, "auto")
            repo.importVelaPendingCapture(device, input)
            val lease = requireNotNull(repo.claimClassificationRequest(input.captureId))
            repo.selectAssistantHandoff(input.captureId, lease)
            val frozen = repo.velaAssistantInput(input.captureId, lease)
            assertThrows(IllegalArgumentException::class.java) {
                repo.deleteCards(listOf(requireNotNull(repo.cardForCapture(input.captureId)).cardId))
            }
            assertEquals(frozen, repo.velaAssistantInput(input.captureId, lease))
            repo.selectAssistantHandoff(input.captureId, lease)
            repo.applyAssistantHandoff(input.captureId, lease)
            assertEquals("complete", repo.classificationProcessing(input.captureId).state)
            assertNull(repo.cardForCapture(input.captureId))
        } finally { repo.close(); context.deleteDatabase("buffer.db") }
    }

    @Test fun retryKeepsRequestIdentityAndRejectsWrongReceipt() {
        val connection = VelaAssistantHandoff.attach(device)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val input = VelaAssistantInput(device, "auto-intent-2", "c".repeat(64), "翻译这句话")
            val future = executor.submit { VelaAssistantHandoff.route(input) { 7L } }
            val sent = mutableListOf<ByteArray>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (sent.isEmpty() && System.nanoTime() < until) {
                connection.tick(0) { sent += it.copyOf(); true }
                Thread.yield()
            }
            assertEquals(1, sent.size)
            connection.tick(2999) { sent += it.copyOf(); true }
            assertEquals(1, sent.size)
            connection.tick(3000) { sent += it.copyOf(); true }
            assertEquals(2, sent.size)
            assertArrayEquals(sent[0], sent[1])
            val request = JSONObject(String(sent[0]))
            val reply = JSONObject().put("type", "vela.capture.assistant.reply").put("protocol", 1)
                .put("request_id", request.getString("request_id")).put("capture_id", input.captureId)
                .put("record_sha256", "b".repeat(64)).put("coordinator_term", 7).put("state", "duplicate")
            assertThrows(IllegalArgumentException::class.java) { connection.receive(reply.toString().toByteArray()) }
            reply.put("record_sha256", input.recordSha256)
            reply.put("coordinator_term", 6)
            connection.receive(reply.toString().toByteArray())
            assertFalse(future.isDone)
            reply.put("coordinator_term", 7)
            connection.receive(reply.toString().toByteArray())
            future.get(2, TimeUnit.SECONDS)
        } finally { VelaAssistantHandoff.detach(connection); executor.shutdownNow() }
    }

    @Test fun wireTargetDefaultsToLegacyAndCannotChangeOnReplay() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("buffer.db")
        val repo = BufferRepository(context)
        fun frame(id: String, target: String?): ByteArray {
            val bytes = "计时器设计想法".toByteArray()
            return JSONObject().put("type", "vela.capture.input.part").put("protocol", 1)
                .put("capture_id", id).put("mode", "normal").put("created_at", 1_790_000_000_000L)
                .put("duration_ms", 0).put("content_type", "text/plain")
                .put("record_sha256", VelaPendingCaptureProtocol.fingerprint(bytes))
                .put("offset", 0).put("total", bytes.size)
                .put("record_base64", java.util.Base64.getEncoder().encodeToString(bytes))
                .also { if (target != null) it.put("target", target) }.toString().toByteArray()
        }
        fun receive(bytes: ByteArray): String = VelaPendingCaptureReceiver(repo, device) { true }.use {
            JSONObject(String(it.receiveAuthenticated(device, bytes, 1000))).getString("state")
        }
        try {
            assertEquals("imported", receive(frame("wire-auto", "auto")))
            assertEquals("pending_transcription", repo.cardForCapture("wire-auto")!!.state)
            val lease = requireNotNull(repo.claimClassificationRequest("wire-auto"))
            assertNotNull(repo.velaAssistantInput("wire-auto", lease))
            assertEquals("conflict", receive(frame("wire-auto", "buffer")))
            assertEquals("imported", receive(frame("wire-legacy", null)))
            assertFalse(repo.cardForCapture("wire-legacy")!!.state == "pending_transcription")
            val oldLease = requireNotNull(repo.claimClassificationRequest("wire-legacy"))
            assertNull(repo.velaAssistantInput("wire-legacy", oldLease))
            assertThrows(IllegalArgumentException::class.java) { receive(frame("wire-invalid", "assistant")) }
        } finally { repo.close(); context.deleteDatabase("buffer.db") }
    }

    @Test fun anotherPeerCannotReceiveDelegatedInput() {
        val connection = VelaAssistantHandoff.attach("b".repeat(64))
        try {
            assertThrows(java.io.IOException::class.java) {
                VelaAssistantHandoff.route(VelaAssistantInput(device, "capture-3", "c".repeat(64), "帮我计时")) { 1L }
            }
        } finally { VelaAssistantHandoff.detach(connection) }
    }
}
