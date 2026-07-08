package com.badger.wear.updater

import android.content.Context
import android.content.Intent
import com.badger.wear.util.WearLogger

/**
 * Holds the PackageInstaller confirmation intent so a missed install dialog can be
 * re-launched later (Update chip tap, service start) instead of dead-ending.
 * In-memory only: if the process dies, the next update check re-downloads and
 * commits a fresh session, which produces a new dialog anyway.
 */
object PendingInstallStore {
    @Volatile var confirmIntent: Intent? = null

    /** Re-launch the stored confirmation dialog. Returns true if one was launched. */
    fun relaunch(context: Context): Boolean {
        val intent = confirmIntent ?: return false
        return try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            WearLogger.i("PendingInstall", "Re-launched install confirmation dialog")
            true
        } catch (e: Exception) {
            WearLogger.e("PendingInstall", "Re-launch failed: ${e.message}")
            confirmIntent = null
            false
        }
    }
}
