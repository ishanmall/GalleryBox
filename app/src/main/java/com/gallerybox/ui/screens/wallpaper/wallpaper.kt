@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("UnsafeOptInUsageError", "DEPRECATION", "BlockingMethodInNonBlockingContext", "unused")

package com.gallerybox.ui.screens.wallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil.size.Size
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.adaptive.WindowWidthSize
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================================
// --- SHARED WALLPAPER TRANSFORM & UI STATE ---
// =========================================================================================
data class WallpaperTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

data class WallpaperUiState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val playAudio: Boolean = false,
    val loopVideo: Boolean = true,
    val dimLevel: Float = 0f
)

class WallpaperViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WallpaperUiState())
    val uiState: StateFlow<WallpaperUiState> = _uiState.asStateFlow()

    private val _transform = MutableStateFlow(WallpaperTransform())
    val transform: StateFlow<WallpaperTransform> = _transform.asStateFlow()

    fun setPreviewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _uiState.update { old ->
            clampState(old.copy(previewWidth = width, previewHeight = height))
        }
    }

    fun setMediaSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        _uiState.update { old ->
            clampState(old.copy(mediaWidth = width, mediaHeight = height))
        }
    }

    fun applyGesture(
        zoom: Float,
        panX: Float,
        panY: Float,
        rotationDelta: Float = 0f
    ) {
        _uiState.update { old ->
            val candidate = old.copy(
                scale = (old.scale * zoom).coerceIn(1f, 8f),
                offsetX = old.offsetX + panX,
                offsetY = old.offsetY + panY,
                rotation = old.rotation
            )
            clampState(candidate)
        }
        val current = _uiState.value
        _transform.value = WallpaperTransform(
            scale = current.scale,
            offsetX = current.offsetX,
            offsetY = current.offsetY
        )
    }

    fun rotateLeft() {
        _uiState.update { old ->
            val nextRot = (old.rotation - 90f) % 360f
            clampState(old.copy(rotation = nextRot))
        }
    }

    fun rotateRight() {
        _uiState.update { old ->
            val nextRot = (old.rotation + 90f) % 360f
            clampState(old.copy(rotation = nextRot))
        }
    }

    fun toggleAudio() {
        _uiState.update { it.copy(playAudio = !it.playAudio) }
    }

    fun setLoopVideo(loop: Boolean) {
        _uiState.update { it.copy(loopVideo = loop) }
    }

    fun setDimLevel(level: Float) {
        _uiState.update { it.copy(dimLevel = level.coerceIn(0f, 0.8f)) }
    }

    fun resetTransform() {
        _uiState.update { old ->
            old.copy(
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
                rotation = 0f,
                dimLevel = 0f
            )
        }
        _transform.value = WallpaperTransform()
    }

    private fun clampState(state: WallpaperUiState): WallpaperUiState {
        val pw = state.previewWidth.toFloat()
        val ph = state.previewHeight.toFloat()
        val mw = state.mediaWidth.toFloat()
        val mh = state.mediaHeight.toFloat()

        if (pw <= 0f || ph <= 0f || mw <= 0f || mh <= 0f) return state

        val rad = Math.toRadians(state.rotation.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()

        val rotW = mw * cosA + mh * sinA
        val rotH = mw * sinA + mh * cosA

        val scaleX = pw / rotW
        val scaleY = ph / rotH
        val baseScale = max(scaleX, scaleY)
        val finalScale = baseScale * state.scale

        val drawnW = rotW * finalScale
        val drawnH = rotH * finalScale

        val maxOffsetX = max(0f, (drawnW - pw) / 2f)
        val maxOffsetY = max(0f, (drawnH - ph) / 2f)

        return state.copy(
            offsetX = state.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
            offsetY = state.offsetY.coerceIn(-maxOffsetY, maxOffsetY)
        )
    }
}

// =========================================================================================
// --- THE SMART RESIZER (CacheEngine) ---
// =========================================================================================
object CacheEngine {
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun decodeExifCorrectedBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val exifRotation = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }

        return if (exifRotation != 0f) {
            val matrix = Matrix().apply { postRotate(exifRotation) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) rawBitmap.recycle()
            rotated
        } else {
            rawBitmap
        }
    }
}

