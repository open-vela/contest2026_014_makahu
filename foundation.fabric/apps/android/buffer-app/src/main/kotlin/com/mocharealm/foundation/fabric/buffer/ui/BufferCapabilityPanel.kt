package com.mocharealm.foundation.fabric.buffer.ui

import android.Manifest
import android.os.Build
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.domain.CapabilityContract
import com.mocharealm.foundation.fabric.buffer.domain.CapabilityProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BufferCapabilityPanel() {
    val application = LocalContext.current.applicationContext as BufferApplication
    val repository = application.repository
    val scope = rememberCoroutineScope()
    var proposals by remember { mutableStateOf(emptyList<CapabilityProposal>()) }
    var generation by remember { mutableStateOf("none") }
    var selected by remember { mutableStateOf<CapabilityProposal?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "通知已授权" else "通知未授权；能力不会显示提醒"
        application.careScheduler.requestReconcile()
    }
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { repository.capabilityProposals() to repository.capabilityGenerationState() }
        proposals = snapshot.first; generation = snapshot.second
        val manager = application.getSystemService(NotificationManager::class.java)
        notificationsAllowed = manager.areNotificationsEnabled() && manager.getNotificationChannel("buffer-capabilities")?.importance != NotificationManager.IMPORTANCE_NONE
        selected?.let { previous -> selected = proposals.firstOrNull { it.id == previous.id && it.version == previous.version && it.digest == previous.digest } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { refresh() } catch (_: Exception) { message = "能力记录暂不可读，请稍后重试" }
            delay(2000)
        }
    }
    Text("能力建议", style = MaterialTheme.typography.headlineSmall)
    Text("AI 根据真实历史提出草图，由你决定是否启用。启用只授权这台手机和所显示的版本。")
    Text("分析会把最近30天的专注时长、标题和回顾发送给已配置的 AI 服务。生成建议不会开始执行。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy && generation !in setOf("pending", "running"), onClick = {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { repository.requestCapabilitySuggestions() }
                application.capabilityWorker.requestProcessing(); message = "正在分析历史，建议生成后可在下方审核"
                refresh()
            } catch (e: Exception) { message = e.message ?: "未能提交建议生成" }
            finally { busy = false }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("根据专注历史提出建议") }
    if (generation in setOf("pending", "running")) TextButton(enabled = !busy, onClick = {
        scope.launch { busy = true;try { withContext(Dispatchers.IO) { repository.cancelCapabilitySuggestions() };refresh();message="已取消，迟到结果不会启用能力" }
            catch (_:Exception){message="取消失败，请重试"}finally{busy=false} }
    }) { Text("取消本次生成") }
    if (generation == "complete" && proposals.isEmpty()) Text("这次分析没有提出有充分依据的能力。")
    if (generation == "complete" && proposals.isNotEmpty()) Text("分析已结束；下方建议需要你逐项审核。")
    if (generation == "pending") Text("建议已排队，等待 AI 服务处理。")
    if (generation == "running") Text("正在分析所选历史…")
    if (generation == "failed") Text("本次分析未完成，可重新发起；已有授权保持原有状态。")
    if (generation == "expired") Text("分析请求已过期，请重新选择当前历史生成建议。")
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    proposals.forEach { proposal ->
        val now = System.currentTimeMillis()
        val state = when {
            proposal.grantUntil > now -> "已启用"
            proposal.state == "approved" || proposal.state == "expired" -> "授权已到期或资源已变化"
            proposal.state == "revoked" -> "已撤销"
            proposal.state == "rejected" -> "已拒绝"
            now >= proposal.expiresAt -> "建议已过期"
            else -> "待你确认"
        }
        OutlinedButton(onClick = { selected = proposal }, modifier = Modifier.fillMaxWidth()) {
            Text("${proposal.title} · 版本${proposal.version}\n$state")
        }
    }
    selected?.let { proposal ->
        HorizontalDivider()
        Text(proposal.title, style = MaterialTheme.typography.titleLarge)
        Text("生成于 " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(proposal.body.getLong("proposed_at"))) + "；14天内可审核启用。", style = MaterialTheme.typography.bodySmall)
        Text("读取：本机专注会话。触发：启用后持续专注 ${proposal.minutes} 分钟，每次专注最多一次。")
        val actions = proposal.body.getJSONArray("message_ids")
        Text("行为：在这台手机额外显示照顾通知：" + (0 until actions.length()).joinToString("；") { CapabilityContract.messages.getValue(actions.getString(it)) })
        Text("边界：不写日历或健康平台，不切换模式，不强制中断，不执行生成代码。启用有效期30天；可在此撤销，未执行的请求会取消。")
        Text("确认方式：启用此版本后，满足条件的通知无需逐条确认。重启后只累计可核实的专注时间，过期通知不补发。", style = MaterialTheme.typography.bodySmall)
        Text("AI 的建议说明：${proposal.explanation}")
        Text("真实依据", style = MaterialTheme.typography.titleMedium)
        val evidence = proposal.body.getJSONArray("evidence")
        for (i in 0 until evidence.length()) {
            val s = evidence.getJSONObject(i).getJSONObject("session")
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.getLong("started_at")))
            val feedback = when (s.getString("experience")) { "tired" -> "感觉疲劳"; "hard" -> "感觉吃力"; "good" -> "感觉良好"; else -> "未填写感受" }
            Text("$date · ${s.getString("title")}\n${(s.getLong("ended_at")-s.getLong("started_at"))/60_000} 分钟 · $feedback", style = MaterialTheme.typography.bodySmall)
        }
        val now = System.currentTimeMillis()
        if (proposal.grantUntil > now) {
            Text("授权至 " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(proposal.grantUntil)))
        }
        Button(enabled = !busy && proposal.state == "proposed" && now < proposal.expiresAt, onClick = {
            scope.launch {
                busy=true
                try {
                    withContext(Dispatchers.IO) { repository.decideCapability(proposal.id,proposal.version,proposal.digest,true);application.careScheduler.requestReconcile() }
                    refresh();message="已启用这个版本，可随时撤销"
                } catch(e:Exception){message=e.message?:"启用失败"} finally{busy=false}
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("明确启用这个版本") }
        TextButton(enabled = !busy && proposal.state in setOf("proposed","approved"), onClick = {
            scope.launch {
                busy=true
                try {
                    withContext(Dispatchers.IO) { repository.decideCapability(proposal.id,proposal.version,proposal.digest,false);application.careScheduler.requestReconcile() }
                    refresh();message="已停止后续执行，正在撤下尚未清除的通知"
                } catch (_:Exception){message="未能停用，请重试"} finally{busy=false}
            }
        }) { Text(if(proposal.state=="approved") "停用并撤销授权" else "拒绝这个建议") }
        if (!notificationsAllowed) Text("系统通知已关闭，当前不会显示能力提醒。", style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许显示系统通知") }
        TextButton(onClick = {
            application.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("查看系统通知设置") }
    }
}
