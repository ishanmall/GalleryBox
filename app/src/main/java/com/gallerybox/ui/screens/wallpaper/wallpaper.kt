@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError", "DEPRECATION", "BlockingMethodInNonBlockingContext", "unused")

package com.gallerybox.ui.screens.wallpaper

// =========================================================================================
// --- IMPORTS ---
// Bringing in all the tools we need.
// =========================================================================================
import android.app.Activity
import android.app.WallpaperManager
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

// Importing the data model
import com.gallerybox.data.MediaItem

// Importing the Adaptive Engine so our UI knows what shape the phone is
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- THE SMART RESIZER (CacheEngine) ---
// Analogy: Imagine trying to fit a giant billboard poster onto a small office desk.
// It would crush the desk! (In phone terms, loading a massive 8K image crashes the app
// with an "Out of Memory" error).
// This tool looks at the giant picture and calculates exactly how much we need to shrink
// it before bringing it into the room.
// =========================================================================================
object CacheEngine {
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2; val halfWidth = width / 2
            // We keep cutting the size in half (1, 2, 4, 8) until it safely fits our required size.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }
}

// =========================================================================================
// --- THE PHOTOSHOP ARTIST (ImageEngine) ---
// Analogy: This is the artist in the back room. You tell them, "Take this photo, spin it
// 45 degrees, zoom in 2x, slide it to the left, and make it 30% darker."
// This engine creates a brand new, single, flat canvas with all your edits permanently painted on.
// =========================================================================================
object ImageEngine {
    fun createFinalBitmap(
        context: Context,
        uri: Uri,
        screenWidth: Int,
        screenHeight: Int,
        imageScaleMode: String,
        userScale: Float,
        userOffsetX: Float,
        userOffsetY: Float,
        userRotation: Float,
        dimLevel: Float
    ): Bitmap? {
        try {
            // 1. "Peek" at the image size without actually loading the heavy image.
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // 2. Calculate how much to shrink it based on our phone's screen size
            options.inSampleSize = CacheEngine.calculateInSampleSize(
                options,
                (screenWidth * userScale).toInt(),
                (screenHeight * userScale).toInt()
            )

            // 3. Now load the actual pixels into memory, safely shrunk!
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val original = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // 4. Create the final blank canvas that exactly matches the phone screen.
            val finalBitmap = Bitmap.createBitmap(
                screenWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(finalBitmap).apply {
                drawColor(Color.BLACK) // Paint the background black
            }

            val matrix = Matrix() // A Matrix is just a math tool used to slide, spin, and stretch things.

            val scaleX = screenWidth.toFloat() / original.width
            val scaleY = screenHeight.toFloat() / original.height

            val baseScaleX: Float
            val baseScaleY: Float

            // How should the picture fit the screen by default?
            when (imageScaleMode) {
                "Fill" -> { baseScaleX = scaleX; baseScaleY = scaleY }
                "Original" -> { baseScaleX = 1f; baseScaleY = 1f }
                else -> { val s = min(scaleX, scaleY); baseScaleX = s; baseScaleY = s } // "Fit"
            }

            val cx = screenWidth / 2f
            val cy = screenHeight / 2f

            // Apply all the user's pinches, spins, and drags to the math matrix
            matrix.postTranslate(-original.width / 2f, -original.height / 2f)
            matrix.postScale(baseScaleX * userScale, baseScaleY * userScale)
            matrix.postRotate(userRotation)
            matrix.postTranslate(cx + userOffsetX, cy + userOffsetY)

            // Paint the original image onto the blank canvas using the math matrix
            canvas.drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            original.recycle() // Throw away the original to save phone memory!

            // If the user wanted the wallpaper darkened, paint a see-through black layer over it.
            if (dimLevel > 0f) {
                val dimCanvas = Canvas(finalBitmap)
                dimCanvas.drawColor(Color.argb((dimLevel * 255).toInt(), 0, 0, 0))
            }

            return finalBitmap
        } catch (e: OutOfMemoryError) {
            Log.e("ImageEngine", "OOM while creating wallpaper bitmap", e)
            return null
        } catch (e: Exception) {
            Log.e("ImageEngine", "Failed to create final bitmap", e)
            return null
        }
    }
}

// =========================================================================================
// --- THE INTERIOR DECORATOR (StaticEngine) ---
// Analogy: Once the Photoshop Artist finishes painting the picture, this guy takes the
// picture and permanently glues it to the wall (your phone's Home Screen or Lock Screen).
// =========================================================================================
object StaticEngine {
    suspend fun apply(
        context: Context, item: MediaItem, flags: Int,
        imageScaleMode: String, scale: Float, offsetX: Float, offsetY: Float, rotation: Float, dimLevel: Float,
        onSuccess: () -> Unit
    ) = withContext(Dispatchers.IO) { // Warehouse Worker: Do this heavy work in the background!
        try {
            val wm = WallpaperManager.getInstance(context)
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds

            val targetWidth = max(wm.desiredMinimumWidth, bounds.width())
            val targetHeight = max(wm.desiredMinimumHeight, bounds.height())

            wm.suggestDesiredDimensions(targetWidth, targetHeight)

            // Ask the artist to paint the picture...
            val finalBitmap = ImageEngine.createFinalBitmap(
                context, item.uri, targetWidth, targetHeight, imageScaleMode,
                scale, offsetX, offsetY, rotation, dimLevel
            ) ?: throw Exception("Failed to create bitmap, possibly OutOfMemory")

            // Glue it to the wall!
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                when (flags) {
                    WallpaperManager.FLAG_SYSTEM -> wm.setBitmap(finalBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    WallpaperManager.FLAG_LOCK -> wm.setBitmap(finalBitmap, null, true, WallpaperManager.FLAG_LOCK)
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK -> {
                        wm.setBitmap(finalBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                        wm.setBitmap(finalBitmap, null, true, WallpaperManager.FLAG_LOCK)
                    }
                    else -> wm.setBitmap(finalBitmap, null, true, flags)
                }
            } else {
                wm.setBitmap(finalBitmap)
            }

            finalBitmap.recycle()

            // Tell the Store Manager (Main Screen) that we are done!
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Wallpaper Applied Successfully", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            Log.e("StaticEngine", Log.getStackTraceString(e))
            withContext(Dispatchers.Main) {
                Toast.makeText(context, e.message ?: "Wallpaper failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// =========================================================================================
// --- THE MOVIE DIRECTOR (VideoEngine) ---
// Analogy: If you pick a Video instead of a photo, we can't just glue a flat picture to
// the wall. We have to save a copy of the movie and tell Android, "Hey, please open the
// Live Wallpaper menu so the user can hit 'Set Wallpaper'."
// =========================================================================================
object VideoEngine {
    suspend fun setVideoWallpaper(
        context: Context, item: MediaItem, playAudio: Boolean, loop: Boolean, scaleMode: String, onSuccess: () -> Unit
    ) = withContext(Dispatchers.IO) { // Warehouse Worker: Doing heavy file-copying in the background
        try {
            // 1. Create a new, temporary copy of the video file deep inside the app's secret folders
            val uniqueName = "live_wp_${System.currentTimeMillis()}.mp4"
            val finalFile = File(context.filesDir, uniqueName)
            val tempFile = File(context.filesDir, "live_wallpaper_video_new.mp4")

            context.contentResolver.openInputStream(item.uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open video stream")

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // Clean up old live wallpapers so we don't eat up the user's storage
            context.filesDir.listFiles { _, name ->
                name.startsWith("live_wp_") && name.endsWith(".mp4") && name != uniqueName
            }?.forEach { it.delete() }

            val safeUri = Uri.fromFile(finalFile).toString()

            // 2. Write down the settings on a "Sticky Note" (SharedPreferences) so the Live Wallpaper
            // engine knows how to play it later.
            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.edit {
                putString("wallpaper_video_uri", safeUri)
                putBoolean("wallpaper_video_audio", playAudio)
                putBoolean("wallpaper_video_loop", loop)
                putString("wallpaper_video_scale_mode", scaleMode)
                putLong("wallpaper_update_trigger", System.currentTimeMillis())
            }

            // 3. Ask Android to open the Live Wallpaper Chooser
            withContext(Dispatchers.Main) {
                try {
                    val component = ComponentName(context, VideoWallpaperService::class.java)
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                    }
                    context.startActivity(intent)
                    onSuccess()
                } catch (e: Exception) {
                    Log.e("VideoEngine", "Direct live wallpaper intent failed", e)
                    try {
                        val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                        context.startActivity(fallbackIntent)
                        Toast.makeText(context, "Please select GalleryBox Live Wallpaper from the list", Toast.LENGTH_LONG).show()
                        onSuccess()
                    } catch (e2: Exception) {
                        Toast.makeText(context, "Live Wallpapers not supported on this device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoEngine", "Failed to prepare video", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to prepare video wallpaper", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// =========================================================================================
// --- THE INVISIBLE CINEMA SCREEN (VideoWallpaperService) ---
// Analogy: This is an Android System Service. It runs 24/7 in the background behind all
// your apps. It holds a tiny DVD Player (ExoPlayer) and constantly loops the movie on
// the background glass (SurfaceHolder).
// =========================================================================================
class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngineInstance()

    private inner class VideoEngineInstance : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private var exoPlayer: ExoPlayer? = null
        private var isVisible = false
        private var systemReceiver: BroadcastReceiver? = null
        private var currentSurfaceHolder: SurfaceHolder? = null
        private var retryCount = 0
        private var pendingUri: String? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Read the Sticky Notes (SharedPreferences) to see what video we should play
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)

            // Setup the DVD Player (ExoPlayer)
            exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
                setPlaybackSpeed(1f)
                repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

                val scaleModeStr = prefs.getString("wallpaper_video_scale_mode", "Fit")
                videoScalingMode = if (scaleModeStr == "Fill") C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when(state) {
                            Player.STATE_READY -> {
                                retryCount = 0
                                if (isVisible) play()
                            }
                            Player.STATE_ENDED -> seekTo(0)
                            Player.STATE_BUFFERING, Player.STATE_IDLE -> { /* No-op */ }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (retryCount < 1) {
                            retryCount++
                            prepare()
                        }
                    }
                })
            }

            pendingUri = prefs.getString("wallpaper_video_uri", null)
            registerSystemReceivers()
        }

        private fun startPlaybackIfReady(forceReload: Boolean = false) {
            val holder = currentSurfaceHolder ?: return
            val player = exoPlayer ?: return
            val uri = pendingUri ?: return

            // Connect the DVD player to the TV Screen (SurfaceHolder)
            player.setVideoSurfaceHolder(holder)
            if (forceReload || player.currentMediaItem?.mediaId != uri || player.playbackState == Player.STATE_IDLE) {
                player.setMediaItem(ExoMediaItem.Builder().setUri(uri.toUri()).setMediaId(uri).build())
                player.prepare()
            }
            player.playWhenReady = true
            if (isVisible) player.play()
        }

        // If the user changes settings in the app, this instantly updates the live wallpaper!
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences == null || exoPlayer == null) return

            when (key) {
                "wallpaper_video_audio" -> exoPlayer?.volume = if (sharedPreferences.getBoolean(key, false)) 1f else 0f
                "wallpaper_video_loop" -> exoPlayer?.repeatMode = if (sharedPreferences.getBoolean(key, true)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                "wallpaper_video_scale_mode" -> {
                    val scaleModeStr = sharedPreferences.getString(key, "Fit")
                    exoPlayer?.videoScalingMode = if (scaleModeStr == "Fill") C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                }
                "wallpaper_video_uri", "wallpaper_update_trigger" -> {
                    pendingUri = sharedPreferences.getString("wallpaper_video_uri", null)
                    startPlaybackIfReady(forceReload = true)
                }
            }
        }

        // We listen to the Android system. If the user turns their screen off, we PAUSE the video
        // to save their battery. When they unlock the phone, we hit PLAY again.
        private fun registerSystemReceivers() {
            systemReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> exoPlayer?.pause()
                        Intent.ACTION_SCREEN_ON -> if (isVisible) checkPowerAndPlay()
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> checkPowerAndPlay()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }

            ContextCompat.registerReceiver(
                applicationContext,
                systemReceiver!!,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        private fun checkPowerAndPlay() {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val shouldPauseForPower = pm.isPowerSaveMode // Don't play videos if Battery Saver is on!

            if (shouldPauseForPower) {
                exoPlayer?.pause()
            } else if (isVisible) {
                exoPlayer?.play()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                checkPowerAndPlay()
            } else {
                exoPlayer?.pause() // If an app is open covering the home screen, pause the video!
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            currentSurfaceHolder = holder
            startPlaybackIfReady()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            currentSurfaceHolder = holder
            startPlaybackIfReady()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            currentSurfaceHolder = null
            exoPlayer?.clearVideoSurfaceHolder(holder)
            exoPlayer?.pause()
        }

        override fun onDestroy() {
            super.onDestroy()
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            systemReceiver?.let {
                try { applicationContext.unregisterReceiver(it) } catch (e: Exception) { /* Ignored */ }
            }
            exoPlayer?.release()
            exoPlayer = null
        }
    }
}

// =========================================================================================
// --- THE CONTROL ROOM (WallpaperScreen) ---
// Analogy: This is the UI where the user actually sees their photo/video. They can use two
// fingers to pinch, zoom, spin, and slide the photo around exactly how they want it before
// hitting the "Apply" button.
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(item: MediaItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 🧠 ADAPTIVE ENGINE: This asks the phone, "Are you a tiny phone? A foldable? A tablet?"
    val adaptiveState = rememberAdaptiveState()

    // Sticky Notes (State) to remember the user's settings
    var playAudio by remember { mutableStateOf(false) }
    var loopVideo by remember { mutableStateOf(true) }
    var scaleMode by remember { mutableStateOf("Fit") }

    var showTargetDialog by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }

    // Animators! These make the image bounce back smoothly when you let go of it.
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val rotationAnim = remember { Animatable(0f) }

    // These hold the live, exact math numbers while the user's fingers are touching the screen.
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }
    var gestureRotation by remember { mutableFloatStateOf(0f) }
    var isTransforming by remember { mutableStateOf(false) }

    // If fingers are touching the screen, use the "gesture" numbers. If not, use the "animator" numbers.
    val currentScale = if (isTransforming) gestureScale else scaleAnim.value
    val currentOffsetX = if (isTransforming) gestureOffsetX else offsetXAnim.value
    val currentOffsetY = if (isTransforming) gestureOffsetY else offsetYAnim.value
    val currentRotation = if (isTransforming) gestureRotation else rotationAnim.value

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val wPx = with(density) { screenWidth.toPx() }
    val hPx = with(density) { screenHeight.toPx() }

    var dimLevel by remember { mutableFloatStateOf(0f) }

    var previewBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var imageLoadFailed by remember { mutableStateOf(false) }

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var tapJob by remember { mutableStateOf<Job?>(null) }

    // Load the picture into memory so we can draw it on the screen
    if (!item.isVideo) {
        LaunchedEffect(item.uri) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(item.uri)
                    .allowHardware(false) // Hardware bitmaps can't be freely drawn on a Canvas, so we disable it.
                    .build()
                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable) {
                        previewBitmap = drawable.bitmap.asImageBitmap()
                    }
                } else {
                    imageLoadFailed = true
                }
            } catch (e: Exception) {
                imageLoadFailed = true
            }
        }
    }

    // A helper command to reset all the image tweaks back to normal with a bouncy spring animation
    val resetTransforms = {
        scope.launch { scaleAnim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { offsetXAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { offsetYAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
        scope.launch { rotationAnim.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 🎨 ADAPTIVE: We scale the text up if they are on a huge monitor!
                title = { Text("Setup Wallpaper", fontSize = (20 * adaptiveState.textScaleFactor).sp, color = androidx.compose.ui.graphics.Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = androidx.compose.ui.graphics.Color.White) } },
                actions = {
                    IconButton(onClick = { showAdjustments = !showAdjustments }) {
                        Icon(Icons.Filled.Tune, "Adjust", tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!isApplying && (item.isVideo || previewBitmap != null)) {
                        showTargetDialog = true
                    } else if (imageLoadFailed) {
                        Toast.makeText(context, "Cannot apply, media corrupted or deleted", Toast.LENGTH_SHORT).show()
                    }
                },
                icon = { Icon(Icons.Rounded.Check, "Apply") },
                text = { Text("Apply", fontSize = (14 * adaptiveState.textScaleFactor).sp) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                // --- HARDCORE MATH ZONE: HANDLING USER FINGERS ---
                // This block calculates exactly how far the user spread their fingers (zoom),
                // twisted their wrist (rotation), and dragged their hand (pan).
                .pointerInput(Unit) {
                    if (!item.isVideo && previewBitmap != null) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            isTransforming = true

                            gestureScale = scaleAnim.value
                            gestureOffsetX = offsetXAnim.value
                            gestureOffsetY = offsetYAnim.value
                            gestureRotation = rotationAnim.value

                            var moved = false

                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val rotChange = event.calculateRotation()

                                if (abs(zoomChange - 1f) > 0.005f || panChange.getDistance() > 1f || abs(rotChange) > 0.5f) {
                                    moved = true
                                }

                                // 1. Calculate Zoom (with a rubber-band effect if they zoom too far)
                                var targetScale = gestureScale * zoomChange
                                if (targetScale < 0.5f) targetScale = 0.5f + (targetScale - 0.5f) * 0.3f
                                else if (targetScale > 10f) targetScale = 10f + (targetScale - 10f) * 0.3f
                                gestureScale = targetScale

                                val imgW = previewBitmap!!.width.toFloat()
                                val imgH = previewBitmap!!.height.toFloat()

                                val screenRatioX = wPx / imgW
                                val screenRatioY = hPx / imgH

                                val baseScaleX = when (scaleMode) {
                                    "Fill" -> screenRatioX
                                    "Original" -> 1f
                                    else -> min(screenRatioX, screenRatioY) // Fit
                                }
                                val baseScaleY = when (scaleMode) {
                                    "Fill" -> screenRatioY
                                    "Original" -> 1f
                                    else -> min(screenRatioX, screenRatioY) // Fit
                                }

                                val totalScaleX = baseScaleX * gestureScale
                                val totalScaleY = baseScaleY * gestureScale

                                val rotationRad = Math.toRadians(gestureRotation.toDouble())
                                val cosA = abs(cos(rotationRad)).toFloat()
                                val sinA = abs(sin(rotationRad)).toFloat()

                                val boundW = imgW * totalScaleX
                                val boundH = imgH * totalScaleY

                                val rotW = boundW * cosA + boundH * sinA
                                val rotH = boundW * sinA + boundH * cosA

                                val limitX = max(0f, (rotW - wPx) / 2f)
                                val limitY = max(0f, (rotH - hPx) / 2f)

                                // 2. Calculate Pan/Drag (with rubber-band effect at edges)
                                var targetX = gestureOffsetX + panChange.x
                                var targetY = gestureOffsetY + panChange.y

                                if (targetX > limitX) targetX = limitX + (targetX - limitX) * 0.3f
                                else if (targetX < -limitX) targetX = -limitX + (targetX + limitX) * 0.3f

                                if (targetY > limitY) targetY = limitY + (targetY - limitY) * 0.3f
                                else if (targetY < -limitY) targetY = -limitY + (targetY + limitY) * 0.3f

                                gestureOffsetX = targetX
                                gestureOffsetY = targetY

                                // 3. Calculate Rotation
                                var targetRot = gestureRotation + rotChange * 0.8f
                                if (targetRot > 180f) targetRot -= 360f
                                else if (targetRot < -180f) targetRot += 360f

                                gestureRotation = targetRot

                                event.changes.forEach { if (it.positionChange() != androidx.compose.ui.geometry.Offset.Zero) it.consume() }
                            } while (event.changes.any { it.pressed })

                            // When fingers lift off the screen:
                            isTransforming = false

                            if (!moved) {
                                // Logic to detect Double-Taps and Triple-Taps to reset the image
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    tapCount++
                                } else {
                                    tapCount = 1
                                }
                                lastTapTime = now

                                tapJob?.cancel()
                                tapJob = scope.launch {
                                    delay(300)
                                    if (tapCount == 2) {
                                        scaleMode = "Fit"
                                        resetTransforms()
                                    } else if (tapCount >= 3) {
                                        scaleMode = "Original"
                                        resetTransforms()
                                    }
                                    tapCount = 0
                                }
                            }

                            // Snap the image back into safe boundaries if they dragged it too far off screen
                            scope.launch {
                                val imgW = previewBitmap!!.width.toFloat()
                                val imgH = previewBitmap!!.height.toFloat()

                                val screenRatioX = wPx / imgW
                                val screenRatioY = hPx / imgH

                                val baseScaleX = when (scaleMode) {
                                    "Fill" -> screenRatioX
                                    "Original" -> 1f
                                    else -> min(screenRatioX, screenRatioY)
                                }
                                val baseScaleY = when (scaleMode) {
                                    "Fill" -> screenRatioY
                                    "Original" -> 1f
                                    else -> min(screenRatioX, screenRatioY)
                                }

                                val finalScale = gestureScale.coerceIn(0.5f, 10f)

                                val totalScaleX = baseScaleX * finalScale
                                val totalScaleY = baseScaleY * finalScale

                                val rotationRad = Math.toRadians(gestureRotation.toDouble())
                                val cosA = abs(cos(rotationRad)).toFloat()
                                val sinA = abs(sin(rotationRad)).toFloat()

                                val boundW = imgW * totalScaleX
                                val boundH = imgH * totalScaleY

                                val rotW = boundW * cosA + boundH * sinA
                                val rotH = boundW * sinA + boundH * cosA

                                val limitX = max(0f, (rotW - wPx) / 2f)
                                val limitY = max(0f, (rotH - hPx) / 2f)

                                val finalX = gestureOffsetX.coerceIn(-limitX, limitX)
                                val finalY = gestureOffsetY.coerceIn(-limitY, limitY)

                                scaleAnim.snapTo(gestureScale)
                                offsetXAnim.snapTo(gestureOffsetX)
                                offsetYAnim.snapTo(gestureOffsetY)
                                rotationAnim.snapTo(gestureRotation)

                                launch { scaleAnim.animateTo(finalScale, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetXAnim.animateTo(finalX, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                                launch { offsetYAnim.animateTo(finalY, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {

            // --- DRAWING THE MEDIA ---
            // If it's a Video, we load ExoPlayer
            if (item.isVideo) {
                var playerError by remember { mutableStateOf(false) }
                val player = remember {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(ExoMediaItem.fromUri(item.uri))
                        playWhenReady = true
                        volume = 0f // Mute the preview so it doesn't blast the user's ears
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                playerError = true
                            }
                        })
                        prepare()
                    }
                }

                LaunchedEffect(loopVideo) { player.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

                DisposableEffect(Unit) { onDispose { player.release() } }

                if (playerError) {
                    Text("Failed to load video", color = androidx.compose.ui.graphics.Color.White)
                } else {
                    key(scaleMode) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    this.player = player
                                    this.resizeMode = when (scaleMode) {
                                        "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        "Original" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                }
                            },
                            update = { view ->
                                view.resizeMode = when (scaleMode) {
                                    "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    "Original" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                // If it's a Photo, we draw it onto a custom Canvas so we can perfectly track
                // the user's zoom and rotation math.
                if (imageLoadFailed) {
                    Text("Failed to load image", color = androidx.compose.ui.graphics.Color.White)
                } else if (previewBitmap != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imgW = previewBitmap!!.width.toFloat()
                        val imgH = previewBitmap!!.height.toFloat()

                        val screenRatioX = size.width / imgW
                        val screenRatioY = size.height / imgH

                        val baseScaleX = when (scaleMode) {
                            "Fill" -> screenRatioX
                            "Original" -> 1f
                            else -> min(screenRatioX, screenRatioY) // Fit
                        }
                        val baseScaleY = when (scaleMode) {
                            "Fill" -> screenRatioY
                            "Original" -> 1f
                            else -> min(screenRatioX, screenRatioY) // Fit
                        }

                        val cx = size.width / 2f
                        val cy = size.height / 2f

                        withTransform({
                            translate(cx + currentOffsetX, cy + currentOffsetY)
                            rotate(currentRotation)
                            scale(baseScaleX * currentScale, baseScaleY * currentScale)
                            translate(-imgW / 2f, -imgH / 2f)
                        }) {
                            drawImage(image = previewBitmap!!)
                        }
                    }
                } else {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                }
            }

            // The dark overlay filter
            if (dimLevel > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = dimLevel))
                )
            }

            // --- SETTINGS MENU ---
            // Opens when the user clicks the little "Tune" icon at the top right.
            AnimatedVisibility(visible = showAdjustments, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {

                // 🎨 ADAPTIVE: Restrict the width of this settings menu on giant tablets so it doesn't stretch 2 feet wide
                val menuWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(400.dp) else Modifier.fillMaxWidth(0.9f)

                Surface(color = androidx.compose.ui.graphics.Color.Black.copy(0.7f), shape = RoundedCornerShape(16.dp), modifier = menuWidth) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Scale Mode", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            Row {
                                listOf("Fit", "Fill", "Original").forEach { mode ->
                                    TextButton(onClick = { scaleMode = mode; resetTransforms() }) {
                                        Text(mode, color = if (scaleMode == mode) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        if (item.isVideo) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Loop Video", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Switch(checked = loopVideo, onCheckedChange = { loopVideo = it })
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Rotation", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(onClick = { scope.launch { rotationAnim.animateTo(rotationAnim.value - 90f) } }) {
                                        Icon(Icons.Filled.RotateLeft, "Rotate Left", tint = androidx.compose.ui.graphics.Color.White)
                                    }
                                    IconButton(onClick = { scope.launch { rotationAnim.animateTo(rotationAnim.value + 90f) } }) {
                                        Icon(Icons.Filled.RotateRight, "Rotate Right", tint = androidx.compose.ui.graphics.Color.White)
                                    }
                                }
                            }
                            Text("Dimming", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            Slider(value = dimLevel, onValueChange = { dimLevel = it }, valueRange = 0f..0.8f)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = {
                                    resetTransforms()
                                    dimLevel = 0f
                                }) { Text("Reset All") }
                            }
                        }
                    }
                }
            }

            // Loading circle while the Heavy background workers apply the wallpaper
            if (isApplying) {
                Box(
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }

    // --- FINAL APPLY DIALOG ---
    // Asks the user: "Do you want this on your Home Screen, Lock Screen, or Both?"
    if (showTargetDialog) {
        var applyTo by remember { mutableIntStateOf(WallpaperManager.FLAG_SYSTEM) }

        AlertDialog(
            onDismissRequest = { if (!isApplying) showTargetDialog = false },
            title = { Text("Set Wallpaper", fontSize = (20 * adaptiveState.textScaleFactor).sp) },
            text = {
                Column {
                    if (item.isVideo) {
                        Text("Play Sound?", fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { playAudio = true }
                        ) {
                            RadioButton(selected = playAudio, onClick = { playAudio = true }, enabled = !isApplying)
                            Text("Yes")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { playAudio = false }
                        ) {
                            RadioButton(selected = !playAudio, onClick = { playAudio = false }, enabled = !isApplying)
                            Text("No")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Note: Live wallpapers are handled by the system picker. Target screens (Home/Lock) will be chosen there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = WallpaperManager.FLAG_SYSTEM }
                        ) {
                            RadioButton(selected = applyTo == WallpaperManager.FLAG_SYSTEM, onClick = { applyTo = WallpaperManager.FLAG_SYSTEM }, enabled = !isApplying)
                            Text("Home Screen")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = WallpaperManager.FLAG_LOCK }
                        ) {
                            RadioButton(selected = applyTo == WallpaperManager.FLAG_LOCK, onClick = { applyTo = WallpaperManager.FLAG_LOCK }, enabled = !isApplying)
                            Text("Lock Screen")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isApplying) { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                        ) {
                            RadioButton(
                                selected = applyTo == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                                onClick = { applyTo = (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) },
                                enabled = !isApplying
                            )
                            Text("Both Screens")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isApplying,
                    onClick = {
                        isApplying = true
                        scope.launch {
                            try {
                                if (item.isVideo) {
                                    // Send the video to the Movie Director (Background Worker)
                                    VideoEngine.setVideoWallpaper(context, item, playAudio, loopVideo, scaleMode) {
                                        onBack()
                                    }
                                } else {
                                    // Send the photo to the Interior Decorator (Background Worker)
                                    StaticEngine.apply(context, item, applyTo, scaleMode, currentScale, currentOffsetX, currentOffsetY, currentRotation, dimLevel) {
                                        onBack()
                                    }
                                }
                            } finally {
                                isApplying = false
                                showTargetDialog = false
                            }
                        }
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isApplying,
                    onClick = { showTargetDialog = false }
                ) { Text("Cancel") }
            }
        )
    }
}