package com.mocharealm.foundation.fabric.buffer.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import kotlinx.coroutines.*
@Composable
internal fun StatusPanel(
    status: String,
    bridgeRunning: Boolean,
    fabricConnected: Boolean,
    mode: String,
    pendingCaptures: Int,
    eventCount: Int,
    phoneAddress: String,
    onToggleBridge: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(status, fontWeight = FontWeight.SemiBold)
            Text("模式：${modeLabel(mode)} · 本地事件：$eventCount · 待处理音频：$pendingCaptures", style = MaterialTheme.typography.bodySmall)
            Text("Vela 桥接：${if (bridgeRunning) "运行中 :${BufferConstants.bridgePort}" else "已停止"} · Fabric：${if (fabricConnected) "已连接" else "未连接"}", style = MaterialTheme.typography.bodySmall)
            Text("手机桥接地址：$phoneAddress:${BufferConstants.bridgePort}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onToggleBridge, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Text(if (bridgeRunning) "停止桥接" else "启动桥接")
            }
        }
    }
}

@Composable
internal fun PendingAudioPanel(
    captures: List<BufferCapture>,
    onSubmit: (BufferCapture, String) -> Unit,
) {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val ids = captures.map { it.captureId }
    var failures by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(ids) {
        while (true) {
            try { failures = withContext(Dispatchers.IO) { repository.meshTranscriptionFailures(ids) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failures = emptyMap() }
            delay(3000)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("待处理语音", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "配置语音服务或批准协作设备后可自动转写；也可以手动补录文字，原始音频仍会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            captures.forEach { capture ->
                var transcript by rememberSaveable(capture.captureId) { mutableStateOf("") }
                Text(
                    "语音记录 · ${maxOf(1, (capture.durationMs + 999) / 1000)} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failures[capture.captureId]?.let { reason ->
                    Text(when (reason) {
                        "no_signal" -> "未检测到有效录音信号。录音已保留，可在下方补充文字。"
                        "invalid_audio" -> "录音格式无法读取。原始文件已保留，可在下方补充文字。"
                        else -> "语音服务未识别到可用文字。录音已保留，可在下方补充文字。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手动转写") },
                    minLines = 2,
                )
                Button(
                    onClick = {
                        onSubmit(capture, transcript)
                        transcript = ""
                    },
                    enabled = transcript.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存转写并分类")
                }
                if (capture != captures.last()) HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun PairingPanel(
    discoveredVelas: List<VelaPeer>,
    pairedVelaIds: List<String>,
    pairingCode: String,
    scanning: Boolean,
    pairing: Boolean,
    onPairingCodeChanged: (String) -> Unit,
    onScan: () -> Unit,
    onPair: (VelaPeer) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已联网设备（局域网）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "扫描同一 Wi-Fi 下的设备，输入设备屏幕上的 6 位配对码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pairedVelaIds.isNotEmpty()) {
                Text("已绑定：${pairedVelaIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            onPairingCodeChanged(value)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Vela 配对码") },
                    singleLine = true,
                )
                Button(onClick = onScan, enabled = !scanning && !pairing) {
                    Text(if (scanning) "扫描中" else "扫描")
                }
            }
            if (discoveredVelas.isEmpty()) {
                Text("点击扫描查找同一 Wi-Fi 下的 Vela", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                discoveredVelas.forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, fontWeight = FontWeight.SemiBold)
                            Text("${peer.address} · ${peer.deviceId}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onPair(peer) },
                            enabled = pairingCode.length == 6 && !pairing,
                        ) { Text(if (pairing) "配对中" else "配对") }
                    }
                }
            }
        }
    }
}
