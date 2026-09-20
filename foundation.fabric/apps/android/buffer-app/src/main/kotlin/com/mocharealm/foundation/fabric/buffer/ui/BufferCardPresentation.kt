package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Render the structure saved during classification. Opening a card never invokes the model. */
import com.mocharealm.foundation.fabric.buffer.BufferApplication
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
@Composable
internal fun BufferCardPresentation(card: BufferCard, onOpen: (String) -> Unit) {
    val repository = (LocalContext.current.applicationContext as BufferApplication).repository
    val view = remember(card) { runCatching {
        repository.cardPresentation(card.cardId)?.let(::JSONObject)?.also(BufferCaptureAgent::validatePresentation)
    }.getOrNull() }
    if (view != null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("整理要点 · AI 生成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val blocks = view.getJSONArray("blocks")
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.getString("title"), style = MaterialTheme.typography.titleMedium)
                        if (block.getString("type") == "paragraph") Text(block.getString("text"), style = MaterialTheme.typography.bodyLarge)
                        else {
                            val items = block.getJSONArray("items")
                            for (j in 0 until items.length()) Text("• ${items.getString(j)}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
    val related = remember(card.relatedCardIds) { card.relatedCardIds.mapNotNull(repository::findCard) }
    if (related.isNotEmpty()) {
        Text("关联旧记录", style = MaterialTheme.typography.titleMedium)
        related.forEach { old ->
            TextButton(onClick = { onOpen(old.cardId) }, modifier = Modifier.fillMaxWidth()) { Text(old.title) }
        }
    }
}