// =========================================================================================
// --- THE PHOTOSHOP ARTIST (ImageEngine) ---
// =========================================================================================
object ImageEngine {
    fun renderSourceBitmapToScreen(
        source: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        userScale: Float,
        userOffsetX: Float,
        userOffsetY: Float,
        userRotation: Float,
        dimLevel: Float
    ): Bitmap? {
        return try {
            val safeScale = userScale.coerceIn(1f, 8f)
            val finalBitmap = Bitmap.createBitmap(
                screenWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(finalBitmap).apply {
                drawColor(Color.BLACK)
            }

            val rad = Math.toRadians(userRotation.toDouble())
            val cosA = abs(cos(rad)).toFloat()
            val sinA = abs(sin(rad)).toFloat()

            val rotW = source.width * cosA + source.height * sinA
            val rotH = source.width * sinA + source.height * cosA

            val sx = screenWidth.toFloat() / rotW
            val sy = screenHeight.toFloat() / rotH

            val base = max(sx, sy)
            val finalScale = base * safeScale

            val mappedOffsetX = if (previewWidth > 0) {
                userOffsetX * (screenWidth.toFloat() / previewWidth.toFloat())
            } else {
                userOffsetX
            }
            val mappedOffsetY = if (previewHeight > 0) {
                userOffsetY * (screenHeight.toFloat() / previewHeight.toFloat())
            } else {
                userOffsetY
            }

            val drawnW = rotW * finalScale
            val drawnH = rotH * finalScale
            val maxOffsetX = max(0f, (drawnW - screenWidth) / 2f)
            val maxOffsetY = max(0f, (drawnH - screenHeight) / 2f)

            val clampedOffsetX = mappedOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
            val clampedOffsetY = mappedOffsetY.coerceIn(-maxOffsetY, maxOffsetY)

            val cx = screenWidth / 2f
            val cy = screenHeight / 2f

            val matrix = Matrix().apply {
                postTranslate(-source.width / 2f, -source.height / 2f)
                postScale(finalScale, finalScale)
                postRotate(userRotation)
                postTranslate(cx + clampedOffsetX, cy + clampedOffsetY)
            }

            canvas.drawBitmap(
                source,
                matrix,
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )

            if (dimLevel > 0f) {
                canvas.drawColor(Color.argb((dimLevel * 255f).roundToInt().coerceIn(0, 255), 0, 0, 0))
            }

            finalBitmap
        } catch (e: OutOfMemoryError) {
            Log.e("ImageEngine", "OOM while rendering bitmap", e)
            null
        } catch (e: Exception) {
            Log.e("ImageEngine", "Failed to render final bitmap", e)
            null
        }
    }

    fun createFinalBitmap(
        context: Context,
        uri: Uri,
        screenWidth: Int,
        screenHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        userScale: Float,
        userOffsetX: Float,
        userOffsetY: Float,
        userRotation: Float,
        dimLevel: Float
    ): Bitmap? {
        return try {
            val safeScale = userScale.coerceIn(1f, 8f)
            val reqW = (screenWidth * min(safeScale, 2.5f)).roundToInt().coerceAtLeast(screenWidth)
            val reqH = (screenHeight * min(safeScale, 2.5f)).roundToInt().coerceAtLeast(screenHeight)

            val original = CacheEngine.decodeExifCorrectedBitmap(context, uri, reqW, reqH) ?: return null
            val rendered = renderSourceBitmapToScreen(
                source = original,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                userScale = userScale,
                userOffsetX = userOffsetX,
                userOffsetY = userOffsetY,
                userRotation = userRotation,
                dimLevel = dimLevel
            )
            original.recycle()
            rendered
        } catch (e: Exception) {
            Log.e("ImageEngine", "Failed to create final bitmap", e)
            null
        }
    }
}

// =========================================================================================
// --- THE INTERIOR DECORATOR (StaticEngine) ---
// =========================================================================================
object StaticEngine {
    fun getDeviceScreenBounds(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return bounds.width().coerceAtLeast(1080) to bounds.height().coerceAtLeast(1920)
    }

