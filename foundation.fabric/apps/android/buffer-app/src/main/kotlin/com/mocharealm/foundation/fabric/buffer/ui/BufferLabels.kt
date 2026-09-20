package com.mocharealm.foundation.fabric.buffer.ui
import androidx.compose.runtime.Composable
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
internal fun modeLabel(mode: String): String = when (mode) {
    "focus" -> "专注"
    "bedtime" -> "睡前"
    "rabbit_hole" -> "深挖"
    else -> "日常"
}

internal fun actionLabel(action: String): String = when (action) {
    "done" -> "完成"
    "later" -> "稍后再看…"
    "archive" -> "收起"
    "remind" -> "提醒"
    "calendar" -> "加入日程"
    "remove_calendar" -> "移出日程"
    "answer" -> "回答"
    "rabbit_hole" -> "深挖"
    "mark_answered" -> "已解决"
    "health_sync" -> "同步健康"
    "accept_classification" -> "接受分类"
    "edit_classification" -> "编辑分类"
    "reject_classification" -> "拒绝分类"
    "mark_researched" -> "标记已深入研究"
    "create_project" -> "建项目草稿"
    "create_article" -> "建文章草稿"
    "create_code_task" -> "建代码任务"
    "ferment" -> "开始发酵"
    "next" -> "下一步"
    else -> action
}

internal fun cardStateLabel(state: String): String = when (state) {
    "pending_transcription" -> "等待转写"
    "classification_rejected" -> "待重新分类"
    "cooling" -> "冷藏中"
    "fermenting" -> "发酵中"
    "converted" -> "已转化"
    "unanswered_gray" -> "未解"
    "rabbit_hole_candidate" -> "兔子洞候选"
    "quick_answered" -> "已快速回答"
    "researched" -> "已深入研究"
    "health_synced" -> "已同步健康"
    "calendar_requested" -> "已加入日程"
    "inbox" -> "待处理"
    "draft" -> "草稿"
    "archived" -> "已归档"
    "done" -> "已完成"
    "later" -> "稍后"
    else -> state
}

internal fun healthCategoryLabel(category: String?): String = LifeLogCategories.labels[category] ?: "生活"

@Composable
internal fun BufferTheme(content: @Composable () -> Unit) {
    BufferDesignTheme(content)
}
