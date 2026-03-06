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

    // PTT audio focus mode (used by PushToTalk.kt + NotificationSettingsScreen)
    const val KEY_PTT_AUDIO_MODE  = "ptt_audio_mode"
    const val PTT_AUDIO_FOCUS     = "audio_focus"
    const val PTT_AUDIO_MUTE      = "mute"
    const val PTT_AUDIO_PRIORITY  = "priority"
    const val PTT_AUDIO_LOWER     = "lower"
    const val PTT_AUDIO_OFF       = "off"

    // TTS audio focus mode (used by BadgerService.kt)
    const val KEY_AUDIO_FOCUS       = "audio_focus_mode"
    const val AUDIO_FOCUS_EXCLUSIVE = "exclusive"
    const val AUDIO_FOCUS_TRANSIENT = "transient"
    const val AUDIO_FOCUS_DUCK      = "duck"
    const val AUDIO_FOCUS_OFF       = "off"

    // Volume boost level
    const val KEY_VOLUME_BOOST = "volume_boost"
    const val VOLUME_BOOST_OFF    = "off"
    const val VOLUME_BOOST_LOW    = "low"
    const val VOLUME_BOOST_MEDIUM = "medium"
    const val VOLUME_BOOST_MAX    = "max"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, key: String, default: Boolean = true): Boolean =
        prefs(context).getBoolean(key, default)

    fun set(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    fun getPttAudioMode(context: Context): String =
        prefs(context).getString(KEY_PTT_AUDIO_MODE, PTT_AUDIO_FOCUS) ?: PTT_AUDIO_FOCUS

    fun setPttAudioMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_PTT_AUDIO_MODE, mode).apply()

    fun getString(context: Context, key: String, default: String = ""): String =
        prefs(context).getString(key, default) ?: default

    fun setString(context: Context, key: String, value: String) =
        prefs(context).edit().putString(key, value).apply()

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
