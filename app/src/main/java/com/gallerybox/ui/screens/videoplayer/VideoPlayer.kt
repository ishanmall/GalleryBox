// =========================================================================================
// 🤫 COMPILER INSTRUCTIONS
// =========================================================================================
// These lines tell the Android building tools to ignore certain warnings.
// Think of it like telling an overly strict spell-checker to ignore specific words
// because we know what we are doing.
@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package com.gallerybox.ui.screens.videoplayer

// =========================================================================================
// 🧰 THE TOOLBOX (IMPORTS)
// =========================================================================================
// This is the toolbox area. We are importing all the tools we need from Android and Google
// to build this video player. We bring in tools for drawing shapes, playing videos,
// swiping the screen, and formatting text.
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import androidx.compose.foundation.border
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
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
// These tools help the app figure out if you are using a normal phone,
// a big tablet, or a foldable phone, so it can change the layout automatically!
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

/**
 * =========================================================================================
 * 🛠️ HELPER CLASSES
 * =========================================================================================
 */
// This finds the main "Window" of the app so we can draw things on top of it.
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// These are categories. They tell the app what kind of gesture the user is doing,
// how the video should fit the screen, and if it should loop.
enum class GestureMode { NONE, SCRUB, VOLUME, BRIGHTNESS }
enum class PremiumResizeMode { FIT, ZOOM, FILL, STRETCH, RATIO_16_9, RATIO_4_3 }
enum class PremiumRepeatMode { OFF, ONE, ALL }

// This acts like a super-fast magnifying glass. When you scrub through the video timeline,
// this class fetches a quick picture of the video frame so you can see where you are.
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
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever?.getScaledFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
            } else {
                retriever?.getFrameAtTime(safeTime * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }
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

// Think of "State" as a notepad where the app writes down everything currently happening.
// Here it writes down: "Is the user changing the volume? Is the brightness bar showing?"
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

// This is the "Brain" for screen touches. It does the math to figure out if dragging
// your finger UP on the left side of the screen should change brightness or volume.
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
                activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = newBrightness }
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

/**
 * THE BOUNCY BUTTON.
 * A reusable button wrapper that shrinks slightly when you press it, like a real physical button.
 */
@Composable
fun ScaleButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Check if the phone is weak. If it is, don't use heavy bouncy animations, just fade it.
    val isLowEnd = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1024.0 * 1024 * 1024)) <= 3.0
    }
    val animSpec: AnimationSpec<Float> = if (isLowEnd) tween(90) else spring(dampingRatio = Spring.DampingRatioMediumBouncy)

    // Animate the size shrinking to 92% when pressed
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
        content() // Draw whatever icon or text was placed inside this button
    }
}

/**
 * =========================================================================================
 * 🎬 MAIN VIDEO PLAYER WRAPPER
 * =========================================================================================
 * This acts as the front door. It receives the link to the video, tells the ViewModel
 * to load it, and then passes the real work to VideoPlayerContent.
 */
