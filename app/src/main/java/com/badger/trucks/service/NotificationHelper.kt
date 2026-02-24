package com.badger.trucks.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.badger.trucks.MainActivity

object NotificationHelper {

    // Notification channel IDs — one per event type
    const val CHANNEL_TRUCK_STATUS  = "badger_truck_status"
    const val CHANNEL_DOOR_STATUS   = "badger_door_status"
    const val CHANNEL_PRESHIFT      = "badger_preshift"
    const val CHANNEL_SYSTEM        = "badger_system"
    const val CHANNEL_CHAT          = "badger_chat"
    const val CHANNEL_SERVICE       = "badger_service"   // foreground service (low priority)

    private var notifIdCounter = 2000

    fun createAllChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            NotificationChannel(
                CHANNEL_TRUCK_STATUS,
                "🚚 Truck Status Changes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a truck changes status in Live Movement"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_DOOR_STATUS,
                "🚪 Door Status Changes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a loading door status is updated"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_PRESHIFT,
                "📋 PreShift Changes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when staging door assignments change"
            },
            NotificationChannel(
                CHANNEL_SYSTEM,
                "📢 System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "System and admin announcements"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_CHAT,
                "💬 Chat Mentions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when someone mentions you in chat"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_SERVICE,
                "Badger Live Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live truck status monitoring with TTS announcements"
                setShowBadge(false)
            },
        )

        channels.forEach { nm.createNotificationChannel(it) }
    }

    fun postNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        tag: String? = null
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = when (channelId) {
            CHANNEL_TRUCK_STATUS -> android.R.drawable.ic_dialog_info
            CHANNEL_DOOR_STATUS  -> android.R.drawable.ic_dialog_info
            CHANNEL_PRESHIFT     -> android.R.drawable.ic_dialog_info
            CHANNEL_SYSTEM       -> android.R.drawable.ic_dialog_alert
            CHANNEL_CHAT         -> android.R.drawable.ic_dialog_email
            else                 -> android.R.drawable.ic_dialog_info
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(icon)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(
                when (channelId) {
                    CHANNEL_SERVICE -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()

        val notifId = if (tag != null) tag.hashCode() else notifIdCounter++
        nm.notify(notifId, notification)
    }
}
