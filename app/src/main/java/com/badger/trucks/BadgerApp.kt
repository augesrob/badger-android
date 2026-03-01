package com.badger.trucks

import android.app.Application
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlin.time.Duration.Companion.seconds

class BadgerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RemoteLogger.init(this)
        val url = BuildConfig.SUPABASE_URL
        val wsUrl = url.replace("https://", "wss://").replace("http://", "ws://") + "/realtime/v1/websocket"
        RemoteLogger.i("BadgerApp", "App started — REST: $url")
        RemoteLogger.i("BadgerApp", "WebSocket URL will be: $wsUrl")
    }

    companion object {
        val supabase by lazy {
            createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_KEY
            ) {
                install(Postgrest)
                install(Realtime) {
                    secure = true
                    reconnectDelay = 3.seconds
                    heartbeatInterval = 15.seconds
                }
            }
        }
    }
}
