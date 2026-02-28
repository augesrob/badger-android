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
        RemoteLogger.i("BadgerApp", "App started — ${BuildConfig.SUPABASE_URL}")
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
                }
            }
        }
    }
}
