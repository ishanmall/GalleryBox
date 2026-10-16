@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.vault

// --- IMPORTS ---
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.gallerybox.data.MediaItem
import com.gallerybox.findActivity
import com.gallerybox.findFragmentActivity
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.SecurityViewModel
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs
import kotlin.math.roundToInt

// =========================================================================================
// 🔐 CRYPTOGRAPHIC CREDENTIAL & LOCKOUT ENGINE (PBKDF2-HMAC-SHA256)
// =========================================================================================
enum class VaultAuthMode {
    PIN,
    PASSWORD
}

class VaultCredentialManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("gallerybox_vault_security_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HASH = "vault_cred_hash"
        private const val KEY_SALT = "vault_cred_salt"
        private const val KEY_MODE = "vault_cred_mode"
        private const val KEY_PIN_LENGTH = "vault_pin_length"
        private const val KEY_BIOMETRIC_ENABLED = "vault_biometric_enabled"
        private const val KEY_SCRAMBLE_PIN = "vault_scramble_keypad"
        private const val KEY_FAILED_ATTEMPTS = "vault_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL_MS = "vault_lockout_until_ms"

        private const val PBKDF2_ITERATIONS = 12_000
        private const val KEY_LENGTH_BITS = 256
    }

    fun hasCustomCredential(): Boolean {
        return !prefs.getString(KEY_HASH, null).isNullOrBlank() &&
                !prefs.getString(KEY_SALT, null).isNullOrBlank()
    }

    fun getAuthMode(): VaultAuthMode {
        val raw = prefs.getString(KEY_MODE, VaultAuthMode.PIN.name) ?: VaultAuthMode.PIN.name
        return runCatching { VaultAuthMode.valueOf(raw) }.getOrDefault(VaultAuthMode.PIN)
    }

    fun getPinLength(): Int = prefs.getInt(KEY_PIN_LENGTH, 4).coerceIn(4, 8)

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun isScrambleKeypadEnabled(): Boolean = prefs.getBoolean(KEY_SCRAMBLE_PIN, false)

    fun setScrambleKeypadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCRAMBLE_PIN, enabled).apply()
    }

    fun saveCredential(secret: String, mode: VaultAuthMode) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2Hash(secret.toCharArray(), salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString(KEY_MODE, mode.name)
            .putInt(KEY_PIN_LENGTH, if (mode == VaultAuthMode.PIN) secret.length.coerceIn(4, 8) else 4)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun clearCustomCredential() {
        prefs.edit()
            .remove(KEY_HASH)
            .remove(KEY_SALT)
            .remove(KEY_MODE)
            .remove(KEY_PIN_LENGTH)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun verifyCredential(input: String): Boolean {
        if (getRemainingLockoutSeconds() > 0) return false
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false

        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
            val actualHash = pbkdf2Hash(input.toCharArray(), salt)
            // Constant-time array comparison to prevent timing attacks
            val matched = MessageDigest.isEqual(expectedHash, actualHash)
            if (matched) {
                resetFailedAttempts()
            } else {
                recordFailedAttempt()
            }
            matched
        } catch (e: Exception) {
            false
        }
    }

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun getRemainingLockoutSeconds(): Int {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0L) ((diff + 999L) / 1000L).toInt() else 0
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    private fun recordFailedAttempt() {
        val attempts = getFailedAttempts() + 1
        val lockoutMs = when {
            attempts >= 10 -> 60_000L
            attempts >= 5 -> 30_000L
            else -> 0L
        }
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putLong(
                KEY_LOCKOUT_UNTIL_MS,
                if (lockoutMs > 0L) System.currentTimeMillis() + lockoutMs else 0L
            )
            .apply()
    }

    private fun pbkdf2Hash(chars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            skf.generateSecret(spec).encoded
        } catch (e: Exception) {
            val fallback = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            fallback.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Helper to check if hardware biometrics (Fingerprint / Face) are enrolled and ready.
 */
private fun isBiometricHardwareReady(context: Context): Boolean {
    val bm = BiometricManager.from(context)
    val res = bm.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    return res == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Launches Android's BiometricPrompt safely across API 28, 29, and 30+.
 *
 * @param allowDeviceCredentialFallback If true, allows the phone's Lock Screen PIN/Pattern/Password.
 *                                      If false, shows a negative button to switch to the in-app Vault PIN/Password.
 */
private fun launchBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String = "Use Vault PIN / Password",
    allowDeviceCredentialFallback: Boolean,
    onSuccess: () -> Unit,
    onNegativeOrCanceled: () -> Unit = {},
    onLockoutOrError: (Int, String) -> Unit = { _, _ -> }
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onNegativeOrCanceled()
                    else -> onLockoutOrError(errorCode, errString.toString())
                }
            }
        }
    )

    try {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)

        if (allowDeviceCredentialFallback) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
        } else {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            builder.setNegativeButtonText(negativeButtonText)
        }

        prompt.authenticate(builder.build())
    } catch (e: Exception) {
        Log.e("VaultAuth", "BiometricPrompt launch failed", e)
        onLockoutOrError(-1, e.localizedMessage ?: "Authentication unavailable")
    }
}

