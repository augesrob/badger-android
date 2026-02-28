package com.badger.trucks.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.badger.trucks.BadgerApp
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object RemoteLogger {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = "unknown"
    private var deviceName: String = "unknown"
    private var initialized = false

    fun init(context: Context) {
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        initialized = true
    }

    fun log(level: String, tag: String, message: String) {
        if (!initialized) return
        Log.d("RemoteLogger", "[$level] $tag: $message")
        scope.launch {
            try {
                BadgerApp.supabase.postgrest["debug_logs"].insert(
                    JsonObject(mapOf(
                        "device_id"   to JsonPrimitive(deviceId),
                        "device_name" to JsonPrimitive(deviceName),
                        "level"       to JsonPrimitive(level),
                        "tag"         to JsonPrimitive(tag),
                        "message"     to JsonPrimitive(message)
                    ))
                )
            } catch (e: Exception) {
                Log.w("RemoteLogger", "Failed to send log: ${e.message}")
            }
        }
    }

    fun i(tag: String, message: String) = log("INFO",  tag, message)
    fun e(tag: String, message: String) = log("ERROR", tag, message)
    fun w(tag: String, message: String) = log("WARN",  tag, message)
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
}
