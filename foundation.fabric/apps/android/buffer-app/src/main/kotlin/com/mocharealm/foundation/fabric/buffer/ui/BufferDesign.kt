package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Official Material Symbols Rounded; provenance and license are in third_party/material-symbols. */
import com.mocharealm.foundation.fabric.buffer.R
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
internal object BufferIcons {
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_settings)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_delete)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_close)
    val Expand: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_expand_more)
    val Mic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_mic)
    val Write: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_edit)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_search)
    val Leaf: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_eco)
    val Back: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_arrow_back)
    val Next: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ms_chevron_right)
}

@Composable
internal fun BufferDesignTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB8D1BE), onPrimary = Color(0xFF22372B),
        primaryContainer = Color(0xFF2C4132), onPrimaryContainer = Color(0xFFD5E8D8),
        secondary = Color(0xFFBBCBBE), onSecondary = Color(0xFF26352B),
        secondaryContainer = Color(0xFF34483B), onSecondaryContainer = Color(0xFFDCE9DF),
        tertiary = Color(0xFFDBC5A0), tertiaryContainer = Color(0xFF4C412D), onTertiaryContainer = Color(0xFFF6E1BC),
        background = Color(0xFF171C18), onBackground = Color(0xFFE3E7DF),
        surface = Color(0xFF171C18), onSurface = Color(0xFFE3E7DF),
        surfaceContainerLow = Color(0xFF202720), surfaceContainer = Color(0xFF262E27),
        surfaceVariant = Color(0xFF303A32), onSurfaceVariant = Color(0xFFADB8AD),
        outline = Color(0xFF7D8A7E), outlineVariant = Color(0xFF3E4940),
    ) else lightColorScheme(
        primary = Color(0xFF3F614D), onPrimary = Color.White,
        primaryContainer = Color(0xFFE4EDDF), onPrimaryContainer = Color(0xFF263E2E),
        secondary = Color(0xFF536A57), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE0EADC), onSecondaryContainer = Color(0xFF304B39),
        tertiary = Color(0xFF79633F), tertiaryContainer = Color(0xFFF2E7D4), onTertiaryContainer = Color(0xFF514225),
        background = Color(0xFFFAF9F4), onBackground = Color(0xFF263129),
        surface = Color(0xFFFAF9F4), onSurface = Color(0xFF263129),
        surfaceContainerLow = Color(0xFFF0F2EA), surfaceContainer = Color(0xFFECEFE6),
        surfaceVariant = Color(0xFFE8ECE2), onSurfaceVariant = Color(0xFF687266),
        outline = Color(0xFF818D7D), outlineVariant = Color(0xFFDCE2D6),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
internal fun BufferActionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(BufferIcons.Next, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BufferEmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Icon(BufferIcons.Leaf, null, modifier = Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun BufferCardPreview(card: BufferCard, state: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (card.kind == "question_card") BufferIcons.Search else BufferIcons.Leaf, null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (!card.classificationConfirmed && card.state != "pending_transcription") Text("· 待确认分类", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (card.state == "pending_transcription") "一段语音记录" else card.title,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.summary != card.title) Text(if (card.state == "pending_transcription") "声音已经收好，等待整理成文字。" else card.summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun BufferWeekPreview(summary: BufferWeeklySummary, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("最近七天", style = MaterialTheme.typography.titleLarge) },
            supportingContent = { Text("回看想法、生活与未完成的事") },
            leadingContent = { Icon(bufferDestinations[2].icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(BufferIcons.Next, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(summary.sparks to "灵感", summary.questions to "未解问题", summary.tasksCompleted to "完成事项").forEach { (count, label) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(count.toString(), style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
