// =========================================================================================
// 🤫 COMPILER INSTRUCTIONS
// =========================================================================================
@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "DEPRECATION")
@file:androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.gallerybox.ui.screens.videoplayer

// =========================================================================================
// 🧰 THE TOOLBOX (IMPORTS)
// =========================================================================================
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gallerybox.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

/**
 * =========================================================================================
 * 🛠️ HELPER CLASSES
 * =========================================================================================
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

enum class GestureMode { NONE, SCRUB, VOLUME, BRIGHTNESS }
// Kept for ViewModel binary compatibility if GalleryViewModel still references PremiumRepeatMode
enum class PremiumRepeatMode { OFF, ONE, ALL }

class VideoFrameLoader(private val context: Context) {
    private var retriever: MediaMetadataRetriever? = MediaMetadataRetriever()
    private val cache = object : LruCache<Long, Bitmap>(6144) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private var sourceUri: Uri? = null

    fun setSource(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        if (retriever == null) retriever = MediaMetadataRetriever()
        try {
            retriever?.setDataSource(context, uri)
            cache.evictAll()
        } catch (e: Exception) {
            try {
                retriever?.setDataSource(uri.path ?: "")
                cache.evictAll()
            } catch (e2: Exception) {
                release()
            }
        }
    }

    suspend fun getFrame(timeMs: Long, durationMs: Long = 0L, width: Int = 240, height: Int = 135): Bitmap? = withContext(Dispatchers.IO) {
        val safeTime = timeMs.coerceAtLeast(0L)
        val resolution = (durationMs / 500L).coerceIn(60L, 5000L)
        val cacheKey = (safeTime / resolution) * resolution

        cache.get(cacheKey)?.let { return@withContext it }
        return@withContext try {
            val frame = retriever?.getScaledFrameAtTime(
                safeTime * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                width,
                height
            )
            frame?.copy(Bitmap.Config.RGB_565, false)?.also { cache.put(cacheKey, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        try {
            cache.evictAll()
            retriever?.release()
            retriever = null
        } catch (_: Exception) {}
    }
}

@Stable
class PlayerGestureState {
    var mode by mutableStateOf(GestureMode.NONE)
    var seekPosition by mutableFloatStateOf(0f)
    var isSeeking by mutableStateOf(false)
    var showVolume by mutableStateOf(false)
    var volume by mutableFloatStateOf(1f)
    var showBrightness by mutableStateOf(false)
    var brightness by mutableFloatStateOf(0.5f)
    var gestureText by mutableStateOf("")
}

class PlayerGestureEngine(private val context: Context, private val activity: Activity?, private val totalDuration: () -> Long) {
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private val touchSlop = 26f
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

    fun onStart(state: PlayerGestureState, currentPosition: Long, cachedSystemBrightness: Float) {
        state.mode = GestureMode.NONE
        accumulatedX = 0f
        accumulatedY = 0f
        state.volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
        val currentBright = activity?.window?.attributes?.screenBrightness ?: -1f
        state.brightness = if (currentBright >= 0f) currentBright else cachedSystemBrightness
    }

    fun onDrag(state: PlayerGestureState, change: PointerInputChange, dragAmount: Offset, width: Float, height: Float, currentPosition: Long) {
        if (width <= 0f || height <= 0f) return
        accumulatedX += dragAmount.x
        accumulatedY += dragAmount.y
        if (state.mode == GestureMode.NONE) {
            val absX = kotlin.math.abs(accumulatedX)
            val absY = kotlin.math.abs(accumulatedY)
            if (absX < touchSlop && absY < touchSlop) return
            state.mode = when {
                absX > absY -> GestureMode.SCRUB
                change.position.x < width / 2f -> GestureMode.BRIGHTNESS
                else -> GestureMode.VOLUME
            }
            if (state.mode == GestureMode.SCRUB) {
                state.isSeeking = true
                state.seekPosition = currentPosition.toFloat()
            }
        }

        val delta = dragAmount.y
        when (state.mode) {
            GestureMode.SCRUB -> {
                val duration = totalDuration().coerceAtLeast(1L)
                val velocity = when {
                    duration > 7200000L -> 4f
                    duration > 3600000L -> 3f
                    duration > 1800000L -> 2f
                    else -> 1.2f
                }
                state.seekPosition = (state.seekPosition + (dragAmount.x / width) * duration * velocity).coerceIn(0f, duration.toFloat())
                state.gestureText = formatTime((state.seekPosition / 1000f).roundToInt() * 1000L)
            }
            GestureMode.VOLUME -> {
                state.volume = (state.volume - (delta / height) * 1.25f).coerceIn(0f, 1f)
                val newVolumeInt = (state.volume * maxVolume).roundToInt()
                if (newVolumeInt != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolumeInt, 0)
                }
                state.showVolume = true
            }
            GestureMode.BRIGHTNESS -> {
                val newBrightness = (state.brightness - (delta / height) * 1.25f).coerceIn(0.01f, 1f)
                activity?.window?.let { window ->
                    val attributes = window.attributes
                    attributes.screenBrightness = newBrightness
                    window.attributes = attributes
                }
                state.brightness = newBrightness
                state.showBrightness = true
            }
            else -> Unit
        }
        change.consume()
    }

    fun onEnd(state: PlayerGestureState) {
        state.mode = GestureMode.NONE
        state.showVolume = false
        state.showBrightness = false
    }
}

@Composable
fun ScaleButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isLowEnd = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024.0 * 1024.0 * 1024.0)) <= 3.0
    }
    val animSpec: AnimationSpec<Float> = if (isLowEnd) tween(90) else spring(dampingRatio = Spring.DampingRatioMediumBouncy)

    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1.0f, animationSpec = animSpec, label = "scale")

    Box(
        modifier = modifier
            .scale(scale)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * =========================================================================================
 * 🎬 MAIN VIDEO PLAYER WRAPPER
 * =========================================================================================
 */
