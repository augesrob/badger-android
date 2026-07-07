package com.badger.wear.status

import android.content.Context

/** Persists the most recent trigger so the complication and popup can show it. */
object StatusStore {

    private const val PREFS = "badger_status"

    data class Last(val title: String, val body: String, val timestamp: Long)

    fun save(ctx: Context, title: String, body: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("body", body)
            .putLong("ts", System.currentTimeMillis())
            .apply()
    }

    fun last(ctx: Context): Last {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Last(
            p.getString("title", "Badger") ?: "Badger",
            p.getString("body", "") ?: "",
            p.getLong("ts", 0L),
        )
    }

    /** Color-code a status string. Statuses are user-defined, so match keywords. */
    fun colorFor(text: String): Long {
        val t = text.lowercase()
        return when {
            listOf("kill", "stop", "hold", "block").any { it in t } -> 0xFFE53935 // red
            listOf("load").any { it in t }                           -> 0xFF43A047 // green
            listOf("door", "dock", "stag").any { it in t }           -> 0xFFFDD835 // yellow
            listOf("done", "complete", "ready", "clear").any { it in t } -> 0xFF1E88E5 // blue
            else                                                     -> 0xFF26A69A // teal
        }
    }
}
