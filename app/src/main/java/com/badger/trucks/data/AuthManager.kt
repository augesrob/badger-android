package com.badger.trucks.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * App-wide auth + profile state for Badger Access.
 * Watches the profiles table for real-time role/permission changes.
 */
object AuthManager {

    private const val PREFS          = "badger_access_auth"
    private const val KEY_EMAIL      = "saved_email"
    private const val KEY_REMEMBER   = "remember_me"
    private const val KEY_PASS_ENC   = "saved_pass_enc"   // AES-encrypted password (base64)
    private const val KEY_PASS_IV    = "saved_pass_iv"    // AES IV (base64)
    private const val KEYSTORE_ALIAS = "badger_biometric_key"

    sealed class AuthState {
        object Loading   : AuthState()
        object LoggedOut : AuthState()
        data class LoggedIn(val profile: UserProfile) : AuthState()
    }

    sealed class ProfileEvent {
        data class RoleChanged(val oldRole: String, val newRole: String) : ProfileEvent()
        data class PermissionsChanged(val message: String) : ProfileEvent()
    }

    private val _state         = MutableStateFlow<AuthState>(AuthState.Loading)
    val  state: StateFlow<AuthState> = _state.asStateFlow()

    private val _profileEvents = MutableSharedFlow<ProfileEvent>(extraBufferCapacity = 4)
    val profileEvents: SharedFlow<ProfileEvent> = _profileEvents.asSharedFlow()

    val profile    get() = (_state.value as? AuthState.LoggedIn)?.profile
    val isLoggedIn get() = _state.value is AuthState.LoggedIn

    private var realtimeJob: Job? = null

    // ── Permission check ──────────────────────────────────────────────────────

    fun canAccess(page: String): Boolean {
        val p = profile ?: return false
        if (p.role == "admin" || p.role == "truck_mover") return true
        if (page == "profile" || page == "notifications" || page == "chat") return true
        return DEFAULT_PAGE_ACCESS[p.role]?.contains(page) == true
    }

    /** True if the current user can perform a specific edit/feature action. */
    fun canFeature(feature: String): Boolean {
        val p = profile ?: return false
        if (p.role == "admin" || p.role == "truck_mover") return true
        return DEFAULT_FEATURE_ACCESS[p.role]?.contains(feature) == true
    }

    /** True if this role gets TTS announcements. */
    fun canUseTts(): Boolean = canFeature("tts")


    // ── Init / session restore ────────────────────────────────────────────────

    suspend fun init() {
        try {
            // Check if we already have a valid user in the persisted session FIRST
            // before attempting a refresh — on Samsung devices, background network
            // is killed on screen lock causing refresh to fail even with valid tokens
            val existingUser = BadgerApp.supabase.auth.currentUserOrNull()
            if (existingUser != null) {
                val p = BadgerRepo.getCurrentProfile()
                if (p != null) {
                    _state.value = AuthState.LoggedIn(p)
                    RemoteLogger.i("AuthManager", "Session valid (no refresh needed) — ${p.username}")
                    startProfileWatch(p.id)
                    // Refresh in background so next startup is even faster
                    try { BadgerApp.supabase.auth.refreshCurrentSession() } catch (_: Exception) {}
                    return
                }
            }

            // No valid session in memory — try refresh from persisted token
            try {
                BadgerApp.supabase.auth.refreshCurrentSession()
                RemoteLogger.i("AuthManager", "JWT refreshed OK")
                val user = BadgerApp.supabase.auth.currentUserOrNull()
                if (user != null) {
                    val p = BadgerRepo.getCurrentProfile()
                    if (p != null) {
                        _state.value = AuthState.LoggedIn(p)
                        RemoteLogger.i("AuthManager", "Session restored after refresh — ${p.username}")
                        startProfileWatch(p.id)
                        return
                    }
                }
            } catch (e: Exception) {
                RemoteLogger.w("AuthManager", "JWT refresh failed: ${e.message}")
            }

            // Session fully expired — try HWID silent re-login using locally stored credentials
            RemoteLogger.i("AuthManager", "Session expired — trying HWID silent login")
            if (tryHwidAutoLogin()) return

        } catch (e: Exception) {
            RemoteLogger.w("AuthManager", "Session restore failed: ${e.message}")
            // Last resort — try HWID login even on unexpected error
            try { if (tryHwidAutoLogin()) return } catch (_: Exception) {}
        }
        _state.value = AuthState.LoggedOut
    }

