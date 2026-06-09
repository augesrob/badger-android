package com.badger.wear.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.badger.wear.util.WearLogger
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

private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/augesrob/badger-android/releases?per_page=50"
private const val VERSION_CHECK_URL   = "https://badger.augesrob.net/api/version-wear"

data class WearUpdateInfo(
    val latestVersion: Int,
    val tagName: String,
    val downloadUrl: String,
)

object WearUpdater {

    private val http = HttpClient(OkHttp) { engine { config { followRedirects(true) } } }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(currentVersionCode: Int): WearUpdateInfo? = withContext(Dispatchers.IO) {
        WearLogger.i("WearUpdater", "Checking for update (current v$currentVersionCode)")
        tryWebsiteCheck(currentVersionCode) ?: tryGithubDirectCheck(currentVersionCode)
    }

    private suspend fun tryWebsiteCheck(currentVersionCode: Int): WearUpdateInfo? {
        return try {
            val body = http.get(VERSION_CHECK_URL) { header("User-Agent", "BadgerWear") }.bodyAsText()
            if (!body.trimStart().startsWith("{")) return null
            val obj = json.parseToJsonElement(body).jsonObject
            val versionCode  = obj["versionCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val tagName      = obj["tagName"]?.jsonPrimitive?.content ?: return null
            val downloadUrl  = obj["downloadUrl"]?.jsonPrimitive?.content ?: return null
            if (versionCode <= currentVersionCode) {
                WearLogger.i("WearUpdater", "Website: up to date at v$currentVersionCode")
                return null
            }
            WearLogger.i("WearUpdater", "Website: update found $tagName")
            WearUpdateInfo(versionCode, tagName, downloadUrl)
        } catch (e: Exception) {
            WearLogger.w("WearUpdater", "Website check failed: ${e.message}")
            null
        }
    }

    private suspend fun tryGithubDirectCheck(currentVersionCode: Int): WearUpdateInfo? {
        return try {
            val body = http.get(GITHUB_RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "BadgerWear")
            }.bodyAsText()

            val releases   = json.parseToJsonElement(body).jsonArray
            val latest     = releases.map { it.jsonObject }
                .filter { it["tag_name"]?.jsonPrimitive?.content?.startsWith("access-v") == true }
                .maxByOrNull { it["tag_name"]?.jsonPrimitive?.content?.removePrefix("access-v")?.toIntOrNull() ?: 0 }
                ?: return null

            val tagName    = latest["tag_name"]?.jsonPrimitive?.content ?: return null
            val latestCode = tagName.removePrefix("access-v").toIntOrNull() ?: return null
            if (latestCode <= currentVersionCode) {
                WearLogger.i("WearUpdater", "GitHub: up to date at v$currentVersionCode")
                return null
            }

            val wearApk = latest["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").let { n -> n.contains("wear") && n.endsWith(".apk") } }
                ?: return null

            val downloadUrl = wearApk["browser_download_url"]?.jsonPrimitive?.content ?: return null
            WearLogger.i("WearUpdater", "GitHub: update found $tagName -> ${wearApk["name"]?.jsonPrimitive?.content}")
            WearUpdateInfo(latestCode, tagName, downloadUrl)
        } catch (e: Exception) {
            WearLogger.w("WearUpdater", "GitHub check failed: ${e.message}")
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
            WearLogger.i("WearUpdater", "Downloading ${info.downloadUrl}")
            val response = http.get(info.downloadUrl) { header("User-Agent", "BadgerWear") }
            val bytes    = response.readRawBytes()
            val file     = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "badger-wear-update.apk")
            file.writeBytes(bytes)
            WearLogger.i("WearUpdater", "Downloaded ${bytes.size} bytes")
            onProgress("Installing...")
            withContext(Dispatchers.Main) { installApkSilent(context, file) }
        } catch (e: Exception) {
            WearLogger.e("WearUpdater", "Download/install failed: ${e.message}")
            onProgress("Update failed: ${e.message}")
        }
    }

    // Wear OS: use PackageInstaller API for silent/background install
    // Falls back to ACTION_VIEW if PackageInstaller fails
    private fun installApkSilent(context: Context, apkFile: File) {
        try {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pi.createSession(params)
            val session   = pi.openSession(sessionId)

            apkFile.inputStream().use { input ->
                session.openWrite("badger-wear.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent("com.badger.wear.INSTALL_COMPLETE").apply { setPackage(context.packageName) }
            val pi2 = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi2.intentSender)
            session.close()
            WearLogger.i("WearUpdater", "PackageInstaller session committed")
        } catch (e: Exception) {
            WearLogger.w("WearUpdater", "PackageInstaller failed, falling back to ACTION_VIEW: ${e.message}")
            // Fallback
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                })
            } catch (e2: Exception) {
                WearLogger.e("WearUpdater", "Fallback install also failed: ${e2.message}")
            }
        }
    }
}
