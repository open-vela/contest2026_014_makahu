package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VelaBridgeServer(
    private val repository: BufferRepository,
    private val bridgePort: Int = BufferConstants.bridgePort,
    private val discoveryPort: Int = BufferConstants.discoveryPort,
) {
    private class Session(val server: ServerSocket, val discovery: DatagramSocket) {
        val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CLIENTS)
        val clients = mutableSetOf<Socket>() // guarded by the server lock
        @Volatile var active = true
    }
    private val lock = Any()
    @Volatile private var session: Session? = null
    val isRunning: Boolean get() = session?.active == true
    internal val listeningPort: Int get() = synchronized(lock) { session?.server?.localPort ?: -1 }
    internal val discoveryListeningPort: Int get() = synchronized(lock) { session?.discovery?.localPort ?: -1 }

    fun start() = synchronized(lock) {
        if (session != null) return@synchronized
        val server = ServerSocket()
        val discovery = DatagramSocket(null)
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), bridgePort), MAX_CLIENTS)
            discovery.reuseAddress = true
            discovery.bind(InetSocketAddress(discoveryPort))
            discovery.broadcast = true
            discovery.soTimeout = DISCOVERY_TIMEOUT_MS
            val current = Session(server, discovery)
            session = current
            Thread({ acceptLoop(current) }, "buffer-bridge-accept").start()
            Thread({ discoveryLoop(current) }, "buffer-bridge-discovery").start()
        } catch (error: Exception) {
            server.close()
            discovery.close()
            session?.let(::stopSession)
            Log.e(TAG, "Buffer LAN bridge failed to start", error)
        }
    }

    fun stop() = synchronized(lock) { session?.let(::stopSession); Unit }

    private fun stopSession(current: Session) = synchronized(lock) {
        current.active = false
        runCatching { current.server.close() }
        current.discovery.close()
        current.clients.forEach { runCatching { it.close() } }
        current.clients.clear()
        current.executor.shutdownNow()
        if (session === current) session = null
    }

    private fun acceptLoop(current: Session) {
        try {
            while (current.active) {
                val client = current.server.accept()
                synchronized(lock) {
                    if (!current.active || current.clients.size >= MAX_CLIENTS) {
                        client.close()
                    } else {
                        current.clients.add(client)
                        current.executor.execute {
                            try { handle(current, client) }
                            catch (error: Exception) {
                                if (current.active) Log.w(TAG, "Buffer client connection failed", error)
                            } finally {
                                runCatching { client.close() }
                                synchronized(lock) { current.clients.remove(client) }
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (current.active) Log.w(TAG, "Buffer bridge accept failed", error)
        } finally { stopSession(current) }
    }

    private fun handle(current: Session, socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
            var authenticated = false
            var authenticatedDeviceId = ""
            var challengeDeviceId = ""
            var challengeNonce = ""
            while (current.active) {
                val line = try {
                    reader.readBoundedLine(MAX_LINE_CHARS) ?: break
                } catch (_: LineTooLongException) {
                    write(writer, JSONObject().put("type", "error").put("code", "message_too_large"))
                    break
                }
                if (!current.active) break
                val request = try {
                    JSONObject(line)
                } catch (error: Exception) {
                    write(writer, JSONObject().put("type", "error").put("code", "invalid_json"))
                    continue
                }
                val type = request.optString("type")
                if (!authenticated) {
                    val deviceId = request.optString("device_id")
                    if (type == "hello" && request.optString("auth") == "challenge") {
                        if (request.optInt("protocol", -1) != BufferConstants.protocolVersion) {
                            write(writer, JSONObject().put("type", "error").put("code", "unsupported_protocol"))
                            break
                        }
                        val token = repository.velaAuthenticationToken(deviceId)
                        if (token.isNullOrBlank()) {
                            write(writer, JSONObject().put("type", "error").put("code", "pairing_required"))
                            break
                        }
                        val clientNonce = request.opt("client_nonce")
                        if (clientNonce !is String || !clientNonce.matches(Regex("[0-9a-f]{64}"))) {
                            write(writer, JSONObject().put("type", "error").put("code", "mutual_auth_required"))
                            break
                        }
                        challengeDeviceId = deviceId
                        challengeNonce = randomHex(CHALLENGE_BYTES)
                        write(
                            writer,
                            JSONObject()
                                .put("type", "hello_challenge")
                                .put("protocol", BufferConstants.protocolVersion)
                                .put("nonce", challengeNonce)
                                .put("device_id", repository.deviceId())
                                .put("phone_proof", PhoneAuthentication.proof(token, clientNonce,
                                    challengeNonce, deviceId, repository.deviceId())),
                        )
                        continue
                    }

                    if (type == "hello_proof" &&
                        request.optInt("protocol", -1) == BufferConstants.protocolVersion &&
                        deviceId == challengeDeviceId && challengeNonce.isNotBlank()
                    ) {
                        val token = repository.velaAuthenticationToken(deviceId)
                        val expected = token?.let { hmacHex(it, challengeNonce, deviceId) }
                        val supplied = request.optString("proof")
                        if (expected.isNullOrBlank() ||
                            !MessageDigest.isEqual(
                                expected.toByteArray(StandardCharsets.UTF_8),
                                supplied.toByteArray(StandardCharsets.UTF_8),
                            )
                        ) {
                            write(writer, JSONObject().put("type", "error").put("code", "pairing_required"))
                            break
                        }
                        val enrollmentId = request.opt("enrollment_id") as? String
                        val enrollmentProof = request.opt("enrollment_proof") as? String
                        if (enrollmentId != null && enrollmentProof != null &&
                            EnrollmentAuthentication.verify(requireNotNull(token), challengeNonce, deviceId,
                                repository.deviceId(), enrollmentId, enrollmentProof)) {
                            repository.velaEnrollments.recoverVerifiedReceipt(deviceId, enrollmentId, token)
                        }
                        repository.velaEnrollments.lanVerified(deviceId, requireNotNull(token))
                        if (repository.pairedVelaToken(deviceId) != token) {
                            write(writer, JSONObject().put("type", "error").put("code", "enrollment_pending"))
                            break
                        }
                        authenticated = true
                        authenticatedDeviceId = deviceId
                        write(
                            writer,
                            BufferWire.handle(
                                repository,
                                JSONObject()
                                    .put("type", "hello")
                                    .put("protocol", BufferConstants.protocolVersion),
                            ),
                        )
                        continue
                    }

                    // The pairing token is never accepted on the data plane.
                    // Every current Vela session must prove possession through
                    // the nonce/HMAC challenge above.
                    write(writer, JSONObject().put("type", "error").put("code", "challenge_required"))
                    break
                }
                if (type == "capture.created" ||
                    type == "device.status" ||
                    type == "upload.retry"
                ) {
                    request.put("device_id", authenticatedDeviceId)
                }
                val response = BufferWire.handle(repository, request)
                write(writer, response)
            }
        }
    }

    private fun discoveryLoop(current: Session) {
        val socket = current.discovery
        try {
            val buffer = ByteArray(MAX_DISCOVERY_PACKET)
            while (current.active) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: Exception) {
                    if (current.active) Log.w(TAG, "Buffer discovery receive failed", error)
                    break
                }

                val request = try {
                    JSONObject(
                        String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            StandardCharsets.UTF_8,
                        ),
                    )
                } catch (_: Exception) {
                    continue
                }
                if (request.optString("type") != "buffer.phone.discover" ||
                    request.optInt("protocol", -1) != BufferConstants.protocolVersion
                ) continue

                val response = JSONObject()
                    .put("type", "buffer.phone")
                    .put("protocol", BufferConstants.protocolVersion)
                    .put("device_id", repository.deviceId())
                    .put("data_port", current.server.localPort)
                val encoded = response.toString().toByteArray(StandardCharsets.UTF_8)
                socket.send(
                    DatagramPacket(
                        encoded,
                        encoded.size,
                        packet.address,
                        packet.port,
                    ),
                )
            }
        } catch (error: Exception) {
            if (current.active) Log.w(TAG, "Buffer discovery responder unavailable", error)
        } finally { stopSession(current) }
    }

    private fun write(writer: BufferedWriter, response: JSONObject) {
        writer.write(response.toString())
        writer.newLine()
        writer.flush()
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun hmacHex(token: String, nonce: String, deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$nonce:$deviceId".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    companion object {
        private const val MAX_CLIENTS = 8
        private const val TAG = "BufferBridge"
        private const val MAX_LINE_CHARS = 1_200_000
        private const val MAX_DISCOVERY_PACKET = 4 * 1024
        private const val DISCOVERY_TIMEOUT_MS = 1_000
        private const val CHALLENGE_BYTES = 32
    }
}
