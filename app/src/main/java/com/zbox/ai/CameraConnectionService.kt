package com.zbox.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/** Owns only power/foreground lifetime; never opens or reconnects a camera by itself. */
class CameraConnectionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("camera_connection", "相机持续连接", NotificationManager.IMPORTANCE_LOW)
        )
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(30, Notification.Builder(this, "camera_connection")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Z30 已连接")
            .setContentText("保持相机通信；可在应用或相机上手动断开")
            .setContentIntent(intent).setOngoing(true).build())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zbox:camera-connection")
            .apply { setReferenceCounted(false); acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
