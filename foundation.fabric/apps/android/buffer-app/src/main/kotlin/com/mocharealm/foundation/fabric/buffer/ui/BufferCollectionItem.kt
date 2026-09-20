package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BufferCollectionItem(
    card: BufferCard, state: String, selected: Boolean, selectionMode: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    val edit by rememberUpdatedState(onEdit)
    val delete by rememberUpdatedState(onDelete)
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        val direction = swipe.currentValue
        if (direction != SwipeToDismissBoxValue.Settled) {
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
            if (direction == SwipeToDismissBoxValue.StartToEnd) edit() else delete()
        }
    }
    SwipeToDismissBox(
        state = swipe, enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
        backgroundContent = {
            val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Surface(color = if (deleting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start) {
                    Icon(if (deleting) BufferIcons.Delete else BufferIcons.Write, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (deleting) "删除" else "编辑", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            overlineContent = { Text(if (card.classificationConfirmed) state else "$state · 待确认分类") },
            supportingContent = { if (card.summary != card.title) Text(card.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                if (selectionMode) Checkbox(checked = selected, onCheckedChange = null)
                else Icon(if (card.kind == "question_card") BufferIcons.Search else BufferIcons.Leaf, null,
                    tint = MaterialTheme.colorScheme.primary)
            },
            colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = "选择收藏", role = if (selectionMode) Role.Checkbox else Role.Button)
                .semantics {
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction("编辑") { onEdit(); true },
                        CustomAccessibilityAction("删除") { onDelete(); true },
                    )
                },
        )
    }
}
