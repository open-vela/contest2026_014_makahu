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
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessing
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferProcessingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun BufferCardDetailContent(card: BufferCard, onOpen: (String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val processing by produceState<BufferProcessing?>(null, card.captureId) {
        val captureId = card.captureId ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { repository.classificationProcessing(captureId) }
            delay(1500)
        }
    }
    var showOriginal by rememberSaveable(card.cardId) { mutableStateOf(false) }
    var showSteps by rememberSaveable(card.cardId) { mutableStateOf(false) }
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
            info.state == "processing" -> "正在分类、查找相关记录并生成整理内容。"
            info.state == "pending" && info.attempts > 0 -> "上次请求未成功，内容已保留。手机会自动重试；持续失败时请检查 AI 服务设置。"
            info.state == "pending" -> "当前内容已保存，分类仍是临时建议。配置好 AI 服务后，由手机自动处理。"
            skipped -> "这条记录已由你修改，AI 结果没有覆盖它。"
            saved -> "分类与整理结果已保存。日程、提醒等操作仍需你确认。"
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
    processing?.original?.takeIf { it.isNotBlank() && it != card.summary }?.let { original ->
        TextButton(onClick = { showOriginal = !showOriginal }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showOriginal) "收起原始记录" else "查看原始记录")
        }
        if (showOriginal) Text(original, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
