@file:Suppress(
    "unused",
    "OPT_IN_USAGE",
    "UNCHECKED_CAST",
    "ObsoleteSdkInt",
    "DEPRECATION",
    "UnsafeOptInUsageError",
    "SpellCheckingInspection"
)
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.caverock.androidsvg.SVG
import com.gallerybox.data.CubeLut
import com.gallerybox.data.EditState
import com.gallerybox.data.TextLayer
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * --- THE TIME MACHINE (EditStateManager) ---
 * Maintains Undo/Redo history stacks and hit-testing geometry for interactive layers.
 */
@Singleton
class EditStateManager @Inject constructor() {
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    var currentState: EditState = EditState()
        private set

    var selectedLayerIds = mutableSetOf<String>()

    fun reset(initialState: EditState = EditState()) {
        undoStack.clear()
        redoStack.clear()
        selectedLayerIds.clear()
        currentState = initialState
    }

    fun pushState(newState: EditState) {
        if (newState == currentState) return
        undoStack.addLast(currentState)
        if (undoStack.size > 50) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        currentState = newState
    }

    fun undo(): EditState? {
        if (undoStack.isEmpty()) {
            return null
        }
        redoStack.addLast(currentState)
        currentState = undoStack.removeLast()
        return currentState
    }

    fun redo(): EditState? {
        if (redoStack.isEmpty()) {
            return null
        }
        undoStack.addLast(currentState)
        currentState = redoStack.removeLast()
        return currentState
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Checks whether a touch point (x, y) lands inside a rotated and scaled layer bounding box.
     */
    fun isPointInLayer(
        x: Float,
        y: Float,
        layerX: Float,
        layerY: Float,
        width: Float,
        height: Float,
        rotation: Float,
        scale: Float = 1f
    ): Boolean {
        val dx = x - layerX
        val dy = y - layerY
        val rad = Math.toRadians((-rotation).toDouble())
        val rx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ry = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val halfW = (width * scale) / 2f
        val halfH = (height * scale) / 2f
        return rx in -halfW..halfW && ry in -halfH..halfH
    }
}

/**
 * --- THE DIGITAL DARKROOM (EditingEngine) ---
 * Master coordinator delegating preview generation, LUT parsing, frame extraction, and final export.
 */
@UnstableApi
@Singleton
class EditingEngine @Inject constructor(
    private val photoEngine: PhotoEditorEngine,
    private val videoEngine: VideoEditorEngine,
    private val lutEngine: LutEngine,
    private val exportEngine: ExportEngine
) {
    suspend fun createPreview(
        bitmap: Bitmap,
        state: EditState,
        renderOverlays: Boolean = false,
        applyGeometry: Boolean = false
    ): Bitmap {
        return photoEngine.createPreview(bitmap, state, renderOverlays, applyGeometry)
    }

    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return videoEngine.extractFrame(uri, posMs)
    }

    suspend fun loadLut(path: String): CubeLut? {
        return lutEngine.loadLut(path)
    }

    suspend fun saveMedia(
        uri: Uri,
        state: EditState,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30,
        videoBitrate: Int = 15000000,
        isVideo: Boolean,
        asSticker: Boolean = false,
        useH265: Boolean = false,
        isPreviewExport: Boolean = false,
        onProgress: (Float) -> Unit
    ): File? {
        return exportEngine.saveMedia(
            uri = uri,
            state = state,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            targetFps = targetFps,
            videoBitrate = videoBitrate,
            isVideo = isVideo,
            asSticker = asSticker,
            useH265 = useH265,
            isPreviewExport = isPreviewExport,
            onProgress = onProgress
        )
    }

    fun cancelExport() {
        videoEngine.cancel()
        exportEngine.cancel()
    }
}

/**
 * --- THE COLOR CHEMIST (LutEngine) ---
 * Parses `.cube` 3D LUT files and applies trilinear interpolation across bitmap pixels.
 */
