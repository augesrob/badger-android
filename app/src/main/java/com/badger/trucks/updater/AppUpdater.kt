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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Primary: website endpoint (server-side token, Vercel cache).
// Fallback: GitHub API directly — repo is PUBLIC so no token needed at all.
private const val VERSION_CHECK_URL   = "https://badger.augesrob.net/api/version"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/augesrob/badger-android/releases?per_page=50"

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
        // Try website endpoint first — fast, cached, no GitHub rate limits
        val websiteResult = tryWebsiteCheck(currentVersionCode)
        if (websiteResult != null) return@withContext websiteResult

        // Fallback: call GitHub releases API directly (public repo — no token needed)
        tryGithubDirectCheck(currentVersionCode)
    }

    private suspend fun tryWebsiteCheck(currentVersionCode: Int): UpdateInfo? {
        return try {
            RemoteLogger.i("AppUpdater", "Checking via website (current=$currentVersionCode)")
            val body = http.get(VERSION_CHECK_URL) {
                header("User-Agent", "BadgerApp")
            }.bodyAsText()

            if (!body.trimStart().startsWith("{")) {
                RemoteLogger.w("AppUpdater", "Website returned non-JSON — falling back to GitHub direct")
                return null
            }

            val info = json.decodeFromString<VersionResponse>(body)
            if (info.versionCode <= currentVersionCode) {
                RemoteLogger.i("AppUpdater", "Up to date via website (current=$currentVersionCode latest=${info.versionCode})")
                return null // null = no update needed, don't fall through to GitHub
            }

            RemoteLogger.i("AppUpdater", "Update found via website: ${info.tagName}")
            UpdateInfo(
                latestVersion = info.versionCode,
                tagName       = info.tagName,
                assetName     = "${info.tagName}.apk",
                downloadUrl   = info.downloadUrl,
            )
        } catch (e: Exception) {
            RemoteLogger.w("AppUpdater", "Website check failed: ${e.message} — trying GitHub direct")
            null
        }
    }

    private suspend fun tryGithubDirectCheck(currentVersionCode: Int): UpdateInfo? {
        return try {
            RemoteLogger.i("AppUpdater", "Checking via GitHub direct (current=$currentVersionCode)")
            val body = http.get(GITHUB_RELEASES_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "BadgerApp")
                // No Authorization header — repo is public, no token needed
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
                RemoteLogger.i("AppUpdater", "Up to date via GitHub (current=$currentVersionCode latest=$latestCode)")
                return null
            }

            val apk = latest["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return null

            val downloadUrl = apk["browser_download_url"]?.jsonPrimitive?.content ?: return null

            RemoteLogger.i("AppUpdater", "Update found via GitHub: $tagName (current=$currentVersionCode)")
            UpdateInfo(
                latestVersion = latestCode,
                tagName       = tagName,
                assetName     = "$tagName.apk",
                downloadUrl   = downloadUrl,
            )
        } catch (e: Exception) {
            RemoteLogger.w("AppUpdater", "GitHub direct check failed: ${e.message}")
            null
        }
    }

    // Download APK directly from browser_download_url (public repo, no auth) then install
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
                val bytes = response.readRawBytes()
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
