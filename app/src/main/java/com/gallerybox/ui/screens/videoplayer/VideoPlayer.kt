// =========================================================================================
// 🤫 COMPILER INSTRUCTIONS
// =========================================================================================
@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

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
import android.os.Build
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gallerybox.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt
import java.util.Locale

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
enum class PremiumResizeMode { FIT, ZOOM, FILL, STRETCH, RATIO_16_9, RATIO_4_3 }
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
            val frame = retriever?.getScaledFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
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
        (mi.totalMem / (1024.0 * 1024 * 1024)) <= 3.0
    }
    val animSpec: AnimationSpec<Float> = if (isLowEnd) tween(90) else spring(dampingRatio = Spring.DampingRatioMediumBouncy)

    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else 1.0f, animationSpec = animSpec, label = "scale")

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
    val autoPlayNext by viewModel.autoPlayNext.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val sleepTimerMs by viewModel.sleepTimerMs.collectAsState()
    val audioDelayMs by viewModel.audioDelayMs.collectAsState()

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

    DisposableEffect(Unit) {
        val sizeListener = object : Player.Listener {
            override fun onVideoSizeChanged(newSize: VideoSize) {
                videoSize = newSize
            }
        }
        player.addListener(sizeListener)
        onDispose {
            player.removeListener(sizeListener)
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
    var resizeMode by remember { mutableStateOf(PremiumResizeMode.FIT) }
    var resizeModeToast by remember { mutableStateOf("") }
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

    LaunchedEffect(resizeModeToast) {
        if (resizeModeToast.isNotEmpty()) {
            delay(1500)
            resizeModeToast = ""
        }
    }

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

    val onNextClick = remember(viewModel) {
        {
            viewModel.playNextVideo()
            resetControlsTimer()
        }
    }

    val onPrevClick = remember(viewModel) {
        {
            viewModel.playPreviousVideo()
            resetControlsTimer()
        }
    }

    val onSeekAction: (Float) -> Unit = remember(gestureState) {
        {
            gestureState.seekPosition = it
            gestureState.isSeeking = true
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

    val onAspectRatioAction = remember {
        {
            val modes = PremiumResizeMode.entries.toTypedArray()
            resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
            resizeModeToast = resizeMode.name.replace("_", " ")
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

                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
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
                        onAspectRatioToggle = onAspectRatioAction,
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
                    viewParams.resizeMode = when(resizeMode) {
                        PremiumResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PremiumResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PremiumResizeMode.FILL, PremiumResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
                    .then(if (isMirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
                    .then(when(resizeMode){
                        PremiumResizeMode.RATIO_16_9 -> Modifier.aspectRatio(16f/9f)
                        PremiumResizeMode.RATIO_4_3 -> Modifier.aspectRatio(4f/3f)
                        else -> Modifier
                    })
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
                                doubleTapJob = scope.launch { delay(600); showDoubleTapText = "" }
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

            AnimatedVisibility(
                visible = gestureState.showVolume,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
            ) {
                VolumeBrightnessBar(value = gestureState.volume, icon = Icons.Rounded.VolumeUp)
            }

            AnimatedVisibility(
                visible = gestureState.showBrightness,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
            ) {
                VolumeBrightnessBar(value = gestureState.brightness, icon = Icons.Rounded.BrightnessMedium)
            }

            AnimatedVisibility(
                visible = showDoubleTapText.isNotEmpty(),
                enter = scaleIn(initialScale = 0.8f) + fadeIn(),
                exit = scaleOut(targetScale = 0.8f) + fadeOut(),
                modifier = Modifier.align(doubleTapAlignment).padding(horizontal = 48.dp)
            ) {
                Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Icon(if (doubleTapForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, contentDescription = null, tint = Color.White)
                        Text(showDoubleTapText, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            AnimatedVisibility(visible = resizeModeToast.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(24.dp)) {
                    Text(resizeModeToast, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }

            AnimatedVisibility(visible = isLongPressing && isPlaying, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
                Surface(color = Color.Black.copy(0.6f), shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("2×", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            if (!isInPiPMode) {
                val currentTitle = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentVideoUri?.substringAfterLast("/") ?: "Video"

                AnimatedVisibility(visible = showControls && !isLocked && !showSettingsMenu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    VideoTopBar(
                        title = currentTitle,
                        format = videoFormat,
                        onBack = onBackPress,
                        onMenu = { showSettingsMenu = true; showControls = false },
                        adaptiveState = adaptiveState
                    )
                }

                AnimatedVisibility(visible = showControls && !isLocked && !showSettingsMenu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
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
                        onAspectRatioToggle = onAspectRatioAction,
                        adaptiveState = adaptiveState
                    )
                }
            }

            AnimatedVisibility(visible = isLocked && showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
                ScaleButton(
                    onClick = {
                        isLocked = false
                        showControls = true
                        resetControlsTimer()
                    },
                    modifier = Modifier.background(Color.Black.copy(0.6f), CircleShape).padding(12.dp)
                ) {
                    Icon(Icons.Outlined.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().zIndex(100f)
        ) {
            FullscreenSettingsMenu(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { },
                currentSpeed = playbackSpeed,
                autoPlayNext = autoPlayNext,
                autoRepeat = repeatMode,
                backgroundPlay = backgroundPlay,
                sleepTimerActive = sleepTimerMs != null,
                audioDelayMs = audioDelayMs,
                onDismissRequest = {
                    showSettingsMenu = false
                    resetControlsTimer()
                },
                onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                onToggleAutoPlay = { viewModel.setAutoPlayNext(it) },
                onToggleAutoRepeat = { viewModel.cycleRepeatMode(it) },
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
 * 🔘 VIDEO TOP BAR
 * =========================================================================================
 */
@Composable
fun VideoTopBar(title: String, format: Format?, onBack: () -> Unit, onMenu: () -> Unit, adaptiveState: AdaptiveState) {
    val height = format?.height ?: 0
    val resBadge = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }

    val transfer = format?.colorInfo?.colorTransfer
    val isHdr = transfer?.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG } == true
    val hdrBadge = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> if (isHdr) "HDR" else null
    }

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.85f), Color.Transparent))))

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScaleButton(onClick = onBack, modifier = Modifier.size(44.dp).background(Color.Black.copy(0.2f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    hdrBadge?.let {
                        Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 6.dp)) {
                            Text(it, color = Color.White, fontSize = (9 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    resBadge?.let {
                        Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 6.dp)) {
                            Text(it, color = Color.White, fontSize = (9 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(title, color = Color.White, fontSize = (16 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            ScaleButton(onClick = onMenu, modifier = Modifier.size(44.dp).background(Color.Black.copy(0.2f), CircleShape)) {
                Icon(Icons.Default.MoreVert, null, tint = Color.White)
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔘 VIDEO BOTTOM CONTROLS (Play, Pause, Timeline)
 * =========================================================================================
 */
@Composable
fun VideoBottomControls(
    isPlaying: Boolean, playbackState: Int,
    currentMs: Long, bufferedMs: Long, durationMs: Long,
    isSeeking: Boolean, previewBitmap: Bitmap?, seekPosition: Float, gestureText: String,
    hasNext: Boolean, hasPrev: Boolean, isLandscape: Boolean,
    onTogglePlay: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit,
    onSeek: (Float) -> Unit, onSeekFinished: () -> Unit, onLock: () -> Unit,
    onRotateToggle: () -> Unit, onAspectRatioToggle: () -> Unit,
    adaptiveState: AdaptiveState
) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)))))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                        Modifier.width(600.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 12.dp
                )
        ){
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                ScaleButton(onClick = onAspectRatioToggle) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }

            PlayerTimelineSection(currentMs, bufferedMs, durationMs, isSeeking, previewBitmap, seekPosition, gestureText, onSeek, onSeekFinished)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScaleButton(onClick = onLock) {
                    Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                ScaleButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = if (hasPrev) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(32.dp))
                }
                ScaleButton(onClick = onTogglePlay) {
                    Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.22f)) {
                        Box(contentAlignment = Alignment.Center) {
                            val playIcon = if (playbackState == Player.STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                            Icon(imageVector = playIcon, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
                ScaleButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Rounded.SkipNext, null, tint = if (hasNext) Color.White else Color.White.copy(0.3f), modifier = Modifier.size(32.dp))
                }

                var rotateAngle by remember { mutableFloatStateOf(0f) }
                val animatedAngle by animateFloatAsState(rotateAngle, spring(), label = "rotation")
                ScaleButton(onClick = {
                    rotateAngle += 180f
                    onRotateToggle()
                }) {
                    Icon(Icons.Outlined.ScreenRotation, null, tint = Color.White, modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = animatedAngle })
                }
            }
        }
    }
}

// Draws the vertical volume/brightness sliders on the left/right of the screen
@Composable
fun VolumeBrightnessBar(value: Float, icon: ImageVector) {
    Column(
        modifier = Modifier.background(Color.Black.copy(0.4f), RoundedCornerShape(16.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(4.dp).height(100.dp).background(Color.White.copy(0.3f), CircleShape), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value).background(Color.White, CircleShape))
        }
    }
}

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

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            if (isSeeking) {
                if (previewBitmap != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .offset {
                                IntOffset(
                                    x = (thumbXOffset - 70.dp.toPx()).roundToInt(),
                                    y = (-20).dp.toPx().roundToInt()
                                )
                            }
                    ) {
                        Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(140.dp, 80.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(0.5f), RoundedCornerShape(8.dp)))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Text(gestureText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else if (gestureText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .offset {
                                IntOffset(
                                    x = (thumbXOffset - 20.dp.toPx()).roundToInt(),
                                    y = (-20).dp.toPx().roundToInt()
                                )
                            }
                    ) {
                        Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(gestureText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            SamsungSeekBar(
                current = if (isSeeking) seekPosition else currentMs.toFloat(),
                buffered = bufferedMs.toFloat(),
                total = maxOf(1f, durationMs.toFloat()),
                modifier = Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter),
                onSeek = { pos, xOffset -> thumbXOffset = xOffset; onSeek(pos) },
                onSeekFinished = onSeekFinished
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(formatTime(durationMs), color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SamsungSeekBar(current: Float, buffered: Float, total: Float, modifier: Modifier, onSeek: (Float, Float) -> Unit, onSeekFinished: () -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }
    val safeTotal = total.coerceAtLeast(1f)

    Canvas(modifier
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
            detectDragGestures(onDragEnd = onSeekFinished) { dragChange, _ ->
                if (width <= 0f) return@detectDragGestures
                val safeX = dragChange.position.x.coerceIn(0f, width)
                val p = (safeX / width) * safeTotal
                onSeek(p, safeX)
            }
        }
    ) {
        width = size.width
        val h = 3.dp.toPx()
        val y = size.height / 2
        val bW = (buffered / safeTotal).coerceIn(0f, 1f) * width
        val cW = (current / safeTotal).coerceIn(0f, 1f) * width
        val thumbR = 8.dp.toPx()

        drawRoundRect(Color.White.copy(0.25f), Offset(0f, y - h / 2), Size(width, h), CornerRadius(h / 2))
        drawRoundRect(Color.White.copy(0.50f), Offset(0f, y - h / 2), Size(bW, h), CornerRadius(h / 2))
        drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(cW, h), CornerRadius(h / 2))
        drawCircle(Color.White, radius = thumbR, center = Offset(cW, y))
    }
}

/**
 * =========================================================================================
 * ⚙️ VIDEO SETTINGS MENU (Full Screen Overlay)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenSettingsMenu(
    modifier: Modifier = Modifier,
    currentSpeed: Float,
    autoPlayNext: Boolean,
    autoRepeat: PremiumRepeatMode,
    backgroundPlay: Boolean,
    sleepTimerActive: Boolean,
    audioDelayMs: Float,
    onDismissRequest: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleAutoRepeat: (PremiumRepeatMode) -> Unit,
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
        containerColor = Color(0xFF121212).copy(alpha = 0.95f),
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
                    IconButton(onClick = {
                        if (currentSubMenu != null) {
                            currentSubMenu = null
                        } else {
                            onDismissRequest()
                        }
                    }) {
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
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AnimatedContent(
                targetState = currentSubMenu,
                transitionSpec = {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                },
                label = "SettingsMenuTransition"
            ) { subMenu ->
                when (subMenu) {
                    "SPEED" -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            val speeds = listOf(0.25f, 0.50f, 0.75f, 1f, 1.25f, 1.50f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                            items(speeds) { speed ->
                                val isSelected = speed == currentSpeed
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    ),
                                    headlineContent = { Text("${speed}x", fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    trailingContent = { if (isSelected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clickable {
                                        onSpeedChange(speed)
                                        currentSubMenu = null
                                    }.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                    "SLEEP_TIMER" -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (sleepTimerActive) {
                                item {
                                    ListItem(
                                        colors = ListItemDefaults.colors(
                                            containerColor = Color.Transparent,
                                            headlineColor = MaterialTheme.colorScheme.error,
                                            leadingIconColor = MaterialTheme.colorScheme.error
                                        ),
                                        headlineContent = { Text("Cancel Timer", fontWeight = FontWeight.Bold) },
                                        leadingContent = { Icon(Icons.Rounded.TimerOff, null) },
                                        modifier = Modifier.clickable {
                                            onCancelSleepTimer()
                                            currentSubMenu = null
                                        }.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                            val times = listOf(15, 30, 45, 60, 90, 120)
                            items(times) { time ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = Color.White,
                                        leadingIconColor = Color.White
                                    ),
                                    headlineContent = { Text("$time Minutes") },
                                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                                    modifier = Modifier.clickable {
                                        onSetSleepTimer(time)
                                        currentSubMenu = null
                                    }.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                    "AUDIO_DELAY" -> {
                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
                            Text(
                                text = "Offset: ${audioDelayMs.toInt()} ms",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 20.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(Modifier.height(32.dp))
                            Slider(
                                value = audioDelayMs,
                                onValueChange = onSetAudioDelay,
                                valueRange = -1000f..1000f,
                                steps = 39,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("-1000 ms", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                Text("+1000 ms", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { SettingsSectionHeader("Playback") }
                            item {
                                SettingsListItem(
                                    headline = "Playback Speed",
                                    supporting = "${currentSpeed}x",
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
                            item { HorizontalDivider(color = Color.White.copy(0.1f)) }

                            item { SettingsSectionHeader("Display & Behavior") }
                            item {
                                SettingsListItem(
                                    headline = "Repeat Mode",
                                    supporting = autoRepeat.name,
                                    icon = Icons.Rounded.Repeat,
                                    onClick = {
                                        val entries = PremiumRepeatMode.entries.toTypedArray()
                                        onToggleAutoRepeat(entries[(autoRepeat.ordinal + 1) % entries.size])
                                    }
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
                            item { HorizontalDivider(color = Color.White.copy(0.1f)) }

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

// ---------------------------------------------------------------------------
// HELPER COMPONENTS FOR THE SETTINGS MENU
// ---------------------------------------------------------------------------

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsListItem(
    headline: String,
    supporting: String? = null,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = Color.White,
            supportingColor = Color.LightGray,
            leadingIconColor = Color.White
        ),
        headlineContent = { Text(headline, fontWeight = FontWeight.Medium) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp)
    )
}

@Composable
fun SettingsSwitchItem(
    headline: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = Color.White,
            leadingIconColor = Color.White
        ),
        headlineContent = { Text(headline, fontWeight = FontWeight.Medium) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

fun formatTime(ms: Long): String {
    val ts = ms / 1000
    val s = ts % 60
    val m = (ts / 60) % 60
    val h = ts / 3600
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}