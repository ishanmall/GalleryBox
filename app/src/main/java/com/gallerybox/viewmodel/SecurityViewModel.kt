@file:Suppress("unused", "DEPRECATION")

package com.gallerybox.viewmodel

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * =========================================================================================
 * 🛡️ THE SECURITY GUARD (SecurityViewModel)
 * =========================================================================================
 * Manages app-wide and Vault authentication state, EncryptedSharedPreferences storage,
 * system biometric / device screen-lock (PIN, Pattern, Password) capability checks,
 * and monotonic auto-lock timers.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "SecurityViewModel"
        private const val PREFS_FILE_NAME = "bank_grade_secure_gallery_prefs"
        private const val LEGACY_VAULT_PREFS_NAME = "gallerybox_vault_security_prefs"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
        private const val DEFAULT_TIMEOUT_MINUTES = 5
    }

    /**
     * Self-healing AES256-GCM EncryptedSharedPreferences.
     * If the Android Keystore is invalidated (e.g., after backup/restore or biometric reset),
     * it safely purges the corrupted keyset and recreates the encrypted storage without crashing.
     */
    private val securePrefs: SharedPreferences by lazy {
        createSafeEncryptedPrefs(context)
    }

    // --- LIVE STATEFLOW SCOREBOARDS ---

    // True when the user has authenticated in the current session
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    // Reactive state for whether global App Lock is enabled
    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    // Monotonic timestamp (elapsedRealtime) of the last successful unlock or active interaction
    private val _lastUnlockElapsedMs = MutableStateFlow(0L)

    // Auto-lock timeout in minutes (0 = Lock Instantly, >0 = Minutes, <0 = Never)
    private val _autoLockTimeout = MutableStateFlow(DEFAULT_TIMEOUT_MINUTES)
    val autoLockTimeout: StateFlow<Int> = _autoLockTimeout.asStateFlow()

    init {
        purgeLegacyCustomPinPrefs(context)

        _autoLockTimeout.value = runCatching {
            securePrefs.getInt(KEY_AUTO_LOCK_TIMEOUT, DEFAULT_TIMEOUT_MINUTES)
        }.getOrDefault(DEFAULT_TIMEOUT_MINUTES)

        _appLockEnabled.value = runCatching {
            securePrefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        }.getOrDefault(false)
    }

    /**
     * Removes any leftover custom Vault PIN/Password preferences from older versions.
     */
    private fun purgeLegacyCustomPinPrefs(ctx: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(LEGACY_VAULT_PREFS_NAME)
            } else {
                ctx.getSharedPreferences(LEGACY_VAULT_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
    }

    private fun createSafeEncryptedPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                ctx,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences corrupted or key invalidated; resetting store", e)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ctx.deleteSharedPreferences(PREFS_FILE_NAME)
                } else {
                    ctx.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
            try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Falling back to standard private SharedPreferences", fallbackEx)
                ctx.getSharedPreferences("${PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * Returns true if the user has enabled App Lock in settings.
     */
    fun isAppLockEnabled(): Boolean {
        val enabled = runCatching {
            securePrefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        }.getOrDefault(false)
        _appLockEnabled.value = enabled
        return enabled
    }

    /**
     * Persists the user's App Lock preference.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        securePrefs.edit { putBoolean(KEY_APP_LOCK_ENABLED, enabled) }
        _appLockEnabled.value = enabled
        if (enabled) {
            _lastUnlockElapsedMs.value = SystemClock.elapsedRealtime()
            _isUnlocked.value = true
        }
    }

    /**
     * Persists the auto-lock timeout in minutes and updates the live StateFlow.
     */
    fun setAutoLockTimeout(minutes: Int) {
        securePrefs.edit { putInt(KEY_AUTO_LOCK_TIMEOUT, minutes) }
        _autoLockTimeout.value = minutes
    }

    /**
     * Unlocks the Vault / App Guard and records the monotonic unlock timestamp.
     */
    fun unlockReal() {
        _lastUnlockElapsedMs.value = SystemClock.elapsedRealtime()
        _isUnlocked.value = true
    }

    /**
     * Immediately locks the Vault / App Guard.
     */
    fun lock() {
        _isUnlocked.value = false
    }

    /**
     * Callback invoked when system BiometricPrompt or device screen-lock verification succeeds.
     */
    fun onAuthenticationSuccess() {
        unlockReal()
    }

    /**
     * Refreshes the active session timestamp (can be called on user interaction or pause).
     */
    fun touchSessionTimestamp() {
        if (_isUnlocked.value) {
            _lastUnlockElapsedMs.value = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Checks whether the device has enrolled Biometrics (Fingerprint / Face) OR a secure
     * Lock Screen credential (PIN / Pattern / Password), safely handling API 28–29 vs API 30+.
     */
    fun canUseSystemAuthentication(): Boolean {
        val biometricManager = BiometricManager.from(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val status = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            if (status == BiometricManager.BIOMETRIC_SUCCESS) return true
        } else {
            val bioStatus = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            if (bioStatus == BiometricManager.BIOMETRIC_SUCCESS) return true
        }

        // Fallback check via KeyguardManager for PIN / Pattern / Password lock screen
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    /**
     * Evaluates whether the Vault or App Guard should relock based on elapsed monotonic time.
     *
     * @param timeoutMinutes < 0 = Never auto-lock, 0 = Lock immediately, > 0 = Lock after N minutes.
     */
    fun shouldRelock(timeoutMinutes: Int = _autoLockTimeout.value): Boolean {
        if (!_isUnlocked.value) return true
        if (timeoutMinutes < 0) return false
        if (timeoutMinutes == 0) return true

        val lastUnlock = _lastUnlockElapsedMs.value
        if (lastUnlock <= 0L) return true

        val elapsedMs = SystemClock.elapsedRealtime() - lastUnlock
        return elapsedMs > (timeoutMinutes * 60_000L)
    }
}