@Composable
fun VideoPlayerScreen(
    initialVideoUrl: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onLockApp: () -> Unit = {}
) {
    var lastOpenedUrl by remember { mutableStateOf("") }

    LaunchedEffect(initialVideoUrl) {
        if (initialVideoUrl.isNotEmpty() && lastOpenedUrl != initialVideoUrl) {
            viewModel.openVideo(initialVideoUrl)
            lastOpenedUrl = initialVideoUrl
        }
    }

    VideoPlayerContent(
        viewModel = viewModel,
        onBackPress = onBackPress,
        onLockApp = onLockApp
    )
}

/**
 * =========================================================================================
 * 🎬 VIDEO PLAYER CONTENT
 * =========================================================================================
 */
@Composable
fun VideoPlayerContent(
    viewModel: GalleryViewModel,
    onBackPress: () -> Unit,
    onLockApp: () -> Unit
) {
    val context = LocalContext.current
    val adaptiveState = rememberAdaptiveState()
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val insetsController = remember { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }

    // Local SharedPreferences to guarantee Auto-Play Next and Repeat Mode survive app restarts/refreshes
    val playerPrefs = remember { context.getSharedPreferences("gallerybox_player_prefs", Context.MODE_PRIVATE) }

    val player = remember(viewModel) { viewModel.getPlayer() }
    val currentVideoUri by viewModel.currentVideoUri.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val bufferedPosition by viewModel.bufferedPosition.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    val videoFormat by viewModel.videoFormat.collectAsState()
    val backgroundPlay by viewModel.backgroundPlay.collectAsState()
    val vmAutoPlayNext by viewModel.autoPlayNext.collectAsState()
    val sleepTimerMs by viewModel.sleepTimerMs.collectAsState()
    val audioDelayMs by viewModel.audioDelayMs.collectAsState()

    // Persistent Repeat & AutoPlay states that survive app restarts and manual video changes
    var isRepeatEnabled by remember {
        mutableStateOf(playerPrefs.getBoolean("repeat_current_video_enabled", false))
    }
    var isAutoPlayNextEnabled by remember {
        mutableStateOf(playerPrefs.getBoolean("auto_play_next_enabled", vmAutoPlayNext))
    }

    // Re-apply saved preferences to ViewModel & ExoPlayer whenever the screen starts or video changes
    LaunchedEffect(Unit) {
        viewModel.setAutoPlayNext(isAutoPlayNextEnabled)
        viewModel.cycleRepeatMode(if (isRepeatEnabled) PremiumRepeatMode.ONE else PremiumRepeatMode.OFF)
    }

    // Keep ExoPlayer repeatMode locked to REPEAT_MODE_ONE on whichever video is currently active
    // until the user manually turns Repeat OFF
    LaunchedEffect(currentVideoUri, isRepeatEnabled) {
        player.repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    val latestAutoPlayNext by rememberUpdatedState(isAutoPlayNextEnabled)
    val latestIsRepeatEnabled by rememberUpdatedState(isRepeatEnabled)

    val gestureState = remember { PlayerGestureState() }
    val gestureEngine = remember(player) { PlayerGestureEngine(context, activity) { duration.coerceAtLeast(1L) } }
    val frameLoader = remember { VideoFrameLoader(context) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val configuration = LocalConfiguration.current
    var manualRotateOverride by remember { mutableStateOf(false) }
    var originalBrightness by remember { mutableFloatStateOf(-1f) }
    var cachedSystemBrightness by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(Unit) {
        cachedSystemBrightness = withContext(Dispatchers.IO) {
            try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            catch (_: Exception) { 0.5f }
        }
    }

    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }

    DisposableEffect(activity) {
        originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            activity?.window?.let { window ->
                val attributes = window.attributes
                attributes.screenBrightness = originalBrightness
                window.attributes = attributes
            }
        }
    }

    LaunchedEffect(manualRotateOverride) {
        if (manualRotateOverride) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Direct Player Listener: handles video size, infinite current-video repeat, and reliable Auto-Play Next
    DisposableEffect(player) {
        val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(newSize: VideoSize) {
                videoSize = newSize
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Ensure repeat mode stays applied to the newly loaded video if user manually skipped (e.g. to video #11 or #26)
                player.repeatMode = if (latestIsRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (latestIsRepeatEnabled) {
                        // Fallback if ViewModel overrides player.repeatMode internally
                        player.seekTo(0L)
                        player.playWhenReady = true
                        player.play()
                    } else if (latestAutoPlayNext && viewModel.hasNextVideo()) {
                        viewModel.playNextVideo()
                    }
                }
            }
        }
        player.addListener(playerListener)
        onDispose {
            player.removeListener(playerListener)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            frameLoader.release()
            previewBitmap = null
            manualRotateOverride = false
        }
    }

    LaunchedEffect(gestureState) {
        snapshotFlow { gestureState.isSeeking to gestureState.seekPosition }
            .debounce(100)
            .collect { (isSeeking, pos) ->
                if (isSeeking) {
                    previewBitmap = frameLoader.getFrame(pos.toLong(), duration)
                } else {
                    previewBitmap = null
                }
            }
    }

    var isLongPressing by remember { mutableStateOf(false) }
    var isMirrored by remember { mutableStateOf(false) }

    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var isInPiPMode by remember { mutableStateOf(false) }

    var showDoubleTapText by remember { mutableStateOf("") }
    var doubleTapForward by remember { mutableStateOf(true) }
    var doubleTapAlignment by remember { mutableStateOf(Alignment.Center) }
    var doubleTapJob by remember { mutableStateOf<Job?>(null) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val compActivity = activity as? ComponentActivity
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPiPMode = info.isInPictureInPictureMode
        }
        compActivity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            try {
                compActivity?.setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                )
            } catch (_: Exception) {}
            compActivity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    LaunchedEffect(isPlaying, playbackState, videoSize) {
        val compActivity = activity as? ComponentActivity
        if (compActivity != null) {
            try {
                val builder = PictureInPictureParams.Builder()
                val shouldAutoEnter = isPlaying && playbackState == Player.STATE_READY
                builder.setAutoEnterEnabled(shouldAutoEnter)

                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width.toFloat() / videoSize.height.toFloat())
                    val safeRatio = ratio.coerceIn(0.4185f, 2.389f)
                    val safeWidth = (safeRatio * 10000).toInt()
                    builder.setAspectRatio(Rational(safeWidth, 10000))
                }
                compActivity.setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    val isPlayingState = rememberUpdatedState(isPlaying)
    val isLockedState = rememberUpdatedState(isLocked)
    val isInPiPState = rememberUpdatedState(isInPiPMode)

    val resetControlsTimer: () -> Unit = remember(scope) {
        {
            showControls = true
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(3000)
                if (isPlayingState.value && !isLockedState.value && !isInPiPState.value && !showSettingsMenu) {
                    showControls = false
                }
            }
        }
    }

    val onTogglePlay = remember(viewModel, haptic) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.togglePlayPause()
            resetControlsTimer()
        }
    }

    // Manual Next/Prev keeps Repeat ON if enabled, so the newly selected video (e.g., #11 or #26) repeats infinitely
    val onNextClick = remember(viewModel) {
        {
            viewModel.playNextVideo()
            player.repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            resetControlsTimer()
        }
    }

    val onPrevClick = remember(viewModel) {
        {
            viewModel.playPreviousVideo()
            player.repeatMode = if (isRepeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            resetControlsTimer()
        }
    }

    val onSeekAction: (Float) -> Unit = remember(gestureState) {
        {
            gestureState.seekPosition = it
            gestureState.isSeeking = true
            gestureState.gestureText = formatTime((it / 1000f).roundToInt() * 1000L)
            resetControlsTimer()
        }
    }

    val onSeekFinishedAction = remember(viewModel, gestureState) {
        {
            viewModel.seekTo(gestureState.seekPosition.toLong())
            gestureState.isSeeking = false
            resetControlsTimer()
        }
    }

    val onLockAction = remember {
        {
            isLocked = true
            showControls = false
        }
    }

    val onRotateAction = remember {
        {
            manualRotateOverride = !manualRotateOverride
            resetControlsTimer()
        }
    }

    LaunchedEffect(playbackState, isPlaying, showControls, showSettingsMenu) {
        if (playbackState == Player.STATE_READY && isPlaying && showControls && !showSettingsMenu) {
            resetControlsTimer()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.savePlaybackPosition()
            }
            if (event == Lifecycle.Event.ON_STOP && !backgroundPlay && !isInPiPMode) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(showControls, isLocked, isInPiPMode, adaptiveState.posture, showSettingsMenu) {
        if ((showControls || showSettingsMenu) && !isLocked && !isInPiPMode) {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    BackHandler { if (!isInPiPMode && !showSettingsMenu) onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        if (adaptiveState.posture == DevicePosture.HALF_OPENED) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { viewParams ->
                            if (viewParams.player != player) viewParams.player = player
                            viewParams.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0C))) {
                    VideoBottomControls(
                        isPlaying = isPlaying,
                        playbackState = playbackState,
                        currentMs = currentPosition,
                        bufferedMs = bufferedPosition,
                        durationMs = duration,
                        isSeeking = gestureState.isSeeking,
                        previewBitmap = previewBitmap,
                        seekPosition = gestureState.seekPosition,
                        gestureText = gestureState.gestureText,
                        hasNext = viewModel.hasNextVideo(),
                        hasPrev = viewModel.hasPreviousVideo() || currentPosition > 3000L,
                        isLandscape = false,
                        onTogglePlay = onTogglePlay,
                        onNext = onNextClick,
                        onPrev = onPrevClick,
                        onSeek = onSeekAction,
                        onSeekFinished = onSeekFinishedAction,
                        onLock = onLockAction,
                        onRotateToggle = onRotateAction,
                        adaptiveState = adaptiveState
                    )
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { viewParams ->
                    if (viewParams.player != player) viewParams.player = player
                    viewParams.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isMirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
                    .pointerInput(isLocked, configuration.orientation) {
                        if (isLocked) {
                            detectTapGestures(
                                onTap = {
                                    if (showControls) {
                                        showControls = false
                                    } else {
                                        showControls = true
                                        hideJob?.cancel()
                                        hideJob = scope.launch {
                                            delay(3000)
                                            showControls = false
                                        }
                                    }
                                }
                            )
                            return@pointerInput
                        }
                        detectTapGestures(
                            onPress = { _ ->
                                val job = scope.launch {
                                    delay(400)
                                    isLongPressing = true
                                    player.playbackParameters = PlaybackParameters((playbackSpeed * 2f).coerceAtMost(8f), 1f)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                tryAwaitRelease()
                                job.cancel()
                                if (isLongPressing) {
                                    isLongPressing = false
                                    viewModel.resetSpeed()
                                }
                            },
                            onTap = {
                                if (showControls) {
                                    showControls = false
                                    hideJob?.cancel()
                                } else {
                                    resetControlsTimer()
                                }
                            },
                            onDoubleTap = { offset ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val dynamicSeekMs = maxOf(10000L, (duration * 0.01).toLong())
                                if (offset.x < size.width / 2f) {
                                    viewModel.seekBackward(dynamicSeekMs)
                                    showDoubleTapText = "-${dynamicSeekMs / 1000}s"
                                    doubleTapForward = false
                                    doubleTapAlignment = Alignment.CenterStart
                                } else {
                                    viewModel.seekForward(dynamicSeekMs)
                                    showDoubleTapText = "+${dynamicSeekMs / 1000}s"
                                    doubleTapForward = true
                                    doubleTapAlignment = Alignment.CenterEnd
                                }
                                doubleTapJob?.cancel()
                                doubleTapJob = scope.launch {
                                    delay(600)
                                    showDoubleTapText = ""
                                }
                            }
                        )
                    }
                    .pointerInput(isLocked, configuration.orientation) {
                        if (isLocked) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                gestureEngine.onStart(gestureState, currentPosition, cachedSystemBrightness)
                                scope.launch(Dispatchers.IO) {
                                    currentVideoUri?.let { uri -> frameLoader.setSource(uri.toUri()) }
                                }
                            },
                            onDragEnd = {
                                if (gestureState.mode == GestureMode.SCRUB) {
                                    viewModel.seekTo(gestureState.seekPosition.toLong())
                                    gestureState.isSeeking = false
                                }
                                gestureEngine.onEnd(gestureState)
                            },
                            onDragCancel = {
                                gestureState.mode = GestureMode.NONE
                                gestureState.isSeeking = false
                            }
                        ) { change, dragAmount ->
                            gestureEngine.onDrag(gestureState, change, dragAmount, size.width.toFloat(), size.height.toFloat(), currentPosition)
                        }
                    }
            )

            // --- SAMSUNG ONE UI VOLUME HUD (Right Edge) ---
            AnimatedVisibility(
                visible = gestureState.showVolume,
                enter = fadeIn(tween(150)) + slideInHorizontally(initialOffsetX = { it / 2 }),
                exit = fadeOut(tween(180)) + slideOutHorizontally(targetOffsetX = { it / 2 }),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
            ) {
                val volIcon = when {
                    gestureState.volume <= 0.01f -> Icons.Rounded.VolumeOff
                    gestureState.volume < 0.5f -> Icons.Rounded.VolumeDown
                    else -> Icons.Rounded.VolumeUp
                }
                VolumeBrightnessBar(value = gestureState.volume, icon = volIcon)
            }

            // --- SAMSUNG ONE UI BRIGHTNESS HUD (Left Edge) ---
            AnimatedVisibility(
                visible = gestureState.showBrightness,
                enter = fadeIn(tween(150)) + slideInHorizontally(initialOffsetX = { -it / 2 }),
                exit = fadeOut(tween(180)) + slideOutHorizontally(targetOffsetX = { -it / 2 }),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
            ) {
                VolumeBrightnessBar(value = gestureState.brightness, icon = Icons.Rounded.WbSunny)
            }

            // --- DOUBLE-TAP SEEK OVERLAY ---
            AnimatedVisibility(
                visible = showDoubleTapText.isNotEmpty(),
                enter = scaleIn(initialScale = 0.82f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(tween(120)),
                exit = scaleOut(targetScale = 0.88f, animationSpec = tween(150)) + fadeOut(tween(150)),
                modifier = Modifier
                    .align(doubleTapAlignment)
                    .padding(horizontal = 44.dp)
            ) {
                Surface(
                    color = Color(0xCC121318),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    shadowElevation = 12.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = if (doubleTapForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = showDoubleTapText,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }

            // --- LONG-PRESS 2X SPEED BADGE ---
            AnimatedVisibility(
                visible = isLongPressing && isPlaying,
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.85f),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 78.dp)
            ) {
                Surface(
                    color = Color(0xD9101116),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FastForward,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${(playbackSpeed * 2f).coerceAtMost(8f)}× Speed",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }

            if (!isInPiPMode) {
                val currentTitle = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString()
                    ?: player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: currentVideoUri?.substringAfterLast("/")
                    ?: "Video"

                AnimatedVisibility(
                    visible = showControls && !isLocked && !showSettingsMenu,
                    enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { -it / 3 }),
                    exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { -it / 3 }),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    VideoTopBar(
                        title = currentTitle,
                        format = videoFormat,
                        onBack = onBackPress,
                        onMenu = {
                            showSettingsMenu = true
                            showControls = false
                        },
                        adaptiveState = adaptiveState
                    )
                }

                AnimatedVisibility(
                    visible = showControls && !isLocked && !showSettingsMenu,
                    enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 3 }),
                    exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { it / 3 }),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoBottomControls(
                        isPlaying = isPlaying,
                        playbackState = playbackState,
                        currentMs = currentPosition,
                        bufferedMs = bufferedPosition,
                        durationMs = duration,
                        isSeeking = gestureState.isSeeking,
                        previewBitmap = previewBitmap,
                        seekPosition = gestureState.seekPosition,
                        gestureText = gestureState.gestureText,
                        hasNext = viewModel.hasNextVideo(),
                        hasPrev = viewModel.hasPreviousVideo() || currentPosition > 3000L,
                        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                        onTogglePlay = onTogglePlay,
                        onNext = onNextClick,
                        onPrev = onPrevClick,
                        onSeek = onSeekAction,
                        onSeekFinished = onSeekFinishedAction,
                        onLock = onLockAction,
                        onRotateToggle = onRotateAction,
                        adaptiveState = adaptiveState
                    )
                }
            }

            // --- SAMSUNG ONE UI LOCKED SCREEN FLOATING UNLOCK PILL ---
            AnimatedVisibility(
                visible = isLocked && showControls,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.88f),
                exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.88f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
            ) {
                ScaleButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isLocked = false
                        showControls = true
                        resetControlsTimer()
                    }
                ) {
                    Surface(
                        color = Color(0xCC121318),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LockOpen,
                                contentDescription = "Unlock",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Unlock",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f)
        ) {
            FullscreenSettingsMenu(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { },
                currentSpeed = playbackSpeed,
                autoPlayNext = isAutoPlayNextEnabled,
                repeatCurrentVideo = isRepeatEnabled,
                backgroundPlay = backgroundPlay,
                sleepTimerActive = sleepTimerMs != null,
                audioDelayMs = audioDelayMs,
                onDismissRequest = {
                    showSettingsMenu = false
                    resetControlsTimer()
                },
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                onToggleAutoPlay = { enabled ->
                    isAutoPlayNextEnabled = enabled
                    playerPrefs.edit().putBoolean("auto_play_next_enabled", enabled).apply()
                    viewModel.setAutoPlayNext(enabled)
                },
                onToggleRepeatCurrentVideo = { enabled ->
                    isRepeatEnabled = enabled
                    playerPrefs.edit().putBoolean("repeat_current_video_enabled", enabled).apply()
                    player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    viewModel.cycleRepeatMode(if (enabled) PremiumRepeatMode.ONE else PremiumRepeatMode.OFF)
                },
                onToggleBackgroundPlay = { viewModel.setBackgroundPlay(it) },
                onSetSleepTimer = { minutes ->
                    viewModel.startSleepTimer(minutes)
                    Toast.makeText(context, "Timer set for $minutes min", Toast.LENGTH_SHORT).show()
                    showSettingsMenu = false
                    resetControlsTimer()
                },
                onCancelSleepTimer = {
                    viewModel.cancelSleepTimer()
                    Toast.makeText(context, "Timer canceled", Toast.LENGTH_SHORT).show()
                },
                onSetAudioDelay = { delayOffset ->
                    viewModel.setAudioDelay(delayOffset)
                    Toast.makeText(context, "Audio Delay: ${delayOffset.toInt()} ms", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/**
 * =========================================================================================
 * 🔘 VIDEO TOP BAR (Samsung One UI Pro Cinema Header)
 * =========================================================================================
 */
@Composable
fun VideoTopBar(title: String, format: Format?, onBack: () -> Unit, onMenu: () -> Unit, adaptiveState: AdaptiveState) {
    val height = format?.height ?: 0
    val resBadge = when {
        height >= 2160 -> "UHD 4K"
        height >= 1440 -> "QHD"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height > 0 -> "${height}p"
        else -> null
    }

    val transfer = format?.colorInfo?.colorTransfer
    val isHdr = transfer?.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG } == true
    val hdrBadge = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10+"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> if (isHdr) "HDR" else null
    }

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.88f),
                        0.6f to Color.Black.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScaleButton(onClick = onBack) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = (16 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 0.15.sp
                )
                if (hdrBadge != null || resBadge != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        hdrBadge?.let {
                            Surface(
                                color = Color(0x33FFC107),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.8.dp, Color(0x80FFD54F))
                            ) {
                                Text(
                                    text = it,
                                    color = Color(0xFFFFE082),
                                    fontSize = (10 * adaptiveState.textScaleFactor).sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        resBadge?.let {
                            Surface(
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.22f))
                            ) {
                                Text(
                                    text = it,
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = (10 * adaptiveState.textScaleFactor).sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            ScaleButton(onClick = onMenu) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔘 VIDEO BOTTOM CONTROLS (Samsung One UI Timeline & Floating Control Deck)
 * =========================================================================================
 */
@Composable
fun VideoBottomControls(
    isPlaying: Boolean,
    playbackState: Int,
    currentMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    previewBitmap: Bitmap?,
    seekPosition: Float,
    gestureText: String,
    hasNext: Boolean,
    hasPrev: Boolean,
    isLandscape: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onLock: () -> Unit,
    onRotateToggle: () -> Unit,
    adaptiveState: AdaptiveState
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.35f to Color.Black.copy(alpha = 0.55f),
                        1.0f to Color.Black.copy(alpha = 0.92f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                        Modifier.width(640.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 14.dp, top = 8.dp)
        ) {
            // --- TIMELINE & FLOATING SCRUB PREVIEW ---
            PlayerTimelineSection(
                currentMs = currentMs,
                bufferedMs = bufferedMs,
                durationMs = durationMs,
                isSeeking = isSeeking,
                previewBitmap = previewBitmap,
                seekPosition = seekPosition,
                gestureText = gestureText,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished
            )

            Spacer(Modifier.height(10.dp))

            // --- SAMSUNG ONE UI GLASSMORPHIC CONTROL DECK ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Utility Cluster: Screen Lock
                    ScaleButton(onClick = onLock, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Lock Screen",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Center Playback Cluster: Previous | Play/Pause | Next
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        ScaleButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.size(46.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.28f),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        ScaleButton(onClick = onTogglePlay) {
                            Surface(
                                modifier = Modifier.size(60.dp),
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.20f),
                                border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.35f)),
                                shadowElevation = 6.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val playIcon = when {
                                        playbackState == Player.STATE_ENDED -> Icons.Rounded.Replay
                                        isPlaying -> Icons.Rounded.Pause
                                        else -> Icons.Rounded.PlayArrow
                                    }
                                    Icon(
                                        imageVector = playIcon,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }

                        ScaleButton(onClick = onNext, enabled = hasNext, modifier = Modifier.size(46.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.28f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Right Utility Cluster: Screen Rotate only
                    var rotateAngle by remember { mutableFloatStateOf(0f) }
                    val animatedAngle by animateFloatAsState(
                        rotateAngle,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "rotation"
                    )
                    ScaleButton(
                        onClick = {
                            rotateAngle += 180f
                            onRotateToggle()
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ScreenRotation,
                            contentDescription = "Rotate Screen",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = animatedAngle }
                        )
                    }
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🎚️ SAMSUNG ONE UI VERTICAL VOLUME & BRIGHTNESS PILL
 * =========================================================================================
 */
@Composable
fun VolumeBrightnessBar(value: Float, icon: ImageVector) {
    val clamped = value.coerceIn(0f, 1f)
    val animatedFill by animateFloatAsState(targetValue = clamped, animationSpec = tween(60), label = "hud_fill")
    val percentage = (clamped * 100).roundToInt()

    Surface(
        color = Color(0xCC121318),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .width(48.dp)
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(116.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFill)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "$percentage%",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * =========================================================================================
 * ⏱️ TIMELINE & CLAMPED FLOATING FRAME PREVIEW
 * =========================================================================================
 */
@Composable
private fun PlayerTimelineSection(
    currentMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    previewBitmap: Bitmap?,
    seekPosition: Float,
    gestureText: String,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit
) {
    val density = LocalDensity.current
    var thumbXOffset by remember { mutableFloatStateOf(0f) }
    var containerWidthPx by remember { mutableFloatStateOf(0f) }

    val activeTimeMs = if (isSeeking) seekPosition.toLong() else currentMs
    val displayGestureText = gestureText.ifEmpty { formatTime(activeTimeMs) }

    val safeTotal = maxOf(1f, durationMs.toFloat())
    val effectiveThumbX = if (thumbXOffset > 0f) {
        thumbXOffset
    } else {
        (activeTimeMs.toFloat() / safeTotal).coerceIn(0f, 1f) * containerWidthPx
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerWidthPx = it.size.width.toFloat() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        ) {
            // --- FLOATING FRAME PREVIEW TOOLTIP ---
            androidx.compose.animation.AnimatedVisibility(
                visible = isSeeking,
                enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.88f),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.88f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        val cardWidthPx = with(density) { (if (previewBitmap != null) 156.dp else 88.dp).toPx() }
                        val halfCard = cardWidthPx / 2f
                        val maxLeft = (containerWidthPx - cardWidthPx).coerceAtLeast(0f)
                        val clampedX = (effectiveThumbX - halfCard).coerceIn(0f, maxLeft)
                        IntOffset(
                            x = clampedX.roundToInt(),
                            y = with(density) { (-34).dp.roundToPx() }
                        )
                    }
            ) {
                if (previewBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF101116),
                        border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.32f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier.size(width = 156.dp, height = 92.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                        )
                                    )
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = displayGestureText,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.4.sp
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xD9121318),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        shadowElevation = 8.dp
                    ) {
                        Text(
                            text = displayGestureText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            SamsungSeekBar(
                current = if (isSeeking) seekPosition else currentMs.toFloat(),
                buffered = bufferedMs.toFloat(),
                total = safeTotal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .align(Alignment.BottomCenter),
                onSeek = { pos, xOffset ->
                    thumbXOffset = xOffset
                    onSeek(pos)
                },
                onSeekFinished = {
                    thumbXOffset = 0f
                    onSeekFinished()
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(activeTimeMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
            Text(
                text = formatTime(durationMs),
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
fun SamsungSeekBar(
    current: Float,
    buffered: Float,
    total: Float,
    modifier: Modifier,
    onSeek: (Float, Float) -> Unit,
    onSeekFinished: () -> Unit
) {
    var width by remember { mutableFloatStateOf(0f) }
    var isDraggingBar by remember { mutableStateOf(false) }
    val safeTotal = total.coerceAtLeast(1f)

    val trackHeightDp by animateDpAsState(
        targetValue = if (isDraggingBar) 6.dp else 4.dp,
        animationSpec = tween(120),
        label = "track_height"
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDraggingBar) 9.dp else 7.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "thumb_radius"
    )

    Canvas(
        modifier = modifier
            .pointerInput(safeTotal) {
                detectTapGestures { tapOffset ->
                    if (width <= 0f) return@detectTapGestures
                    val safeX = tapOffset.x.coerceIn(0f, width)
                    val p = (safeX / width) * safeTotal
                    onSeek(p, safeX)
                    onSeekFinished()
                }
            }
            .pointerInput(safeTotal) {
                detectDragGestures(
                    onDragStart = { isDraggingBar = true },
                    onDragEnd = {
                        isDraggingBar = false
                        onSeekFinished()
                    },
                    onDragCancel = {
                        isDraggingBar = false
                        onSeekFinished()
                    }
                ) { dragChange, _ ->
                    if (width <= 0f) return@detectDragGestures
                    val safeX = dragChange.position.x.coerceIn(0f, width)
                    val p = (safeX / width) * safeTotal
                    onSeek(p, safeX)
                }
            }
    ) {
        width = size.width
        val h = trackHeightDp.toPx()
        val y = size.height / 2f
        val bW = (buffered / safeTotal).coerceIn(0f, 1f) * width
        val cW = (current / safeTotal).coerceIn(0f, 1f) * width
        val thumbR = thumbRadiusDp.toPx()

        // Unplayed Background Track
        drawRoundRect(
            color = Color.White.copy(alpha = 0.24f),
            topLeft = Offset(0f, y - h / 2f),
            size = Size(width, h),
            cornerRadius = CornerRadius(h / 2f)
        )
        // Buffered Track
        drawRoundRect(
            color = Color.White.copy(alpha = 0.48f),
            topLeft = Offset(0f, y - h / 2f),
            size = Size(bW, h),
            cornerRadius = CornerRadius(h / 2f)
        )
        // Active Played Track
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, y - h / 2f),
            size = Size(cW, h),
            cornerRadius = CornerRadius(h / 2f)
        )
        // Outer Thumb Halo when scrubbing
        if (isDraggingBar) {
            drawCircle(
                color = Color.White.copy(alpha = 0.28f),
                radius = thumbR * 1.75f,
                center = Offset(cW, y)
            )
        }
        // Solid Scrubber Thumb
        drawCircle(
            color = Color.White,
            radius = thumbR,
            center = Offset(cW, y)
        )
    }
}

/**
 * =========================================================================================
 * ⚙️ VIDEO SETTINGS MENU (Samsung One UI Grouped Card Overlay)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenSettingsMenu(
    modifier: Modifier = Modifier,
    currentSpeed: Float,
    autoPlayNext: Boolean,
    repeatCurrentVideo: Boolean,
    backgroundPlay: Boolean,
    sleepTimerActive: Boolean,
    audioDelayMs: Float,
    onDismissRequest: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleRepeatCurrentVideo: (Boolean) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSetAudioDelay: (Float) -> Unit
) {
    var currentSubMenu by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (currentSubMenu != null) {
            currentSubMenu = null
        } else {
            onDismissRequest()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xF20B0C10),
        topBar = {
            @kotlin.OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Text(
                        text = when (currentSubMenu) {
                            "SPEED" -> "Playback Speed"
                            "SLEEP_TIMER" -> "Sleep Timer"
                            "AUDIO_DELAY" -> "Audio Delay Sync"
                            else -> "Playback Settings"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentSubMenu != null) {
                                currentSubMenu = null
                            } else {
                                onDismissRequest()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                AnimatedContent(
                    targetState = currentSubMenu,
                    transitionSpec = {
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                    },
                    label = "SettingsMenuTransition"
                ) { subMenu ->
                    when (subMenu) {
                        "SPEED" -> {
                            val speeds = listOf(0.25f, 0.50f, 0.75f, 1f, 1.25f, 1.50f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(speeds) { speed ->
                                    val isSelected = speed == currentSpeed
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable {
                                                onSpeedChange(speed)
                                                currentSubMenu = null
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (speed == 1f) "1.0× (Normal)" else "${speed}×",
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 16.sp
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "SLEEP_TIMER" -> {
                            val times = listOf(15, 30, 45, 60, 90, 120)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (sleepTimerActive) {
                                    item {
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(18.dp))
                                                .clickable {
                                                    onCancelSleepTimer()
                                                    currentSubMenu = null
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.TimerOff,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                                Spacer(Modifier.width(16.dp))
                                                Text(
                                                    text = "Cancel Active Timer",
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                items(times) { time ->
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color.White.copy(alpha = 0.06f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable {
                                                onSetSleepTimer(time)
                                                currentSubMenu = null
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Timer,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.85f)
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Text(
                                                text = "$time Minutes",
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "AUDIO_DELAY" -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 16.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = Color.White.copy(alpha = 0.06f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "${audioDelayMs.toInt()} ms",
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            fontSize = 28.sp
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "Adjust audio synchronization offset",
                                            color = Color.White.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(28.dp))
                                        Slider(
                                            value = audioDelayMs,
                                            onValueChange = onSetAudioDelay,
                                            valueRange = -1000f..1000f,
                                            steps = 39,
                                            colors = SliderDefaults.colors(
                                                thumbColor = MaterialTheme.colorScheme.primary,
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("-1000 ms", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                                            Text("0 ms", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                                            Text("+1000 ms", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item { SettingsSectionHeader("Playback") }
                                item {
                                    SettingsListItem(
                                        headline = "Playback Speed",
                                        supporting = "${currentSpeed}×",
                                        icon = Icons.Outlined.Speed,
                                        onClick = { currentSubMenu = "SPEED" }
                                    )
                                }
                                item {
                                    SettingsListItem(
                                        headline = "Sleep Timer",
                                        supporting = if (sleepTimerActive) "Active" else "Off",
                                        icon = Icons.Rounded.Timer,
                                        onClick = { currentSubMenu = "SLEEP_TIMER" }
                                    )
                                }
                                item {
                                    SettingsListItem(
                                        headline = "Audio Delay Sync",
                                        supporting = "${audioDelayMs.toInt()} ms",
                                        icon = Icons.Rounded.Sync,
                                        onClick = { currentSubMenu = "AUDIO_DELAY" }
                                    )
                                }

                                item { SettingsSectionHeader("Behavior") }
                                item {
                                    SettingsSwitchItem(
                                        headline = "Repeat Video",
                                        icon = Icons.Rounded.RepeatOne,
                                        checked = repeatCurrentVideo,
                                        onCheckedChange = onToggleRepeatCurrentVideo
                                    )
                                }
                                item {
                                    SettingsSwitchItem(
                                        headline = "Auto Play Next",
                                        icon = Icons.Rounded.PlaylistPlay,
                                        checked = autoPlayNext,
                                        onCheckedChange = onToggleAutoPlay
                                    )
                                }

                                item { SettingsSectionHeader("Advanced") }
                                item {
                                    SettingsSwitchItem(
                                        headline = "Background Play",
                                        icon = Icons.Rounded.PlayCircleOutline,
                                        checked = backgroundPlay,
                                        onCheckedChange = onToggleBackgroundPlay
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

// ---------------------------------------------------------------------------
// HELPER COMPONENTS FOR THE SETTINGS MENU
// ---------------------------------------------------------------------------

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.getDefault()),
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsListItem(
    headline: String,
    supporting: String? = null,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = headline,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            if (supporting != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = supporting,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    headline: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = headline,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

fun formatTime(ms: Long): String {
    val ts = ms / 1000
    val s = ts % 60
    val m = (ts / 60) % 60
    val h = ts / 3600
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}