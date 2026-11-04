package com.mocharealm.foundation.fabric.buffer

import android.app.Application
import com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantHandoff
import com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantInput
import com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantRejection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaAssistantHandoffTest {
    private val input = VelaAssistantInput("a".repeat(64), "capture-test", "c".repeat(64), "设置一个三十秒计时器")

    private fun sentRequest(connection: VelaAssistantHandoff.Connection): JSONObject {
        var payload: ByteArray? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (payload == null && System.nanoTime() < deadline) {
            connection.tick(0) { payload = it.copyOf(); true }
            Thread.yield()
        }
        return JSONObject(requireNotNull(payload) { "assistant request was not queued" }.toString(Charsets.UTF_8))
    }

    private fun reply(request: JSONObject, state: String, code: Any? = null): ByteArray =
        JSONObject(request.toString()).apply {
            put("type", "vela.capture.assistant.reply")
            remove("transcript")
            put("state", state)
            if (code != null) put("error_code", code)
        }.toString().toByteArray(Charsets.UTF_8)

    private fun rejected(future: Future<*>): VelaAssistantRejection {
        val error = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
        // The route API must unwrap CompletableFuture's ExecutionException, preserving the safe code.
        assertTrue(error.cause is VelaAssistantRejection)
        return error.cause as VelaAssistantRejection
    }

    @Test fun deviceRefusalPreservesActionableProtocolReasons() {
        for ((state, code) in listOf(
            "failed" to "not_coordinator",
            "failed" to "setup_incomplete",
            "conflict" to "source_hash_mismatch",
            "conflict" to "capture_state_conflict",
        )) {
            val connection = VelaAssistantHandoff.attach(input.deviceId)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { VelaAssistantHandoff.route(input) { 7L } }
                connection.receive(reply(sentRequest(connection), state, code))
                val error = rejected(future)
                assertEquals(state, error.state)
                assertEquals(code, error.errorCode)
                assertEquals("设备未受理助手任务（$code），原记录已保留", error.message)
                assertNull(error.cause)
            } finally { VelaAssistantHandoff.detach(connection); executor.shutdownNow() }
        }
    }

    @Test fun arbitraryRemoteContentNeverBecomesAnExceptionDiagnostic() {
        val secret = "private-remote-value"
        val maliciousCodes = listOf(
            null, JSONObject.NULL, 123, true,
            "not_coordinator\n$secret", "storage_error $secret", "future_error_$secret",
            "x".repeat(8000), JSONObject().put("message", secret),
        )
        for (code in maliciousCodes) {
            val connection = VelaAssistantHandoff.attach(input.deviceId)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { VelaAssistantHandoff.route(input) { 7L } }
                connection.receive(reply(sentRequest(connection), "failed", code))
                val error = rejected(future)
                assertEquals("unknown_error", error.errorCode)
                assertEquals("设备未受理助手任务（unknown_error），原记录已保留", error.message)
                assertFalse(error.stackTraceToString().contains(secret))
                assertNull(error.cause)
            } finally { VelaAssistantHandoff.detach(connection); executor.shutdownNow() }
        }
    }

    @Test fun staleRejectionCannotFailCurrentRequestAndRetryKeepsIdentity() {
        val connection = VelaAssistantHandoff.attach(input.deviceId)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit { VelaAssistantHandoff.route(input) { 7L } }
            val request = sentRequest(connection)
            val stale = JSONObject(request.toString()).put("coordinator_term", 6L)
            connection.receive(reply(stale, "failed", "not_coordinator"))
            assertFalse(first.isDone)
            connection.receive(reply(request, "failed", "not_coordinator"))
            assertEquals("not_coordinator", rejected(first).errorCode)

            val retry = executor.submit { VelaAssistantHandoff.route(input) { 8L } }
            val next = sentRequest(connection)
            assertEquals(request.getString("request_id"), next.getString("request_id"))
            assertEquals(8L, next.getLong("coordinator_term"))
            connection.receive(reply(request, "failed", "not_coordinator"))
            assertFalse(retry.isDone)
            connection.receive(reply(next, "duplicate"))
            retry.get(2, TimeUnit.SECONDS)
        } finally { VelaAssistantHandoff.detach(connection); executor.shutdownNow() }
    }

    @Test fun successDoesNotTurnOptionalRemoteDiagnosticIntoAFailure() {
        for (state in listOf("accepted", "duplicate")) {
            val connection = VelaAssistantHandoff.attach(input.deviceId)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit { VelaAssistantHandoff.route(input) { 7L } }
                connection.receive(reply(sentRequest(connection), state, "untrusted optional text"))
                future.get(2, TimeUnit.SECONDS)
            } finally { VelaAssistantHandoff.detach(connection); executor.shutdownNow() }
        }
    }
}
