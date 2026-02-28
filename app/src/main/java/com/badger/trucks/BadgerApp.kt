package com.badger.trucks

import android.app.Application
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlin.time.Duration.Companion.seconds

class BadgerApp : Application() {
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
