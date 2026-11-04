package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessing
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessingStep
import com.mocharealm.foundation.fabric.buffer.domain.CaptureContext
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryPage
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONArray

@Composable
internal fun BufferCardDetailContent(card: BufferCard, onOpen: (String) -> Unit, onSchedule: () -> Unit = {}) {
    val locale = LocalConfiguration.current.locales[0]
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val processing by produceState<BufferProcessing?>(null, card.captureId) {
        val captureId = card.captureId ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { repository.classificationProcessing(captureId) }
            delay(1500)
        }
    }
    val conversation by produceState("[]", card.cardId) {
        while (true) {
            value = withContext(Dispatchers.IO) { repository.cardConversation(card.cardId) }
            delay(1500)
        }
    }
    val captureContext by produceState<String?>(null, card.cardId, card.captureId) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                repository.cardCaptureContext(card.cardId)?.let { context ->
                    "捕获时间：${java.time.Instant.ofEpochMilli(context.getLong("captured_at")).atZone(java.time.ZoneId.systemDefault())}\n" + CaptureContext.description(context)
                }
            }
            delay(1500)
        }
    }
    var showOriginal by rememberSaveable(card.cardId) { mutableStateOf(false) }
    var showSteps by rememberSaveable(card.cardId) { mutableStateOf(false) }
    var showContext by rememberSaveable(card.cardId) { mutableStateOf(false) }
    var showHistory by rememberSaveable(card.cardId) { mutableStateOf(false) }
    var historyBefore by remember(card.captureId) { mutableStateOf<BufferSourceHistoryCursor?>(null) }
    var historyRebuild by remember(card.captureId) { mutableStateOf("") }
    val historyScope = rememberCoroutineScope()
    val history by produceState(SourceHistoryLoad(), card.captureId, showHistory, historyBefore) {
        value = SourceHistoryLoad()
        val captureId = card.captureId ?: return@produceState
        if (!showHistory) return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) {
                try { SourceHistoryLoad(repository.sourceHistory(captureId, historyBefore)) }
                catch (_: Exception) { SourceHistoryLoad(unavailable = true) }
            }
            delay(1500)
        }
    }
    val bedtimeAssessment by produceState<com.mocharealm.foundation.fabric.buffer.domain.BedtimeAssessment?>(null, card) {
        while (true) {
            value = withContext(Dispatchers.IO) { repository.bedtimeAssessment(card) }
            delay(1500)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.title, style = MaterialTheme.typography.headlineSmall)
        Text(listOf(when (card.kind) {
            "spark_card" -> "灵感"
            "question_card" -> "问题"
            "task_card" -> "待办"
            "care_card" -> "生活"
            "weekly_card" -> "回顾"
            else -> "未分类"
        }, cardStateLabel(card.state)).joinToString(" · "), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(card.summary, style = MaterialTheme.typography.bodyLarge)
    HealthQuantCardPanel(card)
    bedtimeAssessment?.let { assessment ->
        val level = when (assessment.level) { "low" -> "适合轻量回看"; "high" -> "建议留到白天"; else -> "暂不确定" }
        Text("AI 睡前建议：$level · ${assessment.reason}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    captureContext?.let { context ->
        TextButton(onClick = { showContext = !showContext }) { Text(if (showContext) "收起捕获上下文" else "查看捕获上下文") }
        if (showContext) Text(context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (card.kind == "task_card") {
        val scheduled = repository.taskSchedule(card)
        TextButton(onClick = onSchedule) {
            Text(scheduled?.let { "${java.time.Instant.ofEpochMilli(it.start).atZone(java.time.ZoneId.of(it.zone)).toLocalDate()} · ${if (it.allDay) "全天" else java.time.Instant.ofEpochMilli(it.start).atZone(java.time.ZoneId.of(it.zone)).toLocalTime()}" } ?: "设置日期与时间")
        }
    }
    processing?.let { info ->
        val skipped = info.steps.any { it.type == "ClassificationSkipped" }
        val saved = info.steps.any { it.type == "ClassificationProposed" }
        val title = when {
            info.state == "processing" -> "AI 正在整理"
            info.state == "pending" && info.attempts > 0 -> "AI 整理未完成 · 等待重试"
            info.state == "pending" -> "等待 AI 整理"
            skipped -> "保留你的修改"
            saved -> "AI 已完成整理"
            else -> "暂无 AI 整理记录"
        }
        val description = when {
            info.state == "processing" -> "正在整理记录。"
            info.state == "pending" && info.attempts > 0 -> "请求失败，内容已保留，将自动重试。持续失败请检查 AI 服务设置。"
            info.state == "pending" -> "内容已保存，分类待确认。请配置 AI 服务以继续整理。"
            skipped -> "这条记录已由你修改，AI 结果没有覆盖它。"
            saved -> "日程、提醒等操作仍需确认。"
            else -> "这条记录没有可核实的 AI 处理结果。"
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.state == "processing") LinearProgressIndicator(Modifier.fillMaxWidth())
                if (info.steps.isNotEmpty()) {
                    TextButton(onClick = { showSteps = !showSteps }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showSteps) "收起处理记录" else "查看处理记录")
                    }
                    if (showSteps) info.steps.forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(SimpleDateFormat("HH:mm", locale).format(Date(step.time)),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(processingStepLabel(step), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    BufferCardPresentation(card, onOpen)
    card.answer?.takeIf { it.isNotBlank() }?.let {
        Text("回答", style = MaterialTheme.typography.titleMedium)
        Text(it, style = MaterialTheme.typography.bodyLarge)
    }
    val turns = remember(conversation) { runCatching { JSONArray(conversation) }.getOrElse { JSONArray() } }
    for (index in 0 until turns.length()) {
        val turn = turns.getJSONObject(index)
        Text("你", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(turn.getString("question"), style = MaterialTheme.typography.bodyLarge)
        Text("助手", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(turn.getString("answer"), style = MaterialTheme.typography.bodyLarge)
    }
    processing?.original?.takeIf { it.isNotBlank() && it != card.summary }?.let { original ->
        TextButton(onClick = { showOriginal = !showOriginal }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showOriginal) "收起原始记录" else "查看原始记录")
        }
        if (showOriginal) Text(original, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BufferSourceAnswerHistory(repository, card.cardId)
    if (card.captureId != null) {
        TextButton(onClick = { showHistory = !showHistory }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showHistory) "收起来源历史" else "查看来源历史")
        }
        if (showHistory) {
            val page = history.page
            when {
                history.unavailable -> Text("暂时无法读取来源历史，稍后自动重试", style = MaterialTheme.typography.bodySmall)
                page == null -> Text("正在读取来源历史…", style = MaterialTheme.typography.bodySmall)
                page.entries.isEmpty() -> Text("暂无可读取的来源历史", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(if (historyBefore == null) "最近的来源记录" else "较早的来源记录", style = MaterialTheme.typography.labelSmall)
                    if (page.hasHeld) Text("相关来源仍有暂未交付原文的保留位置；下方仅显示已保存的原文", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    page.entries.forEach { event ->
                        val supported = event.state !in setOf("pending", "corrupt", "unsupported_schema")
                        val label = if (supported) sourceEventLabel(event.type) else "原始记录"
                        val time = if (event.createdAt > 0) SimpleDateFormat("MM-dd HH:mm", locale).format(Date(event.createdAt)) else ""
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listOf(time, if (event.local) "本机记录" else "其他设备记录", label)
                                .filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Text(sourceHistoryStateLabel(event.state), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            event.projection?.let { state ->
                                Text(sourceProjectionStateLabel(state), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            event.audio?.let { audio ->
                                Text(sourceAudioStateLabel(audio.stage, audio.problem), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (audio.stage in setOf("pending", "stored", "confirmed", "local_pending", "local_archived", "local_delivered", "local_waiting_event", "local_unavailable", "local_conflict"))
                                    SourceAudioCheck(event.eventId, repository, if (audio.stage.startsWith("local_")) card.captureId else null)
                            }
                        }
                    }
                }
            }
            if (page?.older != null) TextButton(onClick = { historyBefore = page.older }, contentPadding = PaddingValues(0.dp)) {
                Text("查看更早记录")
            }
            if (historyBefore != null) TextButton(onClick = { historyBefore = null }, contentPadding = PaddingValues(0.dp)) {
                Text("回到最近记录")
            }
            if (history.unavailable || page?.entries?.any { it.state in setOf("corrupt", "invalid_clock", "history_gap", "parent_conflict") } == true) {
                TextButton(enabled = historyRebuild != "pending", onClick = {
                    historyRebuild = "pending"
                    historyScope.launch {
                        val requested = withContext(Dispatchers.IO) {
                            try { repository.requestSourceHistoryRebuild(); true } catch (_: Exception) { false }
                        }
                        historyRebuild = if (requested) "requested" else "failed"
                    }
                }, contentPadding = PaddingValues(0.dp)) { Text("重新核对来源历史") }
            }
            if (historyRebuild == "requested") Text("已安排核对，稍后更新", style = MaterialTheme.typography.labelSmall)
            if (historyRebuild == "failed") Text("暂时无法安排核对，请稍后重试", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class SourceHistoryLoad(val page: BufferSourceHistoryPage? = null, val unavailable: Boolean = false)

@Composable
private fun SourceAudioCheck(id: String, repository: BufferRepository, localCapture: String? = null) {
    val scope = rememberCoroutineScope()
    var checking by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    TextButton(enabled = !checking, contentPadding = PaddingValues(0.dp), onClick = {
        checking = true; failed = false
        scope.launch {
            val requested = withContext(Dispatchers.IO) {
                try {
                    if (localCapture == null) repository.requestSourceBlobCheck(id) else repository.requestLocalAudioCheck(localCapture)
                    true
                } catch (_: Exception) { false }
            }
            failed = !requested
            if (requested) delay(5000)
            checking = false
        }
    }) { Text(if (checking) "已安排音频核对" else "重新核对音频") }
    if (failed) Text("暂时无法安排音频核对，请稍后重试", style = MaterialTheme.typography.labelSmall)
}

internal fun sourceAudioStateLabel(stage: String, problem: String): String {
    if (stage == "local_delivered") return if (problem.isEmpty()) "本机原始音频已归档，已记录过对端音频交付"
        else "本机原始音频已归档，已记录过对端交付；仍有交付需要核对"
    if (stage == "local_archived" && problem.isNotEmpty()) return when (problem) {
        "withheld" -> "本机原件已归档，音频共享暂未开放"
        "unsupported" -> "本机原件已归档，对端暂不支持接收音频"
        "event_missing" -> "本机原件已归档，等待对端补齐源记录"
        "no_response" -> "本机原件已归档，等待对端确认音频交付"
        "conflict", "descriptor_conflict" -> "本机原件已归档，音频交付信息需要核对"
        else -> "本机原件已归档，音频交付暂缓，将稍后重试"
    }
    if (stage.startsWith("local_")) return when (stage) {
        "local_archived" -> "本机原始音频已归档，尚未记录对端交付"
        "local_waiting_event" -> "本机原件已归档，等待源记录交付后发送音频"
        "local_pending" -> "本机原始音频待归档"
        "local_unavailable" -> "本机原始音频暂不可读，已有记录保留"
        "local_unverified" -> "未核实为本机原件，不自动转发音频"
        else -> "本机原始音频信息需要核对"
    }
    if (problem.isNotEmpty()) return when (problem) {
        "withheld" -> if (stage == "pending") "来源暂未开放音频共享" else "本机保留音频，等待来源设备确认"
        "unsupported" -> "来源设备暂不支持音频补传"
        "no_response" -> if (stage == "pending") "等待来源设备提供音频" else "本机保留音频，等待设备回执"
        "invalid_blob" -> "音频校验未通过，原始事件已保留"
        "conflict", "descriptor_conflict" -> "音频来源信息需要核对"
        else -> "音频归档暂未完成，稍后重试"
    }
    return when (stage) {
        "pending" -> "音频待归档"
        "stored" -> "音频已在本机归档，等待设备回执"
        "confirmed" -> "双方已记录音频交付"
        "unsupported" -> "音频描述暂不支持，原始事件已保留"
        else -> "音频归档需要核对"
    }
}

private fun sourceProjectionStateLabel(state: String): String = when (state) {
    "applied" -> "已重建对应视图"
    "partial" -> "已重建可读取部分，关联历史尚未齐备"
    "local_override" -> "已保留本机编辑或既有视图"
    "superseded" -> "已有较新的来源视图"
    "pending", "waiting_history", "waiting_local_history" -> "等待重建视图"
    "observed_only" -> "已保存来源观察，保留当前视图"
    "unsupported_capacity" -> "原文已保存，当前内容超出视图处理范围"
    "unsupported_type", "unsupported_schema", "blocked_parent" -> "原文已保存，等待兼容重建"
    else -> "原文已保存，视图重建需要核对"
}

internal fun sourceHistoryStateLabel(state: String): String = when (state) {
    "linked" -> "关联记录已齐备"
    "legacy" -> "旧版历史已归档"
    "pending" -> "原文已保存，正在整理来源关系"
    "waiting_parent", "missing_parent" -> "等待关联记录"
    "parent_available" -> "已找到来源，等待补齐关联记录"
    "parent_withheld" -> "关联记录暂未开放共享"
    "parent_not_found" -> "来源设备暂未提供关联记录"
    "parent_unavailable" -> "等待来源设备提供关联记录"
    "history_gap" -> "本机关联历史需要核对"
    "parent_conflict" -> "关联记录的来源信息需要核对"
    "unsupported_schema", "blocked_parent" -> "原文已保留，等待兼容处理"
    "invalid_clock" -> "原文已保留，关联顺序需要核对"
    else -> "原始记录需要核对"
}

internal fun sourceEventLabel(type: String): String = when (type) {
    "CardDocumentObserved" -> "保存卡片视图观察"
    "CalendarReplicaObserved" -> "收到日程视图观察"
    "CardDocumentUnavailable" -> "原始记录已保存，卡片暂不能同步"
    "CalendarDocumentObserved" -> "保存日程视图观察"
    "CaptureCreated" -> "保存原始记录"
    "CaptureTranscribed" -> "保存转写"
    "ClassificationRequested" -> "请求整理"
    "ClassificationProposed" -> "保存分类提案"
    "ClassificationAccepted" -> "确认分类"
    "ClassificationEdited" -> "修改分类"
    "ClassificationRejected" -> "拒绝分类"
    "QuestionAnswered" -> "保存回答"
    "QuestionResearchNoteAdded" -> "保存研究笔记"
    "QuestionExternalLinkAdded" -> "保存外部链接"
    "CaptureContextCaptured" -> "保存捕获上下文"
    "CaptureBlobDescribed" -> "记录原始音频档案"
    "LegacyAudioSnapshot" -> "归档旧版原始音频"
    "FocusSessionChanged" -> "更新专注会话"
    "FocusModeRequestRecorded" -> "保存专注模式请求"
    "FocusStateObserved" -> "记录来源专注状态"
    "ModeStateChanged", "FocusReminderModeChanged", "FocusReminderModeObserved" -> "记录模式变化"
    "ModeChangeRequested", "ModeChangeRequestCleared" -> "记录模式请求状态"
    "FocusReminderTriggered" -> "记录专注照顾提醒"
    "FocusReminderConfigChanged", "FocusReminderConfigObserved", "FocusReminderScheduleChanged", "FocusReminderNoticeExpired" -> "记录专注提醒状态"
    "AssistantOperationCommitted" -> "保存设备助手操作"
    "AssistantReminderTriggered" -> "记录计时器或闹钟到期"
    "AssistantStateInitialized", "AssistantStateRecovered", "AssistantStateChanged" -> "记录设备助手状态"
    "AssistantDisplayRequested", "AssistantDisplayFailed" -> "记录设备显示结果"
    "ResearchSessionObserved", "ResearchDisplayDeadlineReached" -> "记录研究会话状态"
    "FollowupContextPrepared", "FollowupCaptureCancelled", "ConversationViewUpdated" -> "记录设备追问状态"
    "CaptureRecordUpdated", "CardRecordChanged" -> "更新记录"
    "VelaProcessedCaptureImported", "VelaPendingCaptureImported" -> "导入设备记录"
    else -> "保存历史记录"
}

private fun processingStepLabel(step: BufferProcessingStep): String = when (step.type) {
    "ClassificationRequested" -> "已开始 AI 分类与整理"
    "ClassificationToolExecuted" -> when (step.tool) {
        "search_records" -> if (step.success) "查找旧记录 · 找到 ${step.count} 条候选" else "查找旧记录未成功"
        "read_record" -> if (step.success) "已读取一条旧记录" else "读取旧记录未成功"
        else -> "工具请求未执行"
    }
    "ClassificationProposed" -> "已保存分类、关联与整理结果"
    "ClassificationFailed" -> "本次整理失败，等待自动重试"
    "ClassificationSkipped" -> "检测到你的修改，未覆盖记录"
    else -> ""
}
