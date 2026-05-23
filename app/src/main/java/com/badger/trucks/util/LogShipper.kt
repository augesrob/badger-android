package com.badger.trucks.util

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.badger.trucks.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ships the local log file to the private badger-logs GitHub repo.
 * Called from the 15-min keepalive alarm so every device has a live
 * rolling log visible without any Supabase egress.
 *
 * Repo layout:
 *   augesrob/badger-logs/android/{sanitized-device-name}.txt
 *   augesrob/badger-logs/website/vercel.txt  (written by the website)
 */
object LogShipper {
    private const val OWNER = "augesrob"
    private const val REPO  = "badger-logs"
    private const val TAG   = "LogShipper"
    private const val MAX_SHIP_LINES = 500

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Call this from the keepalive handler (already on a background thread). */
    suspend fun ship(context: Context) = withContext(Dispatchers.IO) {
        val token = BuildConfig.GITHUB_TOKEN
        if (token.isBlank() || token == "null") {
            Log.w(TAG, "No GITHUB_TOKEN configured -- skipping ship")
            return@withContext
        }

        val logFile = File(context.filesDir, "badger_local_log.txt")
        if (!logFile.exists() || logFile.length() == 0L) return@withContext

        val deviceSlug = "${Build.MANUFACTURER}_${Build.MODEL}"
            .replace(" ", "_").replace("/", "_").lowercase()
        val path = "android/$deviceSlug.txt"

        try {
            // Read last N lines so the file stays manageable
            val lines = logFile.readLines().takeLast(MAX_SHIP_LINES)
            val header = "=== ${Build.MANUFACTURER} ${Build.MODEL} | shipped ${dateFmt.format(Date())} ===\n"
            val content = header + lines.joinToString("\n")
            val encoded = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)

            // Get current SHA (needed to update an existing file)
            val sha = getFileSha(path, token)

            // PUT the new content
            val body = JSONObject().apply {
                put("message", "log: ${Build.MODEL} ${dateFmt.format(Date())}")
                put("content", encoded)
                if (sha != null) put("sha", sha)
            }

            val url = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..201) {
                RemoteLogger.i(TAG, "Shipped ${lines.size} log lines to badger-logs/$path")
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                RemoteLogger.w(TAG, "Ship failed HTTP $code: ${err.take(120)}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "LogShipper.ship error: ${e.message}")
        }
    }

    private fun getFileSha(path: String, token: String): String? {
        return try {
            val url = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                json.optString("sha").ifBlank { null }
            } else { conn.disconnect(); null }
        } catch (e: Exception) { null }
    }
}
