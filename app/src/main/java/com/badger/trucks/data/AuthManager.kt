package com.badger.trucks.data

import android.content.Context
import android.content.SharedPreferences
import com.badger.trucks.BadgerApp
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide auth + profile state for Badger Access.
 * Call AuthManager.init() on app startup to restore saved session.
 */
object AuthManager {

    private const val PREFS = "badger_access_auth"
    private const val KEY_EMAIL = "saved_email"
    private const val KEY_REMEMBER = "remember_me"

    sealed class AuthState {
        object Loading  : AuthState()
        object LoggedOut : AuthState()
        data class LoggedIn(val profile: UserProfile) : AuthState()
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val profile get() = (_state.value as? AuthState.LoggedIn)?.profile
    val isLoggedIn get() = _state.value is AuthState.LoggedIn

    fun canAccess(page: String): Boolean {
        val p = profile ?: return false
        if (p.role == "admin") return true
        // "profile" and "notifications" are always accessible once logged in
        if (page == "profile" || page == "notifications" || page == "chat") return true
        return DEFAULT_PAGE_ACCESS[p.role]?.contains(page) == true
    }

    /** Call from coroutine on app start — tries to restore existing Supabase session */
    suspend fun init() {
        try {
            val user = BadgerApp.supabase.auth.currentUserOrNull()
            if (user != null) {
                val p = BadgerRepo.getCurrentProfile()
                if (p != null) {
                    _state.value = AuthState.LoggedIn(p)
                    RemoteLogger.i("AuthManager", "Session restored — ${p.username} role=${p.role}")
                    return
                }
            }
        } catch (e: Exception) {
            RemoteLogger.w("AuthManager", "Session restore failed: ${e.message}")
        }
        _state.value = AuthState.LoggedOut
    }

    suspend fun signIn(email: String, password: String): Result<UserProfile> {
        return try {
            BadgerRepo.signIn(email, password)
            val profile = BadgerRepo.getCurrentProfile()
                ?: return Result.failure(Exception("Profile not found — contact admin"))
            _state.value = AuthState.LoggedIn(profile)
            RemoteLogger.i("AuthManager", "Sign in OK — ${profile.username} role=${profile.role}")
            Result.success(profile)
        } catch (e: Exception) {
            RemoteLogger.e("AuthManager", "Sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try { BadgerRepo.signOut() } catch (_: Exception) {}
        _state.value = AuthState.LoggedOut
        RemoteLogger.i("AuthManager", "Signed out")
    }

    fun saveEmail(context: Context, email: String, remember: Boolean) {
        prefs(context).edit()
            .putString(KEY_EMAIL, if (remember) email else "")
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    fun getSavedEmail(context: Context): String = prefs(context).getString(KEY_EMAIL, "") ?: ""
    fun getRememberMe(context: Context): Boolean = prefs(context).getBoolean(KEY_REMEMBER, false)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Default page access per role — mirrors web app permissions
    private val DEFAULT_PAGE_ACCESS = mapOf(
        "print_room"  to setOf("printroom", "routesheet", "cheatsheet", "tractors", "movement", "chat", "profile", "notifications"),
        "truck_mover" to setOf("printroom", "routesheet", "cheatsheet", "tractors", "movement", "chat", "profile", "notifications"),
        "trainee"     to setOf("printroom", "movement", "chat", "profile", "notifications"),
        "driver"      to setOf("movement", "chat", "profile", "notifications"),
    )
}
