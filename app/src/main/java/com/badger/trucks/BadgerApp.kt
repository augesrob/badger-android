package com.badger.trucks

import android.app.Application
import com.badger.trucks.data.AuthManager
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.functions.Functions
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BadgerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RemoteLogger.init(this)
        appContext = this
        installCrashHandler()
        CoroutineScope(Dispatchers.IO).launch { AuthManager.init() }
        val url = BuildConfig.SUPABASE_URL
        val wsUrl = url.replace("https://", "wss://").replace("http://", "ws://") + "/realtime/v1/websocket"
        RemoteLogger.i("BadgerApp", "App started — REST: $url")
        RemoteLogger.i("BadgerApp", "WebSocket URL will be: $wsUrl")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Build a compact stack trace (first 8 frames is enough)
                val stackTrace = throwable.stackTrace
                    .take(8)
                    .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val msg = "${throwable::class.simpleName}: ${throwable.message} | $stackTrace"

                // Always log locally
                android.util.Log.e("CRASH", msg, throwable)

                // Persist to SharedPreferences — survives even if DB write fails
                // Readable from DebugScreen → "Last Crash" section
                appContext.getSharedPreferences("badger_crash_log", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", msg)
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()

                // Force-enable remote logging for crash so it always goes to DB
                val wasEnabled = RemoteLogger.remoteEnabled
                RemoteLogger.remoteEnabled = true
                RemoteLogger.e("CRASH", msg)
                RemoteLogger.remoteEnabled = wasEnabled

                // Ship logs immediately on crash (no wait for next keepalive)
                kotlinx.coroutines.runBlocking {
                    try { com.badger.trucks.util.LogShipper.ship(appContext) }
                    catch (_: Exception) {}
                }

                Thread.sleep(1500)
            } catch (_: Exception) {
                // Never let the crash handler itself crash
            }
            // Re-throw to default handler so Android still shows the crash dialog
            // and writes to logcat
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var appContext: android.content.Context

        val supabase by lazy {
            createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_KEY
            ) {
                install(Postgrest)
                install(Auth) {
                    // Persist the JWT session to SharedPreferences so it survives process death.
                    // SettingsSessionManager is the supabase-kt v3 mechanism; SharedPreferencesSettings
                    // is from multiplatform-settings (already a transitive dep).
                    sessionManager = io.github.jan.supabase.auth.SettingsSessionManager(
                        settings = com.russhwolf.settings.SharedPreferencesSettings(
                            appContext.getSharedPreferences("badger_supabase_session", android.content.Context.MODE_PRIVATE)
                        )
                    )
                }
                install(Storage)
                install(Functions)
                install(Realtime) {
                    secure = true
                    reconnectDelay = 3.seconds
                    heartbeatInterval = 15.seconds
                }
            }
        }
    }
}
