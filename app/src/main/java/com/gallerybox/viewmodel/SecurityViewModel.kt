package com.gallerybox.viewmodel

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for fingerprint reading, military-grade encryption, and background data management.
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * --- THE SECURITY GUARD (SecurityViewModel) ---
 * The ViewModel is the "Brain" or "Manager" of the screen. This specific manager acts as the
 * app's Security Guard. It handles locking the app, checking fingerprints, and remembering
 * the user's privacy settings.
 *
 * `@HiltViewModel` tells a factory tool called 'Hilt' to automatically build this guard
 * and hand it the tools it needs (like the Application `Context`, which is essentially the phone's environment).
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * THE TITANIUM SAFE (EncryptedSharedPreferences).
     * Normally, apps save settings in a simple digital notebook (`SharedPreferences`) that is relatively easy to hack.
     * Because this is a privacy gallery, we put the settings inside a heavily encrypted bank vault.
     *
     * `by lazy` means we don't actually build the heavy titanium safe until the exact moment we need to use it, saving phone memory.
     */
    private val securePrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "bank_grade_secure_gallery_prefs", // The name of the file
            // We forge a Master Key using AES256-GCM, which is the exact same encryption standard used by banks and the military.
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // --- SCOREBOARDS (StateFlows) ---
    // A StateFlow is like a sports scoreboard. It always shows the current status.
    // The screen (UI) stares at these scoreboards and updates itself automatically when the numbers change.

    // Scoreboard 1: Is the app currently unlocked and open for the user to see? (Default is false/locked)
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked = _isUnlocked.asStateFlow()

    // A private internal stopwatch that remembers the exact millisecond the user last unlocked the app.
    private val _lastUnlockTime = MutableStateFlow(0L)

    // Scoreboard 2: How many minutes should we wait before automatically locking the app again? (Default is 5 minutes)
    private val _autoLockTimeout = MutableStateFlow(5)
    val autoLockTimeout = _autoLockTimeout.asStateFlow()

    /**
     * THE MORNING ROUTINE (`init`)
     * This block runs the moment the Security Guard wakes up when the app starts.
     */
    init {
        // Open the titanium safe, look for the "auto_lock_timeout" setting, and put it on the scoreboard.
        // If the user hasn't set one yet, assume it's 5 minutes.
        _autoLockTimeout.value = securePrefs.getInt("auto_lock_timeout", 5)
    }

    /**
     * Asks the safe: "Did the user turn on the App Lock feature in the settings?"
     */
    fun isAppLockEnabled(): Boolean = securePrefs.getBoolean("app_lock_enabled", false)

    /**
     * Tells the safe to save the user's choice of whether the App Lock is turned on or off.
     */
    fun setAppLockEnabled(enabled: Boolean) {
        securePrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
    }

    /**
     * Tells the safe to save how many minutes to wait before locking the app automatically.
     * It also instantly updates the scoreboard so the app knows about the new rule.
     */
    fun setAutoLockTimeout(minutes: Int) {
        securePrefs.edit().putInt("auto_lock_timeout", minutes).apply()
        _autoLockTimeout.value = minutes
    }

    /**
     * Unlocks the door!
     * It updates the scoreboard to 'True' (Unlocked) and checks the phone's internal clock
     * to record the exact millisecond the door was opened.
     */
    fun unlockReal() {
        _lastUnlockTime.value = System.currentTimeMillis()
        _isUnlocked.value = true
    }

    /**
     * Locks the door. Updates the scoreboard to 'False'.
     */
    fun lock() {
        _isUnlocked.value = false
    }

    /**
     * A helper function that gets called automatically when the phone successfully reads
     * the user's fingerprint or Face ID. It just forwards the command to open the door.
     */
    fun onAuthenticationSuccess() {
        unlockReal()
    }

    /**
     * Checks if the physical phone is even capable of locking the app.
     * We ask the Android Operating System: "Does this phone have a secure fingerprint scanner, face scanner, or a lock screen PIN?"
     */
    fun canUseSystemAuthentication(): Boolean {
        // We require "BIOMETRIC_STRONG" (high-security fingerprint/face) OR "DEVICE_CREDENTIAL" (a secure PIN/Pattern).
        return when (
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        ) {
            // If Android replies "SUCCESS", it means the phone is secure and ready to use.
            BiometricManager.BIOMETRIC_SUCCESS -> true
            // Otherwise (e.g., no fingerprint hardware, or the user hasn't set up a PIN), return false.
            else -> false
        }
    }

    /**
     * THE BOUNCER'S STOPWATCH.
     * Every time the user opens the app or switches back to it, the bouncer checks the time.
     *
     * @param timeoutMinutes How long the user's settings say we should wait before locking.
     * @return True if the app should be locked right now, False if it's safe to stay open.
     */
    fun shouldRelock(timeoutMinutes: Int): Boolean {
        // If the timeout is negative, it means "Never lock automatically".
        if (timeoutMinutes < 0) return false

        // If the timeout is exactly 0, it means "Lock instantly the second I leave the app".
        if (timeoutMinutes == 0) return true

        // Calculate how much time has passed since they last successfully unlocked the app.
        // System.currentTimeMillis() gives us the exact current time in milliseconds.
        val diff = System.currentTimeMillis() - _lastUnlockTime.value

        // Multiply the minutes by 60,000 to convert them into milliseconds.
        // If the difference is greater than the allowed time, tell the app to lock itself!
        return diff > (timeoutMinutes * 60_000L)
    }
}