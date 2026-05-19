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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object RemoteLogger {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = "unknown"
    private var deviceName: String = "unknown"
    private var initialized = false
    private var logFile: File? = null

    // Runtime flag — set from NotificationPrefsStore on init and when toggled in DebugScreen
    @Volatile var remoteEnabled: Boolean = false

    data class LogEntry(val level: String, val tag: String, val message: String, val time: String)

    // In-memory ring buffer — last 200 entries
    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_BUFFER = 200
    private const val MAX_FILE_BYTES = 50 * 1024L  // 50 KB cap
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    // Merge file-persisted logs with in-memory buffer so debug screen shows
    // entries from before the last process death.
    fun recentLogs(): List<LogEntry> {
        val persisted = readPersistedLogs()
        val inMemory   = buffer.toList()
        // Deduplicate: skip persisted entries that are already in the in-memory buffer
        val inMemorySet = inMemory.map { "${it.time}|${it.tag}|${it.message}" }.toHashSet()
        val merged = persisted.filter { "${it.time}|${it.tag}|${it.message}" !in inMemorySet } + inMemory
        return merged.takeLast(400)
    }

    fun init(context: Context) {
        deviceId   = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        initialized = true
        logFile = File(context.filesDir, "badger_local_log.txt")
        remoteEnabled = context.getSharedPreferences("badger_notif_prefs", Context.MODE_PRIVATE)
            .getBoolean("remote_logging_enabled", false)
    }

    fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(level, tag, message, timeFmt.format(Date()))
        buffer.addLast(entry)
        while (buffer.size > MAX_BUFFER) buffer.pollFirst()

        Log.d("RemoteLogger", "[$level] $tag: $message")

        // Always persist W and E to local file (survives process death, zero egress)
        if (initialized && (level == "E" || level == "W" || level == "I")) {
            scope.launch { appendToFile(entry) }
        }

        // Supabase: only when explicitly enabled
        if (!initialized || !remoteEnabled) return
        if (level != "E" && level != "W") return

        val dbLevel = when (level) { "E" -> "ERROR"; "W" -> "WARN"; else -> level }
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

    private fun appendToFile(entry: LogEntry) {
        val file = logFile ?: return
        try {
            // Rotate: if file exceeds cap, keep only the last half
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
            }
            file.appendText("[${entry.level}] ${entry.time} ${entry.tag}: ${entry.message}\n")
        } catch (e: Exception) {
            Log.w("RemoteLogger", "File log write failed: ${e.message}")
        }
    }

    private fun readPersistedLogs(): List<LogEntry> {
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().takeLast(300).mapNotNull { line -> parseFileLine(line) }
        } catch (e: Exception) { emptyList() }
    }

    // Format: "[W] MM-dd HH:mm:ss TAG: message"
    private fun parseFileLine(line: String): LogEntry? {
        val m = Regex("""^\[([WEID])\] (\d{2}-\d{2} \d{2}:\d{2}:\d{2}) ([^:]+): (.+)$""").matchEntire(line.trim())
            ?: return null
        return LogEntry(m.groupValues[1], m.groupValues[3].trim(), m.groupValues[4], m.groupValues[2])
    }

    fun clearLocalLog() {
        try { logFile?.delete() } catch (_: Exception) {}
    }

    fun i(tag: String, message: String) = log("I", tag, message)
    fun e(tag: String, message: String) = log("E", tag, message)
    fun w(tag: String, message: String) = log("W", tag, message)
    fun d(tag: String, message: String) = log("D", tag, message)
}
