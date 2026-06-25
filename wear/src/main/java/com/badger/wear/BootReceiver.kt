package com.badger.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.badger.wear.service.WearService

/**
 * Auto-starts WearService on device boot so the watch gets notifications
 * even if the user never manually opens the app
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            val serviceIntent = Intent(context, WearService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                context.startService(serviceIntent)
            }
        }
    }
}
