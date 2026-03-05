package com.badger.trucks.data

import android.content.Context
import android.content.SharedPreferences
import com.badger.trucks.BadgerApp
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide auth + profile state for Badger Access.
 * Watches the profiles table for real-time role/permission changes.
 */
object AuthManager {

    private const val PREFS      = "badger_access_auth"
    private const val KEY_EMAIL  = "saved_email"
    private const val KEY_REMEMBER = "remember_me"

    sealed class AuthState {
        object Loading   : AuthState()
        object LoggedOut : AuthState()
        data class LoggedIn(val profile: UserProfile) : AuthState()
    }

    /** Fired when the user's role changes while they're logged in. */
    sealed class ProfileEvent {
        /** Role changed — tabs may need to update. */
        data class RoleChanged(val oldRole: String, val newRole: String) : ProfileEvent()
        /** Permissions changed enough to require restart notice. */
        data class PermissionsChanged(val message: String) : ProfileEvent()
    }

    private val _state        = MutableStateFlow<AuthState>(AuthState.Loading)
    val  state: StateFlow<AuthState> = _state.asStateFlow()

    private val _profileEvents = MutableSharedFlow<ProfileEvent>(extraBufferCapacity = 4)
    val profileEvents: SharedFlow<ProfileEvent> = _profileEvents.asSharedFlow()

    val profile   get() = (_state.value as? AuthState.LoggedIn)?.profile
    val isLoggedIn get() = _state.value is AuthState.LoggedIn

    private var realtimeJob: Job? = null

    // ── Permission check ──────────────────────────────────────────────────────

    fun canAccess(page: String): Boolean {
        val p = profile ?: return false
        if (p.role == "admin") return true
        if (page == "profile" || page == "notifications" || page == "chat") return true
        return DEFAULT_PAGE_ACCESS[p.role]?.contains(page) == true
    }

    // ── Init / session restore ────────────────────────────────────────────────

    suspend fun init() {
        try {
            val user = BadgerApp.supabase.auth.currentUserOrNull()
            if (user != null) {
                val p = BadgerRepo.getCurrentProfile()
                if (p != null) {
                    _state.value = AuthState.LoggedIn(p)
                    RemoteLogger.i("AuthManager", "Session restored — ${p.username} role=${p.role}")
                    startProfileWatch(p.id)
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
            startProfileWatch(profile.id)
            Result.success(profile)
        } catch (e: Exception) {
            RemoteLogger.e("AuthManager", "Sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        stopProfileWatch()
        try { BadgerRepo.signOut() } catch (_: Exception) {}
        _state.value = AuthState.LoggedOut
        RemoteLogger.i("AuthManager", "Signed out")
    }

    // ── Real-time profile watch ───────────────────────────────────────────────

    private fun startProfileWatch(userId: String) {
        stopProfileWatch()
        realtimeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val channel = BadgerApp.supabase.channel("profile-watch-$userId")

                val flow = channel.postgresChangeFlow<PostgresAction.Update>("public") {
                    table  = "profiles"
                    filter = "id=eq.$userId"
                }

                channel.subscribe()
                RemoteLogger.i("AuthManager", "Profile realtime watch started for $userId")

                flow.collect { _ ->
                    handleProfileUpdate()
                }
            } catch (e: Exception) {
                RemoteLogger.w("AuthManager", "Profile watch error: ${e.message}")
            }
        }
    }

    private fun stopProfileWatch() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    private suspend fun handleProfileUpdate() {
        val currentProfile = profile ?: return
        try {
            val updatedProfile = BadgerRepo.getCurrentProfile() ?: return
            val oldRole = currentProfile.role
            val newRole = updatedProfile.role

            _state.value = AuthState.LoggedIn(updatedProfile)

            if (oldRole != newRole) {
                RemoteLogger.i("AuthManager", "Role changed: $oldRole → $newRole")
                _profileEvents.emit(ProfileEvent.RoleChanged(oldRole, newRole))
            }
        } catch (e: Exception) {
            RemoteLogger.e("AuthManager", "handleProfileUpdate error: ${e.message}")
        }
    }

    // ── Prefs helpers ─────────────────────────────────────────────────────────

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

    // ── Default page access per role ──────────────────────────────────────────

    private val DEFAULT_PAGE_ACCESS = mapOf(
        "print_room"  to setOf("printroom","routesheet","cheatsheet","tractors","movement","chat","profile","notifications","preshift"),
        "truck_mover" to setOf("printroom","routesheet","cheatsheet","tractors","movement","chat","profile","notifications","preshift"),
        "trainee"     to setOf("printroom","movement","chat","profile","notifications"),
        "driver"      to setOf("movement","chat","profile","notifications"),
    )
}
