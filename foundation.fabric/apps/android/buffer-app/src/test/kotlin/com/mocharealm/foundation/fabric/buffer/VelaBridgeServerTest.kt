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
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBridgeServer
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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaBridgeServerTest {
    private lateinit var repository: BufferRepository
    private lateinit var server: VelaBridgeServer
    @Before fun open() {
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        server = VelaBridgeServer(repository, 0, 0)
        server.start()
        assertTrue(server.isRunning)
    }
    @After fun close() { server.stop(); repository.close() }
    private fun connect() = Socket("127.0.0.1", server.listeningPort).apply { soTimeout = 2000 }
    private fun exchange(socket: Socket, request: JSONObject): JSONObject {
        socket.getOutputStream().write((request.toString() + "\n").toByteArray())
        socket.getOutputStream().flush()
        // One unbuffered line at a time: no reader can prefetch another response.
        val line = StringBuilder()
        while (true) {
            val c = socket.getInputStream().read()
            check(c >= 0) { "Unexpected EOF" }
            if (c == 10) return JSONObject(line.toString())
            line.append(c.toChar())
        }
    }
    private fun hello(id: String) = JSONObject().put("type", "hello").put("auth", "challenge")
        .put("protocol", 1).put("device_id", id).put("client_nonce", "a".repeat(64))
    private fun proof(token: String, nonce: String, id: String): JSONObject {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        val value = mac.doFinal("$nonce:$id".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("type", "hello_proof").put("protocol", 1).put("device_id", id).put("proof", value)
    }
    private fun assertClosed(socket: Socket) {
        try { assertEquals(-1, socket.getInputStream().read()) }
        catch (error: SocketException) { /* Reset is also a closed connection, timeout is not. */ }
    }
    @Test fun rapidRestartClosesAcceptedClientsAndKeepsNewListeners() {
        val row = repository.velaEnrollments.begin("vela-test", "AA:BB:CC:DD:EE:FF")
        repeat(12) {
            connect().use { old ->
                assertEquals("hello_challenge", exchange(old, hello(row.deviceId)).getString("type"))
                server.stop()
                assertFalse(server.isRunning)
                assertClosed(old)
                server.start()
                connect().use { fresh ->
                    assertEquals("hello_challenge", exchange(fresh, hello(row.deviceId)).getString("type"))
                }
                DatagramSocket().use { udp ->
                    udp.soTimeout = 2000
                    val data = "{\"type\":\"buffer.phone.discover\",\"protocol\":1}".toByteArray()
                    udp.send(DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), server.discoveryListeningPort))
                    val response = DatagramPacket(ByteArray(4096), 4096)
                    udp.receive(response)
                    val json = JSONObject(String(response.data, 0, response.length))
                    assertEquals(repository.deviceId(), json.getString("device_id"))
                    assertEquals(server.listeningPort, json.getInt("data_port"))
                }
            }
        }
    }
    @Test fun realTcpRequiresBothEnrollmentSignalsAndValidProof() {
        val row = repository.velaEnrollments.begin("vela-test", "AA:BB:CC:DD:EE:FF")
        connect().use { socket ->
            val challenge = exchange(socket, hello(row.deviceId))
            assertEquals(PhoneAuthentication.proof(row.token, "a".repeat(64), challenge.getString("nonce"),
                row.deviceId, repository.deviceId()), challenge.getString("phone_proof"))
            val result = exchange(socket, proof("wrong-token", challenge.getString("nonce"), row.deviceId))
            assertEquals("pairing_required", result.getString("code"))
        }
        assertFalse(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).lanVerified)
        connect().use { socket ->
            val challenge = exchange(socket, hello(row.deviceId))
            val result = exchange(socket, proof(row.token, challenge.getString("nonce"), row.deviceId))
            assertEquals("enrollment_pending", result.getString("code"))
            assertClosed(socket)
        }
        assertTrue(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).lanVerified)
        assertNull(repository.pairedVelaToken(row.deviceId))
        repository.velaEnrollments.networkReady(row.deviceId, row.requestId)
        assertEquals(row.token, repository.pairedVelaToken(row.deviceId))
        connect().use { socket ->
            val challenge = exchange(socket, hello(row.deviceId))
            val result = exchange(socket, proof(row.token, challenge.getString("nonce"), row.deviceId))
            assertEquals("hello_ack", result.getString("type"))
            server.stop()
            assertClosed(socket)
        }
    }
    @Test fun authenticatedReceiptRecoversLostBleResultButRejectsStaleOrForgedReceipt() {
        val row = repository.velaEnrollments.begin("vela-test", "AA:BB:CC:DD:EE:FF")
        fun attempt(requestId: String, receiptNonce: String? = null, wrongProof: Boolean = false): JSONObject = connect().use { socket ->
            val challenge = exchange(socket, hello(row.deviceId))
            val nonce = challenge.getString("nonce")
            val request = proof(row.token, nonce, row.deviceId)
                .put("enrollment_id", requestId)
                .put("enrollment_proof", if (wrongProof) "0".repeat(64) else EnrollmentAuthentication.proof(
                    row.token, receiptNonce ?: nonce, row.deviceId, repository.deviceId(), requestId))
            exchange(socket, request)
        }
        assertEquals("enrollment_pending", attempt("0".repeat(32)).getString("code"))
        assertEquals("enrollment_pending", attempt(row.requestId, wrongProof = true).getString("code"))
        assertEquals("enrollment_pending", attempt(row.requestId, receiptNonce = "a".repeat(64)).getString("code"))
        assertNull(repository.pairedVelaToken(row.deviceId))
        assertFalse(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).networkReady)
        assertEquals("hello_ack", attempt(row.requestId).getString("type"))
        assertEquals(row.token, repository.pairedVelaToken(row.deviceId))
        assertNull(repository.velaEnrollments.pending(row.deviceId))
    }
    @Test fun cancelledEnrollmentCannotRecoverOverLan() {
        val row = repository.velaEnrollments.begin("vela-test", "AA:BB:CC:DD:EE:FF")
        repository.velaEnrollments.cancel(row.deviceId, row.requestId)
        connect().use { socket -> assertEquals("pairing_required", exchange(socket, hello(row.deviceId)).getString("code")) }
        assertFalse(repository.velaEnrollments.recoverVerifiedReceipt(row.deviceId, row.requestId, row.token))
        assertNull(repository.pairedVelaToken(row.deviceId))
    }
    @Test fun occupiedPortDoesNotReportRunningOrDisturbExistingListener() {
        ServerSocket(0).use { occupied ->
            val blocked = VelaBridgeServer(repository, occupied.localPort, 0)
            try {
                blocked.start()
                assertFalse(blocked.isRunning)
                assertEquals(-1, blocked.listeningPort)
                assertTrue(server.isRunning)
            } finally { blocked.stop() }
        }
    }
}
