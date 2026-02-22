package com.badger.trucks.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    val id: Long = 0,
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0
)

data class UpdateInfo(
    val latestVersion: Int,
    val tagName: String,
    val assetId: Long,
    val assetName: String,
    val downloadUrl: String
)

object AppUpdater {

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val body = http.get(GITHUB_RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "BadgerApp")
                header("Authorization", "Bearer ${com.badger.trucks.BuildConfig.GITHUB_TOKEN}")
            }.bodyAsText()

            val release = json.decodeFromString<GithubRelease>(body)
            val latestCode = Regex("\\d+").find(release.tagName)?.value?.toIntOrNull()
                ?: return@withContext null

            if (latestCode <= currentVersionCode) return@withContext null

            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext null

            UpdateInfo(
                latestVersion = latestCode,
                tagName = release.tagName,
                assetId = apk.id,
                assetName = apk.name,
                downloadUrl = apk.downloadUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Download via Ktor (handles private repo auth redirect correctly) then install
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                onProgress("Downloading ${info.assetName}...")
                android.util.Log.d("AppUpdater", "Starting download of ${info.assetName} from asset ${info.assetId}")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "⬇️ Downloading update ${info.tagName}...", android.widget.Toast.LENGTH_LONG).show()
                }

                val apiAssetUrl = "https://api.github.com/repos/augesrob/badger-android/releases/assets/${info.assetId}"

                val response: HttpResponse = http.get(apiAssetUrl) {
                    header("Authorization", "Bearer ${com.badger.trucks.BuildConfig.GITHUB_TOKEN}")
                    header("Accept", "application/octet-stream")
                    header("User-Agent", "BadgerApp")
                }

                android.util.Log.d("AppUpdater", "Download response status: ${response.status}")
                val file = File(context.getExternalFilesDir(null), info.assetName)
                val bytes = response.readBytes()
                android.util.Log.d("AppUpdater", "Downloaded ${bytes.size} bytes, saving to ${file.path}")
                file.writeBytes(bytes)

                onProgress("Installing...")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "✅ Download complete, installing...", android.widget.Toast.LENGTH_LONG).show()
                    installApk(context, file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("AppUpdater", "Download failed: ${e.message}", e)
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

        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(install)
    }
}
// dummy Sun Feb 22 06:30:52 UTC 2026