@Singleton
class LutEngine @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun loadLut(path: String): CubeLut? {
        return withContext(Dispatchers.IO) {
            try {
                context.assets.open(path).bufferedReader().use { reader ->
                    var size = 0
                    val data = mutableListOf<Float>()

                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("LUT_3D_SIZE")) {
                            val parts = trimmed.split(Regex("\\s+"))
                            size = parts.last().toIntOrNull() ?: 0
                        } else if (
                            !trimmed.startsWith("#") &&
                            !trimmed.startsWith("TITLE") &&
                            !trimmed.startsWith("DOMAIN_") &&
                            !trimmed.startsWith("LUT_1D_SIZE") &&
                            trimmed.isNotBlank()
                        ) {
                            val rgb = trimmed.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                            if (rgb.size >= 3) {
                                data.add(rgb[0])
                                data.add(rgb[1])
                                data.add(rgb[2])
                            }
                        }
                    }
                    val expectedFloats = size * size * size * 3
                    if (size > 1 && data.size >= expectedFloats) {
                        CubeLut(size, data.toFloatArray())
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("LutEngine", "Load LUT failed for $path", e)
                null
            }
        }
    }

    fun applyCpuLut(src: Bitmap, lut: CubeLut, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val s = lut.size
        val d = lut.data
        val maxIdx = s - 1
        val s2 = s * s
        val clampedIntensity = intensity.coerceIn(0f, 1f)

        fun lerp(v0: Float, v1: Float, t: Float): Float = v0 + (v1 - v0) * t

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xff
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff

            val rF = (r / 255f) * maxIdx
            val gF = (g / 255f) * maxIdx
            val bF = (b / 255f) * maxIdx

            val r0 = rF.toInt().coerceIn(0, maxIdx)
            val g0 = gF.toInt().coerceIn(0, maxIdx)
            val b0 = bF.toInt().coerceIn(0, maxIdx)

            val r1 = (r0 + 1).coerceIn(0, maxIdx)
            val g1 = (g0 + 1).coerceIn(0, maxIdx)
            val b1 = (b0 + 1).coerceIn(0, maxIdx)

            val fr = rF - r0
            val fg = gF - g0
            val fb = bF - b0

            val idx000 = (r0 + g0 * s + b0 * s2) * 3
            val idx100 = (r1 + g0 * s + b0 * s2) * 3
            val idx010 = (r0 + g1 * s + b0 * s2) * 3
            val idx110 = (r1 + g1 * s + b0 * s2) * 3
            val idx001 = (r0 + g0 * s + b1 * s2) * 3
            val idx101 = (r1 + g0 * s + b1 * s2) * 3
            val idx011 = (r0 + g1 * s + b1 * s2) * 3
            val idx111 = (r1 + g1 * s + b1 * s2) * 3

            var nr = 0f
            var ng = 0f
            var nb = 0f

            for (ch in 0..2) {
                val c000 = d[idx000 + ch]
                val c100 = d[idx100 + ch]
                val c010 = d[idx010 + ch]
                val c110 = d[idx110 + ch]
                val c001 = d[idx001 + ch]
                val c101 = d[idx101 + ch]
                val c011 = d[idx011 + ch]
                val c111 = d[idx111 + ch]

                val cx00 = lerp(c000, c100, fr)
                val cx10 = lerp(c010, c110, fr)
                val cx01 = lerp(c001, c101, fr)
                val cx11 = lerp(c011, c111, fr)

                val cxy0 = lerp(cx00, cx10, fg)
                val cxy1 = lerp(cx01, cx11, fg)

                val fVal = lerp(cxy0, cxy1, fb)

                when (ch) {
                    0 -> nr = fVal * 255f
                    1 -> ng = fVal * 255f
                    2 -> nb = fVal * 255f
                }
            }

            val finalR = (r + (nr - r) * clampedIntensity).roundToInt().coerceIn(0, 255)
            val finalG = (g + (ng - g) * clampedIntensity).roundToInt().coerceIn(0, 255)
            val finalB = (b + (nb - b) * clampedIntensity).roundToInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        val bmp = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}

/**
 * --- THE STICKER FACTORY (StickerEngine) ---
 * Renders `.svg` vector assets into cached ARGB_8888 Bitmaps with memory and disk caching.
 */
