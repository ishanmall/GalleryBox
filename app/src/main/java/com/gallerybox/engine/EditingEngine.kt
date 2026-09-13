// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress(
    "unused",
    "OPT_IN_USAGE",
    "UNCHECKED_CAST",
    "ObsoleteSdkInt",
    "DEPRECATION",
    "UnsafeOptInUsageError",
    "SpellCheckingInspection"
)
// Tells Android that we are using some advanced, experimental video-editing tools (Media3).
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.engine

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// Tools for image manipulation (Bitmaps), video rendering (Transformer), drawing math (Matrix), and file saving.
import android.content.Context
import android.graphics.*
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
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.caverock.androidsvg.SVG
import com.gallerybox.data.*
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

/**
 * --- THE TIME MACHINE (EditStateManager) ---
 * Every time the user changes a slider, adds a sticker, or crops a photo, we save a "snapshot" of all their settings.
 * This class keeps a stack of those snapshots so the user can hit the "Undo" or "Redo" button to go backward or forward in time.
 */
@Singleton
class EditStateManager @Inject constructor() {
    private val undoStack = ArrayDeque<EditState>() // The past
    private val redoStack = ArrayDeque<EditState>() // The alternate future (if you undo, then want to go back)

    // The current active set of instructions (brightness: 50%, crop: square, etc.)
    var currentState: EditState = EditState()
        private set

    // Keeps track of which stickers or text the user is currently tapping on.
    var selectedLayerIds = mutableSetOf<String>()

    // Saves a new snapshot to history
    fun pushState(newState: EditState) {
        undoStack.addLast(currentState)
        // We only remember the last 50 edits so the phone's memory doesn't get completely filled up.
        if (undoStack.size > 50) {
            undoStack.removeFirst()
        }
        redoStack.clear() // If you change history, the alternate future is destroyed.
        currentState = newState
    }

    // Goes backward in time one step
    fun undo(): EditState? {
        if (undoStack.isEmpty()) {
            return null // We are at the very beginning, nothing to undo.
        }
        redoStack.addLast(currentState) // Save where we are right now into the future stack just in case
        currentState = undoStack.removeLast() // Pull the last snapshot from the past
        return currentState
    }

    // Goes forward in time one step
    fun redo(): EditState? {
        if (redoStack.isEmpty()) {
            return null // No alternate future exists.
        }
        undoStack.addLast(currentState)
        currentState = redoStack.removeLast()
        return currentState
    }

    /**
     * THE HITBOX CHECKER.
     * When the user taps their finger on the screen, did they hit a specific sticker?
     * This math accounts for if the sticker is rotated, shrunk, or moved into a weird corner.
     */
    fun isPointInLayer(
        x: Float, y: Float, // Where the user tapped
        layerX: Float, layerY: Float, // The exact center of the sticker
        width: Float, height: Float, // How big the sticker is
        rotation: Float, scale: Float = 1f // Is it rotated or shrunk?
    ): Boolean {
        // Advanced Trigonometry: We basically temporarily un-rotate the whole universe to see if the tap lands inside the box.
        val dx = x - layerX
        val dy = y - layerY
        val rad = Math.toRadians((-rotation).toDouble())
        val rx = dx * cos(rad) - dy * sin(rad)
        val ry = dx * sin(rad) + dy * cos(rad)
        val halfW = (width * scale) / 2f
        val halfH = (height * scale) / 2f
        return rx in -halfW..halfW && ry in -halfH..halfH // If true, the user poked the sticker!
    }
}

/**
 * --- THE DIGITAL DARKROOM (EditingEngine) ---
 * This is the master coordinator. It doesn't do the math itself, but it takes orders from the screen
 * and hands them to the specialized workers below (Photo Engine, Video Engine, Text Engine, etc.).
 */
