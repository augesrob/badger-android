package com.badger.trucks.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
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

private const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/augesrob/badger-android/releases/latest"

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0
)

data class UpdateInfo(
    val latestVersion: Int,       // parsed from tag like "v5" or "5"
    val tagName: String,
    val downloadUrl: String,
    val assetName: String
)

object AppUpdater {

    private val http = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Check GitHub for a newer release.
     * Returns UpdateInfo if there's a newer version, null if up to date or error.
     */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val body = http.get(GITHUB_RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "BadgerApp")
                header("Authorization", "Bearer ${com.badger.trucks.BuildConfig.GITHUB_TOKEN}")
            }.bodyAsText()

            val release = json.decodeFromString<GithubRelease>(body)

            // Parse version from tag: "v5", "5", "v1.5", etc. → take first integer group
            val latestCode = Regex("\\d+").find(release.tagName)?.value?.toIntOrNull() ?: return@withContext null

            if (latestCode <= currentVersionCode) return@withContext null

            // Find the APK asset
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext null

            UpdateInfo(
                latestVersion = latestCode,
                tagName = release.tagName,
                downloadUrl = apk.downloadUrl,
                assetName = apk.name
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Download the APK using DownloadManager and trigger install when done.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (String) -> Unit) {
        onProgress("Downloading ${info.assetName}...")

        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle("Badger Update ${info.tagName}")
            setDescription("Downloading update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, info.assetName)
            setMimeType("application/vnd.android.package-archive")
            addRequestHeader("User-Agent", "BadgerApp")
            addRequestHeader("Authorization", "Bearer ${com.badger.trucks.BuildConfig.GITHUB_TOKEN}")
            addRequestHeader("Accept", "application/octet-stream")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // Listen for completion
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        info.assetName
                    )
                    if (file.exists()) {
                        onProgress("Installing...")
                        installApk(context, file)
                    } else {
                        onProgress("Download failed — file not found")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(install)
    }
}
