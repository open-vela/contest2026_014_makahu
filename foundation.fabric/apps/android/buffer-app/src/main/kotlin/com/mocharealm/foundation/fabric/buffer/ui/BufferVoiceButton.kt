package com.mocharealm.foundation.fabric.buffer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceButton(
    recording: Boolean,
    onStartRecording: () -> Boolean,
    onStopRecording: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            .semantics {
                role = Role.Button
                stateDescription = if (recording) "正在录音" else "未录音"
                onClick(label = if (recording) "保存录音" else "开始录音") {
                    if (recording) { onStopRecording(); true } else onStartRecording()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (onStartRecording()) {
                            try {
                                tryAwaitRelease()
                            } finally {
                                onStopRecording()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(BufferIcons.Mic, null, tint = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)
            Text(if (recording) "松开，收好这段声音" else "按住，说点什么",
                color = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}
