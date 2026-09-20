package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import kotlinx.coroutines.delay

@Composable
internal fun BufferRhythmPanel(mode: String, onModeChanged: (String) -> Unit, onSettings: () -> Unit) {
    val modes = listOf("normal", "focus", "bedtime", "rabbit_hole")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("现在的节奏", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(BufferIcons.Settings, contentDescription = "专注提醒设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, candidate ->
                    SegmentedButton(
                        selected = mode == candidate,
                        onClick = { onModeChanged(candidate) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.weight(1f),
                        icon = {},
                        label = { Text(modeLabel(candidate), maxLines = 1) },
                    )
                }
            }
            Text(when (mode) {
                "focus" -> "安心投入，也给休息留一点时间。"
                "bedtime" -> "通知暂缓，给今晚留一点安静。"
                "rabbit_hole" -> "留住探索的过程，慢慢靠近答案。"
                else -> "不赶进度，按自己的节奏来。"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BufferExplorationPanel(
    session: RabbitHoleSession?,
    onResearch: () -> Unit,
    onRecord: () -> Unit,
    onHistory: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session?.id, session?.active) {
        while (session?.active == true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(BufferIcons.Search, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(if (session?.active == true) "正在探索" else "给好奇心一点时间", style = MaterialTheme.typography.titleMedium)
            }
            if (session?.active == true) {
                Text(session.title, style = MaterialTheme.typography.bodyLarge)
                Text("剩余 ${session.remainingMinutes(now)} 分钟", style = MaterialTheme.typography.labelLarge)
            } else {
                Text("从一个收藏出发，继续上次的想法。", style = MaterialTheme.typography.bodyMedium)
                if (session != null) Text("上次探索：${session.title}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onResearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (session?.active == true) "结束并留下记录" else "开始探索")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onHistory) { Text("过去的探索") }
                if (session != null && !session.active) TextButton(onClick = onRecord) { Text("补充记录") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BufferFocusSettingsPanel(
    load: () -> FocusReminderConfig,
    save: (FocusReminderConfig) -> Boolean,
    onSaved: () -> Unit,
) {
    val initial = remember { load() }
    var interval by rememberSaveable { mutableStateOf(initial.intervalMs / 60_000) }
    var snooze by rememberSaveable { mutableStateOf(initial.snoozeMs / 60_000) }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("专注提醒设置", style = MaterialTheme.typography.headlineSmall)
        Text("专注时，轻轻提醒你喝水、休息或活动一下。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("提醒间隔", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(5L, 15L, 25L, 50L, 60L, 90L, 120L, 180L) + initial.intervalMs / 60_000).distinct().sorted().forEach { minutes ->
                    FilterChip(selected = interval == minutes, onClick = { interval = minutes }, label = { Text("$minutes 分钟") })
                }
            }
            Text("修改后会重新计时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("稍后提醒", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(1L, 5L, 10L, 15L, 30L, 60L) + initial.snoozeMs / 60_000).distinct().sorted().forEach { minutes ->
                    FilterChip(selected = snooze == minutes, onClick = { snooze = minutes }, label = { Text("$minutes 分钟") })
                }
            }
        }
        if (error) Text("未能保存，请重试。", color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (save(FocusReminderConfig(interval * 60_000, snooze * 60_000))) onSaved() else error = true
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存设置") }
    }
}
