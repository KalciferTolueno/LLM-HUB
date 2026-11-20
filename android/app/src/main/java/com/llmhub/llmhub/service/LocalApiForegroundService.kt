package com.llmhub.llmhub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.R

/**
 * Keeps the local OpenAI-compatible HTTP server alive while the app is backgrounded or the screen is off.
 * Without a foreground service, Android may freeze or kill the process and the LAN API stops being reachable.
 */
class LocalApiForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LlmHubApplication
        val server = app.localApiServer

        val ip = server.getLocalIpAddress().orEmpty()
        val notification = buildNotification(ip)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (!server.isRunning()) {
                server.start()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start LlmApiServer", e)
            stopForegroundGracefully()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            (application as LlmHubApplication).localApiServer.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "stop server on destroy", e)
        }
        stopForegroundGracefully()
        super.onDestroy()
    }

    private fun stopForegroundGracefully() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_api_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.local_api_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String): Notification {
        val title = getString(R.string.local_api_notification_title)
        val text = if (ip.isNotEmpty()) {
            getString(R.string.local_api_notification_text, ip)
        } else {
            getString(R.string.local_api_notification_text_no_ip)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "LocalApiFgService"
        private const val CHANNEL_ID = "local_api_foreground"
        private const val NOTIFICATION_ID = 27080

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, LocalApiForegroundService::class.java)
            )
        }
    }
}
