package com.badger.wear.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.badger.wear.util.WearLogger

/**
 * Receives PackageInstaller session results for self-updates.
 * Critical piece: when the system replies STATUS_PENDING_USER_ACTION we must
 * launch the confirmation intent ourselves -- otherwise the install session
 * silently stalls forever and the update never applies.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    WearLogger.e("InstallReceiver", "PENDING_USER_ACTION without confirm intent")
                    return
                }
                // Keep the intent so a missed/dismissed dialog can be re-launched from
                // the Update chip or on service start instead of silently dead-ending
                PendingInstallStore.confirmIntent = confirm
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                    WearLogger.i("InstallReceiver", "Launched install confirmation dialog")
                } catch (e: Exception) {
                    WearLogger.e("InstallReceiver", "Failed to launch confirm: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                PendingInstallStore.confirmIntent = null
                WearLogger.i("InstallReceiver", "Update installed successfully")
            }
            else -> {
                PendingInstallStore.confirmIntent = null
                WearLogger.w("InstallReceiver", "Install status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
