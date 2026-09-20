package com.mocharealm.foundation.fabric.buffer.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

import com.mocharealm.foundation.fabric.buffer.R
internal data class BufferDestination(
    val id: String,
    val label: String,
    val title: String,
    @param:DrawableRes val iconResource: Int,
    @param:DrawableRes val selectedIconResource: Int = iconResource,
) {
    val icon: ImageVector
        @Composable get() = ImageVector.vectorResource(iconResource)
    val selectedIcon: ImageVector
        @Composable get() = ImageVector.vectorResource(selectedIconResource)
}

internal val bufferDestinations = listOf(
    BufferDestination("capture", "此刻", "此刻", R.drawable.ms_edit_note, R.drawable.ms_edit_note_filled),
    BufferDestination("library", "收藏", "收藏", R.drawable.ms_bookmarks, R.drawable.ms_bookmarks_filled),
    BufferDestination("review", "回顾", "回顾", R.drawable.ms_history, R.drawable.ms_history_filled),
    BufferDestination("devices", "设备", "我的设备", R.drawable.ms_devices),
    BufferDestination("settings", "设置", "设置", R.drawable.ms_settings),
)
