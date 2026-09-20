package com.mocharealm.foundation.fabric.buffer
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

class BufferCaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.buffer_capture_widget)
            views.setOnClickPendingIntent(R.id.capture_text,
                QuickCaptureEntry.pendingIntent(context, QuickCaptureTarget.TEXT))
            views.setOnClickPendingIntent(R.id.capture_voice,
                QuickCaptureEntry.pendingIntent(context, QuickCaptureTarget.VOICE))
            manager.updateAppWidget(id, views)
        }
    }
}