    suspend fun apply(
        context: Context,
        item: MediaItem,
        flags: Int,
        previewWidth: Int,
        previewHeight: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Float,
        dimLevel: Float,
        onSuccess: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val wm = WallpaperManager.getInstance(context)
            val (targetWidth, targetHeight) = getDeviceScreenBounds(context)

            runCatching { wm.suggestDesiredDimensions(targetWidth, targetHeight) }

            val finalBitmap = ImageEngine.createFinalBitmap(
                context = context,
                uri = item.uri,
                screenWidth = targetWidth,
                screenHeight = targetHeight,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                userScale = scale,
                userOffsetX = offsetX,
                userOffsetY = offsetY,
                userRotation = rotation,
                dimLevel = dimLevel
            ) ?: throw Exception("Failed to create bitmap, possibly OutOfMemory")

            val visibleCropHint = Rect(0, 0, targetWidth, targetHeight)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (flags) {
                    WallpaperManager.FLAG_SYSTEM ->
                        wm.setBitmap(finalBitmap, visibleCropHint, true, WallpaperManager.FLAG_SYSTEM)
                    WallpaperManager.FLAG_LOCK ->
                        wm.setBitmap(finalBitmap, visibleCropHint, true, WallpaperManager.FLAG_LOCK)
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK -> {
                        wm.setBitmap(finalBitmap, visibleCropHint, true, WallpaperManager.FLAG_SYSTEM)
                        wm.setBitmap(finalBitmap, visibleCropHint, true, WallpaperManager.FLAG_LOCK)
                    }
                    else -> wm.setBitmap(finalBitmap, visibleCropHint, true, flags)
                }
            } else {
                wm.setBitmap(finalBitmap)
            }

            finalBitmap.recycle()

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
// --- THE MOVIE DIRECTOR (VideoEngine — Direct Apply without 2nd Screen when Possible) ---
// =========================================================================================
object VideoEngine {
    fun isGalleryBoxLiveWallpaperActive(context: Context): Boolean {
        return runCatching {
            val wm = WallpaperManager.getInstance(context)
            val info = wm.wallpaperInfo
            info != null &&
                    info.packageName == context.packageName &&
                    info.serviceName == VideoWallpaperService::class.java.name
        }.getOrDefault(false)
    }

    suspend fun applyVideoWallpaper(
        context: Context,
        item: MediaItem,
        flags: Int,
        state: WallpaperUiState,
        onAppliedDirectly: () -> Unit,
        onNeedsSystemActivation: (Intent) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val uniqueName = "live_wp_${System.currentTimeMillis()}.mp4"
            val finalFile = File(context.filesDir, uniqueName)
            val tempFile = File(context.filesDir, "live_wallpaper_video_new.mp4")

            context.contentResolver.openInputStream(item.uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            } ?: throw Exception("Cannot open video stream")

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            if (!finalFile.exists() || finalFile.length() <= 0L) {
                throw Exception("Saved video file is empty")
            }

            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val previousUri = prefs.getString("wallpaper_video_uri", null)
            val previousFileName = previousUri?.toUri()?.lastPathSegment

            context.filesDir.listFiles { _, name ->
                name.startsWith("live_wp_") &&
                        name.endsWith(".mp4") &&
                        name != uniqueName &&
                        name != previousFileName
            }?.forEach { it.delete() }

            val safeUri = Uri.fromFile(finalFile).toString()

            val normOffsetX = if (state.previewWidth > 0) {
                state.offsetX / state.previewWidth.toFloat()
            } else {
                0f
            }
            val normOffsetY = if (state.previewHeight > 0) {
                state.offsetY / state.previewHeight.toFloat()
            } else {
                0f
            }

            prefs.edit()
                .putString("wallpaper_video_uri", safeUri)
                .putBoolean("wallpaper_video_audio", state.playAudio)
                .putBoolean("wallpaper_video_loop", state.loopVideo)
                .putFloat("wallpaper_video_scale", state.scale)
                .putFloat("wallpaper_video_offset_x_norm", normOffsetX)
                .putFloat("wallpaper_video_offset_y_norm", normOffsetY)
                .putFloat("wallpaper_video_rotation", state.rotation)
                .putFloat("wallpaper_video_dim", state.dimLevel)
                .putInt("wallpaper_video_flags", flags)
                .putLong("wallpaper_update_trigger", System.currentTimeMillis())
                .commit()

            val wm = WallpaperManager.getInstance(context)
            val (targetW, targetH) = StaticEngine.getDeviceScreenBounds(context)

            // If user selected Lock Screen Only (or Both Screens), also apply a high-res transformed frame
            // directly to FLAG_LOCK so the Lock Screen updates immediately without any 2nd screen!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (flags and WallpaperManager.FLAG_LOCK) != 0) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, item.uri)
                    val rawFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L)
                    retriever.release()

