package com.mocharealm.foundation.fabric.buffer.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
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
internal fun BedtimeSchedulePanel(
    schedule: String,
    onScheduleChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("自动睡前模式", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "可选。每天到达该时间后，手机进入低刺激睡前模式并向 Vela 下发答案箱/生活回顾卡。普通事项通知会暂缓，退出睡前模式后恢复。留空即可关闭自动进入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = schedule,
                onValueChange = { value ->
                    if (value.length <= 5) onScheduleChanged(value)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("每天时间（HH:mm）") },
                placeholder = { Text("22:00") },
                singleLine = true,
            )
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (schedule.isBlank()) "关闭自动睡前模式" else "保存睡前时间")
            }
        }
    }
}

@Composable
internal fun PendingAudioPanel(
    captures: List<BufferCapture>,
    onSubmit: (BufferCapture, String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("待处理语音", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "配置 MiMo 服务后会自动转写；也可以手动补录文字，原始音频仍会保留。",
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
                "Gemini S1 自己生成配对码并显示在设备屏幕上。手机只需扫描设备，再输入这 6 位数字。",
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
