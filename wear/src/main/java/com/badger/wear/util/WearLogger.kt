package com.badger.wear.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object WearLogger {
    private var context: Context? = null
    private var deviceId   = "wear-unknown"
    private var deviceName = "Galaxy Watch"
    private var logFile: File? = null
    private val timeFmt    = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val buffer     = ConcurrentLinkedDeque<String>()
    private const val MAX  = 300
    private const val MAX_FILE = 50 * 1024L

    fun init(ctx: Context) {
        context    = ctx.applicationContext
        deviceId   = "wear-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
        deviceName = "Watch ${Build.MODEL}"
        logFile    = File(ctx.filesDir, "badger_wear_log.txt")
        i("WearLogger", "Initialized — device=$deviceId model=$deviceName v${com.badger.wear.BuildConfig.VERSION_CODE}")
    }

    fun log(level: String, tag: String, message: String) {
        val time = timeFmt.format(Date())
        val line = "[$level] $time $tag: $message"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.pollFirst()
        Log.println(when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; "I" -> Log.INFO; else -> Log.DEBUG }, tag, message)
        appendToFile(line)
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
    fun getContext(): Context? = context

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun d(tag: String, msg: String) = log("D", tag, msg)
}