@Singleton
class StickerEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val stickerCache = LruCache<String, Bitmap>(80)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getDiskCacheFile(key: String): File {
        return File(context.cacheDir, "sticker_$key.png")
    }

    fun getStickerBitmap(assetPath: String, targetBaseResolution: Int = 1080): Bitmap? {
        if (assetPath.isBlank()) return null
        val safeRes = targetBaseResolution.coerceAtLeast(256)
        val cacheKey = "${assetPath.replace("/", "_")}_$safeRes"

        val cachedBitmap = stickerCache.get(cacheKey)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            return cachedBitmap
        }

        val diskFile = getDiskCacheFile(cacheKey)
        if (diskFile.exists()) {
            try {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    stickerCache.put(cacheKey, diskBitmap)
                    return diskBitmap
                }
            } catch (e: Exception) {
                Log.e("StickerEngine", "Disk cache read failed", e)
            }
        }

        return try {
            val svg = SVG.getFromAsset(context.assets, assetPath)

            val docWidth = if (svg.documentWidth > 0) svg.documentWidth else 500f
            val docHeight = if (svg.documentHeight > 0) svg.documentHeight else 500f
            val aspect = (docWidth / docHeight).takeIf { it > 0f } ?: 1f

            val w = (safeRes * 0.4166f).roundToInt().coerceAtLeast(64)
            val h = (w / aspect).roundToInt().coerceAtLeast(64)

            svg.documentWidth = w.toFloat()
            svg.documentHeight = h.toFloat()

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            svg.renderToCanvas(canvas)

            stickerCache.put(cacheKey, bmp)

            engineScope.launch {
                try {
                    FileOutputStream(diskFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    Log.e("StickerEngine", "Disk cache write failed", e)
                }
            }

            bmp
        } catch (e: Exception) {
            Log.e("StickerEngine", "Load SVG failed: $assetPath", e)
            null
        }
    }
}

/**
 * --- THE TYPOGRAPHER (TextEngine) ---
 * Renders styled multi-line text and emoji layers into transparent Bitmaps.
 */
@Singleton
class TextEngine @Inject constructor() {
    private val textBitmapCache = LruCache<String, Bitmap>(20)

    fun getTextBitmap(layer: TextLayer, targetBaseResolution: Int = 1080): Bitmap? {
        if (layer.text.isBlank()) {
            return null
        }

        val safeRes = targetBaseResolution.coerceAtLeast(256)
        val cacheKey = "${layer.id}_${layer.text}_${layer.color}_${layer.size}_${layer.opacity}_$safeRes"

        val cachedBitmap = textBitmapCache.get(cacheKey)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            return cachedBitmap
        }

        return try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                color = layer.color
                textSize = (layer.size * 3f * (safeRes / 1080f)).coerceAtLeast(12f)
                typeface = Typeface.DEFAULT_BOLD
                isSubpixelText = true
                isLinearText = true
                isDither = true
            }

            val maxWidth = (safeRes * 0.85f).roundToInt().coerceAtLeast(100)

            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(layer.text, paint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            }

            if (layout.width <= 0 || layout.height <= 0) {
                return null
            }

            val bmp = Bitmap.createBitmap(
                (layout.width + 24).coerceAtLeast(1),
                (layout.height + 24).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            canvas.translate(12f, 12f)
            layout.draw(canvas)

            textBitmapCache.put(cacheKey, bmp)
            bmp
        } catch (e: Exception) {
            Log.e("TextEngine", "Render Text failed", e)
            null
        }
    }
}

/**
 * --- THE MASTER PAINTER (PhotoEditorEngine) ---
 * Applies cropping, ColorMatrix adjustments, pixel-level highlights/shadows, 3D LUTs, overlays, and geometry.
 */