                    if (rawFrame != null) {
                        val transformedLockBitmap = ImageEngine.renderSourceBitmapToScreen(
                            source = rawFrame,
                            screenWidth = targetW,
                            screenHeight = targetH,
                            previewWidth = state.previewWidth,
                            previewHeight = state.previewHeight,
                            userScale = state.scale,
                            userOffsetX = state.offsetX,
                            userOffsetY = state.offsetY,
                            userRotation = state.rotation,
                            dimLevel = state.dimLevel
                        )
                        rawFrame.recycle()

                        if (transformedLockBitmap != null) {
                            wm.setBitmap(
                                transformedLockBitmap,
                                Rect(0, 0, targetW, targetH),
                                true,
                                WallpaperManager.FLAG_LOCK
                            )
                            transformedLockBitmap.recycle()
                        }
                    }
                }
            }

            val wantsHomeLive = (flags and WallpaperManager.FLAG_SYSTEM) != 0
            val alreadyActive = isGalleryBoxLiveWallpaperActive(context)

            withContext(Dispatchers.Main) {
                if (!wantsHomeLive || alreadyActive) {
                    // Instant direct update! Zero 2nd screen needed.
                    Toast.makeText(context, "Wallpaper Applied Successfully", Toast.LENGTH_SHORT).show()
                    onAppliedDirectly()
                } else {
                    // Android OS requires one-time activation when switching from a static wallpaper to a Live Wallpaper service
                    val component = ComponentName(context, VideoWallpaperService::class.java)
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                    }
                    onNeedsSystemActivation(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoEngine", "Failed to apply video wallpaper", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to apply video wallpaper", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// =========================================================================================
// --- THE INVISIBLE CINEMA SCREEN (VideoWallpaperService with Full Transform & Dim Support) ---
// =========================================================================================
class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngineInstance()

    private inner class VideoEngineInstance : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private var player: ExoPlayer? = null
        private var holder: SurfaceHolder? = null
        private var isVisible = false
        private var systemReceiver: BroadcastReceiver? = null
        private var retryCount = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.holder = surfaceHolder

            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)

            player = ExoPlayer.Builder(applicationContext).build().apply {
                volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
                setPlaybackSpeed(1f)
                repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            retryCount = 0
                            resumeIfAllowed()
                        } else if (state == Player.STATE_ENDED) {
                            seekTo(0)
                            resumeIfAllowed()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoWallpaperService", "ExoPlayer error", error)
                        if (retryCount < 2) {
                            retryCount++
                            loadCurrentMedia(force = true)
                        }
                    }
                })
            }

            loadCurrentMedia(force = true)
            registerSystemReceivers()
        }

        private fun loadCurrentMedia(force: Boolean = false) {
            val exo = player ?: return
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val uriString = prefs.getString("wallpaper_video_uri", null) ?: return

            exo.volume = if (prefs.getBoolean("wallpaper_video_audio", false)) 1f else 0f
            exo.repeatMode = if (prefs.getBoolean("wallpaper_video_loop", true)) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            exo.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

            if (!force &&
                exo.currentMediaItem?.mediaId == uriString &&
                exo.playbackState != Player.STATE_IDLE
            ) {
                return
            }

            val mediaItem = ExoMediaItem.Builder()
                .setUri(uriString.toUri())
                .setMediaId(uriString)
                .build()

            exo.setMediaItem(mediaItem)
            exo.prepare()
        }

        private fun resumeIfAllowed() {
            val exo = player ?: return
            val currentHolder = holder ?: return

            if (!currentHolder.surface.isValid) return

            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) {
                exo.pause()
                return
            }

            if (isVisible) {
                if (exo.playbackState == Player.STATE_IDLE) {
                    exo.prepare()
                }
                exo.playWhenReady = true
                exo.play()
            } else {
                exo.pause()
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences == null || player == null) return

            when (key) {
                "wallpaper_video_audio" -> {
                    player?.volume = if (sharedPreferences.getBoolean(key, false)) 1f else 0f
                }
                "wallpaper_video_loop" -> {
                    player?.repeatMode = if (sharedPreferences.getBoolean(key, true)) {
                        Player.REPEAT_MODE_ONE
                    } else {
                        Player.REPEAT_MODE_OFF
                    }
                }
                "wallpaper_video_uri", "wallpaper_update_trigger" -> {
                    holder?.let { player?.setVideoSurfaceHolder(it) }
                    loadCurrentMedia(force = true)
                    resumeIfAllowed()
                }
            }
        }

        private fun registerSystemReceivers() {
            systemReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> player?.pause()
                        Intent.ACTION_SCREEN_ON -> resumeIfAllowed()
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> resumeIfAllowed()
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

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.isVisible = visible
            if (visible) {
                holder?.let { player?.setVideoSurfaceHolder(it) }
                loadCurrentMedia()
                resumeIfAllowed()
            } else {
                player?.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = holder
            player?.setVideoSurfaceHolder(holder)
            loadCurrentMedia()
            resumeIfAllowed()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            this.holder = holder
            this.surfaceWidth = width
            this.surfaceHeight = height
            player?.setVideoSurfaceHolder(holder)
            resumeIfAllowed()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            if (this.holder === holder) {
                this.holder = null
            }
            player?.clearVideoSurfaceHolder(holder)
            player?.pause()
        }

        override fun onDestroy() {
            super.onDestroy()
            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            systemReceiver?.let {
                try {
                    applicationContext.unregisterReceiver(it)
                } catch (_: Exception) {
                }
            }
            holder = null
            player?.release()
            player = null
        }
    }
}

