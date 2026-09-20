package com.mocharealm.foundation.fabric.buffer
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class BufferBridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Buffer")
            .setContentText("正在等待 Gemini S1 同步")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(QuickCaptureEntry.pendingIntent(this, null))
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_text),
                QuickCaptureEntry.pendingIntent(this, QuickCaptureTarget.TEXT)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_voice),
                QuickCaptureEntry.pendingIntent(this, QuickCaptureTarget.VOICE)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        (application as BufferApplication).bridge.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        (application as BufferApplication).bridge.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Buffer 设备桥接",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "buffer-bridge"
        private const val NOTIFICATION_ID = 1401
    }
}
