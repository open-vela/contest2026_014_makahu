package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.SourceObservationEntry
import com.mocharealm.foundation.fabric.buffer.data.local.SourceObservationPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal fun sourceObservationTitle(entry: SourceObservationEntry): String = when (entry.kind) {
    "focus" -> "专注 · ${entry.body.getString("title")}"
    "research" -> "研究 · ${entry.body.getString("title").ifBlank { "来源设备记录" }}"
    "timer","alarm" -> "${if(entry.kind=="timer")"计时器" else "闹钟"} · ${entry.body.getJSONObject("item").getString("label").ifBlank { "来源设备记录" }}"
    "mode" -> "模式 · ${sourceModeName(entry.body.getJSONObject("item").getString("mode"))}"
    "focus_config" -> "专注照顾 · 提醒间隔"
    "focus_reminder" -> "专注照顾 · 设备运行记录"
    else -> entry.body.getString("question")
}

private fun sourceModeName(mode: String): String = when(mode) {
    "focus"->"专注";"bedtime"->"睡前";"rabbit_hole"->"研究";else->"普通"
}

internal fun sourceObservationDetail(entry: SourceObservationEntry): String = buildString {
    val b = entry.body
    fun time(value: Long) = if (value > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value)) else "时间未提供"
    append("来源设备 ${entry.peer.take(8)}\n")
    when (entry.kind) {
        "focus" -> {
            append("${b.getString("title")}\n开始：${time(b.getLong("started_at"))}\n")
            val state = when (b.getString("status")) { "active" -> "记录时进行中"; "review_pending" -> "记录时待回顾"; else -> "已记录回顾" }
            append(state)
            if (b.getLong("ended_at") > 0) append("\n结束：${time(b.getLong("ended_at"))}")
            if (b.getInt("planned_minutes") > 0) append("\n原计划 ${b.getInt("planned_minutes")} 分钟")
            val progress = when (b.getString("progress")) { "done" -> "已完成"; "partial" -> "部分推进"; "not_started" -> "尚未开始"; else -> "" }
            val experience = when (b.getString("experience")) { "good" -> "状态不错"; "tired" -> "感到疲劳"; "hard" -> "推进困难"; else -> "" }
            if (progress.isNotEmpty()) append("\n当时进展：$progress")
            if (experience.isNotEmpty()) append("\n当时感受：$experience")
            if (b.getString("remaining").isNotBlank()) append("\n未完成事项：${b.getString("remaining")}")
            append("\n这是来源历史，不代表当前会话状态。")
        }
        "research" -> {
            append("${b.getString("title").ifBlank { "研究记录" }}\n记录时间：${time(b.getLong("recorded_at"))}\n")
            if (b.getBoolean("active")) append("当时仍在研究，记录时剩余 ${(b.getLong("remaining_ms") + 59_999) / 60_000} 分钟")
            else append(when (b.getString("reason")) { "time_up" -> "当时已到时间"; "mode_changed" -> "当时已切换模式"; else -> "当时已结束" })
            if (b.getBoolean("display_expired")) append("\n来源设备显示时间已到")
            append("\n这是设备当时看到的状态，不补造原会话的开始或结束时间。")
        }
        "timer","alarm" -> {
            val item=b.getJSONObject("item")
            append("${item.getString("label").ifBlank { if(entry.kind=="timer")"计时器" else "闹钟" }}\n记录时间：${time(b.getLong("recorded_at"))}\n")
            append(when(item.getInt("state")){1->"记录时运行中";2->"记录时已暂停";3->"记录时已触发";else->"记录时已取消"})
            if(entry.kind=="timer") {
                append("\n原定时长：${item.getLong("duration_ms")/1000} 秒")
                append("\n保存的剩余时长：${(item.getLong("remaining_ms")+999)/1000} 秒")
            } else append("\n原定时间：${time(item.getString("scheduled_at_unix_ms").toLong())}")
            append("\n本页为设备历史记录，提醒由来源设备处理。")
        }
        "mode","focus_config","focus_reminder" -> {
            val item=b.getJSONObject("item")
            append("记录时间：${time(b.getLong("recorded_at"))}")
            if(entry.kind!="focus_config")append("\n当时模式：${sourceModeName(item.getString("mode"))}")
            if(entry.kind!="mode")append("\n提醒间隔：${item.getLong("interval_ms")/60_000} 分钟")
            if(entry.kind=="focus_reminder") {
                append("\n当时已专注：${item.getLong("elapsed_ms")/60_000} 分钟")
                if(item.getString("mode")=="focus")append("\n当时距下次提醒：${(item.getLong("remaining_ms")+999)/1000} 秒")
                if(item.getLong("notice_ms")>0)append("\n当时正在显示照顾提醒")
            }
            append("\n本页记录来源设备当时的状态。")
        }
        else -> append("${time(b.getLong("created_at"))}\n\n问：${b.getString("question")}\n\n答：${b.getString("answer")}")
    }
    if (entry.projection != "applied") append("\n关联历史尚待核对，本页仅展示已保存的内容。")
}

private data class ObservationLoad(val page: SourceObservationPage? = null, val failed: Boolean = false)

@Composable
internal fun BufferSourceAnswerHistory(repository: BufferRepository, cardId: String) {
    var expanded by rememberSaveable(cardId) { mutableStateOf(false) }
    var before by remember(cardId) { mutableStateOf<String?>(null) }
    val load by produceState(ObservationLoad(), cardId, expanded, before) {
        if (!expanded) return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) {
                try { ObservationLoad(repository.sourceObservations(cardId, before)) }
                catch (_: Exception) { ObservationLoad(failed = true) }
            }
            delay(3000)
        }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起设备追问历史" else "查看设备追问历史") }
    if (expanded) {
        val page = load.page
        when {
            load.failed -> Text("来源追问暂时无法读取，稍后自动重试", style = MaterialTheme.typography.bodySmall)
            page == null -> Text("正在读取来源追问…", style = MaterialTheme.typography.bodySmall)
            page.recovering -> Text("来源追问历史正在恢复…", style = MaterialTheme.typography.bodySmall)
            page.entries.isEmpty() -> Text("暂无已保存的设备追问", style = MaterialTheme.typography.bodySmall)
            else -> {
                Text("来源设备的历史观察", style = MaterialTheme.typography.labelSmall)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    page.entries.forEach { entry -> Text(sourceObservationDetail(entry), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Row {
            if (page?.older != null) TextButton(onClick = { before = page.older }) { Text("更早记录") }
            if (before != null) TextButton(onClick = { before = null }) { Text("返回最新") }
        }
    }
}
