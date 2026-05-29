package com.badger.trucks.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.badger.trucks.util.RemoteLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// Version check goes through our own website — no token in APK, no expiry, no rate limits.
// The website /api/version endpoint calls GitHub server-side with its own env token.
// The repo is public so browser_download_url works without auth.
private const val VERSION_CHECK_URL = "https://badger.augesrob.net/api/version"

@Serializable
private data class VersionResponse(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("tagName")     val tagName: String,
    @SerialName("downloadUrl") val downloadUrl: String,
)

data class UpdateInfo(
    val latestVersion: Int,
    val tagName: String,
    val assetName: String,
    val downloadUrl: String,
)

object AppUpdater {

    private val http = HttpClient(OkHttp) {
        engine { config { followRedirects(true) } }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            RemoteLogger.i("AppUpdater", "Checking for update via website (current=$currentVersionCode)")
            val body = http.get(VERSION_CHECK_URL) {
                header("User-Agent", "BadgerApp")
            }.bodyAsText()

            val info = json.decodeFromString<VersionResponse>(body)

            if (info.versionCode <= currentVersionCode) {
                RemoteLogger.i("AppUpdater", "Up to date (current=$currentVersionCode latest=${info.versionCode})")
                return@withContext null
            }

            RemoteLogger.i("AppUpdater", "Update found: ${info.tagName} (current=$currentVersionCode)")
            UpdateInfo(
                latestVersion = info.versionCode,
                tagName       = info.tagName,
                assetName     = "${info.tagName}.apk",
                downloadUrl   = info.downloadUrl,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            RemoteLogger.w("AppUpdater", "checkForUpdate failed: ${e.message}")
            null
        }
    }

    // Download APK directly from browser_download_url (public repo, no auth needed) then install
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                onProgress("Downloading ${info.assetName}...")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "⬇️ Downloading update ${info.tagName}...", android.widget.Toast.LENGTH_LONG).show()
                }

                RemoteLogger.i("AppUpdater", "Downloading ${info.downloadUrl}")
                val response: HttpResponse = http.get(info.downloadUrl) {
                    header("User-Agent", "BadgerApp")
                }

                val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), info.assetName)
                val bytes = response.readBytes()
                RemoteLogger.i("AppUpdater", "Downloaded ${bytes.size} bytes")
                file.writeBytes(bytes)

                onProgress("Installing...")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "✅ Download complete, installing...", android.widget.Toast.LENGTH_LONG).show()
                    installApk(context, file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                RemoteLogger.w("AppUpdater", "downloadAndInstall failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "❌ Update failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
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