/**
 * =========================================================================================
 * 🛡️ THE MASTER VAULT SCREEN
 * =========================================================================================
 */
@Composable
fun VaultSecureScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    isGlobalAppGuard: Boolean = false,
    onBack: () -> Unit,
    onNavigateToPicker: () -> Unit = {},
    onUnlockGlobalSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    val unlocked by securityViewModel.isUnlocked.collectAsState()
    var isUnlocking by remember { mutableStateOf(false) }
    val autoLockTimeout by securityViewModel.autoLockTimeout.collectAsState(initial = 5)

    // 📳 THE PANIC SHAKE
    VaultShakeDetector {
        securityViewModel.lock()
        viewModel.clearTempVaultCache()
        if (!isGlobalAppGuard) onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.clearTempVaultCache()
    }

    val hiddenItems by viewModel.hiddenMedia.collectAsState(emptyList())

    // 🚫 THE SCREENSHOT BLOCKER
    DisposableEffect(isGlobalAppGuard) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (!isGlobalAppGuard) {
                securityViewModel.lock()
                viewModel.clearTempVaultCache()
            }
        }
    }

    // THE TIMEOUT WATCHER
    DisposableEffect(lifecycleOwner, autoLockTimeout) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE || ev == Lifecycle.Event.ON_STOP) {
                if (autoLockTimeout == 0) {
                    securityViewModel.lock()
                    viewModel.clearTempVaultCache()
                }
            }
            if (ev == Lifecycle.Event.ON_RESUME) {
                if (securityViewModel.shouldRelock(autoLockTimeout)) {
                    securityViewModel.lock()
                }
            }
            if (ev == Lifecycle.Event.ON_DESTROY) {
                securityViewModel.lock()
                viewModel.clearTempVaultCache()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
        }
    }

    val state = when {
        isUnlocking -> "PROCESSING"
        !unlocked -> "AUTH_GUARD"
        else -> "GRANTED"
    }

    when (state) {
        "PROCESSING" -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Decrypting Vault...", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        "AUTH_GUARD" -> {
            StandardAppLockScreen(
                viewModel = securityViewModel,
                isGlobalAppGuard = isGlobalAppGuard,
                onBack = onBack
            )
        }
        "GRANTED" -> {
            LaunchedEffect(Unit) {
                isUnlocking = false
                if (isGlobalAppGuard) onUnlockGlobalSuccess()
            }
            if (!isGlobalAppGuard) {
                VaultGridScreen(
                    items = hiddenItems,
                    viewModel = viewModel,
                    securityViewModel = securityViewModel,
                    onBack = onBack,
                    onAdd = onNavigateToPicker
                )
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔐 THE UPGRADED BOUNCER (Biometrics + PBKDF2 PIN Keypad / Password + Lockout Guard)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardAppLockScreen(
    viewModel: SecurityViewModel,
    isGlobalAppGuard: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findFragmentActivity() }
    val credManager = remember(context) { VaultCredentialManager(context) }

    var hasCustomCred by remember { mutableStateOf(credManager.hasCustomCredential()) }
    var authMode by remember { mutableStateOf(credManager.getAuthMode()) }
    var targetPinLength by remember { mutableIntStateOf(credManager.getPinLength()) }
    var biometricEnabled by remember { mutableStateOf(credManager.isBiometricEnabled()) }
    var scrambleKeypad by remember { mutableStateOf(credManager.isScrambleKeypadEnabled()) }

    var inputSecret by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isErrorState by remember { mutableStateOf(false) }
    var showSetupSheet by rememberSaveable { mutableStateOf(false) }
    var autoPromptedBiometric by rememberSaveable { mutableStateOf(false) }

    // Cooldown countdown state for brute-force protection
    var lockoutSeconds by remember { mutableIntStateOf(credManager.getRemainingLockoutSeconds()) }

    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            while (lockoutSeconds > 0) {
                delay(1000L)
                lockoutSeconds = credManager.getRemainingLockoutSeconds()
            }
            statusMessage = ""
            isErrorState = false
        }
    }

    // Horizontal shake animation when wrong PIN/Password is entered
    val shakeOffset = remember { Animatable(0f) }
    fun triggerErrorShake(msg: String) {
        isErrorState = true
        statusMessage = msg
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            for (i in 0..3) {
                shakeOffset.animateTo(if (i % 2 == 0) 18f else -18f, tween(45))
            }
            shakeOffset.animateTo(0f, tween(45))
        }
    }

    // Keypad digits (scrambled if enabled)
    val keypadDigits = remember(scrambleKeypad, hasCustomCred) {
        val base = (1..9).map { it.toString() }
        if (scrambleKeypad) (base + "0").shuffled() else base + "0"
    }

    BackHandler {
        if (isGlobalAppGuard) {
            activity?.moveTaskToBack(true)
        } else {
            onBack()
        }
    }

    val canUseSysAuth = remember(context) { viewModel.canUseSystemAuthentication() }
    val hasBioHardware = remember(context) { isBiometricHardwareReady(context) }

    // Trigger Biometric / System Device Credential
    val triggerSystemOrBiometricAuth: (Boolean) -> Unit = { forceDeviceCredential ->
        if (activity == null) {
            Toast.makeText(context, "Activity context unavailable", Toast.LENGTH_SHORT).show()
        } else if (!canUseSysAuth && !hasBioHardware) {
            if (!hasCustomCred) {
                showSetupSheet = true
            } else {
                triggerErrorShake("No biometric hardware enrolled on device")
            }
        } else {
            val useDeviceCred = forceDeviceCredential || !hasCustomCred || !hasBioHardware
            launchBiometricPrompt(
                activity = activity,
                title = if (isGlobalAppGuard) "GalleryBox App Lock" else "Secure Vault",
                subtitle = if (useDeviceCred) "Verify with your biometrics or screen lock" else "Scan fingerprint or face to unlock",
                negativeButtonText = if (authMode == VaultAuthMode.PASSWORD) "Use Password" else "Use PIN",
                allowDeviceCredentialFallback = useDeviceCred,
                onSuccess = {
                    credManager.resetFailedAttempts()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.onAuthenticationSuccess()
                },
                onNegativeOrCanceled = {
                    // Stay on lock screen so user can type PIN/Password or retry
                    statusMessage = if (hasCustomCred) {
                        "Enter your Vault ${if (authMode == VaultAuthMode.PASSWORD) "Password" else "PIN"}"
                    } else {
                        "Authentication canceled. Tap Unlock to try again."
                    }
                    isErrorState = false
                },
                onLockoutOrError = { _, errStr ->
                    isErrorState = true
                    statusMessage = errStr
                }
            )
        }
    }

    // Forgot PIN/Password recovery via Device Screen Lock
    val triggerForgotCredentialRecovery = {
        if (activity != null && canUseSysAuth) {
            launchBiometricPrompt(
                activity = activity,
                title = "Reset Vault Credential",
                subtitle = "Confirm device screen lock to set a new Vault PIN/Password",
                allowDeviceCredentialFallback = true,
                onSuccess = {
                    credManager.clearCustomCredential()
                    hasCustomCred = false
                    inputSecret = ""
                    statusMessage = "Identity verified. Set a new PIN or Password."
                    isErrorState = false
                    showSetupSheet = true
                },
                onLockoutOrError = { _, err ->
                    triggerErrorShake(err)
                }
            )
        } else {
            Toast.makeText(context, "Set up a phone screen lock in Android Settings first", Toast.LENGTH_LONG).show()
        }
    }

    // Submit PIN / Password verification
    fun verifyCurrentInput(candidate: String = inputSecret) {
        if (lockoutSeconds > 0) {
            triggerErrorShake("Locked out. Try again in ${lockoutSeconds}s")
            return
        }
        if (candidate.isBlank()) return

        if (credManager.verifyCredential(candidate)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            inputSecret = ""
            viewModel.onAuthenticationSuccess()
        } else {
            inputSecret = ""
            lockoutSeconds = credManager.getRemainingLockoutSeconds()
            val attempts = credManager.getFailedAttempts()
            if (lockoutSeconds > 0) {
                triggerErrorShake("Too many failed attempts. Wait ${lockoutSeconds}s")
            } else {
                val remainingBeforeLockout = (5 - (attempts % 5)).coerceIn(1, 5)
                triggerErrorShake("Incorrect ${if (authMode == VaultAuthMode.PASSWORD) "Password" else "PIN"} ($remainingBeforeLockout attempts left)")
            }
        }
    }

    // Auto-launch biometric scanner once when entering screen
    LaunchedEffect(Unit) {
        if (!autoPromptedBiometric) {
            autoPromptedBiometric = true
            if (biometricEnabled && (hasBioHardware || (!hasCustomCred && canUseSysAuth))) {
                triggerSystemOrBiometricAuth(!hasCustomCred)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0C12),
                        Color(0xFF101420),
                        Color(0xFF08090D)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Bar (Back / Exit + Setup Custom PIN button if none exists)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isGlobalAppGuard) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(42.dp))
            }

            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isGlobalAppGuard) "One UI App Guard" else "Knox-Style Secure Vault",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = {
                    if (hasCustomCred) {
                        triggerForgotCredentialRecovery()
                    } else {
                        showSetupSheet = true
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = "Configure PIN or Password",
                    tint = Color.White
                )
            }
        }

        // Main Center Authentication Column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated Glowing Shield Emblem
            Surface(
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(24.dp),
                color = if (isErrorState || lockoutSeconds > 0) {
                    Color(0x33FF5252)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                },
                border = BorderStroke(
                    1.5.dp,
                    if (isErrorState || lockoutSeconds > 0) {
                        Color(0xFFFF5252)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    }
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            lockoutSeconds > 0 -> Icons.Rounded.Timer
                            isErrorState -> Icons.Rounded.GppBad
                            else -> Icons.Rounded.Lock
                        },
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = if (isErrorState || lockoutSeconds > 0) Color(0xFFFF6B6B) else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when {
                    lockoutSeconds > 0 -> "Try Again in ${lockoutSeconds}s"
                    isGlobalAppGuard -> "GalleryBox Locked"
                    else -> "Secure Vault"
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when {
                    statusMessage.isNotBlank() -> statusMessage
                    hasCustomCred && authMode == VaultAuthMode.PIN -> "Enter your $targetPinLength-digit Vault PIN or scan biometrics"
                    hasCustomCred && authMode == VaultAuthMode.PASSWORD -> "Enter your Vault password or scan biometrics"
                    else -> "Verify your identity with fingerprint, face, or screen lock"
                },
                color = if (isErrorState) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- CASE 1: CUSTOM PIN KEYPAD ---
            if (hasCustomCred && authMode == VaultAuthMode.PIN) {
                // PIN Dots Indicator Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    for (i in 0 until targetPinLength) {
                        val filled = i < inputSecret.length
                        val dotScale by animateFloatAsState(
                            targetValue = if (filled) 1.2f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "pin_dot"
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    scaleX = dotScale
                                    scaleY = dotScale
                                }
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isErrorState -> Color(0xFFFF5252)
                                        filled -> Color.White
                                        else -> Color.White.copy(alpha = 0.20f)
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (filled) Color.White else Color.White.copy(alpha = 0.35f),
                                    CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3x4 Samsung One UI Glassmorphic Keypad
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    for (row in 0 until 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (col in 0 until 3) {
                                val digit = keypadDigits[row * 3 + col]
                                VaultPinKeyButton(
                                    digit = digit,
                                    enabled = lockoutSeconds == 0,
                                    onClick = {
                                        if (inputSecret.length < targetPinLength) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            isErrorState = false
                                            statusMessage = ""
                                            val updated = inputSecret + digit
                                            inputSecret = updated
                                            if (updated.length == targetPinLength) {
                                                verifyCurrentInput(updated)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Bottom Keypad Row: [Biometric] [0] [Backspace]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Biometric / System Auth Key
                        Surface(
                            onClick = { triggerSystemOrBiometricAuth(false) },
                            modifier = Modifier.size(68.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Fingerprint,
                                    contentDescription = "Biometric Unlock",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        // Last digit (0 or scrambled 10th digit)
                        val tenthDigit = keypadDigits[9]
                        VaultPinKeyButton(
                            digit = tenthDigit,
                            enabled = lockoutSeconds == 0,
                            onClick = {
                                if (inputSecret.length < targetPinLength) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isErrorState = false
                                    statusMessage = ""
                                    val updated = inputSecret + tenthDigit
                                    inputSecret = updated
                                    if (updated.length == targetPinLength) {
                                        verifyCurrentInput(updated)
                                    }
                                }
                            }
                        )

                        // Backspace Key
                        Surface(
                            onClick = {
                                if (inputSecret.isNotEmpty()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    inputSecret = inputSecret.dropLast(1)
                                }
                            },
                            modifier = Modifier.size(68.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                    contentDescription = "Backspace",
                                    tint = Color.White.copy(alpha = if (inputSecret.isNotEmpty()) 0.95f else 0.35f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
            // --- CASE 2: CUSTOM ALPHANUMERIC PASSWORD INPUT ---
            else if (hasCustomCred && authMode == VaultAuthMode.PASSWORD) {
                OutlinedTextField(
                    value = inputSecret,
                    onValueChange = {
                        isErrorState = false
                        statusMessage = ""
                        inputSecret = it
                    },
                    enabled = lockoutSeconds == 0,
                    singleLine = true,
                    label = { Text("Vault Password") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle password visibility",
                                tint = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { verifyCurrentInput() }),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hasBioHardware || canUseSysAuth) {
                        FilledTonalButton(
                            onClick = { triggerSystemOrBiometricAuth(false) },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.Fingerprint, contentDescription = "Biometrics")
                        }
                    }
                    Button(
                        onClick = { verifyCurrentInput() },
                        enabled = inputSecret.isNotBlank() && lockoutSeconds == 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unlock Vault", fontWeight = FontWeight.Bold)
                    }
                }
            }
            // --- CASE 3: SYSTEM BIOMETRICS / DEVICE CREDENTIAL ONLY (NO CUSTOM PIN SET YET) ---
            else {
                Button(
                    onClick = { triggerSystemOrBiometricAuth(true) },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Unlock with Biometrics / PIN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showSetupSheet = true },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                ) {
                    Icon(Icons.Outlined.Password, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create Custom Vault PIN / Password", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            // Bottom helper links (Use Device Screen Lock / Forgot PIN)
            if (hasCustomCred && canUseSysAuth) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { triggerSystemOrBiometricAuth(true) }) {
                        Icon(Icons.Outlined.PhonelinkLock, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use Phone Lock", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                    TextButton(onClick = { triggerForgotCredentialRecovery() }) {
                        Text("Forgot ${if (authMode == VaultAuthMode.PASSWORD) "Password" else "PIN"}?", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showSetupSheet) {
        VaultSecuritySetupSheet(
            credManager = credManager,
            onDismiss = { showSetupSheet = false },
            onSaved = {
                hasCustomCred = credManager.hasCustomCredential()
                authMode = credManager.getAuthMode()
                targetPinLength = credManager.getPinLength()
                biometricEnabled = credManager.isBiometricEnabled()
                scrambleKeypad = credManager.isScrambleKeypadEnabled()
                inputSecret = ""
                statusMessage = "Vault security updated"
                isErrorState = false
                showSetupSheet = false
            }
        )
    }
}

@Composable
private fun VaultPinKeyButton(
    digit: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(68.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = digit,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * =========================================================================================
 * ⚙️ VAULT SECURITY SETTINGS & PIN/PASSWORD CREATION SHEET
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSecuritySetupSheet(
    credManager: VaultCredentialManager,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(credManager.getAuthMode()) }
    var firstSecret by remember { mutableStateOf("") }
    var confirmSecret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var bioEnabled by remember { mutableStateOf(credManager.isBiometricEnabled()) }
    var scrambleEnabled by remember { mutableStateOf(credManager.isScrambleKeypadEnabled()) }
    var errorMsg by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Vault Security Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Configure PIN, Password & Biometrics", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Mode Selector: Numeric PIN vs Alphanumeric Password
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VaultAuthMode.entries.forEach { mode ->
                    val selected = selectedMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedMode = mode
                            firstSecret = ""
                            confirmSecret = ""
                            errorMsg = ""
                        },
                        label = {
                            Text(
                                if (mode == VaultAuthMode.PIN) "Numeric PIN (4–8 digits)" else "Password",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (mode == VaultAuthMode.PIN) Icons.Outlined.Dialpad else Icons.Outlined.Password,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                value = firstSecret,
                onValueChange = { value ->
                    errorMsg = ""
                    firstSecret = if (selectedMode == VaultAuthMode.PIN) {
                        value.filter { it.isDigit() }.take(8)
                    } else {
                        value.take(64)
                    }
                },
                singleLine = true,
                label = { Text(if (selectedMode == VaultAuthMode.PIN) "New PIN (4–8 digits)" else "New Password (min 4 chars)") },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (selectedMode == VaultAuthMode.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = confirmSecret,
                onValueChange = { value ->
                    errorMsg = ""
                    confirmSecret = if (selectedMode == VaultAuthMode.PIN) {
                        value.filter { it.isDigit() }.take(8)
                    } else {
                        value.take(64)
                    }
                },
                singleLine = true,
                label = { Text(if (selectedMode == VaultAuthMode.PIN) "Confirm PIN" else "Confirm Password") },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (selectedMode == VaultAuthMode.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }

            // Biometric & Scramble Toggles
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Biometric Quick Unlock", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Allow fingerprint or face unlock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = bioEnabled,
                            onCheckedChange = {
                                bioEnabled = it
                                credManager.setBiometricEnabled(it)
                            }
                        )
                    }

                    if (selectedMode == VaultAuthMode.PIN) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scramble PIN Keypad", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Randomize digit layout each time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = scrambleEnabled,
                                onCheckedChange = {
                                    scrambleEnabled = it
                                    credManager.setScrambleKeypadEnabled(it)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (credManager.hasCustomCredential()) {
                    OutlinedButton(
                        onClick = {
                            credManager.clearCustomCredential()
                            Toast.makeText(context, "Custom Vault credential removed", Toast.LENGTH_SHORT).show()
                            onSaved()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Remove PIN", fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        when {
                            firstSecret.isBlank() && confirmSecret.isBlank() && credManager.hasCustomCredential() -> {
                                // Only updated switches
                                onSaved()
                            }
                            firstSecret.length < 4 -> {
                                errorMsg = "Must be at least 4 ${if (selectedMode == VaultAuthMode.PIN) "digits" else "characters"}"
                            }
                            firstSecret != confirmSecret -> {
                                errorMsg = "Entries do not match"
                            }
                            else -> {
                                credManager.saveCredential(firstSecret, selectedMode)
                                Toast.makeText(context, "Vault ${if (selectedMode == VaultAuthMode.PIN) "PIN" else "Password"} saved!", Toast.LENGTH_SHORT).show()
                                onSaved()
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Save Security", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ THE SECURE GRID (VaultGridScreen)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VaultGridScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    val credManager = remember(context) { VaultCredentialManager(context) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var viewerItemId by remember { mutableStateOf<Long?>(null) }
    var showSecuritySheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = viewerItemId != null || selectionMode) {
        if (viewerItemId != null) {
            viewerItemId = null
        } else if (selectionMode) {
            selectionMode = false
            selectedIds.clear()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (selectionMode) "${selectedIds.size} selected" else "Secure Vault",
                                fontWeight = FontWeight.Bold
                            )
                            if (!selectionMode) {
                                Text(
                                    text = "${items.size} encrypted items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds.clear()
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        if (!selectionMode) {
                            IconButton(onClick = { showSecuritySheet = true }) {
                                Icon(imageVector = Icons.Outlined.Security, contentDescription = "Vault Security Settings")
                            }
                            IconButton(onClick = onAdd) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                            }
                            IconButton(
                                onClick = {
                                    securityViewModel.lock()
                                    viewModel.clearTempVaultCache()
                                }
                            ) {
                                Icon(imageVector = Icons.Outlined.Lock, contentDescription = "Lock Vault Now")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionItem(
                                icon = Icons.Outlined.LockOpen,
                                label = "Unhide"
                            ) {
                                viewModel.unhideMedia(selectedIds.keys.toList())
                                selectedIds.clear()
                                selectionMode = false
                            }

                            ActionItem(
                                icon = Icons.Outlined.Share,
                                label = "Export"
                            ) {
                                val exportItems = items.filter { selectedIds.containsKey(it.id) }
                                scope.launch {
                                    stripExifAndShare(context, exportItems, viewModel)
                                    selectedIds.clear()
                                    selectionMode = false
                                }
                            }

                            ActionItem(
                                icon = Icons.Outlined.Delete,
                                label = "Delete",
                                isDestructive = true
                            ) {
                                viewModel.deleteSecureMedia(selectedIds.keys.toList())
                                selectedIds.clear()
                                selectionMode = false
                            }
                        }
                    }
                }
            }
        ) { padding ->
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Vault is empty", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp, start = 2.dp, end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = selectedIds.containsKey(item.id)
                        val tileScale by animateFloatAsState(
                            targetValue = if (isSelected) 0.90f else 1f,
                            label = "tileScale"
                        )
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = tileScale
                                    scaleY = tileScale
                                    clip = true
                                    shape = RoundedCornerShape(if (isSelected) 12.dp else 0.dp)
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            if (selectedIds.containsKey(item.id)) {
                                                selectedIds.remove(item.id)
                                            } else {
                                                selectedIds[item.id] = item.size
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } else {
                                            viewerItemId = item.id
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds[item.id] = item.size
                                        }
                                    }
                                )
                        ) {
                            SecureAsyncImage(
                                item = item,
                                viewModel = viewModel,
                                isThumbnail = true,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (item.isVideo) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp)
                                )
                            }

                            if (selectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                        .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(if (isSelected) 12.dp else 0.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(0.3f), CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- FULL SCREEN VIEWER OVERLAY ---
        AnimatedVisibility(
            visible = viewerItemId != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (viewerItemId != null) {
                SecureFullscreenViewer(
                    initialIndex = items.indexOfFirst { it.id == viewerItemId }.coerceAtLeast(0),
                    items = items,
                    viewModel = viewModel,
                    onBack = { viewerItemId = null }
                )
            }
        }

        if (showSecuritySheet) {
            VaultSecuritySetupSheet(
                credManager = credManager,
                onDismiss = { showSecuritySheet = false },
                onSaved = { showSecuritySheet = false }
            )
        }
    }
}

/**
 * =========================================================================================
 * 🕵️ THE DECRYPTION ROOM (SecureFullscreenViewer)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SecureFullscreenViewer(
    initialIndex: Int,
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val containerHeightPx = LocalWindowInfo.current.containerSize.height

    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    var showControls by remember { mutableStateOf(true) }
    var showMeta by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(context.findActivity()) {
        val w = context.findActivity()?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, view)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            w?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = !showControls) {
        showControls = true
    }
    BackHandler(enabled = showControls) {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            var tempFile by remember(item.id) { mutableStateOf<File?>(null) }
            var videoLoading by remember(item.id) { mutableStateOf(item.isVideo) }
            var trigger by remember { mutableIntStateOf(0) }

            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var isVideoPlaying by remember(item.id) { mutableStateOf(false) }

            DisposableEffect(item.id) {
                onDispose {
                    if (exoPlayer.currentMediaItem?.mediaId == item.id.toString()) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                    tempFile?.let {
                        it.delete()
                        viewModel.deleteTempFile(it)
                    }
                    isVideoPlaying = false
                }
            }

            LaunchedEffect(item.id, trigger) {
                if (item.isVideo) {
                    videoLoading = true
                    tempFile = withContext(Dispatchers.IO) {
                        viewModel.decryptToTempFile(item.path)
                    }
                    tempFile?.deleteOnExit()
                    videoLoading = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                    .graphicsLayer {
                        scaleX = scale * (1f - (abs(dragOffsetY) / 2000f))
                        scaleY = scaleX
                        alpha = 1f - (abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction) * 0.3f)
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 3f)
                            if (scale > 1.05f) {
                                offset += pan
                                dragOffsetY = 0f
                            } else {
                                offset = Offset.Zero
                                dragOffsetY += pan.y
                                if (abs(dragOffsetY) > 50f) {
                                    showControls = false
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val ev = awaitPointerEvent()
                            } while (ev.changes.any { it.pressed })

                            if (scale <= 1.05f && abs(dragOffsetY) > containerHeightPx * 0.25f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onBack()
                            } else {
                                dragOffsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    videoLoading -> CircularProgressIndicator()

                    !item.isVideo -> SecureAsyncImage(
                        item = item,
                        viewModel = viewModel,
                        isThumbnail = false,
                        modifier = Modifier.fillMaxSize()
                    )

                    item.isVideo && tempFile != null -> {
                        if (isVideoPlaying) {
                            AndroidView(
                                factory = {
                                    PlayerView(context).apply {
                                        player = exoPlayer
                                        useController = true
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = {
                                    if (exoPlayer.currentMediaItem?.mediaId != item.id.toString()) {
                                        exoPlayer.clearMediaItems()
                                        exoPlayer.setMediaItem(
                                            ExoMediaItem.Builder()
                                                .setUri(Uri.fromFile(tempFile))
                                                .setMediaId(item.id.toString())
                                                .build()
                                        )
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                SecureAsyncImage(
                                    item = item,
                                    viewModel = viewModel,
                                    isThumbnail = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = {
                                        isVideoPlaying = true
                                        exoPlayer.playWhenReady = true
                                        showControls = false
                                    },
                                    modifier = Modifier
                                        .size(80.dp)
                                        .background(Color.Black.copy(0.4f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayCircle,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(text = "Decryption Failure", color = Color.White)
                        TextButton(onClick = { trigger++ }) {
                            Text(text = "Retry")
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                if (items.isNotEmpty()) {
                    Text(
                        text = items[pagerState.currentPage].dateHeader,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { showMeta = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    }

    if (showMeta) {
        val currentItem = items.getOrNull(pagerState.currentPage)
        val context = LocalContext.current
        val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale

        if (currentItem != null) {
            val dateString = remember(currentItem.dateAdded, locale) {
                val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) {
                    "EEEE, MMMM dd, yyyy 'at' HH:mm"
                } else {
                    "EEEE, MMMM dd, yyyy 'at' hh:mm a"
                }
                SimpleDateFormat(pattern, locale).format(Date(currentItem.dateAdded * 1000L))
            }

            ModalBottomSheet(onDismissRequest = { showMeta = false }) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Secure File Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    MetadataRow(
                        icon = Icons.Outlined.Title,
                        label = "Name",
                        value = currentItem.name
                    )
                    MetadataRow(
                        icon = Icons.Outlined.Folder,
                        label = "Encrypted Container Path",
                        value = currentItem.path
                    )
                    MetadataRow(
                        icon = Icons.Outlined.CalendarToday,
                        label = "Injected Timestamp",
                        value = dateString
                    )
                    MetadataRow(
                        icon = Icons.Outlined.Storage,
                        label = "Size",
                        value = android.text.format.Formatter.formatFileSize(context, currentItem.size)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Directly pulling this asset bypasses privacy buffers. Use the metadata scrub export tool for secure transmission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            showMeta = false
        }
    }
}

@Composable
fun SecureAsyncImage(
    item: MediaItem,
    viewModel: GalleryViewModel,
    isThumbnail: Boolean,
    modifier: Modifier = Modifier
) {
    var bytes by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var trigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    DisposableEffect(item.id) {
        onDispose {
            bytes = null
        }
    }

    LaunchedEffect(item.id, trigger) {
        loading = true
        if (!item.isVideo) {
            bytes = withContext(Dispatchers.IO) {
                if (isThumbnail) viewModel.decryptThumbnailToMemory(item.path) else viewModel.decryptToMemory(item.path)
            }
        }
        loading = false
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            item.isVideo && isThumbnail -> AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).videoFrameMillis(1000).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            bytes != null -> AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(bytes)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false)
                    .build(),
                contentScale = if (isThumbnail) ContentScale.Crop else ContentScale.Fit,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Default.BrokenImage, contentDescription = null)
                if (!isThumbnail) {
                    TextButton(onClick = { trigger++ }) {
                        Text(text = "Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun ActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        val color = if (!enabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        } else if (isDestructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = color
        )
    }
}

@Composable
fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun VaultShakeDetector(onShakeDetected: () -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(sensorManager, accelerometer) {
        if (accelerometer == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            private var lastUpdate = 0L
            private var lastX = 0f
            private var lastY = 0f
            private var lastZ = 0f
            private val SHAKE_THRESHOLD = 800f

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val currentTime = System.currentTimeMillis()

                if ((currentTime - lastUpdate) > 100) {
                    val diffTime = currentTime - lastUpdate
                    lastUpdate = currentTime
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    val speed: Float = kotlin.math.abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000f

                    if (speed > SHAKE_THRESHOLD) {
                        onShakeDetected()
                    }

                    lastX = x
                    lastY = y
                    lastZ = z
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}

suspend fun secureWipeFile(file: File) = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext
    try {
        val random = SecureRandom()
        RandomAccessFile(file, "rws").use { raf ->
            val b = ByteArray(4096)
            var w = 0L
            while (w < file.length()) {
                random.nextBytes(b)
                val t = minOf(b.size.toLong(), file.length() - w).toInt()
                raf.write(b, 0, t)
                w += t
            }
        }
    } finally {
        file.delete()
    }
}

private suspend fun stripExifAndShare(
    context: Context,
    items: List<MediaItem>,
    viewModel: GalleryViewModel
) {
    withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        for (item in items) {
            val f = viewModel.decryptToTempFile(item.path) ?: continue
            f.deleteOnExit()

            if (!item.isVideo && (item.mimeType.contains("jpeg") || item.mimeType.contains("jpg"))) {
                try {
                    ExifInterface(f.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                        setAttribute(ExifInterface.TAG_DATETIME, null)
                        setAttribute(ExifInterface.TAG_MAKE, "SecureVault")
                        setAttribute(ExifInterface.TAG_MODEL, "SecureVault")
                        saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.e("VaultShare", "EXIF clean skipped", e)
                }
            }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.provider", f))
        }
        withContext(Dispatchers.Main) {
            if (uris.isNotEmpty()) {
                val intent = Intent().apply {
                    action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                    type = "*/*"
                    if (uris.size > 1) {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    } else {
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share Securely"))
            }
        }
    }
}