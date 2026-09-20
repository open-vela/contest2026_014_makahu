package com.mocharealm.foundation.fabric.buffer
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal enum class QuickCaptureTarget(val action: String, val requestCode: Int) {
    TEXT("com.mocharealm.foundation.fabric.buffer.CAPTURE_TEXT", 1410),
    VOICE("com.mocharealm.foundation.fabric.buffer.CAPTURE_VOICE", 1411);

    companion object {
        fun fromAction(action: String?): QuickCaptureTarget? = entries.firstOrNull { it.action == action }
    }
}

internal object QuickCaptureEntry {
    fun pendingIntent(context: Context, target: QuickCaptureTarget?): PendingIntent =
        PendingIntent.getActivity(
            context, target?.requestCode ?: 1412,
            Intent(context, MainActivity::class.java)
                .setAction(target?.action ?: Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