// =========================================================================================
// --- PHOTO PREVIEW COMPOSABLE (Fixed Center Pivot & Full-Frame Cover) ---
// =========================================================================================
@Composable
private fun PhotoPreview(
    bitmap: Bitmap,
    state: WallpaperUiState
) {
    val image = remember(bitmap) {
        bitmap.asImageBitmap()
    }

    ComposeCanvas(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val rad = Math.toRadians(state.rotation.toDouble())
        val cosA = abs(cos(rad)).toFloat()
        val sinA = abs(sin(rad)).toFloat()

        val rotW = imageWidth * cosA + imageHeight * sinA
        val rotH = imageWidth * sinA + imageHeight * cosA

        val scaleX = size.width / rotW
        val scaleY = size.height / rotH

        val baseScale = max(scaleX, scaleY)
        val finalScale = baseScale * state.scale

        withTransform({
            translate(
                left = size.width / 2f + state.offsetX,
                top = size.height / 2f + state.offsetY
            )
            rotate(
                degrees = state.rotation,
                pivot = Offset.Zero
            )
            scale(
                scaleX = finalScale,
                scaleY = finalScale,
                pivot = Offset.Zero
            )
            translate(
                left = -imageWidth / 2f,
                top = -imageHeight / 2f
            )
        }) {
            drawImage(image)
        }
    }
}