@UnstableApi
@Singleton
class EditingEngine @Inject constructor(
    private val photoEngine: PhotoEditorEngine,
    private val videoEngine: VideoEditorEngine,
    private val lutEngine: LutEngine,
    private val exportEngine: ExportEngine
) {
    // Quickly generate a low-quality preview so the user can see their edits in real-time as they drag the brightness slider.
    suspend fun createPreview(
        bitmap: Bitmap,
        state: EditState,
        renderOverlays: Boolean = false,
        applyGeometry: Boolean = false
    ): Bitmap {
        return photoEngine.createPreview(bitmap, state, renderOverlays, applyGeometry)
    }

    // Pulls a single still picture out of a video file.
    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return videoEngine.extractFrame(uri, posMs)
    }

    // Loads a cinematic color filter file.
    suspend fun loadLut(path: String): CubeLut? {
        return lutEngine.loadLut(path)
    }

    // The big, slow job. Taking all the final edits and permanently burning them into a new file to save to the phone.
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
        onProgress: (Float) -> Unit // Sends the 0% to 100% progress back to the screen
    ): File? {
        return exportEngine.saveMedia(
            uri, state, targetWidth, targetHeight, targetFps, videoBitrate, isVideo, asSticker, useH265, isPreviewExport, onProgress
        )
    }

    // Stops a long saving process if the user hits "Cancel".
    fun cancelExport() {
        videoEngine.cancel()
        exportEngine.cancel()
    }
}

/**
 * --- THE COLOR CHEMIST (LutEngine) ---
 * A LUT (Look-Up Table) is a complex cinematic filter. Instead of just "Turn up Brightness",
 * a LUT says "Turn dark blues into purples, make skin tones warmer, and crush the dark shadows."
 */
@Singleton
class LutEngine @Inject constructor(@ApplicationContext private val context: Context) {

    // Reads the raw math from a ".cube" file inside the app.
    suspend fun loadLut(path: String): CubeLut? {
        return withContext(Dispatchers.IO) { // Do this in the background warehouse, reading files is slow.
            try {
                context.assets.open(path).bufferedReader().use { reader ->
                    var size = 0
                    val data = mutableListOf<Float>()

                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("LUT_3D_SIZE")) {
                            val parts = trimmed.split(Regex("\\s+"))
                            size = parts.last().toIntOrNull() ?: 0
                        } else if (!trimmed.startsWith("#") && !trimmed.startsWith("TITLE") && !trimmed.startsWith("DOMAIN_") && trimmed.isNotBlank()) {
                            // Extract the raw Red, Green, and Blue adjustment numbers
                            val rgb = trimmed.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                            if (rgb.size >= 3) {
                                data.addAll(rgb.take(3))
                            }
                        }
                    }
                    if (size > 0 && data.isNotEmpty()) {
                        CubeLut(size, data.toFloatArray()) // Package it up into our data box
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("LutEngine", "Load LUT failed", e)
                null
            }
        }
    }

