package com.mocharealm.foundation.fabric.buffer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePolicy
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun BedtimePolicyPanel(onDefer: (BufferCard) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BufferApplication
    val repository = app.repository
    val initial = remember { repository.bedtimePolicy() }
    var method by rememberSaveable { mutableStateOf(initial.method) }
    var fallback by rememberSaveable { mutableStateOf(initial.fallback.ifBlank { "22:00" }) }
    var sleep by rememberSaveable { mutableStateOf(initial.sleepTime) }
    var selected by remember { mutableStateOf(initial.calendars) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRefresh++
        if (!granted) message = "未授予日历读取权限；已保存的日程策略会使用回退时间"
    }
    val calendars by produceState(emptyList<Pair<Long, String>>(), permissionRefresh) {
        value = withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                emptyList()
            else runCatching {
                buildList {
                    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                        null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)?.use { rows ->
                        while (rows.moveToNext()) {
                            if (size == 128) break
                            add(rows.getLong(0) to listOf(rows.getString(1).orEmpty(), rows.getString(2).orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    val plan by produceState<String?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { repository.bedtimePlanSnapshot() }
            delay(1500)
        }
    }
    Text("自动睡前模式", style = MaterialTheme.typography.headlineSmall)
    for ((id, title) in listOf("off" to "关闭", "fixed" to "每天固定时间", "calendar" to "日程结束后，入睡前一小时")) {
        Row(Modifier.fillMaxWidth()) {
            RadioButton(selected = method == id, onClick = { method = id })
            TextButton(onClick = { method = id }) { Text(title) }
        }
    }
    if (method != "off") OutlinedTextField(fallback, { if (it.length <= 5) fallback = it },
        modifier = Modifier.fillMaxWidth(), label = { Text(if (method == "calendar") "无可用日程时的回退时间（HH:mm）" else "开始时间（HH:mm）") }, singleLine = true)
    if (method == "calendar") {
        OutlinedTextField(sleep, { if (it.length <= 5) sleep = it }, modifier = Modifier.fillMaxWidth(), label = { Text("期望入睡时间（HH:mm）") }, singleLine = true)
        Text("上午的入睡时间属于前一晚。只参考你选择的日历；晚间日程延长时顺延，保留一小时收束。专注和研究期间会等待；已过本晚窗口不补触发。")
        TextButton(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) { Text("授权或刷新可选日历") }
        calendars.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(id in selected, onCheckedChange = { checked ->
                    if (!checked) selected = selected - id
                    else if (selected.size < 16) selected = (selected + id).distinct()
                    else message = "一次最多选择十六个日历"
                })
                Text(name.ifBlank { "未命名日历" }, Modifier.weight(1f).padding(top = 12.dp))
            }
        }
        if (calendars.isEmpty()) Text("暂无可选日历；请检查系统授权和日历服务。")
    }
    Button(onClick = {
        try {
            repository.setBedtimePolicy(BedtimePolicy(method, if (method == "off") "" else fallback.trim(),
                if (method == "calendar") sleep.trim() else "23:00", selected.sorted()))
            app.careScheduler.requestReconcile()
            message = "策略已保存。睡前模式暂缓普通事项通知；退出后恢复。"
        } catch (error: Exception) { message = error.message ?: "睡前策略保存失败" }
    }, modifier = Modifier.fillMaxWidth()) { Text("保存睡前策略") }
    if (message.isNotBlank()) Text(message)
    plan?.let { raw ->
        val value = JSONObject(raw)
        val nights = value.getJSONArray("nights")
        val now = System.currentTimeMillis()
        val night = (0 until nights.length()).map { nights.getJSONObject(it) }.firstOrNull { it.getLong("expires") > now }
        val reason = when (night?.optString("source") ?: value.optString("status")) {
            "calendar_end" -> "根据所选日历最后一项结束时间顺延"
            "no_late_agenda" -> "晚间日程已结束，按期望入睡前一小时"
            "no_agenda_fallback" -> "当天无可用日程，使用回退时间"
            "calendar_permission_denied" -> "日历未授权，使用回退时间"
            "calendar_unavailable" -> "日历服务暂不可用，使用回退时间"
            "calendar_conflict" -> "日程跨越本晚窗口，暂停该晚自动触发"
            else -> "等待下一次计划核对"
        }
        if (method == "calendar") Text(night?.let {
            "计划开始：${java.time.Instant.ofEpochMilli(it.getLong("start")).atZone(java.time.ZoneId.systemDefault())}\n$reason"
        } ?: reason, style = MaterialTheme.typography.bodySmall)
    }
    BedtimeDeferredSuggestions(onDefer)
}

@Composable
private fun BedtimeDeferredSuggestions(onDefer: (BufferCard) -> Unit) {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val candidates by produceState(emptyList<Pair<BufferCard, String>>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { repository.bedtimeDeferredCandidates() }
            delay(1500)
        }
    }
    if (candidates.isNotEmpty()) {
        HorizontalDivider()
        Text("适合留到白天的问题", style = MaterialTheme.typography.titleMedium)
        Text("以下仅为建议。你可以选择明天、周末或其他时间；取消不会改动卡片。已有稍后计划不重复建议。")
        candidates.forEach { (card, reason) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.title)
                Text(reason, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDefer(card) }) { Text("选择回流时间…") }
            }
        }
    }
}

@Composable
internal fun BedtimeReadabilityActions(card: BufferCard, onTags: (BufferCard, String) -> Unit) {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val approved by produceState(false, card) {
        while (true) {
            value = withContext(Dispatchers.IO) { repository.bedtimeUserApproved(card) }
            delay(1500)
        }
    }
    val safe = BedtimeDeck.SAFE_TAG
    val high = BedtimeDeck.HIGH_TAG
    if (safe in card.tags && !approved) Text("内容已更新或标记尚未确认，请重新确认睡前可读。", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        val tags = if (approved) card.tags - safe else (card.tags - high + safe).distinct()
        onTags(card, tags.joinToString(","))
    }) { Text(if (approved) "取消睡前可读标记" else "确认这条内容睡前可读") }
    TextButton(onClick = {
        val tags = if (high in card.tags) card.tags - high else (card.tags - safe + high).distinct()
        onTags(card, tags.joinToString(","))
    }) { Text(if (high in card.tags) "取消白天再研究标记" else "留到白天研究") }
}
