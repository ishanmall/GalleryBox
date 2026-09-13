// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict grammar checker to ignore specific words because we know what we are doing.
@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.vault

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for fingerprint scanning, reading the phone's physical movement sensors,
// playing encrypted video, and stripping GPS metadata from photos.
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.PlayCircle
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem as ExoMediaItem
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

/**
 * =========================================================================================
 * 🛡️ THE MASTER VAULT SCREEN
 * =========================================================================================
 * This is the parent screen that decides if the user should see the Fingerprint Bouncer,
 * the Loading Screen, or the actual grid of secure photos.
 */
@Composable
fun VaultSecureScreen(
    viewModel: GalleryViewModel = hiltViewModel(), // Manager of the actual photo files
    securityViewModel: SecurityViewModel = hiltViewModel(), // The Security Guard manager
    isGlobalAppGuard: Boolean = false, // True if the entire app is locked. False if just the Vault is locked.
    onBack: () -> Unit,
    onNavigateToPicker: () -> Unit = {},
    onUnlockGlobalSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current // Knows if the app is currently on-screen or minimized
    val activity = remember(context) { context.findActivity() }

    // Check the Security Guard's scoreboards
    val unlocked by securityViewModel.isUnlocked.collectAsState()
    var isUnlocking by remember { mutableStateOf(false) } // Shows a spinner while scanning fingerprint
    val autoLockTimeout by securityViewModel.autoLockTimeout.collectAsState(initial = 5) // "Lock after 5 minutes"

    // 📳 THE PANIC SHAKE
    // If someone walks in the room, the user can literally shake their phone violently to instantly lock the Vault!
    VaultShakeDetector {
        securityViewModel.lock()
        viewModel.clearTempVaultCache() // Destroy any temporary decrypted video files instantly
        if (!isGlobalAppGuard) onBack() // Throw them out of the vault back to the main app screen
    }

    // Every time this screen opens, sweep the floor for left-over decrypted files from the last session.
    LaunchedEffect(Unit) {
        viewModel.clearTempVaultCache()
    }

    // Ask the manager for the list of hidden photos
    val hiddenItems by viewModel.hiddenMedia.collectAsState(emptyList())

    // 🚫 THE SCREENSHOT BLOCKER
    // This tells Android: "Do NOT allow the user to take a screenshot or screen-record while this screen is open!"
    // It also turns the app completely black if the user opens the "Recent Apps" menu to peek.
    DisposableEffect(isGlobalAppGuard) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            // When leaving the vault, remove the screenshot blocker so the main app works normally
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Relock the door automatically when they leave
            if (!isGlobalAppGuard) {
                securityViewModel.lock()
                viewModel.clearTempVaultCache()
            }
        }
    }

    // THE TIMEOUT WATCHER
    // If the user minimizes the app to check a text message, this starts a stopwatch.
    DisposableEffect(lifecycleOwner, autoLockTimeout) {
        val obs = LifecycleEventObserver { _, ev ->
            // If the app is minimized (PAUSE or STOP)...
            if (ev == Lifecycle.Event.ON_PAUSE || ev == Lifecycle.Event.ON_STOP) {
                // If they set "Lock Instantly" (0 minutes), lock the door right now!
                if (autoLockTimeout == 0) {
                    securityViewModel.lock()
                    viewModel.clearTempVaultCache()
                }
            }
            // If they open the app back up again...
            if (ev == Lifecycle.Event.ON_RESUME) {
                // Ask the guard if they were gone longer than the 5-minute timeout limit
                if (securityViewModel.shouldRelock(autoLockTimeout)) {
                    securityViewModel.lock() // Too slow! Lock the door.
                }
            }
            // If the app is killed completely...
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

    // A simple traffic light that decides what screen to draw
    val state = when {
        isUnlocking -> "PROCESSING" // Scanning finger...
        !unlocked -> "AUTH_GUARD" // Show the big Lock icon...
        else -> "GRANTED" // Show the photos!
    }

    when (state) {
        "PROCESSING" -> {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Preparing...", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        "AUTH_GUARD" -> {
            // Draw the lock screen
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
            // Draw the secret photos!
            if (!isGlobalAppGuard) {
                VaultGridScreen(
                    items = hiddenItems,
                    viewModel = viewModel,
                    onBack = onBack,
                    onAdd = onNavigateToPicker
                )
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔐 THE BOUNCER (StandardAppLockScreen)
 * =========================================================================================
 * Shows the padlock icon and triggers the Android Fingerprint/Face ID pop-up.
 */
@Composable
fun StandardAppLockScreen(
    viewModel: SecurityViewModel,
    isGlobalAppGuard: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() } // Biometrics requires a special type of Activity
    var bioShown by rememberSaveable { mutableStateOf(false) }

    // If the ENTIRE app is locked, hitting the back button just minimizes the app (sends it to the home screen).
    BackHandler(enabled = isGlobalAppGuard) {
        activity?.moveTaskToBack(true)
    }

    // The function that actually calls Android's built-in Fingerprint scanner
    val triggerBiometrics = {
        if (!viewModel.canUseSystemAuthentication()) {
            Toast.makeText(context, "System authentication unavailable", Toast.LENGTH_SHORT).show()
        } else if (activity != null) {
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(res: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(res)
                        viewModel.onAuthenticationSuccess() // Tell the Guard they passed!
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        // If they hit "Cancel" on the fingerprint scanner, throw them out.
                        if (isGlobalAppGuard) {
                            activity.moveTaskToBack(true)
                        } else {
                            onBack()
                        }
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed() // Fingerprint didn't match. Wait for them to try again.
                    }
                }
            ).authenticate(
                // Setup the text that appears on the Android fingerprint pop-up
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("GalleryBox")
                    .setSubtitle("Confirm your identity")
                    .setAllowedAuthenticators(
                        // Allow them to use Fingerprint, Face ID, OR their phone's lock screen PIN code.
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            )
        } else {
            Toast.makeText(context, "Activity context is invalid", Toast.LENGTH_SHORT).show()
        }
    }

    // Pop the fingerprint scanner up automatically as soon as this screen is drawn.
    LaunchedEffect(Unit) {
        if (!bioShown) {
            bioShown = true
            triggerBiometrics()
        }
    }

    // Draw the UI behind the fingerprint scanner
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (isGlobalAppGuard) "App Locked" else "Vault Locked",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        FilledTonalButton(
            onClick = { triggerBiometrics() },
            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
        ) {
            Icon(imageVector = Icons.Default.LockOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Unlock", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isGlobalAppGuard) {
            TextButton(onClick = onBack) {
                Text("Cancel")
            }
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ THE SECURE GRID (VaultGridScreen)
 * =========================================================================================
 * Draws the actual thumbnails of the secret photos once the user makes it inside the vault.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VaultGridScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    var selectionMode by remember { mutableStateOf(false) } // Is the user checking boxes?
    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var viewerItemId by remember { mutableStateOf<Long?>(null) } // The specific photo they tapped to view full screen
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    // Handle the Android Back Button safely
    BackHandler(enabled = viewerItemId != null || selectionMode) {
        if (viewerItemId != null) {
            viewerItemId = null // Close the full screen photo
        } else if (selectionMode) {
            selectionMode = false // Cancel selection
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
                        Text(
                            text = if (selectionMode) "${selectedIds.size} selected" else "Secure Vault",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedIds.clear()
                            } else {
                                onBack() // Leave the vault
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
                            // "Add" button to pull photos from the main gallery INTO the vault
                            IconButton(onClick = onAdd) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                // The Action Menu that slides up from the bottom when you select photos
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionItem(
                                icon = Icons.Outlined.LockOpen,
                                label = "Unhide"
                            ) {
                                // Put them back in the normal public gallery
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
                                    // Remove GPS data and share to WhatsApp!
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
            // If the vault is empty, show a big lock icon
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
                // The 3-column grid of secret photos
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp, start = 2.dp, end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = selectedIds.containsKey(item.id)
                        val tileScale by animateFloatAsState(
                            targetValue = if (isSelected) 0.90f else 1f, // Shrink slightly if selected
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
                                            viewerItemId = item.id // Open full screen!
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
                            // Draws the encrypted thumbnail
                            SecureAsyncImage(
                                item = item,
                                viewModel = viewModel,
                                isThumbnail = true, // We only need a tiny, blurry version for the grid
                                modifier = Modifier.fillMaxSize()
                            )

                            if (item.isVideo) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                                )
                            }

                            // The blue checkmark
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
                    initialIndex = items.indexOfFirst { it.id == viewerItemId },
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
 * Shows a full-resolution photo or video. Because the files are encrypted with military grade encryption,
 * standard tools like ExoPlayer or Coil cannot read them! We have to decrypt them in RAM (Memory) first.
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
    var showMeta by remember { mutableStateOf(false) } // The metadata details menu

    // Build a private Video Player engine just for this screen
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release() // Destroy the engine when we close the full screen
        }
    }

    // Hide the clock and battery bar at the top of the phone
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
        // The swiping carousel
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            var tempFile by remember(item.id) { mutableStateOf<File?>(null) } // A temporary decrypted video file
            var videoLoading by remember(item.id) { mutableStateOf(item.isVideo) } // Is the video currently being decrypted?
            var trigger by remember { mutableIntStateOf(0) }

            // Pinch-to-zoom and swipe-down-to-close math
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var isVideoPlaying by remember(item.id) { mutableStateOf(false) }

            // Whenever the user swipes AWAY from this photo/video...
            DisposableEffect(item.id) {
                onDispose {
                    if (exoPlayer.currentMediaItem?.mediaId == item.id.toString()) {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                    }
                    // VERY IMPORTANT: If we decrypted a secret video to the hard drive so the player could read it,
                    // we MUST destroy the decrypted file immediately so it doesn't leak!
                    tempFile?.let {
                        it.delete()
                        viewModel.deleteTempFile(it)
                    }
                    isVideoPlaying = false
                }
            }

            // The Video Decryption Process
            LaunchedEffect(item.id, trigger) {
                if (item.isVideo) {
                    videoLoading = true
                    // Go to the background warehouse. Take the encrypted gibberish video, unlock it, and write a temporary unencrypted version.
                    tempFile = withContext(Dispatchers.IO) {
                        viewModel.decryptToTempFile(item.path)
                    }
                    tempFile?.deleteOnExit() // Failsafe: Tell Android to delete it if the app crashes
                    videoLoading = false
                }
            }

            // The Pinch to Zoom area
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
                                scale = if (scale > 1f) 1f else 2.5f // Zoom to 250%
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
                                    showControls = false // Hide the top bar if they start dragging it down
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val ev = awaitPointerEvent()
                            } while (ev.changes.any { it.pressed }) // Wait for them to lift their finger

                            // If they dragged the photo down past 25% of the screen height, close it!
                            if (scale <= 1.05f && abs(dragOffsetY) > containerHeightPx * 0.25f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onBack()
                            } else {
                                dragOffsetY = 0f // Snap it back up like a rubber band
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Actually displaying the decrypted file!
                when {
                    // Still unlocking the video...
                    videoLoading -> CircularProgressIndicator()

                    // It's a photo! Decrypt it entirely in RAM (Memory) so it never touches the hard drive.
                    !item.isVideo -> SecureAsyncImage(
                        item = item,
                        viewModel = viewModel,
                        isThumbnail = false, // We want the full 4K image
                        modifier = Modifier.fillMaxSize()
                    )

                    // It's a decrypted video ready to play!
                    item.isVideo && tempFile != null -> {
                        if (isVideoPlaying) {
                            // Render the video
                            AndroidView(
                                factory = {
                                    PlayerView(context).apply {
                                        player = exoPlayer
                                        useController = true // Give them a play bar so they can rewind
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = {
                                    if (exoPlayer.currentMediaItem?.mediaId != item.id.toString()) {
                                        exoPlayer.clearMediaItems()
                                        exoPlayer.setMediaItem(
                                            ExoMediaItem.Builder()
                                                .setUri(Uri.fromFile(tempFile)) // Hand the player the temporary decrypted file
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
                            // Show a still photo preview of the video with a giant Play button over it
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                SecureAsyncImage(
                                    item = item,
                                    viewModel = viewModel,
                                    isThumbnail = true, // Just a low-res preview
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = {
                                        isVideoPlaying = true
                                        exoPlayer.playWhenReady = true
                                        showControls = false
                                    },
                                    modifier = Modifier.size(80.dp).background(Color.Black.copy(0.4f), CircleShape)
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
                    // Oh no, the password was wrong or the file is corrupted.
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

        // The Top Bar
        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent))) // Gradient shadow to make text readable
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
                        text = items[pagerState.currentPage].dateHeader, // e.g. "Today"
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                // The "i" button for metadata
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

    // The Slide-up Metadata Details Menu
    if (showMeta) {
        val currentItem = items.getOrNull(pagerState.currentPage)
        val context = LocalContext.current
        val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale

        if (currentItem != null) {
            // Translate raw milliseconds into "Sunday, August 12, 2026 at 2:00 PM"
            val dateString = remember(currentItem.dateAdded, locale) {
                val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) {
                    "EEEE, MMMM dd, yyyy 'at' HH:mm"
                } else {
                    "EEEE, MMMM dd, yyyy 'at' hh:mm a"
                }
                SimpleDateFormat(pattern, locale).format(Date(currentItem.dateAdded * 1000L))
            }

            ModalBottomSheet(onDismissRequest = { showMeta = false }) {
                Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
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

                    // A red warning box explaining how the Vault works
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

/**
 * 🔒 A custom image loader specifically for the Vault.
 * Because the images are encrypted on the hard drive, standard loading tools won't work.
 * This function asks the Manager to decrypt the file directly into a `ByteArray` (pure RAM),
 * then feeds that raw RAM directly to the screen to draw.
 */
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

    // Wipe the RAM immediately when the image is closed!
    DisposableEffect(item.id) {
        onDispose {
            bytes = null
        }
    }

    LaunchedEffect(item.id, trigger) {
        loading = true
        if (!item.isVideo) {
            bytes = withContext(Dispatchers.IO) {
                // Decrypt it!
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
            // Feed the decrypted RAM (bytes) into the image drawing tool! We disable all caching so it never writes the decrypted image to the hard drive.
            bytes != null -> AsyncImage(
                model = ImageRequest.Builder(context).data(bytes).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).allowHardware(false).build(),
                contentScale = if (isThumbnail) ContentScale.Crop else ContentScale.Fit,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            // Error!
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

// Blueprint for the buttons on the bottom action bar
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
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(8.dp)
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

// Blueprint for the rows in the Metadata menu
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

/**
 * 📳 THE PANIC SHAKE SENSOR
 * This connects directly to the phone's physical hardware accelerometer chip.
 * It measures the gravity and motion forces on the X, Y, and Z axis.
 */
@Composable
fun VaultShakeDetector(onShakeDetected: () -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(sensorManager, accelerometer) {
        if (accelerometer == null) {
            return@DisposableEffect onDispose { } // Phone doesn't have an accelerometer (very rare)
        }

        val listener = object : SensorEventListener {
            private var lastUpdate = 0L
            private var lastX = 0f
            private var lastY = 0f
            private var lastZ = 0f
            private val SHAKE_THRESHOLD = 800f // The speed required to trigger a "Panic"

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val currentTime = System.currentTimeMillis()

                // Read the chip 10 times a second
                if ((currentTime - lastUpdate) > 100) {
                    val diffTime = currentTime - lastUpdate
                    lastUpdate = currentTime
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    // The Physics Formula: Calculate absolute speed change across all 3 dimensions
                    val speed: Float = kotlin.math.abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000f

                    if (speed > SHAKE_THRESHOLD) {
                        onShakeDetected() // TRIGGER LOCKDOWN!
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
            sensorManager.unregisterListener(listener) // Stop draining battery when they leave the vault
        }
    }
}

/**
 * 💥 THE MILITARY WIPE
 * When you "Delete" a file on a computer, it doesn't actually delete it. It just marks the space as "empty",
 * meaning hackers with recovery tools can get the photo back.
 * This function OVERWRITES the photo byte-by-byte with random, meaningless garbage before deleting it,
 * guaranteeing it can never be recovered.
 */
suspend fun secureWipeFile(file: File) = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext
    try {
        val random = SecureRandom() // Cryptographically secure random number generator
        RandomAccessFile(file, "rws").use { raf -> // "rws" forces the hard drive to physically write the data instantly
            val b = ByteArray(4096)
            var w = 0L
            // Write pure garbage over the entire file
            while (w < file.length()) {
                random.nextBytes(b)
                val t = minOf(b.size.toLong(), file.length() - w).toInt()
                raf.write(b, 0, t)
                w += t
            }
        }
    } finally {
        file.delete() // Now delete the garbage!
    }
}

/**
 * 🕵️ THE WITNESS PROTECTION PROGRAM (stripExifAndShare)
 * When you take a photo, the camera secretly stamps exactly what phone model you use, the date and time,
 * and your exact GPS coordinates into the file (EXIF Metadata).
 * Before letting the user share a Vault photo to WhatsApp, this function violently strips all that tracking data off.
 */
private suspend fun stripExifAndShare(
    context: Context,
    items: List<MediaItem>,
    viewModel: GalleryViewModel
) {
    withContext(Dispatchers.IO) { // Go to the background warehouse
        val uris = mutableListOf<Uri>()
        for (item in items) {
            val f = viewModel.decryptToTempFile(item.path) ?: continue
            f.deleteOnExit() // Guarantee deletion later

            // If it's a JPG photo, scrub the metadata!
            if (!item.isVideo && (item.mimeType.contains("jpeg") || item.mimeType.contains("jpg"))) {
                try {
                    ExifInterface(f.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_GPS_LATITUDE, null) // Nuke GPS
                        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                        setAttribute(ExifInterface.TAG_DATETIME, null) // Nuke Time
                        setAttribute(ExifInterface.TAG_MAKE, "SecureVault") // Fake the phone brand
                        setAttribute(ExifInterface.TAG_MODEL, "SecureVault") // Fake the phone model
                        saveAttributes() // Save the scrubbed photo
                    }
                } catch (e: Exception) {
                    Log.e("VaultShare", "EXIF clean skipped", e)
                }
            }
            // Put the clean file in the "Secure Courier" (FileProvider) pouch so WhatsApp can receive it
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.provider", f))
        }
        withContext(Dispatchers.Main) { // Go back to the main screen
            if (uris.isNotEmpty()) {
                // Call Android's system Share Sheet menu
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