    // Extremely complex math that applies the LUT filter to every single pixel in the photo.
    fun applyCpuLut(src: Bitmap, lut: CubeLut, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val s = lut.size
        val d = lut.data

        // A tiny helper tool. "Lerp" means finding the exact middle point between two numbers.
        fun lerp(v0: Float, v1: Float, t: Float): Float = v0 + (v1 - v0) * t

        // Go through every single pixel in the photo
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xff
            val r = (c shr 16) and 0xff // Extract the Red color of the pixel
            val g = (c shr 8) and 0xff  // Extract the Green
            val b = c and 0xff          // Extract the Blue

            // Calculate where this specific color exists inside the 3D color cube of the filter
            val rF = (r / 255f) * (s - 1)
            val gF = (g / 255f) * (s - 1)
            val bF = (b / 255f) * (s - 1)

            val r0 = rF.toInt().coerceIn(0, s - 1)
            val g0 = gF.toInt().coerceIn(0, s - 1)
            val b0 = bF.toInt().coerceIn(0, s - 1)

            val r1 = (r0 + 1).coerceIn(0, s - 1)
            val g1 = (g0 + 1).coerceIn(0, s - 1)
            val b1 = (b0 + 1).coerceIn(0, s - 1)

            val fr = rF - r0
            val fg = gF - g0
            val fb = bF - b0

            var nr = 0f
            var ng = 0f
            var nb = 0f

            // This math blends the 8 closest colors inside the filter cube to figure out exactly what the new color should be.
            for (ch in 0..2) {
                val c000 = d[((r0 + g0 * s + b0 * s * s) * 3) + ch]
                val c100 = d[((r1 + g0 * s + b0 * s * s) * 3) + ch]
                val c010 = d[((r0 + g1 * s + b0 * s * s) * 3) + ch]
                val c110 = d[((r1 + g1 * s + b0 * s * s) * 3) + ch]
                val c001 = d[((r0 + g0 * s + b1 * s * s) * 3) + ch]
                val c101 = d[((r1 + g0 * s + b1 * s * s) * 3) + ch]
                val c011 = d[((r0 + g1 * s + b1 * s * s) * 3) + ch]
                val c111 = d[((r1 + g1 * s + b1 * s * s) * 3) + ch]

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

            // Mix the original color with the new filtered color based on how strong the user set the filter slider.
            val finalR = (r + (nr - r) * intensity).toInt().coerceIn(0, 255)
            val finalG = (g + (ng - g) * intensity).toInt().coerceIn(0, 255)
            val finalB = (b + (nb - b) * intensity).toInt().coerceIn(0, 255)

            // Reconstruct the final pixel
            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        // Apply all the modified pixels back onto a blank digital canvas
        val bmp = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}

/**
 * --- THE STICKER FACTORY (StickerEngine) ---
 * Converts digital sticker files (.svg vectors) into flat images we can paste onto a photo.
 */
@Singleton
class StickerEngine @Inject constructor(@ApplicationContext private val context: Context) {
    // Memory box to hold up to 80 stickers so we don't have to rebuild them if the user uses the same sticker twice.
    private val stickerCache = LruCache<String, Bitmap>(80)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getDiskCacheFile(key: String): File {
        return File(context.cacheDir, "sticker_$key.png")
    }

    fun getStickerBitmap(assetPath: String, targetBaseResolution: Int = 1080): Bitmap? {
        val cacheKey = "${assetPath.replace("/", "_")}_$targetBaseResolution"

        // Check if we already built this sticker and kept it in memory
        val cachedBitmap = stickerCache.get(cacheKey)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        // Check if we built it previously and saved it to the phone's hard drive cache
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

        // If not found anywhere, we have to build it from scratch from the raw SVG code.
        return try {
            val svg = SVG.getFromAsset(context.assets, assetPath)

            val docWidth = if (svg.documentWidth > 0) svg.documentWidth else 500f
            val docHeight = if (svg.documentHeight > 0) svg.documentHeight else 500f
            val aspect = docWidth / docHeight

            // Make sure the sticker size scales correctly whether it's on a 1080p photo or a 4K photo.
            val w = (targetBaseResolution * 0.4166f).toInt()
            val h = (w / aspect).toInt()

            svg.documentWidth = w.toFloat()
            svg.documentHeight = h.toFloat()

            // Draw the sticker onto a transparent digital canvas
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            svg.renderToCanvas(canvas)

            stickerCache.put(cacheKey, bmp) // Save to fast memory

            // Save to hard drive in the background for next time
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
            Log.e("StickerEngine", "Load SVG failed", e)
            null
        }
    }
}

/**
 * --- THE TYPOGRAPHER (TextEngine) ---
 * Turns text (like "Happy Birthday!") into a transparent image so it can be stamped onto a photo like a sticker.
 */
@Singleton
class TextEngine @Inject constructor() {
    private val textBitmapCache = LruCache<String, Bitmap>(20)

    fun getTextBitmap(layer: TextLayer, targetBaseResolution: Int = 1080): Bitmap? {
        // Unique name for this exact piece of text (changes if they change color, size, etc.)
        val cacheKey = "${layer.id}_${layer.text}_${layer.color}_${layer.size}_${layer.opacity}_$targetBaseResolution"

        val cachedBitmap = textBitmapCache.get(cacheKey)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        if (layer.text.isBlank()) {
            return null
        }

        return try {
            // Set up the digital paintbrush
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                color = layer.color
                // Make sure the text font size scales properly based on the photo resolution
                textSize = (layer.size * 3f * (targetBaseResolution / 1080f)).coerceAtLeast(12f)
                typeface = Typeface.DEFAULT_BOLD // Make it thick so it's easy to read on photos
                isSubpixelText = true
                isLinearText = true
                isDither = true
            }

            val maxWidth = (targetBaseResolution * 0.85f).toInt() // Prevent text from going entirely off-screen

            // Android's built-in tool that handles turning words into actual visible shapes with line breaks
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

            // Create a transparent canvas slightly larger than the text
            val bmp = Bitmap.createBitmap((layout.width + 24).toInt(), (layout.height + 24).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(12f, 12f) // Add a tiny bit of padding
            layout.draw(canvas) // Stamp the text onto the transparent canvas

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
 * This worker takes the original photo and applies ALL the user's edits (cropping, brightness, stickers) layer by layer.
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
            // Make a working copy of the original image so we don't accidentally ruin the original file.
            var res = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext bitmap

            // --- STEP 1: CROPPING (SCISSORS) ---
            val cropRect = state.cropRect
            if (cropRect != null) {
                // Check if they actually cropped it, or left the box at 100% full size.
                if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                    val x = (cropRect.left * res.width).toInt().coerceAtLeast(0)
                    val y = (cropRect.top * res.height).toInt().coerceAtLeast(0)
                    val w = (cropRect.width() * res.width).toInt().coerceAtMost(res.width - x)
                    val h = (cropRect.height() * res.height).toInt().coerceAtMost(res.height - y)
                    if (w > 0 && h > 0) {
                        val newBmp = Bitmap.createBitmap(res, x, y, w, h) // Cut out the selected box
                        if (newBmp !== res) {
                            if (res !== bitmap) res.recycle() // Throw away the un-cropped version to save memory
                            res = newBmp
                        }
                    }
                }
            }

            // --- STEP 2: COLOR ADJUSTMENTS (BRIGHTNESS, CONTRAST, SATURATION) ---
            // A ColorMatrix is a math grid. When pixels pass through the grid, their colors change.
            val cm = ColorMatrix()

            // Brightness & Exposure
            val sc = (1f + state.brightness) * (2.0f.pow(state.exposure))
            val scaleMatrix = ColorMatrix()
            scaleMatrix.setScale(sc, sc, sc, 1f)
            cm.postConcat(scaleMatrix) // Add this rule to the grid

            // Contrast
            val c = state.contrast
            val t = (-.5f * c + .5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    c,  0f, 0f, 0f, t,
                    0f, c,  0f, 0f, t,
                    0f, 0f, c,  0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)

            // Saturation
            val saturationMatrix = ColorMatrix()
            saturationMatrix.setSaturation(state.saturation)
            cm.postConcat(saturationMatrix)

            // Temperature (Warm/Cool) & Tint (Green/Magenta)
            val tempTintMatrix = ColorMatrix(
                floatArrayOf(
                    1f + state.temperature * 0.1f, 0f,                            0f, 0f, 0f,
                    0f,                            1f + state.tint * 0.1f,        0f, 0f, 0f,
                    0f,                            1f - state.temperature * 0.1f, 0f, 0f, 0f,
                    0f,                            0f,                            0f, 1f, 0f
                )
            )
            cm.postConcat(tempTintMatrix)

            // Actually apply the massive grid of math to the image all at once.
            var out = Bitmap.createBitmap(res.width, res.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(cm)
            val canvas = Canvas(out)
            canvas.drawBitmap(res, 0f, 0f, paint)

            if (res !== bitmap) {
                res.recycle()
            }

            // Highlights and Shadows (Advanced: requires analyzing every single pixel individually to see if it's light or dark)
            if (state.highlights != 0f || state.shadows != 0f) {
                val pixels = IntArray(out.width * out.height)
                out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
                for (i in pixels.indices) {
                    val col = pixels[i]
                    val a = (col shr 24) and 0xff
                    var r = (col shr 16) and 0xff
                    var g = (col shr 8) and 0xff
                    var b = col and 0xff

                    // Figure out if this specific pixel is a "highlight" (bright) or a "shadow" (dark)
                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val sMsk = (1f - lum) * (1f - lum) // Shadow Mask
                    val hMsk = lum * lum // Highlight Mask

                    // Adjust it
                    r = (r + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()
                    g = (g + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()
                    b = (b + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()

                    val safeR = r.coerceIn(0, 255)
                    val safeG = g.coerceIn(0, 255)
                    val safeB = b.coerceIn(0, 255)

                    pixels[i] = (a shl 24) or (safeR shl 16) or (safeG shl 8) or safeB
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
            // Think of this like taking clear glass plates with stickers on them, and laying them over the photo.
            if (renderOverlays) {
                val sCan = Canvas(out) // The main painting canvas
                val resV = max(out.width, out.height)

                val matrix = Matrix() // A tool used to move, shrink, or spin the stickers before stamping them
                val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

                // Sort stickers by "Z-Index" (which one is in front, which one is in back)
                val activeStickers = state.stickers.filter { it.isVisible }.sortedBy { it.zIndex }
                for (s in activeStickers) {
                    // Is this a standard image sticker, or an Emoji text sticker?
                    if (s.assetPath.isEmpty() && s.emoji.isNotEmpty()) {
                        // Pretend the Emoji is just a text layer
                        val textLayerEquiv = TextLayer(
                            id = s.id, text = s.emoji, color = android.graphics.Color.BLACK,
                            size = 150f * s.scale, x = s.x, y = s.y, rotation = s.rotation, opacity = s.opacity, isVisible = s.isVisible, zIndex = s.zIndex
                        )
                        val bmp = textEngine.getTextBitmap(textLayerEquiv, out.width)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f) // Center the sticker
                            matrix.postRotate(s.rotation) // Spin it
                            matrix.postTranslate(s.x * out.width, s.y * out.height) // Move it to where the user dragged it
                            overlayPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255) // Make it slightly see-through if needed
                            sCan.drawBitmap(bmp, matrix, overlayPaint) // STAMP IT!
                        }
                    } else {
                        val bmp = stickerEngine.getStickerBitmap(s.assetPath, out.width)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                            matrix.postScale(s.scale, s.scale) // Shrink or grow it
                            matrix.postRotate(s.rotation)
                            matrix.postTranslate(s.x * out.width, s.y * out.height)
                            overlayPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
                            sCan.drawBitmap(bmp, matrix, overlayPaint) // STAMP IT!
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

                        overlayPaint.alpha = (t.opacity * 255).toInt().coerceIn(0, 255)
                        sCan.drawBitmap(bmp, matrix, overlayPaint)
                    }
                }
            }

            // --- STEP 5: ROTATING & FLIPPING ---
            // We only do this right at the very end when permanently saving the file.
            if (applyGeometry) {
                val sX = if (state.flipHorizontal) -1f else 1f // Mirror left/right
                val sY = if (state.flipVertical) -1f else 1f // Mirror upside down

                if (state.rotationDegrees != 0f || state.straightenDegrees != 0f || sX != 1f || sY != 1f) {
                    val finalMatrix = Matrix()
                    finalMatrix.postRotate(
                        state.rotationDegrees + state.straightenDegrees,
                        out.width / 2f,
                        out.height / 2f
                    )
                    finalMatrix.postScale(sX, sY, out.width / 2f, out.height / 2f)

                    val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, finalMatrix, true)
                    if (rotated !== out && out !== bitmap) {
                        out.recycle()
                    }
                    return@withContext rotated
                }
            }

            out // Return the final, fully-edited painting!
        }
    }
}

/**
 * --- THE FILM SPLICER (VideoEditorEngine) ---
 * Provides basic utilities for video files, like grabbing a single screenshot out of the middle of a movie.
 */
@UnstableApi
@Singleton
class VideoEditorEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private var activeTransformer: Transformer? = null

    // Pulls a single picture frame out of a video file and saves it to the gallery.
    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure our custom folder exists on the phone
                val galleryDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GalleryBox")
                galleryDir.mkdirs()

                val f = File(galleryDir, "Frame_${System.currentTimeMillis()}.jpg")
                val retriever = android.media.MediaMetadataRetriever()

                retriever.setDataSource(context, uri) // Load the video
                // Grab the exact frame. We multiply by 1000 because this specific tool expects microseconds, not milliseconds.
                val frameBitmap = retriever.getFrameAtTime(posMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                if (frameBitmap != null) {
                    val fos = FileOutputStream(f)
                    frameBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos) // Save the picture
                    fos.close()
                }

                retriever.release()
                f
            } catch (e: Exception) {
                Log.e("VideoEngine", "Extract frame failed", e)
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
 * --- THE PRINTING PRESS (ExportEngine) ---
 * This worker takes the final edited photo (or video) and permanently burns it to the phone's hard drive as a new file.
 */
@UnstableApi
@Singleton
class ExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoEngine: PhotoEditorEngine,
    private val stickerEngine: StickerEngine,
    private val textEngine: TextEngine
) {
    private var activeTransformer: Transformer? = null // Android's master tool for video rendering

    suspend fun saveMedia(
        uri: Uri,
        state: EditState,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30, // Frames per second (for videos)
        videoBitrate: Int = 15000000, // Video quality
        isVideo: Boolean,
        asSticker: Boolean, // Saving a photo with a transparent background
        useH265: Boolean = false, // Advanced video compression
        isPreviewExport: Boolean = false,
        onProgress: (Float) -> Unit // Communication walkie-talkie back to the screen's loading bar
    ): File? {
        return withContext(Dispatchers.IO) { // Always do file saving in the background warehouse
            try {
                // Ensure our folder exists
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val galleryDir = File(picturesDir, "GalleryBox")
                galleryDir.mkdirs()

                val extension = if (isVideo) {
                    "mp4"
                } else if (asSticker) {
                    "png" // PNG supports invisible backgrounds, JPG does not
                } else {
                    "jpg"
                }

                val file = File(galleryDir, "Saved_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")

                // --- SAVING A PHOTO ---
                if (!isVideo) {
                    onProgress(0.1f) // Tell the screen "We are 10% done!"

                    val sampleSize = if (isPreviewExport) 2 else 1 // Are we saving a quick low-res version, or full HD?

                    // Load the original image from the phone
                    val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                            decoder.setTargetSampleSize(sampleSize)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val options = BitmapFactory.Options()
                        options.inSampleSize = sampleSize
                        options.inMutable = true

                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val decoded = BitmapFactory.decodeStream(inputStream, null, options)
                            inputStream.close()
                            decoded ?: MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        } else {
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                    }

                    onProgress(0.4f) // "40% done!"

                    // Send it to the Master Painter to apply all the edits
                    val out = photoEngine.createPreview(src, state, renderOverlays = true, applyGeometry = true)

                    onProgress(0.8f) // "80% done!"

                    // Take the painted canvas and write the raw data to the hard drive file.
                    val fos = FileOutputStream(file)
                    val format = if (asSticker) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    out.compress(format, 95, fos) // 95% quality compression
                    fos.close()

                    if (src !== out) {
                        src.recycle() // Cleanup memory
                    }
                    out.recycle()

                    onProgress(1f) // "100% complete!"

                    // Call the Android System and say "Hey, I just put a new file on the hard drive, please update your list!"
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> }
                    return@withContext file
                }

                // --- SAVING A VIDEO (EXTREMELY COMPLEX) ---
                // We use Google's 'Media3 Transformer' tool to edit video files.
                val bldr = Media3Item.Builder()
                bldr.setUri(uri)

                // 1. Trimming (Cutting out the beginning or end of the video)
                val ts = runCatching { state.trimStartMs }.getOrDefault(0L)
                val te = runCatching { state.trimEndMs }.getOrDefault(0L)

                if (ts > 0L || te > 0L) {
                    val clipConfig = Media3Item.ClippingConfiguration.Builder()
                    clipConfig.setStartPositionMs(ts)
                    if (te > ts) {
                        clipConfig.setEndPositionMs(te)
                    }
                    bldr.setClippingConfiguration(clipConfig.build())
                }

                val effs = mutableListOf<androidx.media3.common.Effect>() // Visual effects (crop, spin, stickers)
                val auds = mutableListOf<AudioProcessor>() // Audio effects (mute, volume)

                // 2. Audio Volume
                if (state.videoVolume != 1f) {
                    auds.add(VolumeAudioProcessor(state.videoVolume))
                }

                // 3. Resolution (e.g. shrinking 4K down to 1080p)
                if (targetWidth > 0 && targetHeight > 0) {
                    effs.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))
                }

                // 4. Cropping (Zooming in)
                val cropRect = state.cropRect
                if (cropRect != null) {
                    if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                        effs.add(Crop(cropRect.left * 2f - 1f, cropRect.right * 2f - 1f, 1f - cropRect.bottom * 2f, 1f - cropRect.top * 2f))
                    }
                }

