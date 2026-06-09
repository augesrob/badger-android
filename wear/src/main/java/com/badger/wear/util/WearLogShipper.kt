package com.badger.wear.util

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.badger.wear.BuildConfig
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
 * Ships the local wear log file to augesrob/badger-logs/android/{device}.txt
 * Same pattern as the phone's LogShipper — no Supabase egress.
 */
object WearLogShipper {
    private const val OWNER = "augesrob"
    private const val REPO  = "badger-logs"
    private const val TAG   = "WearLogShipper"
    private const val MAX_LINES = 500

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun ship(context: Context) = withContext(Dispatchers.IO) {
        val token = BuildConfig.GITHUB_TOKEN
        if (token.isBlank() || token == "null") {
            Log.w(TAG, "No GITHUB_TOKEN — skipping ship")
            return@withContext
        }

        val logFile = File(context.filesDir, "badger_wear_log.txt")
        if (!logFile.exists() || logFile.length() == 0L) return@withContext

        val slug = "watch_${Build.MODEL}".replace(" ", "_").replace("/", "_").lowercase()
        val path = "android/$slug.txt"

        try {
            val lines   = logFile.readLines().takeLast(MAX_LINES)
            val header  = "=== ${Build.MODEL} (Wear OS) | v${BuildConfig.VERSION_CODE} | shipped ${dateFmt.format(Date())} ===\n"
            val content = header + lines.joinToString("\n")
            val encoded = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
            val sha     = getFileSha(path, token)

            val body = JSONObject().apply {
                put("message", "wear-log: ${Build.MODEL} ${dateFmt.format(Date())}")
                put("content", encoded)
                if (sha != null) put("sha", sha)
            }

            val url  = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout    = 15_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..201) {
                WearLogger.i(TAG, "Shipped ${lines.size} lines to $path")
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
                WearLogger.w(TAG, "Ship failed HTTP $code: ${err.take(120)}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Ship error: ${e.message}")
        }
    }

    private fun getFileSha(path: String, token: String): String? {
        return try {
            val url  = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8_000
                readTimeout    = 8_000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                json.optString("sha").ifBlank { null }
            } else { conn.disconnect(); null }
        } catch (_: Exception) { null }
    }
}