// =========================================================================================
// --- VIDEO PREVIEW COMPOSABLE (Supports Pinch-Zoom, Drag/Pan & Rotation on 1st Screen) ---
// =========================================================================================
@Composable
private fun VideoPreview(
    player: ExoPlayer,
    state: WallpaperUiState
) {
    val pw = state.previewWidth.toFloat()
    val ph = state.previewHeight.toFloat()
    val mw = state.mediaWidth.toFloat().coerceAtLeast(1f)
    val mh = state.mediaHeight.toFloat().coerceAtLeast(1f)

    val rad = Math.toRadians(state.rotation.toDouble())
    val cosA = abs(cos(rad)).toFloat()
    val sinA = abs(sin(rad)).toFloat()

    val rotW = mw * cosA + mh * sinA
    val rotH = mw * sinA + mh * cosA

    // Calculate cover scale so the TextureView automatically fills the phone frame without stretching
    val coverScaleX = if (pw > 0f && rotW > 0f) max(1f, (pw / rotW) * (rotH / ph.coerceAtLeast(1f))) else 1f
    val coverScaleY = if (ph > 0f && rotH > 0f) max(1f, (ph / rotH) * (rotW / pw.coerceAtLeast(1f))) else 1f
    val baseCover = max(coverScaleX, coverScaleY)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val textureView = TextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                    addView(textureView)
                    player.setVideoTextureView(textureView)
                }
            },
            update = { container ->
                val textureView = container.getChildAt(0) as? TextureView
                if (textureView != null) {
                    player.setVideoTextureView(textureView)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = baseCover * state.scale
                    scaleY = baseCover * state.scale
                    rotationZ = state.rotation
                    translationX = state.offsetX
                    translationY = state.offsetY
                }
        )
    }
}

