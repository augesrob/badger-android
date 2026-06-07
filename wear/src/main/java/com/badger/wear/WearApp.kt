package com.badger.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class WearApp : Application() {

    companion object {
        const val CHANNEL_SERVICE  = "badger_wear_service"
        const val CHANNEL_ALERTS   = "badger_wear_alerts"
        lateinit var instance: WearApp private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Badger Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Badger watch monitoring service"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Badger Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Truck and door change alerts"
                enableVibration(true)
            }
        )
    }
}
