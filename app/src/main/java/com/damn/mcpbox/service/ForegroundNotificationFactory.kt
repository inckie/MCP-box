package com.damn.mcpbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.damn.mcpbox.R

object ForegroundNotificationFactory {
    const val CHANNEL_ID = "mcp_box_channel"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.foreground_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = context.getString(R.string.foreground_notification_text)
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, contentText: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.foreground_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }
}

