package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
@Composable
internal fun WeeklySummaryPanel(summary: BufferWeeklySummary, onGenerate: () -> Unit) {
    val application = LocalContext.current.applicationContext as BufferApplication
    var automatic by remember { mutableStateOf(application.repository.weeklyAutoEnabled()) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf(repositoryStatus(application)) }
    LaunchedEffect(application) {
        while (true) { job = repositoryStatus(application); kotlinx.coroutines.delay(1000) }
    }
    val generated = job?.optString("card_id")?.takeIf { it.isNotBlank() }?.let(application.repository::findCard)
    val today = LocalDate.now()
    val date = DateTimeFormatter.ofPattern("M月d日")
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${today.minusDays(7).format(date)} — ${today.format(date)}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("这一周的点滴", style = MaterialTheme.typography.headlineMedium)
            Text("回看留下的想法，也留意自己的状态。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI 周总结", style = MaterialTheme.typography.titleLarge)
            when (job?.optString("state")) {
                "complete" -> if (generated != null) {
                    Text(generated.title, style = MaterialTheme.typography.titleMedium)
                    Text(generated.summary, style = MaterialTheme.typography.bodyLarge)
                    Text("由 LLM 根据本周记录生成", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else ReviewNote("这份总结已从收藏中移除。")
                "running" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); ReviewNote("正在整理这一周的记录…") }
                "pending" -> ReviewNote("等待生成；请确认已在设置中配置 LLM 服务。")
                "failed" -> ReviewNote("生成未成功，稍后会自动重试，也可以立即重试。")
                else -> ReviewNote("让 LLM 从真实记录中回顾这一周，发现联系并提出少量建议。")
            }
        }
        FilledTonalButton(onClick = onGenerate, enabled = job?.optString("state") !in listOf("running", "pending", "complete"), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (job?.optString("state") == "failed") "重新生成周总结" else if (job?.optString("state") == "complete") "周总结已保存" else "生成 AI 周总结")
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf(listOf(summary.sparks to "灵感", summary.questions to "未解问题"),
                    listOf(summary.tasksCompleted to "已完成事项", summary.careRecords to "照顾记录")).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (count, label) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        ReviewGroup("值得继续探索", "${summary.rabbitHoleCandidates.size} 个候选主题", summary.rabbitHoleCandidates.isNotEmpty()) {
            if (summary.rabbitHoleCandidates.isEmpty()) ReviewNote("还没有标记研究主题。遇到想深入的问题时，可以先收藏。")
            summary.rabbitHoleCandidates.forEach { ReviewEntry(it.title, it.summary) }
        }
        ReviewGroup("生活与照顾", "${summary.lifeOverview.sumOf { it.cardIds.size }} 条已确认记录") {
            val recorded = summary.lifeOverview.filter { it.cardIds.isNotEmpty() }
            if (recorded.isEmpty()) ReviewNote("这段时间还没有已确认的生活记录。未记录不代表未发生。")
            recorded.forEach { category ->
                ReviewEntry(category.label, "${category.cardIds.size} 条记录 · ${category.recordedDays} 天" +
                    if (category.examples.isEmpty()) "" else "\n" + category.examples.joinToString("\n"))
            }
            if (summary.unconfirmedLifeRecords > 0) ReviewNote("另有 ${summary.unconfirmedLifeRecords} 条记录等待确认分类。")
            ReviewNote("专注照顾提醒 ${summary.focusReminders} 次")
        }
        ReviewGroup("反复出现的想法", "${summary.recurringThemes.size} 个主题") {
            if (summary.recurringThemes.isEmpty()) ReviewNote("暂时没有反复出现的标签。")
            summary.recurringThemes.forEach { ReviewEntry(it.tag, "出现在 ${it.cardIds.size} 条记录中") }
        }
        ReviewGroup("还没开始的事", "${summary.deferredTasks.size} 项反复延后") {
            if (summary.deferredTasks.isEmpty()) ReviewNote("没有反复延后的事项。")
            summary.deferredTasks.forEach { ReviewEntry(it.card.title, "延后 ${it.times} 次 · ${it.card.summary}") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ListItem(
            headlineContent = { Text("自动生成周回顾") },
            supportingContent = { Text("每周日 20:00") },
            trailingContent = {
                Switch(checked = automatic, onCheckedChange = { enabled ->
                    try {
                        application.repository.setWeeklyAutoEnabled(enabled)
                        automatic = enabled
                        application.careScheduler.onAlarm()
                        error = null
                    } catch (_: Exception) { error = "未能保存，请重试" }
                })
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    }
}

@Composable
private fun ReviewGroup(title: String, summary: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(summary) },
            trailingContent = { Icon(BufferIcons.Expand, null, modifier = Modifier.rotate(if (expanded) 180f else 0f)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable(onClickLabel = if (expanded) "收起$title" else "展开$title") { expanded = !expanded },
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Composable
private fun ReviewNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReviewEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (body != title) ReviewNote(body)
    }
}

private fun repositoryStatus(application: BufferApplication) = application.repository.weeklyLlmStatus()
