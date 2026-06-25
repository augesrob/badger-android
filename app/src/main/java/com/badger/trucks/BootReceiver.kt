package com.badger.trucks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.badger.trucks.service.BadgerService

/**
 * Auto-starts BadgerService on device boot so the phone gets live updates
 * and TTS announcements even if the user never manually opens the app
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            val serviceIntent = Intent(context, BadgerService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                context.startService(serviceIntent)
            }
        }
    }
}
