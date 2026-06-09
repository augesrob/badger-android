package com.badger.wear.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
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

object WearLogger {
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var supabase: SupabaseClient? = null
    private var deviceId   = "wear-unknown"
    private var deviceName = "Galaxy Watch"
    private var logFile: File? = null
    private val timeFmt    = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val buffer     = ConcurrentLinkedDeque<String>()
    private const val MAX  = 300
    private const val MAX_FILE = 50 * 1024L

    fun init(context: Context, client: SupabaseClient) {
        supabase   = client
        deviceId   = "wear-" + (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
        deviceName = "Watch ${Build.MODEL}"
        logFile    = File(context.filesDir, "badger_wear_log.txt")
        i("WearLogger", "Initialized — device=$deviceId model=$deviceName")
    }

    fun log(level: String, tag: String, message: String) {
        val time = timeFmt.format(Date())
        val line = "[$level] $time $tag: $message"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.pollFirst()
        Log.println(when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; "I" -> Log.INFO; else -> Log.DEBUG }, tag, message)
        scope.launch { appendToFile(line) }
        if (level == "E" || level == "W" || level == "I") {
            scope.launch { ship(level, tag, message) }
        }
    }

    private suspend fun ship(level: String, tag: String, message: String) {
        val client = supabase ?: return
        try {
            client.from("debug_logs").insert(JsonObject(mapOf(
                "device_id"   to JsonPrimitive(deviceId),
                "device_name" to JsonPrimitive(deviceName),
                "level"       to JsonPrimitive(when (level) { "E" -> "ERROR"; "W" -> "WARN"; else -> "INFO" }),
                "tag"         to JsonPrimitive(tag),
                "message"     to JsonPrimitive(message)
            )))
        } catch (e: Exception) {
            Log.w("WearLogger", "Ship failed: ${e.message}")
        }
    }

    private fun appendToFile(line: String) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_FILE) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
            }
            f.appendText("$line\n")
        } catch (_: Exception) {}
    }

    fun recentLogs(): List<String> = buffer.toList().takeLast(MAX)

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun d(tag: String, msg: String) = log("D", tag, msg)
}
