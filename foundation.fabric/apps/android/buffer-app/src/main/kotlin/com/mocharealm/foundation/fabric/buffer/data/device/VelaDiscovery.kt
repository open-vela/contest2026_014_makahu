package com.mocharealm.foundation.fabric.buffer.data.device
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

internal data class VelaPeer(
    val deviceId: String,
    val name: String,
    val address: String,
    val pairingPort: Int,
    val dataPort: Int,
)

internal data class VelaPairing(
    val deviceId: String,
    val token: String,
)

internal class VelaDiscovery {
    fun scan(): List<VelaPeer> {
        val request = JSONObject()
            .put("type", "buffer.discover")
            .put("protocol", BufferConstants.protocolVersion)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val peers = LinkedHashMap<String, VelaPeer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = broadcastTargets()
            targets.forEach { target ->
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        target,
                        BufferConstants.discoveryPort,
                    ),
                )
            }

            val packetBuffer = ByteArray(MAX_DISCOVERY_PACKET)
            val deadline = System.currentTimeMillis() + SCAN_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                socket.soTimeout = remaining.coerceIn(20, RECEIVE_TIMEOUT_MS)
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parsePeer(packet)?.let { peer -> peers[peer.deviceId] = peer }
            }
        }
        return peers.values.toList()
    }

    fun pair(peer: VelaPeer, pairingCode: String, phoneDeviceId: String): VelaPairing {
        require(pairingCode.matches(Regex("\\d{6}"))) { "配对码应为 6 位数字" }
        require(phoneDeviceId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "invalid Android device id"
        }

        Socket().use { socket ->
            socket.soTimeout = PAIRING_TIMEOUT_MS
            socket.connect(InetSocketAddress(peer.address, peer.pairingPort), PAIRING_TIMEOUT_MS)
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
            )
            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            )
            val request = JSONObject()
                .put("type", "buffer.pair")
                .put("protocol", BufferConstants.protocolVersion)
                .put("pairing_code", pairingCode)
                .put("phone_device_id", phoneDeviceId)
                .put("phone_port", BufferConstants.bridgePort)
            writer.write(request.toString())
            writer.newLine()
            writer.flush()

            val line = try {
                reader.readBoundedLine(MAX_PAIRING_LINE)
                    ?: throw IOException("Vela 未返回配对结果")
            } catch (error: LineTooLongException) {
                throw IOException("Vela 配对响应过大", error)
            }
            val response = JSONObject(line)
            if (response.optString("type") != "buffer.pair_ack" ||
                response.optInt("protocol", -1) != BufferConstants.protocolVersion
            ) {
                throw IOException(response.optString("detail", "配对码错误或 Vela 拒绝配对"))
            }
            val deviceId = response.optString("device_id")
            val token = response.optString("token")
            if (deviceId != peer.deviceId || token.length !in 8..128) {
                throw IOException("Vela 返回了无效的配对身份")
            }
            return VelaPairing(deviceId, token)
        }
    }

    private fun parsePeer(packet: DatagramPacket): VelaPeer? {
        return try {
            val response = JSONObject(
                String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8),
            )
            if (response.optString("type") != "buffer.vela" ||
                response.optInt("protocol", -1) != BufferConstants.protocolVersion
            ) return null
            val deviceId = response.optString("device_id")
            val address = packet.address.hostAddress ?: return null
            val pairingPort = response.optInt("pairing_port", -1)
            val dataPort = response.optInt("data_port", -1)
            if (!deviceId.matches(Regex("[A-Za-z0-9._-]{1,80}")) ||
                address.isBlank() ||
                pairingPort !in 1024..65535 ||
                dataPort !in 1024..65535
            ) return null
            VelaPeer(
                deviceId = deviceId,
                name = response.optString("name", "Gemini S1"),
                address = address,
                pairingPort = pairingPort,
                dataPort = dataPort,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashMap<String, InetAddress>()
        targets[GLOBAL_BROADCAST] = InetAddress.getByName(GLOBAL_BROADCAST)
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.values.toList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val address = interfaceAddress.address
                val broadcast = interfaceAddress.broadcast
                if (address is Inet4Address && broadcast != null) {
                    targets[broadcast.hostAddress ?: return@forEach] = broadcast
                }
            }
        }
        return targets.values.toList()
    }

    companion object {
        private const val GLOBAL_BROADCAST = "255.255.255.255"
        private const val SCAN_WINDOW_MS = 1_200L
        private const val RECEIVE_TIMEOUT_MS = 250
        private const val PAIRING_TIMEOUT_MS = 5_000
        private const val MAX_DISCOVERY_PACKET = 4 * 1024
        private const val MAX_PAIRING_LINE = 16 * 1024
    }
}