                // 5. Flipping and Spinning
                val sx = if (state.flipHorizontal) -1f else 1f
                val sy = if (state.flipVertical) -1f else 1f

                val totalRotation = state.rotationDegrees + state.straightenDegrees

                if (totalRotation != 0f || sx != 1f || sy != 1f) {
                    val transformBuilder = ScaleAndRotateTransformation.Builder()
                    transformBuilder.setRotationDegrees(totalRotation)
                    transformBuilder.setScale(sx, sy)
                    effs.add(transformBuilder.build())
                }

                // 6. Stickers and Text on Video
                // Videos are tricky. We take all the stickers, stamp them onto ONE clear glass plate,
                // and then tell the Video Engine to permanently superimpose that single glass plate over the entire movie.
                if (state.stickers.any { it.isVisible } || state.textLayers.any { it.isVisible }) {
                    val overlayBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888) // The glass plate
                    val canvas = Canvas(overlayBmp)
                    val matrix = Matrix()
                    val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

                    val activeStickers = state.stickers.filter { it.isVisible }.sortedBy { it.zIndex }
                    for (s in activeStickers) {
                        if (s.assetPath.isEmpty() && s.emoji.isNotEmpty()) {
                            val textLayerEquiv = TextLayer(
                                id = s.id, text = s.emoji, color = android.graphics.Color.BLACK,
                                size = 150f * s.scale, x = s.x, y = s.y, rotation = s.rotation, opacity = s.opacity, isVisible = s.isVisible, zIndex = s.zIndex
                            )
                            val bmp = textEngine.getTextBitmap(textLayerEquiv, targetWidth)
                            if (bmp != null) {
                                matrix.reset()
                                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                                matrix.postRotate(s.rotation)
                                matrix.postTranslate(s.x * targetWidth, s.y * targetHeight)
                                overlayPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
                                canvas.drawBitmap(bmp, matrix, overlayPaint)
                            }
                        } else {
                            val bmp = stickerEngine.getStickerBitmap(s.assetPath, targetWidth)
                            if (bmp != null) {
                                matrix.reset()
                                matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                                matrix.postScale(s.scale, s.scale)
                                matrix.postRotate(s.rotation)
                                matrix.postTranslate(s.x * targetWidth, s.y * targetHeight)
                                overlayPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
                                canvas.drawBitmap(bmp, matrix, overlayPaint)
                            }
                        }
                    }
                    val activeTextLayers = state.textLayers.filter { it.isVisible }.sortedBy { it.zIndex }
                    for (t in activeTextLayers) {
                        val bmp = textEngine.getTextBitmap(t, targetWidth)
                        if (bmp != null) {
                            matrix.reset()
                            matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                            matrix.postRotate(t.rotation)
                            matrix.postTranslate(t.x * targetWidth, t.y * targetHeight)
                            overlayPaint.alpha = (t.opacity * 255).toInt().coerceIn(0, 255)
                            canvas.drawBitmap(bmp, matrix, overlayPaint)
                        }
                    }
                    // Apply the glass plate to the video instructions
                    val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBmp)
                    effs.add(OverlayEffect(listOf(bitmapOverlay)))
                }

                // Compile all instructions (Trimming, Effects, Audio) into a single packet
                val editedMediaItemBuilder = EditedMediaItem.Builder(bldr.build())
                editedMediaItemBuilder.setEffects(Effects(auds, effs))
                editedMediaItemBuilder.setRemoveAudio(state.isMuted) // Mute if requested
                editedMediaItemBuilder.setFrameRate(targetFps)

                val editedMediaItem = editedMediaItemBuilder.build()
                val sequence = EditedMediaItemSequence(editedMediaItem)
                val seqs = mutableListOf(sequence)
                val composition = Composition.Builder(seqs).build()

                // START THE RENDER PROCESS
                withContext(Dispatchers.Main) { // Must be started on main thread
                    suspendCancellableCoroutine<Unit> { cont ->
                        val transformerBuilder = Transformer.Builder(context)
                        val mimeType = if (useH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
                        transformerBuilder.setVideoMimeType(mimeType)
                        activeTransformer = transformerBuilder.build()

                        // A listener that acts as a watcher, telling us when the rendering finishes or crashes
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(c: Composition, r: ExportResult) {
                                Log.d("EXPORT", "Completed")
                                activeTransformer = null
                                onProgress(1f) // "100%!"
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                                Log.e("EXPORT", "Error", e)
                                activeTransformer = null
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }

                        activeTransformer?.addListener(listener)

                        Log.d("EXPORT", "Starting export")
                        activeTransformer?.start(composition, file.absolutePath) // GO!

                        // A ticking clock that checks the Transformer's progress every 0.1 seconds and updates the UI.
                        val progressJob = launch {
                            val p = ProgressHolder()
                            while (isActive) {
                                if (activeTransformer?.getProgress(p) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    Log.d("EXPORT", "Progress = ${p.progress}")
                                    onProgress(p.progress / 100f)
                                }
                                delay(100)
                            }
                        }

                        // If the user hits "Cancel" on the screen, stop the rendering instantly
                        cont.invokeOnCancellation {
                            progressJob.cancel()
                            activeTransformer?.cancel()
                            activeTransformer = null
                        }
                    }
                }

                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> }

                file // Return the finished Video file
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
 * Android's default video editor doesn't have a simple "Volume Slider".
 * So we build a custom Audio Processor. As the audio waves stream through the pipes during rendering,
 * this interceptor catches the numbers, multiplies them by the user's volume setting (e.g., x0.5 for half volume),
 * and puts them back in the pipe.
 */
@UnstableApi
class VolumeAudioProcessor(private val volume: Float) : BaseAudioProcessor() {

    // Ensure we are working with standard uncompressed audio (PCM 16-bit)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            return inputAudioFormat
        } else {
            return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    // Intercepts the audio buffer byte by byte
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        val buffer = replaceOutputBuffer(inputBuffer.remaining())

        while (inputBuffer.hasRemaining()) {
            val originalValue = inputBuffer.getShort()
            val adjustedValue = (originalValue * volume).toInt() // Multiply by the volume setting
            // Ensure the loud noises don't break the limits and cause horrible crackling
            val clampedValue = adjustedValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(clampedValue.toShort())
        }

        buffer.flip() // Send it out
    }
}