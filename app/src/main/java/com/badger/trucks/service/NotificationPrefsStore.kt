package com.badger.trucks.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Local per-device notification preference storage.
 * Mirrors the notification_preferences table in Supabase but stored on-device.
 * Each toggle maps to a SharedPreferences boolean.
 */
object NotificationPrefsStore {

    private const val PREFS_NAME = "badger_notif_prefs"

    // Event type keys
    const val KEY_TRUCK_STATUS  = "notify_truck_status"
    const val KEY_DOOR_STATUS   = "notify_door_status"
    const val KEY_CHAT_MENTION  = "notify_chat_mention"
    const val KEY_PRESHIFT      = "notify_preshift"
    const val KEY_SYSTEM        = "notify_system"

    // Channel keys
    const val KEY_CHANNEL_APP   = "channel_app"   // heads-up push notification
    const val KEY_CHANNEL_TTS   = "channel_tts"   // text-to-speech announcement

    // Features
    const val KEY_HOTWORD       = "feature_hotword" // always-on "Badger" wake word

    // UI button visibility
    const val KEY_SHOW_PTT      = "ui_show_ptt"      // Push-to-talk radio button
    const val KEY_SHOW_MIC      = "ui_show_mic"      // Voice command mic button
    const val KEY_SHOW_FIXALL   = "ui_show_fixall"   // Fix All wrench button

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, key: String, default: Boolean = true): Boolean =
        prefs(context).getBoolean(key, default)

    fun set(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    fun getAll(context: Context): Map<String, Boolean> {
        val p = prefs(context)
        return mapOf(
            KEY_TRUCK_STATUS to p.getBoolean(KEY_TRUCK_STATUS, true),
            KEY_DOOR_STATUS  to p.getBoolean(KEY_DOOR_STATUS,  true),
            KEY_CHAT_MENTION to p.getBoolean(KEY_CHAT_MENTION, true),
            KEY_PRESHIFT     to p.getBoolean(KEY_PRESHIFT,     true),
            KEY_SYSTEM       to p.getBoolean(KEY_SYSTEM,       true),
            KEY_CHANNEL_APP  to p.getBoolean(KEY_CHANNEL_APP,  true),
            KEY_CHANNEL_TTS  to p.getBoolean(KEY_CHANNEL_TTS,  true),
            KEY_HOTWORD      to p.getBoolean(KEY_HOTWORD,      true),
            KEY_SHOW_PTT     to p.getBoolean(KEY_SHOW_PTT,     true),
            KEY_SHOW_MIC     to p.getBoolean(KEY_SHOW_MIC,     true),
            KEY_SHOW_FIXALL  to p.getBoolean(KEY_SHOW_FIXALL,  true),
        )
    }

    fun setAll(context: Context, prefs: Map<String, Boolean>) {
        val editor = prefs(context).edit()
        prefs.forEach { (k, v) -> editor.putBoolean(k, v) }
        editor.apply()
    }
}
