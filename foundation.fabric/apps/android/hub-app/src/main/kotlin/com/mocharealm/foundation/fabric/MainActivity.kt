package com.mocharealm.foundation.fabric

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private data class HubSnapshot(
    val status: String,
    val providerCount: Int,
    val devices: List<HubDevice>,
    val candidates: List<DiscoveredDevice>,
)

private data class HubDevice(
    val id: String,
    val state: String,
    val online: Boolean,
    val label: String,
)

private data class DiscoveredDevice(
    val label: String,
    val address: String,
    val sources: String,
)

class MainActivity : ComponentActivity() {
    private var runtimeStatus by mutableStateOf("Waiting for local network permission")
    private var providerCount by mutableIntStateOf(0)
    private var devices by mutableStateOf(emptyList<HubDevice>())
    private var candidates by mutableStateOf(emptyList<DiscoveredDevice>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FabricTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    val localGranted = Build.VERSION.SDK_INT < 37 ||
                        grants[LOCAL_NETWORK_PERMISSION] == true ||
                        checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    if (localGranted) startHub() else runtimeStatus = "Local network permission denied"
                }
                LaunchedEffect(Unit) {
                    val permissions = buildList {
                        if (Build.VERSION.SDK_INT >= 37 &&
                            checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
                        ) add(LOCAL_NETWORK_PERMISSION)
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
                        ) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        if (Build.VERSION.SDK_INT >= 31) {
                            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.BLUETOOTH_SCAN)
                            }
                            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            }
                        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        if (Build.VERSION.SDK_INT >= 26 &&
                            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                        ) add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (permissions.isEmpty()) startHub() else permissionLauncher.launch(permissions.toTypedArray())
                    while (true) {
                        // Run the JNI calls (which contend the RUNTIME mutex with
                        // discovery) and string parsing off the main thread; only
                        // the cheap state assignments happen on the UI thread, so
                        // the poll can no longer stall rendering.
                        val snapshot = withContext(Dispatchers.IO) {
                            HubSnapshot(
                                status = NativeHub.nativeStatus(),
                                providerCount = FabricHubService.providerCount(),
                                devices = parseDevices(NativeHub.nativeDevices()),
                                candidates = parseCandidates(NativeHub.nativeCandidates()),
                            )
                        }
                        runtimeStatus = snapshot.status
                        providerCount = snapshot.providerCount
                        devices = snapshot.devices
                        candidates = snapshot.candidates
                        delay(1_000)
                    }
                }
                HubScreen(
                    status = runtimeStatus,
                    providerCount = providerCount,
                    devices = devices,
                    candidates = candidates,
                    onStart = {
                        if (Build.VERSION.SDK_INT >= 37 &&
                            checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
                        ) permissionLauncher.launch(arrayOf(LOCAL_NETWORK_PERMISSION)) else startHub()
                    },
                    onStop = { stopService(Intent(this, FabricHubService::class.java)) },
                    onPair = { address -> nativeAction("Pairing failed") { NativeHub.nativePair(address) } },
                    onAccept = { id -> nativeAction("Pairing approval failed") { NativeHub.nativeAccept(id) } },
                    onDelete = { id -> nativeAction("Delete failed") { NativeHub.nativeRemove(id) } },
                )
            }
        }
    }

    private fun startHub() {
        val service = Intent(this, FabricHubService::class.java).setAction(FabricHubService.ACTION_BIND_HUB)
        startForegroundService(service)
    }

    private fun nativeAction(errorMessage: String, action: () -> Boolean) {
        Thread {
            if (!action()) runOnUiThread { runtimeStatus = errorMessage }
        }.start()
    }
}

private fun parseDevices(value: String?): List<HubDevice> = value.orEmpty().lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { row ->
        val fields = row.split('|')
        if (fields.size != 4) null else HubDevice(
            fields[0],
            fields[1],
            fields[2].toBooleanStrictOrNull() ?: false,
            fields[3],
        )
    }
    .toList()

private fun parseCandidates(value: String?): List<DiscoveredDevice> = value.orEmpty().lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { row ->
        val fields = row.split('|')
        if (fields.size != 3) null else DiscoveredDevice(fields[0], fields[1], fields[2])
    }
    .toList()

@Composable
private fun FabricTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF176B4A),
            onPrimary = Color.White,
            surface = Color(0xFFF7F9F8),
            onSurface = Color(0xFF191F1D),
            surfaceVariant = Color(0xFFE8EFEB),
        ),
        content = content,
    )
}

@Composable
private fun HubScreen(
    status: String,
    providerCount: Int,
    devices: List<HubDevice>,
    candidates: List<DiscoveredDevice>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPair: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var showManualPairing by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("Device Fabric Hub", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
            Spacer(Modifier.height(18.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("RUNTIME", fontSize = 12.sp, color = Color(0xFF52615B))
                    Text(status, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text("Providers: $providerCount", modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Nearby devices", fontWeight = FontWeight.SemiBold)
            if (candidates.isEmpty()) {
                Text(
                    "Searching on the local network...",
                    color = Color(0xFF6A6259),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(candidates, key = { it.address }) { candidate ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.label, fontWeight = FontWeight.Medium)
                                Text(
                                    "${candidate.address}  ${candidate.sources}",
                                    color = Color(0xFF6A6259),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Button(onClick = { onPair(candidate.address) }) {
                                Text("Pair")
                            }
                        }
                    }
                }
            }
            Button(onClick = { showManualPairing = !showManualPairing }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Pair by IP address", Modifier.padding(start = 8.dp))
            }
            if (showManualPairing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("IP address and port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onPair(address.trim()) }, enabled = address.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Pair")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Devices", fontWeight = FontWeight.SemiBold)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(devices, key = { it.id }) { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.label, fontWeight = FontWeight.Medium)
                            Text(
                                "${device.id.take(12)} · " +
                                    if (device.state == "pending") "Approval required" else if (device.online) "Online" else "Offline",
                                color = if (device.online) Color(0xFF176B4A) else Color(0xFF6A6259),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (device.state == "pending") {
                            IconButton(onClick = { onAccept(device.id) }) {
                                Icon(Icons.Default.Check, contentDescription = "Accept pairing")
                            }
                        }
                        IconButton(onClick = { onDelete(device.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete device")
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7A2E2E), contentColor = Color.White),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text("Stop", Modifier.padding(start = 8.dp))
                }
                Button(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Start", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