@Composable
fun VideoPlayerScreen(
    initialVideoUrl: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onLockApp: () -> Unit = {}
) {
    // "remember" acts as the app's short-term memory so it doesn't reload the video constantly
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
 * 🎬 VIDEO PLAYER CONTENT (Where the magic happens)
 * =========================================================================================
 * This draws the actual video screen, the play/pause buttons, the timeline slider,
 * and the settings menu.
 */
@Composable
fun VideoPlayerContent(
    viewModel: GalleryViewModel,
    onBackPress: () -> Unit,
    onLockApp: () -> Unit
) {
    val context = LocalContext.current

    // 🧠 Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()

    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope() // Gives us a space to run tasks in the background
    val haptic = LocalHapticFeedback.current // Lets us trigger the phone's vibration motor
    val lifecycleOwner = LocalLifecycleOwner.current
    val insetsController = remember { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }

    // Fetch the video player engine and all its current settings from our ViewModel
    val player = remember(viewModel) { viewModel.getPlayer() }
    val currentVideoUri by viewModel.currentVideoUri.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val bufferedPosition by viewModel.bufferedPosition.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    // ---> WE ARE GRABBING THE CURRENT PITCH VALUE FROM THE VIEWMODEL HERE!
    val playbackPitch by viewModel.playbackPitch.collectAsState()

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

    // This background task safely checks how bright the user's phone screen normally is.
    LaunchedEffect(Unit) {
        cachedSystemBrightness = withContext(Dispatchers.IO) {
            try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            catch (_: Exception) { 0.5f }
        }
    }

    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }

    // Restores the original screen brightness when the user closes the video player
    DisposableEffect(activity) {
        originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = originalBrightness }
        }
    }

    // Forces the phone into landscape mode if the user clicks the rotate button
    LaunchedEffect(manualRotateOverride) {
        if (manualRotateOverride) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Listens for the video loading so we know exactly how wide and tall the video is
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

    // When the user drags the timeline slider, this asks the FrameLoader to fetch a quick picture.
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

    var showControls by remember { mutableStateOf(true) } // Are the play/pause buttons visible?
    var isLocked by remember { mutableStateOf(false) } // Is the screen locked from accidental touches?
    var hideJob by remember { mutableStateOf<Job?>(null) } // The invisible timer that hides the controls
    var isInPiPMode by remember { mutableStateOf(false) } // Is Picture-in-Picture (floating window) active?

    var showDoubleTapText by remember { mutableStateOf("") }
    var doubleTapForward by remember { mutableStateOf(true) }
    var doubleTapAlignment by remember { mutableStateOf(Alignment.Center) }
    var doubleTapJob by remember { mutableStateOf<Job?>(null) }
    var showMenuSheet by remember { mutableStateOf(false) }

    // A tiny background task that hides the "Stretch to fit" message after 1.5 seconds.
    LaunchedEffect(resizeModeToast) {
        if (resizeModeToast.isNotEmpty()) {
            delay(1500)
            resizeModeToast = ""
        }
    }

    // Configures Android's Picture-in-Picture mode (the little floating video that stays on screen when you go home)
    DisposableEffect(activity) {
        val compActivity = activity as? ComponentActivity
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPiPMode = info.isInPictureInPictureMode
        }
        compActivity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    compActivity?.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                    )
                } catch (_: Exception) {}
            }
            compActivity?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    // Updates the Picture-in-Picture floating window to match the exact dimensions of the video
    LaunchedEffect(isPlaying, playbackState, videoSize) {
        val compActivity = activity as? ComponentActivity
        if (compActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val builder = PictureInPictureParams.Builder()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val shouldAutoEnter = isPlaying && playbackState == Player.STATE_READY
                    builder.setAutoEnterEnabled(shouldAutoEnter)
                }
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

    // This resets the 3-second countdown timer. If the timer hits zero, the buttons fade away.
    val resetControlsTimer: () -> Unit = remember(scope) {
        {
            showControls = true
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(3000) // Wait 3 seconds
                if (isPlayingState.value && !isLockedState.value && !isInPiPState.value) {
                    showControls = false // Hide everything
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

    // Auto-hides the controls when a video first starts playing
    LaunchedEffect(playbackState, isPlaying, showControls) {
        if (playbackState == Player.STATE_READY && isPlaying && showControls) {
            resetControlsTimer()
        }
    }

    // Listens to the app minimizing or closing. Pauses the video automatically so it doesn't keep making sound.
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
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // Don't let the screen fall asleep!

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars()) // Bring the clock and battery bar back
        }
    }

    // Hide System UI based on the Adaptive Posture (clock and battery icons at the top)
    LaunchedEffect(showControls, isLocked, isInPiPMode, adaptiveState.posture) {
        if (showControls && !isLocked && !isInPiPMode) {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // If the back button is pressed, exit out of the video player
    BackHandler { if (!isInPiPMode) onBackPress() }

    // "Box" is a layout that stacks things on top of each other, like a sandwich.
    // The very bottom piece of bread is the Black Background.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // TABLETOP MODE CHECK (HALF OPENED FOLDABLE)
        // If the user's foldable phone is bent 90 degrees and resting on a table,
        // we shove the video to the TOP half, and the controls to the BOTTOM half!
        if (adaptiveState.posture == DevicePosture.HALF_OPENED) {
            // "Column" stacks things vertically (Top to Bottom)
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Screen: The Video
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

                // Bottom Screen: The Controls (Always visible in Tabletop mode)
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
            // NORMAL VIEW (Phones, Tablets, Flat Foldables)

            // This is the actual physical Android component that plays the video.
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
                    // Detect taps on the screen (Single tap shows controls, Double tap skips forward 10 seconds)
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
                                // If the user holds down their finger for 0.4 seconds, we fast forward at 2x speed!
                                val job = scope.launch {
                                    delay(400)
                                    isLongPressing = true
                                    player.playbackParameters = PlaybackParameters((playbackSpeed * 2f).coerceAtMost(8f), playbackPitch)
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
                                // Did they double tap the left side or the right side?
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
                    // Detect swiping to change volume or brightness
                    .pointerInput(isLocked, configuration.orientation) {
                        if (isLocked) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                gestureEngine.onStart(gestureState, currentPosition, cachedSystemBrightness)
                                scope.launch(Dispatchers.IO) {
                                    currentVideoUri?.let { uri -> frameLoader.setSource(Uri.parse(uri)) }
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

            // Volume Indicator Graphic
            AnimatedVisibility(
                visible = gestureState.showVolume,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
            ) {
                VolumeBrightnessBar(value = gestureState.volume, icon = Icons.Rounded.VolumeUp)
            }

            // Brightness Indicator Graphic
            AnimatedVisibility(
                visible = gestureState.showBrightness,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
            ) {
                VolumeBrightnessBar(value = gestureState.brightness, icon = Icons.Rounded.BrightnessMedium)
            }

            // The "+10s" text that pops up when you double tap
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

            // The text that pops up when you change the aspect ratio (e.g., "FIT", "ZOOM")
            AnimatedVisibility(visible = resizeModeToast.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                Surface(color = Color.Black.copy(0.6f), shape = RoundedCornerShape(24.dp)) {
                    Text(resizeModeToast, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }

            // The "2x" text that pops up when you hold the screen to fast forward
            AnimatedVisibility(visible = isLongPressing && isPlaying, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) {
                Surface(color = Color.Black.copy(0.6f), shape = CircleShape) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("2×", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            // If we are NOT in floating window mode, draw the Top and Bottom Menus
            if (!isInPiPMode) {
                val currentTitle = player.currentMediaItem?.mediaMetadata?.displayTitle?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentVideoUri?.substringAfterLast("/") ?: "Video"

                // The Top Bar (Back Arrow, Title, Resolution, Settings Dots)
                AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
                    VideoTopBar(
                        title = currentTitle,
                        format = videoFormat,
                        onBack = onBackPress,
                        onMenu = { showMenuSheet = true; showControls = false },
                        adaptiveState = adaptiveState
                    )
                }

                // The Bottom Bar (Play, Pause, Skip, Timeline Slider)
                AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
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

            // The little Padlock button that floats on the screen when it is locked
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
    }

    // Shows the slide-up Settings menu when the 3 dots are clicked
    if (showMenuSheet) {
        PlaybackMenuSheet(
            currentSpeed = playbackSpeed,
            currentPitch = playbackPitch, // Passed in here!
            autoPlayNext = autoPlayNext,
            autoRepeat = repeatMode,
            backgroundPlay = backgroundPlay,
            sleepTimerActive = sleepTimerMs != null,
            audioDelayMs = audioDelayMs,
            onDismissRequest = {
                showMenuSheet = false
                resetControlsTimer() // Start the hide timer again when menu closes
            },
            onSpeedChange = { viewModel.setPlaybackSpeed(it) },
            onSetPitch = { viewModel.setPlaybackPitch(it) }, // Pass the new pitch back to ViewModel!
            onToggleAutoPlay = { viewModel.setAutoPlayNext(it) },
            onToggleAutoRepeat = { viewModel.cycleRepeatMode(it) },
            onToggleBackgroundPlay = { viewModel.setBackgroundPlay(it) },
            onSetSleepTimer = { minutes ->
                viewModel.startSleepTimer(minutes)
                Toast.makeText(context, "Timer set for $minutes min", Toast.LENGTH_SHORT).show()
                showMenuSheet = false
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

/**
 * =========================================================================================
 * 🔘 VIDEO TOP BAR
 * =========================================================================================
 * Draws the Back button, the Title of the video, and the "4K" / "HDR" tags if applicable.
 */
@Composable
fun VideoTopBar(title: String, format: Format?, onBack: () -> Unit, onMenu: () -> Unit, adaptiveState: AdaptiveState) {
    // Check how tall the video is to figure out if it's 4K, 1080p, or lower quality.
    val height = format?.height ?: 0
    val resBadge = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }

    // Check if the video has crazy bright colors (HDR)
    val transfer = format?.colorInfo?.colorTransfer
    val isHdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
    val hdrBadge = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> if (isHdr) "HDR" else null
    }

    // Draws a black gradient at the top of the screen so the white text is always readable
    Box(Modifier.fillMaxWidth().height(80.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Transparent))))

    // "Row" puts items horizontally next to each other.
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ScaleButton(onClick = onBack, modifier = Modifier.size(44.dp).background(Color.Black.copy(0.2f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
        }
        Spacer(Modifier.width(16.dp))

        // "Column" stacks the 4K tags on top of the Video Title
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

        // The 3 dots for Settings
        ScaleButton(onClick = onMenu, modifier = Modifier.size(44.dp).background(Color.Black.copy(0.2f), CircleShape)) {
            Icon(Icons.Default.MoreVert, null, tint = Color.White)
        }
    }
}

/**
 * =========================================================================================
 * 🔘 VIDEO BOTTOM CONTROLS (Play, Pause, Timeline)
 * =========================================================================================
 * Draws all the buttons at the bottom of the screen.
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
    // If the phone is horizontal, push the buttons down a little lower.
    val bottomOffset = if (isLandscape) (-12).dp else 8.dp

    Box(Modifier.fillMaxSize()) {
        // Gradient shadow so white icons are visible on white video backgrounds
        Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.65f)))))

        // Center the controls on giant tablets, fill width on phones
        val controlWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(600.dp) else Modifier.fillMaxWidth()

        Column(Modifier.align(Alignment.BottomCenter).then(controlWidth).navigationBarsPadding().padding(horizontal = 16.dp).offset(y = bottomOffset)) {

            // Aspect Ratio button
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                ScaleButton(onClick = onAspectRatioToggle) {
                    Icon(Icons.Outlined.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }

            // The slider bar that lets you scrub through the video
            PlayerTimelineSection(currentMs, bufferedMs, durationMs, isSeeking, previewBitmap, seekPosition, gestureText, onSeek, onSeekFinished)

            // The main playback buttons (Lock, Prev, Play, Next, Rotate)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
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

                // Animation for the Rotate button (spins it smoothly)
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

// Combines the physical slider bar and the little pop-up preview image above it
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
    var thumbXOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(60.dp)) {
            // Draw the preview image if the user is dragging the slider
            if (isSeeking) {
                if (previewBitmap != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .offset(x = with(LocalDensity.current) { thumbXOffset.toDp() } - 70.dp, y = (-20).dp)
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
                        modifier = Modifier.align(Alignment.BottomStart).offset(x = with(LocalDensity.current) { thumbXOffset.toDp() } - 20.dp, y = (-20).dp)
                    ) {
                        Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(gestureText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // The physical slider bar
            SamsungSeekBar(
                current = if (isSeeking) seekPosition else currentMs.toFloat(),
                buffered = bufferedMs.toFloat(),
                total = maxOf(1f, durationMs.toFloat()),
                modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter),
                onSeek = { pos, xOffset -> thumbXOffset = xOffset; onSeek(pos) },
                onSeekFinished = onSeekFinished
            )
        }

        // The text timers (e.g., 00:15 / 03:45)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(formatTime(durationMs), color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

// The core drawing code for the slider bar. It paints the loaded portion grey and the played portion white.
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

        drawRoundRect(Color.White.copy(0.25f), Offset(0f, y - h/2), Size(width, h), CornerRadius(h/2))
        drawRoundRect(Color.White.copy(0.50f), Offset(0f, y - h/2), Size(bW, h), CornerRadius(h/2))
        drawRoundRect(Color.White, Offset(0f, y - h/2), Size(cW, h), CornerRadius(h/2))
        drawCircle(Color.White, radius = thumbR, center = Offset(cW, y))
    }
}

/**
 * =========================================================================================
 * ⚙️ VIDEO SETTINGS MENU
 * =========================================================================================
 * The slide up menu you see when you click the 3 dots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackMenuSheet(
    currentSpeed: Float,
    currentPitch: Float, // Takes the current pitch setting
    autoPlayNext: Boolean,
    autoRepeat: PremiumRepeatMode,
    backgroundPlay: Boolean,
    sleepTimerActive: Boolean,
    audioDelayMs: Float,
    onDismissRequest: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSetPitch: (Float) -> Unit, // Allows changing the pitch!
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleAutoRepeat: (PremiumRepeatMode) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSetAudioDelay: (Float) -> Unit
) {
    // Remembers which sub-menu is currently open (like if you clicked "Playback Speed" from the main list)
    var currentSubMenu by remember { mutableStateOf<String?>(null) }

    @kotlin.OptIn(ExperimentalMaterial3Api::class)
    ModalBottomSheet(onDismissRequest = onDismissRequest, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {

        // This makes the transition smooth when you dive into a sub-menu! It slides over like a page turning.
        AnimatedContent(
            targetState = currentSubMenu,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
            },
            label = "MenuTransition"
        ) { subMenu ->
            when (subMenu) {
                // 1. The Speed Selection Sub-Menu
                "SPEED" -> {
                    LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                Text("Playback Speed", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        val speeds = listOf(0.25f, 0.50f, 0.75f, 1f, 1.25f, 1.50f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                        items(speeds) { speed ->
                            ListItem(
                                headlineContent = { Text("${speed}x") },
                                trailingContent = { if (speed == currentSpeed) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    onSpeedChange(speed)
                                    currentSubMenu = null // Go back to the main menu!
                                }
                            )
                        }
                    }
                }
                // 2. The Pitch Selection Sub-Menu
                "PITCH" -> {
                    LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // "Back" button to return to the main settings list
                                IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                Text("Audio Pitch", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        // This list matches exactly what you requested!
                        // It covers all increments from deep (-2.0) to squeaky (+2.0)
                        val pitches = listOf(-2.0f, -1.75f, -1.5f, -1.25f, -1.0f, -0.75f, -0.5f, -0.25f, 0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        items(pitches) { pitch ->
                            ListItem(
                                // Format the text beautifully so positive numbers get a "+" (like "+1.25")
                                headlineContent = { Text(if (pitch > 0) "+$pitch" else "$pitch") },
                                trailingContent = {
                                    // Draw a checkmark next to the currently active setting
                                    if (pitch == currentPitch) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                modifier = Modifier.clickable {
                                    // Send the new pitch value to the video player
                                    onSetPitch(pitch)
                                    // Close the sub-menu and return to the main list
                                    currentSubMenu = null
                                }
                            )
                        }
                    }
                }
                // 3. The Sleep Timer Sub-Menu
                "SLEEP_TIMER" -> {
                    LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                Text("Sleep Timer", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (sleepTimerActive) {
                            item {
                                ListItem(
                                    headlineContent = { Text("Cancel Timer", color = MaterialTheme.colorScheme.error) },
                                    leadingContent = { Icon(Icons.Rounded.TimerOff, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.clickable {
                                        onCancelSleepTimer()
                                        currentSubMenu = null
                                    }
                                )
                            }
                        }
                        val times = listOf(15, 30, 45, 60, 90, 120)
                        items(times) { time ->
                            ListItem(
                                headlineContent = { Text("$time Minutes") },
                                leadingContent = { Icon(Icons.Rounded.Timer, null) },
                                modifier = Modifier.clickable { onSetSleepTimer(time) }
                            )
                        }
                    }
                }
                // 4. The Audio Delay Tool
                "AUDIO_DELAY" -> {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { currentSubMenu = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                            Text("Audio Delay Sync", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Offset: ${audioDelayMs.toInt()} ms", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Slider(
                            value = audioDelayMs,
                            onValueChange = { onSetAudioDelay(it) },
                            valueRange = -1000f..1000f,
                            steps = 39,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("-1000ms", style = MaterialTheme.typography.bodySmall)
                            Text("+1000ms", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // 5. The Main Settings Screen (This draws first!)
                else -> {
                    LazyColumn(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp)) {

                        item { Text("Playback", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp)) }
                        item {
                            ListItem(
                                headlineContent = { Text("Playback Speed") },
                                supportingContent = { Text("${currentSpeed}x") },
                                leadingContent = { Icon(Icons.Outlined.Speed, null) },
                                modifier = Modifier.clickable { currentSubMenu = "SPEED" }.height(64.dp)
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Audio Pitch") },
                                supportingContent = { Text(if (currentPitch > 0) "+$currentPitch" else "$currentPitch") }, // Displays the active pitch cleanly
                                leadingContent = { Icon(Icons.Rounded.GraphicEq, null) },
                                modifier = Modifier.clickable { currentSubMenu = "PITCH" }.height(64.dp)
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Sleep Timer") },
                                supportingContent = { if (sleepTimerActive) Text("Active", color = MaterialTheme.colorScheme.primary) else null },
                                leadingContent = { Icon(Icons.Rounded.Timer, null) },
                                modifier = Modifier.clickable { currentSubMenu = "SLEEP_TIMER" }.height(64.dp)
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Audio Delay Sync") },
                                leadingContent = { Icon(Icons.Rounded.Sync, null) },
                                modifier = Modifier.clickable { currentSubMenu = "AUDIO_DELAY" }.height(64.dp)
                            )
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                        item { Text("Display & Behavior", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp)) }
                        item {
                            ListItem(
                                headlineContent = { Text("Repeat Mode") },
                                supportingContent = { Text(autoRepeat.name) },
                                leadingContent = { Icon(Icons.Rounded.Repeat, null) },
                                modifier = Modifier.clickable {
                                    val entries = PremiumRepeatMode.entries.toTypedArray()
                                    onToggleAutoRepeat(entries[(autoRepeat.ordinal + 1) % entries.size])
                                }.height(64.dp)
                            )
                        }
                        item {
                            ListItem(
                                headlineContent = { Text("Auto Play Next") },
                                trailingContent = { Switch(checked = autoPlayNext, onCheckedChange = onToggleAutoPlay) },
                                modifier = Modifier.height(64.dp)
                            )
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                        item { Text("Advanced", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp)) }
                        item {
                            ListItem(
                                headlineContent = { Text("Background Play") },
                                trailingContent = { Switch(checked = backgroundPlay, onCheckedChange = onToggleBackgroundPlay) },
                                modifier = Modifier.height(64.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Tool to format massive millisecond integers into beautiful time strings
fun formatTime(ms: Long): String {
    val ts = ms / 1000
    val s = ts % 60
    val m = (ts / 60) % 60
    val h = ts / 3600
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}