    /**
     * Silent sign-in using locally stored credentials bound to this device.
     * No network call to look up the device — uses SharedPreferences directly.
     * This works even if device_registrations table doesn't exist in DB.
     */
    private suspend fun tryHwidAutoLogin(): Boolean {
        return try {
            val context = BadgerApp.appContext
            // Use the saved email + stored encrypted password — same as biometric flow
            // but without requiring the user to touch the fingerprint sensor
            val email = getSavedEmail(context)
            if (email.isBlank()) return false
            val pass = getDecryptedPassword(context) ?: return false
            RemoteLogger.i("AuthManager", "HWID silent login for $email")
            val result = signIn(email, pass)
            if (result.isSuccess) {
                RemoteLogger.i("AuthManager", "HWID silent login OK")
                true
            } else false
        } catch (e: Exception) {
            RemoteLogger.w("AuthManager", "HWID silent login failed: ${e.message}")
            false
        }
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

    // ── Biometric credential storage (Android Keystore AES) ──────────────────

    /** Call after successful password sign-in to store credentials for biometric unlock. */
    fun saveEncryptedPassword(context: Context, password: String) {
        try {
            val key    = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            prefs(context).edit()
                .putString(KEY_PASS_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_PASS_IV,  Base64.encodeToString(cipher.iv,  Base64.NO_WRAP))
                .apply()
            RemoteLogger.i("AuthManager", "Password encrypted and saved for biometric")
        } catch (e: Exception) {
            RemoteLogger.w("AuthManager", "Failed to save encrypted password: ${e.message}")
        }
    }

    /** Returns decrypted password, or null if nothing stored or decryption fails. */
    fun getDecryptedPassword(context: Context): String? {
        return try {
            val p     = prefs(context)
            val encB  = Base64.decode(p.getString(KEY_PASS_ENC, null) ?: return null, Base64.NO_WRAP)
            val iv    = Base64.decode(p.getString(KEY_PASS_IV,  null) ?: return null, Base64.NO_WRAP)
            val key   = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            String(cipher.doFinal(encB), Charsets.UTF_8)
        } catch (e: Exception) {
            RemoteLogger.w("AuthManager", "Failed to decrypt password: ${e.message}")
            null
        }
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val p = prefs(context)
        return p.contains(KEY_PASS_ENC) && p.getString(KEY_EMAIL, "").orEmpty().isNotBlank()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(false) // key available without auth so we can decrypt after biometric
                .build()
        )
        return kg.generateKey()
    }

    // ── Real-time profile watch ───────────────────────────────────────────────

    private fun startProfileWatch(userId: String) {
        stopProfileWatch()
        realtimeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val channel = BadgerApp.supabase.channel("profile-watch-$userId")
                val flow = channel.postgresChangeFlow<PostgresAction.Update>("public") {
                    table = "profiles"
                }
                channel.subscribe()
                RemoteLogger.i("AuthManager", "Profile realtime watch started for $userId")
                flow.collect { action ->
                    val rowId = action.record["id"]?.toString()?.trim('"')
                    if (rowId == userId) handleProfileUpdate()
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
                RemoteLogger.i("AuthManager", "Role changed: $oldRole -> $newRole")
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

    fun getSavedEmail(context: Context): String  = prefs(context).getString(KEY_EMAIL, "") ?: ""
    fun getRememberMe(context: Context): Boolean = prefs(context).getBoolean(KEY_REMEMBER, false)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Default page access per role (admin + truck_mover handled by canAccess directly) ──

    private val DEFAULT_PAGE_ACCESS = mapOf(
        "print_room"  to setOf("movement","preshift","printroom","tractors","chat","profile","notifications"),
        "trainee"     to setOf("movement","preshift","printroom","tractors","chat","profile","notifications"),
        "semi_driver" to setOf("movement","chat","profile","notifications"),
        "driver"      to setOf("movement","chat","profile","notifications"),
    )

    // Features that allow editing / TTS per role
    private val DEFAULT_FEATURE_ACCESS = mapOf(
        "print_room"  to setOf("movement_edit","movement_door_edit","preshift_edit","printroom_edit","fleet_edit","tts","ptt"),
        "trainee"     to setOf("movement_edit","movement_door_edit","preshift_edit","printroom_edit","fleet_edit","tts","ptt"),
        "semi_driver" to setOf<String>(),
        "driver"      to setOf<String>(),
    )
}
