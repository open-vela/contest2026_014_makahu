package com.mocharealm.foundation.fabric.buffer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.BufferApplication

@Composable
internal fun BufferCaptureContextPanel() {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    var policy by remember { mutableStateOf(repository.captureContextPolicy()) }
    var message by remember { mutableStateOf("") }
    fun save(field: String, enabled: Boolean) {
        try {
            val current = repository.captureContextPolicy()
            current.put(field, enabled)
            repository.setCaptureContextPolicy(current.getBoolean("location"), current.getBoolean("calendar"), current.getBoolean("share"))
            policy = repository.captureContextPolicy()
            message = "已保存；仅影响后续采集和共享，不删除历史记录"
        } catch (_: Exception) { message = "设置未保存，请重试" }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save("location", true) else message = "位置未授权，记录仍可保存"
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save("calendar", true) else message = "日历未授权，记录仍可保存"
    }
    Text("捕获上下文", style = MaterialTheme.typography.headlineSmall)
    Text("记录保存后可附上粗略位置与当时正在进行的日程。权限或系统服务不可用时会注明原因，不影响记录保存。上下文不自动发送给 AI 服务。")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("粗略位置", style = MaterialTheme.typography.titleMedium)
            Text("只保存约十公里的区域，使用捕获前十五分钟内的系统位置缓存；不记录精确坐标或地址。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(policy.getBoolean("location"), onCheckedChange = { enabled ->
            if (!enabled) save("location", false)
            else if (repository.captureContextPolicy().getBoolean("location_granted")) save("location", true)
            else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("当时日程", style = MaterialTheme.typography.titleMedium)
            Text("只读捕获时正在进行的非全天事项，最多三条标题与时段；不会写入系统日历。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(policy.getBoolean("calendar"), onCheckedChange = { enabled ->
            if (!enabled) save("calendar", false)
            else if (repository.captureContextPolicy().getBoolean("calendar_granted")) save("calendar", true)
            else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        })
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("共享给已配对设备", style = MaterialTheme.typography.titleMedium)
            Text("开启后，本机记录的上下文可通过加密连接随卡片发给已认证的 Buffer 设备。关闭后在下次成功同步时撤下展示副本；已经传输的数据和对端历史无法远程保证删除。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(policy.getBoolean("share"), onCheckedChange = { save("share", it) })
    }
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}
