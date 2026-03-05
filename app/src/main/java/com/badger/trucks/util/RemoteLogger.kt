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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object RemoteLogger {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = "unknown"
    private var deviceName: String = "unknown"
    private var initialized = false

    data class LogEntry(val level: String, val tag: String, val message: String, val time: String)

    // In-memory ring buffer — last 200 entries, visible in DebugScreen without network
    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_BUFFER = 200
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun recentLogs(): List<LogEntry> = buffer.toList()

    fun init(context: Context) {
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        initialized = true
    }

    fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(level, tag, message, timeFmt.format(Date()))
        buffer.addLast(entry)
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()

        Log.d("RemoteLogger", "[$level] $tag: $message")
        if (!initialized) return
        val dbLevel = when (level) { "I" -> "INFO"; "E" -> "ERROR"; "W" -> "WARN"; "D" -> "DEBUG"; else -> level }
        scope.launch {
            try {
                BadgerApp.supabase.postgrest["debug_logs"].insert(
                    JsonObject(mapOf(
                        "device_id"   to JsonPrimitive(deviceId),
                        "device_name" to JsonPrimitive(deviceName),
                        "level"       to JsonPrimitive(dbLevel),
                        "tag"         to JsonPrimitive(tag),
                        "message"     to JsonPrimitive(message)
                    ))
                )
            } catch (e: Exception) {
                Log.w("RemoteLogger", "Failed to send log: ${e.message}")
            }
        }
    }

    fun i(tag: String, message: String) = log("I", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun d(tag: String, message: String) = log("D", tag, message)
}