// =========================================================================================
// --- THE CONTROL ROOM (WallpaperScreen — All Logic in 1st Screen Only) ---
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(
    item: MediaItem,
    onBack: () -> Unit,
    viewModel: WallpaperViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adaptiveState = rememberAdaptiveState()

    val uiState by viewModel.uiState.collectAsState()

    var showTargetDialog by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoBackdropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageLoadFailed by remember { mutableStateOf(false) }

    // Handles the one-time Android OS Live Wallpaper activation if VideoWallpaperService wasn't active yet
    val liveWallpaperActivationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isApplying = false
        if (VideoEngine.isGalleryBoxLiveWallpaperActive(context)) {
            Toast.makeText(context, "Wallpaper Applied Successfully", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    LaunchedEffect(item.uri, item.isVideo) {
        viewModel.resetTransform()
        imageLoadFailed = false

        if (!item.isVideo) {
            withContext(Dispatchers.IO) {
                try {
                    val bmp = CacheEngine.decodeExifCorrectedBitmap(
                        context = context,
                        uri = item.uri,
                        reqWidth = 1440,
                        reqHeight = 2560
                    )
                    if (bmp != null) {
                        previewBitmap = bmp
                        viewModel.setMediaSize(bmp.width, bmp.height)
                    } else {
                        imageLoadFailed = true
                    }
                } catch (e: Exception) {
                    imageLoadFailed = true
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, item.uri)
                    val frame = retriever.getFrameAtTime(0L)
                    retriever.release()
                    if (frame != null) {
                        videoBackdropBitmap = frame
                        viewModel.setMediaSize(frame.width, frame.height)
                    } else if (item.width > 0 && item.height > 0) {
                        viewModel.setMediaSize(item.width, item.height)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Setup Wallpaper",
                        fontSize = (20 * adaptiveState.textScaleFactor).sp,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                },
                actions = {
                    if (item.isVideo) {
                        IconButton(onClick = { viewModel.toggleAudio() }) {
                            Icon(
                                if (uiState.playAudio) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "Toggle Sound",
                                tint = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                    IconButton(onClick = { showAdjustments = !showAdjustments }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "Adjust",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!isApplying && (item.isVideo || previewBitmap != null)) {
                        showTargetDialog = true
                    } else if (imageLoadFailed) {
                        Toast.makeText(
                            context,
                            "Cannot apply, media corrupted or deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                icon = { Icon(Icons.Rounded.Check, contentDescription = "Apply") },
                text = { Text("Apply", fontSize = (14 * adaptiveState.textScaleFactor).sp) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            // Blurred ambient background behind the phone frame
            val bgBitmap = previewBitmap ?: videoBackdropBitmap
            if (bgBitmap != null) {
                Image(
                    bitmap = remember(bgBitmap) { bgBitmap.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(32.dp)
                        .graphicsLayer { alpha = 0.38f }
                )
            }

            // Samsung-style Phone Preview Frame (Supports Drag, Pinch-Zoom & Double-Tap Reset for BOTH Photos and Videos)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val windowManager = remember {
                        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    }
                    val deviceAspectRatio = remember {
                        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            windowManager.currentWindowMetrics.bounds
                        } else {
                            val dm = context.resources.displayMetrics
                            Rect(0, 0, dm.widthPixels, dm.heightPixels)
                        }
                        if (b.height() > 0 && b.width() > 0) {
                            (b.width().toFloat() / b.height().toFloat()).coerceIn(0.42f, 0.56f)
                        } else {
                            0.46f
                        }
                    }

                    val phoneWidth = maxWidth * 0.82f
                    val phoneHeight = minOf(
                        maxHeight * 0.88f,
                        phoneWidth / deviceAspectRatio
                    )
                    val adjustedPhoneWidth = phoneHeight * deviceAspectRatio

                    Box(
                        modifier = Modifier
                            .width(adjustedPhoneWidth)
                            .height(phoneHeight)
                            .clip(RoundedCornerShape(30.dp))
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .border(
                                width = 1.5.dp,
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(30.dp)
                            )
                            .onSizeChanged { size ->
                                viewModel.setPreviewSize(size.width, size.height)
                            }
                            .pointerInput(item.uri) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    viewModel.applyGesture(
                                        zoom = zoom,
                                        panX = pan.x,
                                        panY = pan.y
                                    )
                                }
                            }
                            .pointerInput(item.uri) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        viewModel.resetTransform()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.isVideo) {
                            var playerError by remember { mutableStateOf(false) }
                            val player = remember(item.uri) {
                                ExoPlayer.Builder(context).build().apply {
                                    setMediaItem(ExoMediaItem.fromUri(item.uri))
                                    playWhenReady = true
                                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                    volume = if (uiState.playAudio) 1f else 0f
                                    repeatMode = if (uiState.loopVideo) {
                                        Player.REPEAT_MODE_ONE
                                    } else {
                                        Player.REPEAT_MODE_OFF
                                    }
                                    addListener(object : Player.Listener {
                                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                                            if (videoSize.width > 0 && videoSize.height > 0) {
                                                viewModel.setMediaSize(videoSize.width, videoSize.height)
                                            }
                                        }

                                        override fun onPlayerError(error: PlaybackException) {
                                            playerError = true
                                        }
                                    })
                                    prepare()
                                }
                            }

                            LaunchedEffect(uiState.loopVideo) {
                                player.repeatMode = if (uiState.loopVideo) {
                                    Player.REPEAT_MODE_ONE
                                } else {
                                    Player.REPEAT_MODE_OFF
                                }
                            }
                            LaunchedEffect(uiState.playAudio) {
                                player.volume = if (uiState.playAudio) 1f else 0f
                            }

                            DisposableEffect(item.uri) {
                                onDispose { player.release() }
                            }

                            if (playerError) {
                                Text(
                                    "Failed to load video",
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            } else {
                                VideoPreview(
                                    player = player,
                                    state = uiState
                                )
                            }
                        } else {
                            if (imageLoadFailed) {
                                Text(
                                    "Failed to load image",
                                    color = androidx.compose.ui.graphics.Color.White
                                )
                            } else if (previewBitmap != null) {
                                PhotoPreview(
                                    bitmap = previewBitmap!!,
                                    state = uiState
                                )
                            } else {
                                CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                            }
                        }

                        if (uiState.dimLevel > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        androidx.compose.ui.graphics.Color.Black.copy(alpha = uiState.dimLevel)
                                    )
                            )
                        }
                    }
                }
            }

            // Unified Adjustments Menu (Works for BOTH Photos and Videos on the 1st Screen)
            AnimatedVisibility(
                visible = showAdjustments,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
            ) {
                val menuWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                    Modifier.width(400.dp)
                } else {
                    Modifier.fillMaxWidth(0.9f)
                }

                Surface(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = menuWidth
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (item.isVideo) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Loop Video",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Switch(
                                    checked = uiState.loopVideo,
                                    onCheckedChange = { viewModel.setLoopVideo(it) }
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Rotation",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                IconButton(onClick = { viewModel.rotateLeft() }) {
                                    Icon(
                                        Icons.Filled.RotateLeft,
                                        contentDescription = "Rotate Left",
                                        tint = androidx.compose.ui.graphics.Color.White
                                    )
                                }
                                IconButton(onClick = { viewModel.rotateRight() }) {
                                    Icon(
                                        Icons.Filled.RotateRight,
                                        contentDescription = "Rotate Right",
                                        tint = androidx.compose.ui.graphics.Color.White
                                    )
                                }
                            }
                        }

                        Text(
                            "Dimming",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = uiState.dimLevel,
                            onValueChange = { viewModel.setDimLevel(it) },
                            valueRange = 0f..0.8f
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.resetTransform() }) {
                                Text("Reset All")
                            }
                        }
                    }
                }
            }

            if (isApplying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }

    // Unified Target Dialog for BOTH Photos & Videos (Home Screen / Lock Screen / Both Screens)
    if (showTargetDialog) {
        var applyTo by remember { mutableIntStateOf(WallpaperManager.FLAG_SYSTEM) }

        AlertDialog(
            onDismissRequest = { if (!isApplying) showTargetDialog = false },
            title = {
                Text(
                    "Set Wallpaper",
                    fontSize = (20 * adaptiveState.textScaleFactor).sp
                )
            },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isApplying) {
                                applyTo = WallpaperManager.FLAG_SYSTEM
                            }
                    ) {
                        RadioButton(
                            selected = applyTo == WallpaperManager.FLAG_SYSTEM,
                            onClick = { applyTo = WallpaperManager.FLAG_SYSTEM },
                            enabled = !isApplying
                        )
                        Text("Home Screen")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isApplying) {
                                applyTo = WallpaperManager.FLAG_LOCK
                            }
                    ) {
                        RadioButton(
                            selected = applyTo == WallpaperManager.FLAG_LOCK,
                            onClick = { applyTo = WallpaperManager.FLAG_LOCK },
                            enabled = !isApplying
                        )
                        Text("Lock Screen")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isApplying) {
                                applyTo = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                            }
                    ) {
                        RadioButton(
                            selected = applyTo == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                            onClick = {
                                applyTo = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                            },
                            enabled = !isApplying
                        )
                        Text("Both Screens")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isApplying,
                    onClick = {
                        isApplying = true
                        val snapshot = uiState
                        showTargetDialog = false
                        scope.launch {
                            if (item.isVideo) {
                                VideoEngine.applyVideoWallpaper(
                                    context = context,
                                    item = item,
                                    flags = applyTo,
                                    state = snapshot,
                                    onAppliedDirectly = {
                                        isApplying = false
                                        onBack()
                                    },
                                    onNeedsSystemActivation = { activationIntent ->
                                        runCatching {
                                            liveWallpaperActivationLauncher.launch(activationIntent)
                                        }.onFailure {
                                            isApplying = false
                                            Toast.makeText(
                                                context,
                                                "Live Wallpapers not supported on this device",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            } else {
                                try {
                                    StaticEngine.apply(
                                        context = context,
                                        item = item,
                                        flags = applyTo,
                                        previewWidth = snapshot.previewWidth,
                                        previewHeight = snapshot.previewHeight,
                                        scale = snapshot.scale,
                                        offsetX = snapshot.offsetX,
                                        offsetY = snapshot.offsetY,
                                        rotation = snapshot.rotation,
                                        dimLevel = snapshot.dimLevel
                                    ) {
                                        onBack()
                                    }
                                } finally {
                                    isApplying = false
                                }
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