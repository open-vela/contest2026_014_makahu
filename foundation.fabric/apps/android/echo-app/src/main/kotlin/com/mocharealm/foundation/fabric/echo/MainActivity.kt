package com.mocharealm.foundation.fabric.echo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.IBinder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.foundation.fabric.ipc.IFabricAbility
import com.mocharealm.foundation.fabric.ipc.IFabricHub
import com.mocharealm.foundation.fabric.ipc.IFabricResultCallback
import kotlinx.coroutines.delay

private data class EchoDevice(val id: String, val label: String, val online: Boolean)

class MainActivity : ComponentActivity() {
    private var hub: IFabricHub? = null
    private var bound = false
    private var status by mutableStateOf("Checking Hub permission")
    private var input by mutableStateOf("Hello from Android Echo")
    private var output by mutableStateOf("")
    private var connected by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)
    private var devices by mutableStateOf(listOf(EchoDevice("local", "This device", false)))
    private var selectedDevice by mutableStateOf("local")
    private var sentAt = 0L

    private val provider = object : IFabricAbility.Stub() {
        override fun onInvoke(requestId: Long, payload: ByteArray) {
            hub?.complete(requestId, payload)
        }

        override fun onStreamOpen(
            requestId: Long,
            ability: String?,
            streamName: String?,
            readFromRemote: ParcelFileDescriptor?,
            writeToRemote: ParcelFileDescriptor?,
        ) {
            // The echo demo has no bulk-stream ability; close any offered descriptors.
            readFromRemote?.close()
            writeToRemote?.close()
        }
    }

    private val resultCallback = object : IFabricResultCallback.Stub() {
        override fun onResult(requestId: Long, payload: ByteArray?, error: String?) {
            val elapsed = SystemClock.elapsedRealtime() - sentAt
            runOnUiThread {
                output = error?.let { "Request failed: $it" }
                    ?: payload?.decodeToString().orEmpty()
                status = "Connected | round trip $elapsed ms"
                connected = true
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            hub = IFabricHub.Stub.asInterface(service)
            bound = true
            try {
                hub?.registerAbility(ECHO_ABILITY, packageName, provider)
                status = hub?.status() ?: "Hub unavailable"
                connected = true
            } catch (error: Exception) {
                showError("Registration failed", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            hub = null
            bound = false
            connected = false
            status = "Hub disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EchoTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    permissionDenied = !granted
                    if (granted) bindHub() else status = "Hub permission denied"
                }
                LaunchedEffect(Unit) {
                    if (checkSelfPermission(CONNECT_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                        bindHub()
                    } else {
                        permissionLauncher.launch(CONNECT_PERMISSION)
                    }
                    while (true) {
                        refreshDevices()
                        delay(1_000)
                    }
                }
                EchoScreen(
                    status = status,
                    input = input,
                    output = output,
                    connected = connected,
                    permissionDenied = permissionDenied,
                    devices = devices,
                    selectedDevice = selectedDevice,
                    onInputChanged = { input = it },
                    onDeviceSelected = { selectedDevice = it },
                    onGrantPermission = { permissionLauncher.launch(CONNECT_PERMISSION) },
                    onSend = ::sendEcho,
                )
            }
        }
    }

    override fun onDestroy() {
        try {
            hub?.unregisterAbility(ECHO_ABILITY, packageName)
        } catch (_: Exception) {
            // The Hub may already have stopped.
        }
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun bindHub() {
        val intent = Intent(HUB_ACTION).setComponent(ComponentName(HUB_PACKAGE, HUB_SERVICE))
        if (packageManager.resolveService(intent, 0) == null) {
            status = "Device Fabric Hub is not installed"
            return
        }
        try {
            if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                status = "Unable to bind Device Fabric Hub"
            }
        } catch (error: SecurityException) {
            showError("Hub permission rejected", error)
        }
    }

    private fun sendEcho() {
        val connectedHub = hub ?: return
        val payload = input.encodeToByteArray()
        if (payload.size > MAX_PAYLOAD_BYTES) {
            status = "Payload exceeds 512 KiB"
            return
        }
        connected = false
        status = "Sending"
        sentAt = SystemClock.elapsedRealtime()
        try {
            connectedHub.invoke(selectedDevice, ECHO_ABILITY, payload, resultCallback)
        } catch (error: Exception) {
            showError("Request failed", error)
        }
    }

    private fun showError(prefix: String, error: Exception) {
        status = "$prefix: ${error.message}"
        connected = false
    }

    private fun refreshDevices() {
        val rows = try {
            hub?.devices()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (rows.isEmpty()) return
        val parsed = rows.mapNotNull { row ->
            val fields = row.split('|')
            if (fields.size != 4) return@mapNotNull null
            val id = fields[0]
            EchoDevice(
                id = id,
                label = fields[3],
                online = fields[2].toBooleanStrictOrNull() ?: false,
            )
        }
        devices = parsed
        if (parsed.none { it.id == selectedDevice }) selectedDevice = "local"
    }

    private companion object {
        const val CONNECT_PERMISSION =
            "com.mocharealm.foundation.fabric.permission.CONNECT_HUB"
        const val HUB_PACKAGE = "com.mocharealm.foundation.fabric"
        const val HUB_SERVICE = "com.mocharealm.foundation.fabric.FabricHubService"
        const val HUB_ACTION = "com.mocharealm.foundation.fabric.BIND_HUB"
        const val ECHO_ABILITY = "com.example.echo"
        const val MAX_PAYLOAD_BYTES = 512 * 1024
    }
}

@Composable
private fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF176B4A),
            onPrimary = Color.White,
            surface = Color(0xFFF7F9F8),
            onSurface = Color(0xFF191F1D),
        ),
        content = content,
    )
}

@Composable
private fun EchoScreen(
    status: String,
    input: String,
    output: String,
    connected: Boolean,
    permissionDenied: Boolean,
    devices: List<EchoDevice>,
    selectedDevice: String,
    onInputChanged: (String) -> Unit,
    onDeviceSelected: (String) -> Unit,
    onGrantPermission: () -> Unit,
    onSend: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                "Echo Ability",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            )
            Text(
                status,
                color = if (connected) Color(0xFF176B4A) else Color(0xFF7A4A20),
                modifier = Modifier.padding(top = 12.dp),
            )
            if (permissionDenied) {
                Button(onClick = onGrantPermission, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Grant Hub permission")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Target device", fontWeight = FontWeight.SemiBold)
            Button(onClick = { menuOpen = true }, modifier = Modifier.padding(top = 6.dp)) {
                Text(devices.find { it.id == selectedDevice }?.label ?: "Select device")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text("${device.label} (${if (device.online) "online" else "offline"})") },
                        onClick = {
                            onDeviceSelected(device.id)
                            menuOpen = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                label = { Text("Request payload") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val targetOnline = devices.any { it.id == selectedDevice && it.online }
                Button(onClick = onSend, enabled = connected && targetOnline && input.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text("Send", Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = output,
                onValueChange = {},
                readOnly = true,
                label = { Text("Echo response") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
