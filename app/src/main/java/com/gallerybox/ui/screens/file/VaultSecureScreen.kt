@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.vault

// --- IMPORTS ---
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
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
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

// =========================================================================================
// 🔐 SYSTEM LOCK SCREEN & BIOMETRIC AUTHENTICATION HELPER
// =========================================================================================

/**
 * Launches Android's native BiometricPrompt configured to accept Biometrics (Fingerprint / Face)
 * OR the phone's default Screen Lock (PIN, Pattern, or Password).
 */
private fun launchSystemDeviceAuth(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onCanceled: () -> Unit = {},
    onError: (Int, String) -> Unit = { _, _ -> }
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
                    BiometricPrompt.ERROR_CANCELED -> onCanceled()
                    else -> onError(errorCode, errString.toString())
                }
            }
        }
    )

    try {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)

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

        prompt.authenticate(builder.build())
    } catch (e: Exception) {
        Log.e("VaultAuth", "BiometricPrompt launch failed", e)
        onError(-1, e.localizedMessage ?: "Authentication unavailable")
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
                    Text(
                        text = "Decrypting Vault...",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
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
 * 🔐 THE LOCK SCREEN (System Biometrics + Device PIN / Pattern / Password)
 * =========================================================================================
 */
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

    var statusMessage by remember { mutableStateOf("") }
    var isErrorState by remember { mutableStateOf(false) }
    var autoPromptedAuth by rememberSaveable { mutableStateOf(false) }

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

    BackHandler {
        if (isGlobalAppGuard) {
            activity?.moveTaskToBack(true)
        } else {
            onBack()
        }
    }

    val canUseSysAuth = remember(context) { viewModel.canUseSystemAuthentication() }

    val triggerDeviceAuth: () -> Unit = {
        if (activity == null) {
            Toast.makeText(context, "Activity context unavailable", Toast.LENGTH_SHORT).show()
        } else if (!canUseSysAuth) {
            triggerErrorShake("No screen lock set on this phone. Please set a PIN, Pattern, or Password in Android Settings.")
        } else {
            launchSystemDeviceAuth(
                activity = activity,
                title = if (isGlobalAppGuard) "GalleryBox App Lock" else "Secure Vault",
                subtitle = "Unlock with biometrics or your phone's screen lock (PIN, Pattern, or Password)",
                onSuccess = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.onAuthenticationSuccess()
                },
                onCanceled = {
                    statusMessage = "Authentication canceled. Tap below to unlock."
                    isErrorState = false
                },
                onError = { _, errStr ->
                    triggerErrorShake(errStr)
                }
            )
        }
    }

    // Auto-launch device lock prompt once when entering the screen
    LaunchedEffect(Unit) {
        if (!autoPromptedAuth) {
            autoPromptedAuth = true
            if (canUseSysAuth) {
                triggerDeviceAuth()
            } else {
                isErrorState = true
                statusMessage = "No phone screen lock detected. Set up a PIN, Pattern, or Password in Settings."
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
        // Top Bar
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
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
                        text = if (isGlobalAppGuard) "App Guard" else "Secure Vault",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.size(42.dp))
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
            Surface(
                modifier = Modifier.size(84.dp),
                shape = RoundedCornerShape(26.dp),
                color = if (isErrorState) {
                    Color(0x33FF5252)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                },
                border = BorderStroke(
                    1.5.dp,
                    if (isErrorState) {
                        Color(0xFFFF5252)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    }
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isErrorState) Icons.Rounded.GppBad else Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isErrorState) Color(0xFFFF6B6B) else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (isGlobalAppGuard) "GalleryBox Locked" else "Secure Vault",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (statusMessage.isNotBlank()) {
                    statusMessage
                } else {
                    "Use your fingerprint, face, or phone screen lock (PIN, Pattern, or Password) to unlock"
                },
                color = if (isErrorState) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { triggerDeviceAuth() },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(imageVector = Icons.Rounded.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Unlock with Screen Lock",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            if (!canUseSysAuth) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Open Android Security Settings",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
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
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var viewerItemId by remember { mutableStateOf<Long?>(null) }
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

                    val speed: Float = abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000f

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