@Singleton
class PhotoEditorEngine @Inject constructor(
    private val lutEngine: LutEngine,
    private val stickerEngine: StickerEngine,
    private val textEngine: TextEngine
) {
    suspend fun createPreview(
        bitmap: Bitmap,
        state: EditState,
        renderOverlays: Boolean,
        applyGeometry: Boolean = false
    ): Bitmap {
        return withContext(Dispatchers.Default) {
            var res = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext bitmap

            // --- STEP 1: CROPPING ---
            val cropRect = state.cropRect
            if (cropRect != null) {
                if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                    val x = (cropRect.left * res.width).roundToInt().coerceIn(0, res.width - 1)
                    val y = (cropRect.top * res.height).roundToInt().coerceIn(0, res.height - 1)
                    val w = (cropRect.width() * res.width).roundToInt().coerceIn(1, res.width - x)
                    val h = (cropRect.height() * res.height).roundToInt().coerceIn(1, res.height - y)
                    if (w > 0 && h > 0) {
                        val newBmp = Bitmap.createBitmap(res, x, y, w, h)
                        if (newBmp !== res) {
                            if (res !== bitmap) res.recycle()
                            res = newBmp
                        }
                    }
                }
            }

            // --- STEP 2: COLOR ADJUSTMENTS (BRIGHTNESS, EXPOSURE, CONTRAST, SATURATION, TEMP/TINT) ---
            val cm = ColorMatrix()

            // Brightness & Exposure: sc = (1 + brightness) * (2 ^ exposure)
            val sc = (1f + state.brightness) * (2.0f.pow(state.exposure))
            val scaleMatrix = ColorMatrix().apply {
                setScale(sc, sc, sc, 1f)
            }
            cm.postConcat(scaleMatrix)

            // Contrast
            val c = state.contrast
            val t = (-0.5f * c + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)

            // Saturation
            val saturationMatrix = ColorMatrix().apply {
                setSaturation(state.saturation)
            }
            cm.postConcat(saturationMatrix)

            // Temperature (Warm/Cool on R & B diagonals) & Tint (Green/Magenta on G diagonal)
            val tempTintMatrix = ColorMatrix(
                floatArrayOf(
                    1f + state.temperature * 0.1f, 0f, 0f, 0f, 0f,
                    0f, 1f + state.tint * 0.1f, 0f, 0f, 0f,
                    0f, 0f, 1f - state.temperature * 0.1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(tempTintMatrix)

            var out = Bitmap.createBitmap(res.width, res.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            val canvas = Canvas(out)
            canvas.drawBitmap(res, 0f, 0f, paint)

            if (res !== bitmap) {
                res.recycle()
            }

            // Highlights and Shadows
            if (state.highlights != 0f || state.shadows != 0f) {
                val pixels = IntArray(out.width * out.height)
                out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
                for (i in pixels.indices) {
                    val col = pixels[i]
                    val a = (col shr 24) and 0xff
                    var r = (col shr 16) and 0xff
                    var g = (col shr 8) and 0xff
                    var b = col and 0xff

                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val sMsk = (1f - lum) * (1f - lum)
                    val hMsk = lum * lum

                    val delta = (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)
                    r = (r + delta).roundToInt().coerceIn(0, 255)
                    g = (g + delta).roundToInt().coerceIn(0, 255)
                    b = (b + delta).roundToInt().coerceIn(0, 255)

                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
            }

            // --- STEP 3: CINEMATIC FILTERS (LUTS) ---
            val currentLut = state.lutData
            if (currentLut != null && state.lutIntensity > 0.001f) {
                val lutBmp = lutEngine.applyCpuLut(out, currentLut, state.lutIntensity)
                if (out !== bitmap) {
                    out.recycle()
                }
                out = lutBmp
            }

            // --- STEP 4: OVERLAYS (STICKERS AND TEXT) ---
            if (renderOverlays) {
                val sCan = Canvas(out)
                val resV = max(out.width, out.height)

                val matrix = Matrix()
                val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

                val activeStickers = state.stickers.filter { it.isVisible }.sortedBy { it.zIndex }
                for (s in activeStickers) {
                    if (s.assetPath.isEmpty() && s.emoji.isNotEmpty()) {
                        val textLayerEquiv = TextLayer(
                            id = s.id,
                            text = s.emoji,
                            color = Color.BLACK,
                            size = 150f * s.scale,
                            x = s.x,
                            y = s.y,
                            rotation = s.rotation,
                            opacity = s.opacity,
                            isVisible = s.isVisible,
                            zIndex = s.zIndex
                        )
                        val bmp = textEngine.getTextBitmap(textLayerEquiv, out.width)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                            matrix.postRotate(s.rotation)
                            matrix.postTranslate(s.x * out.width, s.y * out.height)
                            overlayPaint.alpha = (s.opacity * 255f).roundToInt().coerceIn(0, 255)
                            sCan.drawBitmap(bmp, matrix, overlayPaint)
                        }
                    } else {
                        val bmp = stickerEngine.getStickerBitmap(s.assetPath, out.width)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                            matrix.postScale(s.scale, s.scale)
                            matrix.postRotate(s.rotation)
                            matrix.postTranslate(s.x * out.width, s.y * out.height)
                            overlayPaint.alpha = (s.opacity * 255f).roundToInt().coerceIn(0, 255)
                            sCan.drawBitmap(bmp, matrix, overlayPaint)
                        }
                    }
                }

                val activeTextLayers = state.textLayers.filter { it.isVisible }.sortedBy { it.zIndex }
                for (t in activeTextLayers) {
                    val bmp = textEngine.getTextBitmap(t, resV)
                    if (bmp != null) {
                        matrix.reset()
                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                        matrix.postRotate(t.rotation)
                        matrix.postTranslate(t.x * out.width, t.y * out.height)
                        overlayPaint.alpha = (t.opacity * 255f).roundToInt().coerceIn(0, 255)
                        sCan.drawBitmap(bmp, matrix, overlayPaint)
                    }
                }
            }

            // --- STEP 5: ROTATING & FLIPPING ---
            if (applyGeometry) {
                val sX = if (state.flipHorizontal) -1f else 1f
                val sY = if (state.flipVertical) -1f else 1f

                if (state.rotationDegrees != 0f || state.straightenDegrees != 0f || sX != 1f || sY != 1f) {
                    val finalMatrix = Matrix().apply {
                        postRotate(
                            state.rotationDegrees + state.straightenDegrees,
                            out.width / 2f,
                            out.height / 2f
                        )
                        postScale(sX, sY, out.width / 2f, out.height / 2f)
                    }

                    val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, finalMatrix, true)
                    if (rotated !== out && out !== bitmap) {
                        out.recycle()
                    }
                    return@withContext rotated
                }
            }

            out
        }
    }
}

