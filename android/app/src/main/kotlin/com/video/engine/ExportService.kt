package com.video.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service to keep video export alive when app is backgrounded.
 * 
 * Why needed:
 * - When app goes to background, Android may kill the process or limit resources
 * - Foreground service prevents low-memory kills by showing persistent notification
 * - Ensures FFmpeg encoder can complete even if user locks screen or switches apps
 * - Foreground execution avoids export interruptions on aggressive devices
 */
class ExportService : Service() {
    companion object {
        private const val TAG = "[ExportService]"
        private const val EXPORT_CHANNEL_ID = "video_export_fg"
        private const val EXPORT_NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Export service started (foreground)")
        
        // Start in foreground with persistent notification
        val notification = NotificationCompat.Builder(this, EXPORT_CHANNEL_ID)
            .setContentTitle("Video Export")
            .setContentText("Export in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        startForeground(EXPORT_NOTIFICATION_ID, notification)
        
        // Keep service running until export completes
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXPORT_CHANNEL_ID,
                "Video Export Background",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps export running when app is backgrounded"
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
