package com.llmhub.llmhub.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.llmhub.llmhub.R

/**
 * Persistent system notification while an LLM is loaded in memory (any load path).
 * Separate from [com.llmhub.llmhub.service.LocalApiForegroundService], which only covers the HTTP server.
 */
object ModelRunningNotifier {

    const val NOTIFICATION_ID: Int = 27082
    private const val CHANNEL_ID = "model_running"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.model_running_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.model_running_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, modelName: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.model_running_notification_title))
            .setContentText(appContext.getString(R.string.model_running_notification_text, modelName))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
