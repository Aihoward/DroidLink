package com.droidlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "DroidLinkCapture"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_READY =
            "com.droidlink.app.MEDIA_PROJECTION_SERVICE_READY"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification =
            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("DroidLink")
                .setContentText("Screen sharing is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        Log.d(
            "DroidLink",
            "MediaProjection foreground service READY"
        )

        sendBroadcast(
            Intent(ACTION_READY)
        )

        return START_STICKY
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "DroidLink Screen Capture",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}