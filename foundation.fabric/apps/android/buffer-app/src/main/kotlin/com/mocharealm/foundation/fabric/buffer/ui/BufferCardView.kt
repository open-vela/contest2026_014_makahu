package com.mocharealm.foundation.fabric.buffer.ui
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
@Composable
internal fun BedtimeCardView(card: BufferCard, onAction: (BufferCard, String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, fontWeight = FontWeight.Bold)
            Text(card.summary)
            card.answer?.takeIf(String::isNotBlank)?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.actions.forEach { action ->
                    Button(onClick = { onAction(card, action) }) { Text(actionLabel(action)) }
                }
            }
        }
    }
}

@Composable
internal fun CardView(
    card: BufferCard,
    onAction: (BufferCard, String) -> Unit,
    onAnswer: (BufferCard, String) -> Unit,
    onResearchNote: (BufferCard, String) -> Unit,
    onExternalLink: (BufferCard, String) -> Unit,
    onTags: (BufferCard, String) -> Unit,
    onAssociateCard: (BufferCard, BufferCard) -> Unit,
    onUnlinkRelatedCard: (BufferCard, BufferCard) -> Unit,
    associationCandidates: List<BufferCard>,
    onQuickAnswer: (BufferCard) -> Unit,
    onHealthSync: (BufferCard) -> Unit,
    detail: Boolean = false,
    showHeader: Boolean = true,
) {
    var answerText by rememberSaveable(card.cardId, card.answer) {
        mutableStateOf(card.answer.orEmpty())
    }
    var researchNoteText by rememberSaveable(card.cardId, card.researchNotes) {
        mutableStateOf("")
    }
    var externalLinkText by rememberSaveable(card.cardId, card.externalLinks.joinToString("\u0000")) {
        mutableStateOf("")
    }
    var tagsText by rememberSaveable(card.cardId, card.tags.joinToString(",")) {
        mutableStateOf(card.tags.joinToString(", "))
    }
    var expanded by rememberSaveable(card.cardId) { mutableStateOf(detail) }
    var relatedSearch by rememberSaveable(card.cardId) { mutableStateOf("") }
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showHeader) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
                Text(cardStateLabel(card.state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(card.summary, style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
            if (!detail) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起详情" else "查看与处理")
            }
            if (expanded) {
                if (!card.classificationConfirmed) {
                    Text(
                        if (card.kind == ClassificationReview.UNCLASSIFIED) {
                            "已拒绝分类 · 原始内容保留，可重新编辑分类"
                        } else {
                            "分类提案 · 可接受、编辑或拒绝，确认前不会写入日历或健康数据"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (card.healthCategory != null) {
                    Text(
                        "生活记录：${healthCategoryLabel(card.healthCategory)} · " +
                            if (HealthConnectExporter.canExport(card.healthCategory)) {
                                if (card.state == "health_synced") "已同步" else "可由手机同步"
                            } else {
                                "仅保存在本机"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.kind in setOf("question_card", "spark_card", "weekly_card")) {
                    val bedtimeSafe = BedtimeDeck.SAFE_TAG in card.tags
                    Button(onClick = {
                        val tags = if (bedtimeSafe) card.tags - BedtimeDeck.SAFE_TAG
                            else card.tags + BedtimeDeck.SAFE_TAG
                        onTags(card, tags.joinToString(","))
                    }) { Text(if (bedtimeSafe) "取消睡前可读" else "标为睡前可读") }
                    Text("睡前只展示短内容；长篇研究、待办和深挖候选留到白天。", style = MaterialTheme.typography.bodySmall)
                }
                if (card.kind == "spark_card") {
                    if (card.tags.isNotEmpty()) {
                        Text("标签：${card.tags.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标签（逗号分隔）") },
                        singleLine = true,
                    )
                    Button(
                        onClick = { onTags(card, tagsText) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存标签") }
                }
                if (card.kind == "question_card") {
                    OutlinedTextField(
                        value = answerText,
                        onValueChange = { answerText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("你的答案（可选）") },
                        minLines = 2,
                    )
                    Button(
                        onClick = { onAnswer(card, answerText) },
                        enabled = answerText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (card.answer.isNullOrBlank()) "保存答案" else "更新答案") }
                    if (card.answer.isNullOrBlank()) {
                        Button(
                            onClick = { onQuickAnswer(card) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        ) { Text("请求短答案") }
                    }
                    if (!card.researchNotes.isNullOrBlank()) {
                        Text("研究笔记", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        Text(
                            card.researchNotes.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (card.externalLinks.isNotEmpty()) {
                        Text("关联链接", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        card.externalLinks.forEach { link ->
                            Text(
                                text = link,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                        }
                                    },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = researchNoteText,
                        onValueChange = { researchNoteText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("追加研究笔记") },
                        minLines = 2,
                    )
                    Button(
                        onClick = {
                            onResearchNote(card, researchNoteText)
                            researchNoteText = ""
                        },
                        enabled = researchNoteText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("追加研究笔记") }
                    OutlinedTextField(
                        value = externalLinkText,
                        onValueChange = { externalLinkText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("关联外部链接或文章") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            onExternalLink(card, externalLinkText)
                            externalLinkText = ""
                        },
                        enabled = externalLinkText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("关联链接") }
                    val relatedCards = associationCandidates.filter { it.cardId in card.relatedCardIds }
                    if (relatedCards.isNotEmpty()) {
                        Text("关联的 Spark / 生活事件", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        relatedCards.forEach { related ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    related.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Button(
                                    onClick = { onUnlinkRelatedCard(card, related) },
                                    contentPadding = ButtonDefaults.ContentPadding,
                                ) { Text("移除", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (associationCandidates.isNotEmpty()) {
                        OutlinedTextField(
                            value = relatedSearch,
                            onValueChange = { relatedSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("关联 Spark 或生活事件") },
                            singleLine = true,
                        )
                        associationCandidates
                            .filter { candidate ->
                                candidate.cardId !in card.relatedCardIds &&
                                    (relatedSearch.isBlank() ||
                                        candidate.title.contains(relatedSearch, ignoreCase = true) ||
                                        candidate.summary.contains(relatedSearch, ignoreCase = true))
                            }
                            .take(6)
                            .forEach { candidate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        candidate.title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Button(
                                        onClick = { onAssociateCard(card, candidate) },
                                        contentPadding = ButtonDefaults.ContentPadding,
                                    ) { Text("关联", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                    }
                }
                if (card.kind == "spark_card" && associationCandidates.isNotEmpty()) {
                    Text("关联旧想法或生活事件", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    val relatedCards = associationCandidates.filter { it.cardId in card.relatedCardIds }
                    relatedCards.forEach { related ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                related.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = { onUnlinkRelatedCard(card, related) },
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) { Text("移除", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    OutlinedTextField(
                        value = relatedSearch,
                        onValueChange = { relatedSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("查找要关联的卡片") },
                        singleLine = true,
                    )
                    associationCandidates
                        .filter { candidate ->
                            candidate.cardId != card.cardId &&
                                candidate.cardId !in card.relatedCardIds &&
                                (relatedSearch.isBlank() ||
                                    candidate.title.contains(relatedSearch, ignoreCase = true) ||
                                    candidate.summary.contains(relatedSearch, ignoreCase = true))
                        }
                        .take(6)
                        .forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    candidate.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Button(
                                    onClick = { onAssociateCard(card, candidate) },
                                    contentPadding = ButtonDefaults.ContentPadding,
                                ) { Text("关联", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.actions.chunked(2).forEach { rowActions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowActions.forEach { action ->
                                if (action == "health_sync") {
                                    Button(
                                        onClick = { onHealthSync(card) },
                                        enabled = card.classificationConfirmed,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                    ) { Text(actionLabel(action), style = MaterialTheme.typography.bodySmall) }
                                } else {
                                    Button(
                                        onClick = { onAction(card, action) },
                                        enabled = action != "calendar" || card.classificationConfirmed,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                    ) { Text(actionLabel(action), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
