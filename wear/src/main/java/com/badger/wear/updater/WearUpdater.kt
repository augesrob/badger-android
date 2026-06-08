package com.badger.wear.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Same GitHub releases endpoint — filters for wear APK asset instead of phone APK
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/augesrob/badger-android/releases?per_page=50"
// Website version endpoint — returns wear version too
private const val VERSION_CHECK_URL = "https://badger.augesrob.net/api/version-wear"

data class WearUpdateInfo(
    val latestVersion: Int,
    val tagName: String,
    val downloadUrl: String,
)

object WearUpdater {

    private val http = HttpClient(OkHttp) {
        engine { config { followRedirects(true) } }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(currentVersionCode: Int): WearUpdateInfo? = withContext(Dispatchers.IO) {
        // Try website first, fall back to GitHub direct
        tryWebsiteCheck(currentVersionCode) ?: tryGithubDirectCheck(currentVersionCode)
    }

    private suspend fun tryWebsiteCheck(currentVersionCode: Int): WearUpdateInfo? {
        return try {
            val body = http.get(VERSION_CHECK_URL) {
                header("User-Agent", "BadgerWear")
            }.bodyAsText()
            if (!body.trimStart().startsWith("{")) return null
            val obj = json.parseToJsonElement(body).jsonObject
            val versionCode = obj["versionCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val tagName = obj["tagName"]?.jsonPrimitive?.content ?: return null
            val downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.content ?: return null
            if (versionCode <= currentVersionCode) return null
            Log.i("WearUpdater", "Update found via website: $tagName")
            WearUpdateInfo(versionCode, tagName, downloadUrl)
        } catch (e: Exception) {
            Log.w("WearUpdater", "Website check failed: ${e.message}")
            null
        }
    }

    private suspend fun tryGithubDirectCheck(currentVersionCode: Int): WearUpdateInfo? {
        return try {
            Log.i("WearUpdater", "Checking GitHub releases (current v$currentVersionCode)")
            val body = http.get(GITHUB_RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "BadgerWear")
            }.bodyAsText()

            val releases = json.parseToJsonElement(body).jsonArray
            val latest = releases
                .map { it.jsonObject }
                .filter { it["tag_name"]?.jsonPrimitive?.content?.startsWith("access-v") == true }
                .maxByOrNull {
                    it["tag_name"]?.jsonPrimitive?.content
                        ?.removePrefix("access-v")?.toIntOrNull() ?: 0
                } ?: return null

            val tagName = latest["tag_name"]?.jsonPrimitive?.content ?: return null
            val latestCode = tagName.removePrefix("access-v").toIntOrNull() ?: return null
            if (latestCode <= currentVersionCode) {
                Log.i("WearUpdater", "Already up to date (v$currentVersionCode)")
                return null
            }

            // Find the wear APK asset specifically
            val wearApk = latest["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull {
                    val name = it["name"]?.jsonPrimitive?.content ?: ""
                    name.contains("wear") && name.endsWith(".apk")
                } ?: return null

            val downloadUrl = wearApk["browser_download_url"]?.jsonPrimitive?.content ?: return null
            Log.i("WearUpdater", "Update found: $tagName -> ${wearApk["name"]?.jsonPrimitive?.content}")
            WearUpdateInfo(latestCode, tagName, downloadUrl)
        } catch (e: Exception) {
            Log.w("WearUpdater", "GitHub check failed: ${e.message}")
            null
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        info: WearUpdateInfo,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress("Downloading ${info.tagName}...")
            Log.i("WearUpdater", "Downloading ${info.downloadUrl}")
            val response = http.get(info.downloadUrl) { header("User-Agent", "BadgerWear") }
            val bytes = response.readBytes()
            val fileName = "badger-wear-${info.tagName}.apk"
            val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
            file.writeBytes(bytes)
            Log.i("WearUpdater", "Downloaded ${bytes.size} bytes -> $fileName")
            onProgress("Installing...")
            withContext(Dispatchers.Main) { installApk(context, file) }
        } catch (e: Exception) {
            Log.e("WearUpdater", "Download/install failed: ${e.message}")
            onProgress("Update failed: ${e.message}")
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }
}