/**
 * --- THE FILM SPLICER (VideoEditorEngine) ---
 * Extracts high-quality still frames from videos.
 */
@UnstableApi
@Singleton
class VideoEditorEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private var activeTransformer: Transformer? = null

    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val galleryDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "GalleryBox"
                )
                galleryDir.mkdirs()

                val f = File(galleryDir, "Frame_${System.currentTimeMillis()}.jpg")
                retriever.setDataSource(context, uri)

                // MediaMetadataRetriever expects microseconds (posMs * 1000L)
                val frameBitmap = retriever.getFrameAtTime(
                    posMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (frameBitmap != null) {
                    FileOutputStream(f).use { fos ->
                        frameBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                        fos.flush()
                    }
                    frameBitmap.recycle()
                    f
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("VideoEngine", "Extract frame failed", e)
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }
}

/**
 * --- THE PRINTING PRESS (ExportEngine) ---
 * Exports final edited images and videos to `Pictures/GalleryBox`.
 */
@UnstableApi
@Singleton
class ExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoEngine: PhotoEditorEngine,
    private val stickerEngine: StickerEngine,
    private val textEngine: TextEngine
) {
    private var activeTransformer: Transformer? = null

    private fun decodeSourceBitmapWithExif(uri: Uri, sampleSize: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetSampleSize(sampleSize.coerceAtLeast(1))
            }
        } else {
            @Suppress("DEPRECATION")
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            } ?: MediaStore.Images.Media.getBitmap(context.contentResolver, uri)

            val exifRotation = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            }.getOrDefault(0f)

            return if (exifRotation != 0f) {
                val m = Matrix().apply { postRotate(exifRotation) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                    if (it !== raw) raw.recycle()
                }
            } else {
                raw
            }
        }
    }

    suspend fun saveMedia(
        uri: Uri,
        state: EditState,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30,
        videoBitrate: Int = 15000000,
        isVideo: Boolean,
        asSticker: Boolean,
        useH265: Boolean = false,
        isPreviewExport: Boolean = false,
        onProgress: (Float) -> Unit
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val galleryDir = File(picturesDir, "GalleryBox")
                galleryDir.mkdirs()

                val extension = when {
                    isVideo -> "mp4"
                    asSticker -> "png"
                    else -> "jpg"
                }

                val file = File(galleryDir, "Saved_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")

                // --- SAVING A PHOTO ---
                if (!isVideo) {
                    onProgress(0.1f)
                    val sampleSize = if (isPreviewExport) 2 else 1
                    val src = decodeSourceBitmapWithExif(uri, sampleSize)

                    onProgress(0.4f)
                    val out = photoEngine.createPreview(
                        bitmap = src,
                        state = state,
                        renderOverlays = true,
                        applyGeometry = true
                    )

                    onProgress(0.8f)
                    FileOutputStream(file).use { fos ->
                        val format = if (asSticker) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        out.compress(format, 95, fos)
                        fos.flush()
                    }

                    if (src !== out) {
                        src.recycle()
                    }
                    out.recycle()

                    onProgress(1f)
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> }
                    return@withContext file
                }

                // --- SAVING A VIDEO (Media3 Transformer) ---
                val bldr = Media3Item.Builder().setUri(uri)

                // 1. Trimming
                val ts = runCatching { state.trimStartMs }.getOrDefault(0L)
                val te = runCatching { state.trimEndMs }.getOrDefault(0L)

                if (ts > 0L || te > 0L) {
                    val clipConfig = Media3Item.ClippingConfiguration.Builder()
                        .setStartPositionMs(ts.coerceAtLeast(0L))
                    if (te > ts) {
                        clipConfig.setEndPositionMs(te)
                    }
                    bldr.setClippingConfiguration(clipConfig.build())
                }

                val effs = mutableListOf<Effect>()
                val auds = mutableListOf<AudioProcessor>()

                // 2. Audio Volume
                if (state.videoVolume != 1f && !state.isMuted) {
                    auds.add(VolumeAudioProcessor(state.videoVolume))
                }

                // 3. Resolution
                val safeTargetW = if (targetWidth > 0) targetWidth else 1920
                val safeTargetH = if (targetHeight > 0) targetHeight else 1080
                effs.add(
                    Presentation.createForWidthAndHeight(
                        safeTargetW,
                        safeTargetH,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )

                // 4. Cropping (Normalized [0..1] converted to OpenGL NDC [-1..1])
                val cropRect = state.cropRect
                if (cropRect != null) {
                    if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                        val leftNdc = (cropRect.left * 2f - 1f).coerceIn(-1f, 1f)
                        val rightNdc = (cropRect.right * 2f - 1f).coerceIn(-1f, 1f)
                        val bottomNdc = (1f - cropRect.bottom * 2f).coerceIn(-1f, 1f)
                        val topNdc = (1f - cropRect.top * 2f).coerceIn(-1f, 1f)
                        if (rightNdc > leftNdc && topNdc > bottomNdc) {
                            effs.add(Crop(leftNdc, rightNdc, bottomNdc, topNdc))
                        }
                    }
                }

                // 5. Video Color Adjustments (Brightness, Exposure, Contrast, Temperature, Tint)
                if (
                    state.brightness != 0f ||
                    state.exposure != 0f ||
                    state.contrast != 1f ||
                    state.temperature != 0f ||
                    state.tint != 0f
                ) {
                    val gain = (1f + state.brightness) * (2.0f.pow(state.exposure)) * state.contrast
                    val rScale = (gain * (1f + state.temperature * 0.1f)).coerceIn(0f, 4f)
                    val gScale = (gain * (1f + state.tint * 0.1f)).coerceIn(0f, 4f)
                    val bScale = (gain * (1f - state.temperature * 0.1f)).coerceIn(0f, 4f)
                    effs.add(
                        RgbMatrix { _, _ ->
                            floatArrayOf(
                                rScale, 0f, 0f, 0f,
                                0f, gScale, 0f, 0f,
                                0f, 0f, bScale, 0f,
                                0f, 0f, 0f, 1f
                            )
                        }
                    )
                }

                // 6. Flipping and Rotation
                val sx = if (state.flipHorizontal) -1f else 1f
                val sy = if (state.flipVertical) -1f else 1f
                val totalRotation = state.rotationDegrees + state.straightenDegrees

                if (totalRotation != 0f || sx != 1f || sy != 1f) {
                    val transformBuilder = ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(totalRotation)
                        .setScale(sx, sy)
                    effs.add(transformBuilder.build())
                }

                // 7. Stickers and Text Overlays on Video
                if (state.stickers.any { it.isVisible } || state.textLayers.any { it.isVisible }) {
                    val overlayBmp = Bitmap.createBitmap(safeTargetW, safeTargetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(overlayBmp)
                    val matrix = Matrix()
                    val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

                    val activeStickers = state.stickers.filter { it.isVisible }.sortedBy { it.zIndex }
                    for (s in activeStickers) {
                        if (s.assetPath.isEmpty() && s.emoji.isNotEmpty()) {
                            val textLayerEquiv = TextLayer(
                                id = s.id,
                                text = s.emoji,
                                color = Color.BLACK,
                                size = 150f * s.scale,
                                x = s.x,
                                y = s.y,
                                rotation = s.rotation,
                                opacity = s.opacity,
                                isVisible = s.isVisible,
                                zIndex = s.zIndex
                            )
                            val bmp = textEngine.getTextBitmap(textLayerEquiv, safeTargetW)
                            if (bmp != null) {
                                matrix.reset()
                                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                                matrix.postRotate(s.rotation)
                                matrix.postTranslate(s.x * safeTargetW, s.y * safeTargetH)
                                overlayPaint.alpha = (s.opacity * 255f).roundToInt().coerceIn(0, 255)
                                canvas.drawBitmap(bmp, matrix, overlayPaint)
                            }
                        } else {
                            val bmp = stickerEngine.getStickerBitmap(s.assetPath, safeTargetW)
                            if (bmp != null) {
                                matrix.reset()
                                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                                matrix.postScale(s.scale, s.scale)
                                matrix.postRotate(s.rotation)
                                matrix.postTranslate(s.x * safeTargetW, s.y * safeTargetH)
                                overlayPaint.alpha = (s.opacity * 255f).roundToInt().coerceIn(0, 255)
                                canvas.drawBitmap(bmp, matrix, overlayPaint)
                            }
                        }
                    }

                    val activeTextLayers = state.textLayers.filter { it.isVisible }.sortedBy { it.zIndex }
                    for (t in activeTextLayers) {
                        val bmp = textEngine.getTextBitmap(t, safeTargetW)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                            matrix.postRotate(t.rotation)
                            matrix.postTranslate(t.x * safeTargetW, t.y * safeTargetH)
                            overlayPaint.alpha = (t.opacity * 255f).roundToInt().coerceIn(0, 255)
                            canvas.drawBitmap(bmp, matrix, overlayPaint)
                        }
                    }

                    val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBmp)
                    effs.add(OverlayEffect(ImmutableList.of(bitmapOverlay) as List<TextureOverlay>))
                }

                val editedMediaItem = EditedMediaItem.Builder(bldr.build())
                    .setEffects(Effects(auds, effs))
                    .setRemoveAudio(state.isMuted)
                    .setFrameRate(targetFps.coerceIn(15, 120))
                    .build()

                val sequence = EditedMediaItemSequence(editedMediaItem)
                val composition = Composition.Builder(listOf(sequence)).build()

                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val transformerBuilder = Transformer.Builder(context)
                        val mimeType = if (useH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
                        transformerBuilder.setVideoMimeType(mimeType)
                        activeTransformer = transformerBuilder.build()

                        val listener = object : Transformer.Listener {
                            override fun onCompleted(c: Composition, r: ExportResult) {
                                Log.d("EXPORT", "Completed")
                                activeTransformer = null
                                onProgress(1f)
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                                Log.e("EXPORT", "Error", e)
                                activeTransformer = null
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }

                        activeTransformer?.addListener(listener)
                        activeTransformer?.start(composition, file.absolutePath)

                        val progressJob = launch {
                            val p = ProgressHolder()
                            while (isActive) {
                                if (activeTransformer?.getProgress(p) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    onProgress((p.progress / 100f).coerceIn(0f, 1f))
                                }
                                delay(100)
                            }
                        }

                        cont.invokeOnCancellation {
                            progressJob.cancel()
                            activeTransformer?.cancel()
                            activeTransformer = null
                        }
                    }
                }

                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> }
                file
            } catch (e: Exception) {
                Log.e("ExportEngine", "Save exception", e)
                null
            }
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }
}

/**
 * --- THE AUDIO MIXER (VolumeAudioProcessor) ---
 * Scales PCM 16-bit audio samples by `volume` with saturation clamping.
 */
@UnstableApi
class VolumeAudioProcessor(private val volume: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        val buffer = replaceOutputBuffer(inputBuffer.remaining())

        while (inputBuffer.hasRemaining()) {
            val originalValue = inputBuffer.getShort()
            val adjustedValue = (originalValue * volume).roundToInt()
            val clampedValue = adjustedValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(clampedValue.toShort())
        }

        buffer.flip()
    }
}