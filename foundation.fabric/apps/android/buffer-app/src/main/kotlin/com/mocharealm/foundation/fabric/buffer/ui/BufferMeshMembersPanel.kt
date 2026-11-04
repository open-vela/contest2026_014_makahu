package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshMember
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshFacts
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun meshExchange(client: FabricClient, peer: String, payload: ByteArray): ByteArray = withTimeout(15_000) {
    suspendCancellableCoroutine { continuation ->
        try {
            client.invoke(peer, BufferConstants.ability, payload) { response, error ->
                if (continuation.isActive) {
                    if (error != null || response == null) continuation.resumeWithException(IllegalStateException(error ?: "设备未回复"))
                    else continuation.resume(response)
                }
            }
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }
}

@Composable
internal fun BufferMeshMembersPanel() {
    val app = LocalContext.current.applicationContext as BufferApplication
    val repository = app.repository
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf(emptyList<BufferMeshMember>()) }
    var factCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    var taskHistory by remember { mutableStateOf(emptyList<BufferMeshFacts.TaskHistory>()) }
    var showHistory by remember { mutableStateOf(false) }
    var factStates by remember { mutableStateOf(app.meshFactsWorker.states) }
    var historyStates by remember { mutableStateOf(app.meshHistoryWorker.states) }
    var channelStates by remember { mutableStateOf(app.meshChannelWorker.states) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var localIdentity by remember { mutableStateOf<String?>(null) }
    var confirmations by remember { mutableStateOf(app.meshConfirmationWorker.progress) }
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            val saved = repository.meshMembers()
            val facts = repository.meshFactCounts()
            val history = repository.meshTaskHistory()
            val local = try { app.fabricClient?.takeIf { it.isConnected }?.localDeviceId?.joinToString("") { "%02x".format(it) } }
                catch (_: Exception) { null }
            Triple(saved, local, facts to history)
        }
        members = snapshot.first; localIdentity = snapshot.second
        factCounts = snapshot.third.first; taskHistory = snapshot.third.second
        confirmations = app.meshConfirmationWorker.progress
        factStates = app.meshFactsWorker.states
        historyStates = app.meshHistoryWorker.states
        channelStates = app.meshChannelWorker.states
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { refresh() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { message = "设备记录暂不可读，请稍后重试" }
            delay(2000)
        }
    }
    Text("协作设备", style = MaterialTheme.typography.headlineSmall)
    if (confirmations.connected && confirmations.total > 0)
        Text((if (confirmations.adopted) "本机已提交此成员配置。" else "成员配置等待首次完整确认。") +
            "当前在线确认：${confirmations.confirmed}/${confirmations.total} 台设备。转写、分类、问答和总结任务依据可用设备状态分派。", style = MaterialTheme.typography.bodySmall)
    Text("先在 Fabric 中确认设备身份，再在每台设备的 Buffer 中分别批准应用访问。设备出现或发来能力声明不会自动获得授权。")
    Text("已批准的设备可参与转写、分类及用户发起的问答和总结，任务按需提供录音、文字、专注快照和相关记录，并使用执行设备上配置的 AI 服务。支持历史同步的设备还会交换本机可共享的原始记录及录音原件、卡片、修改和删除；上下文仍受单独共享设置限制，其他来源的私人历史不转发。撤销后停止后续任务和数据传输。", style = MaterialTheme.typography.bodySmall)
    Text("参与同一任务的设备会同步该任务的协作记录；支持历史同步的设备会在重连后继续补传可共享原文。旧版应用授权需重新批准，历史记录不会重新发起任务。", style = MaterialTheme.typography.bodySmall)
    Text("已连接 Vela 的自动输入也可由协作设备提出助手分流建议，实际交接由原设备受理。", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "收起协作记录" else "查看协作记录") }
    if (showHistory) {
        if (taskHistory.isEmpty()) Text("暂无协作记录。", style = MaterialTheme.typography.bodySmall)
        taskHistory.forEach { task ->
            val name = when (task.stage) {
                "transcribe" -> "录音转写"; "classify" -> "记录分类"; "answer" -> "问题解答"; "generate" -> "周总结"; else -> "协作任务"
            }
            Text(name + " · " + if (task.applied) "来源设备已保存结果" else "已有 ${task.facts} 条协作记录", style = MaterialTheme.typography.bodySmall)
            if (task.resultVariants > 1) Text("存在不同执行结果，已保留全部原始记录。", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("批准后允许读取当前显示卡片与状态、发送输入和卡片操作、请求模式切换；日历及健康平台仍沿用各自的确认与系统权限。可随时撤销后续访问。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            busy = true
            try {
                val client = requireNotNull(app.fabricClient?.takeIf { it.isConnected }) { "请先连接 Fabric 服务" }
                val epoch = client.connectionGeneration
                val local = withContext(Dispatchers.IO) { client.localDeviceId.joinToString("") { "%02x".format(it) } }
                val peers = withContext(Dispatchers.IO) {
                    client.devices().map { it.split('|', limit = 4) }.filter {
                        it.size == 4 && it[0].matches(Regex("[0-9a-f]{64}")) && it[0] != local && it[1] == "trusted" && it[2] == "true"
                    }.map { it[0] }.distinct()
                }
                require(peers.size <= 64) { "可信在线设备过多，请先在 Fabric 中整理设备" }
                var found = 0; var failed = 0
                val request = withContext(Dispatchers.IO) { repository.meshHello(local).toString().toByteArray(Charsets.UTF_8) }
                for (peer in peers) {
                    check(app.fabricClient === client && client.isConnected && client.connectionGeneration == epoch) { "连接已变化，请重新查询" }
                    message = "正在查询设备 ${found + failed + 1}/${peers.size}"
                    try {
                        val response = withContext(Dispatchers.IO) { meshExchange(client, peer, request) }
                        require(response.size in 1..8192)
                        val raw = response.toString(Charsets.UTF_8)
                        require(raw.toByteArray(Charsets.UTF_8).contentEquals(response))
                        val offer = BufferSourceEvent.parseObject(raw, 8192)
                        require(offer.get("type") == "buffer.mesh.hello.reply")
                        withContext(Dispatchers.IO) {
                            check(app.fabricClient === client && client.isConnected && client.connectionGeneration == epoch &&
                                client.localDeviceId.joinToString("") { "%02x".format(it) } == local) { "连接身份已变化" }
                            repository.observeMeshMember(peer, local, offer)
                        }
                        found++
                    } catch (_: TimeoutCancellationException) { failed++ }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed++ }
                }
                refresh(); message = "已查询到 $found 台 Buffer 设备，$failed 台暂未返回有效声明。"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "设备查询失败" }
            finally { busy = false }
        }
    }) { Text("查询 Fabric 已信任的在线设备") }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    members.forEach { member ->
        HorizontalDivider()
        Text(member.offer.getString("label"), style = MaterialTheme.typography.titleMedium)
        Text("身份：${member.peer}", style = MaterialTheme.typography.bodySmall)
        factCounts[member.peer]?.let { Text("已同步 $it 条协作记录", style = MaterialTheme.typography.bodySmall) }
        if (member.state == "approved") channelStates[member.peer]?.let { state ->
            Text(when (state) {
                "active" -> "协作连接已就绪"
                "opening" -> "正在建立协作连接"
                else -> "协作连接暂不可用，稍后重试"
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (member.state == "approved") factStates[member.peer]?.let { state ->
            Text(when (state) {
                "syncing" -> "正在同步协作记录"
                "idle" -> "已同步当前可用协作记录"
                else -> "协作记录同步暂不可用，稍后重试"
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (member.state == "approved") historyStates[member.peer]?.let { state ->
            Text(when (state) {
                "syncing" -> "正在核对和补传可共享原文"
                "audio" -> "正在核对和补传录音原件"
                "idle" -> "当前可共享原文已核对，受限记录保留待补传"
                else -> "原始记录同步暂不可用，稍后重试"
            }, style = MaterialTheme.typography.bodySmall)
        }
        val deviceClass = when (member.offer.getString("device_class")) {
            "PHONE" -> "手机"; "TABLET" -> "平板"; "VELA" -> "Vela 设备"; else -> "其他设备"
        }
        Text("$deviceClass · " + when (member.state) {
            "approved" -> when {
                localIdentity == null -> "本机已批准，当前未连接 Fabric"
                member.approver != localIdentity -> "本机身份已变化，需重新批准"
                else -> "本机已批准应用访问"
            }
            "revoked" -> "已撤销"; else -> "等待本机批准"
        })
        val offered = member.offer.getJSONObject("capabilities")
        val names = mapOf("TRANSCRIBE" to "转写", "CLASSIFY" to "分类", "ANSWER" to "问答", "GENERATE" to "生成", "HISTORY" to "历史同步")
        Text("声明的能力：" + offered.keys().asSequence().joinToString("、") { "${names.getValue(it)} v${offered.getInt(it)}" }.ifBlank { "暂无" }, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && (member.state != "approved" || (localIdentity != null && member.approver != localIdentity)), onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            val client = requireNotNull(app.fabricClient?.takeIf { it.isConnected }) { "请先连接 Fabric 服务" }
                            repository.decideMeshMember(member.peer, member.digest, true,
                                client.localDeviceId.joinToString("") { "%02x".format(it) })
                        }
                        refresh(); message = "已批准此设备的当前声明版本"
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: "批准失败" }
                    finally { busy = false }
                }
            }) { Text("批准此设备") }
            TextButton(enabled = !busy && member.state != "revoked", onClick = {
                scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { repository.decideMeshMember(member.peer, member.digest, false, null) }; refresh(); message = "已撤销后续访问" }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { message = "撤销失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("撤销") }
        }
        if (member.state == "approved") TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    val client = requireNotNull(app.fabricClient?.takeIf { it.isConnected }) { "请先连接 Fabric 服务" }
                    val epoch = client.connectionGeneration
                    val local = withContext(Dispatchers.IO) { client.localDeviceId.joinToString("") { "%02x".format(it) } }
                    val request = org.json.JSONObject().put("type", "buffer.mesh.binding.get").put("protocol", 1)
                        .put("device_id", local).toString().toByteArray(Charsets.UTF_8)
                    val bytes = withContext(Dispatchers.IO) { meshExchange(client, member.peer, request) }
                    require(bytes.size in 1..8192)
                    val raw = bytes.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                    val reply = BufferSourceEvent.parseObject(raw, 8192)
                    check(reply.optString("type") != "error") { reply.optString("detail", "对方尚未批准应用访问") }
                    require(reply.keys().asSequence().toSet() == setOf("type", "protocol", "device_id", "digest", "revision", "generation", "state", "member_count"))
                    require(reply.get("type") == "buffer.mesh.binding.reply" && reply.get("protocol") == 1 &&
                        reply.get("device_id") == member.peer && reply.get("state") == "prepared" &&
                        reply.getString("digest").matches(Regex("[0-9a-f]{64}")))
                    val current = withContext(Dispatchers.IO) {
                        check(app.fabricClient === client && client.isConnected && client.connectionGeneration == epoch &&
                            client.localDeviceId.joinToString("") { "%02x".format(it) } == local) { "连接身份已变化" }
                        repository.withApprovedMeshMember(member.peer, local) { repository.meshBinding(local) }
                    }
                    message = if (reply.getString("digest") == current.getString("digest")) "两台设备的成员名单和能力声明一致。"
                        else "设备间批准的成员或能力声明不同，请在两端核对。"
                } catch (_: TimeoutCancellationException) { message = "配置核对超时，请稍后重试" }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { message = error.message ?: "配置核对失败" }
                finally { busy = false }
            }
        }) { Text("核对协作配置") }
        if (member.state != "approved") TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { repository.forgetMeshMember(member.peer, member.digest) }; refresh() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { message = "移除失败，请重试" }
                finally { busy = false }
            }
        }) { Text("移除此候选") }
    }
}
