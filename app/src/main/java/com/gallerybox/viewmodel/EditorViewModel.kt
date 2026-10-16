@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gallerybox.viewmodel

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.CubeLut
import com.gallerybox.data.EditState
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.MediaItem
import com.gallerybox.data.StickerLayer
import com.gallerybox.data.StickerUiItem
import com.gallerybox.data.StickerUnicode
import com.gallerybox.data.TextLayer
import com.gallerybox.engine.EditStateManager
import com.gallerybox.engine.EditingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Container holding metadata and optional preview thumbnail for a `.cube` LUT filter.
 */
data class LutItem(
    val name: String,
    val path: String,
    val category: String,
    val thumbnail: Bitmap? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val editingEngine: EditingEngine,
    private val galleryDao: GalleryDao,
    private val editStateManager: EditStateManager
) : AndroidViewModel(application) {

    private val TAG = "EditorViewModel"

    // --- CHANNELS AND FLOWS (COMMUNICATION SYSTEM) ---
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState = _fileOperationState.asStateFlow()

    private var fileOperationJob: Job? = null

    // --- CURRENT LOADED MEDIA ITEM (LINKED WITH GALLERY VIEW MODEL) ---
    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem: StateFlow<MediaItem?> = _currentMediaItem.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    // --- HISTORY MANAGEMENT (UNDO / REDO) ---
    private val editMutex = Mutex()
    private val editHistory = mutableListOf<EditState>()
    private var currentEditIndex = -1

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var isGestureActive = false
    private var gestureInitialState: EditState? = null

    private val _currentEditState = MutableStateFlow(EditState())
    val currentEditState = _currentEditState.asStateFlow()

    val aspectRatio: StateFlow<Float?> = _currentEditState
        .map { it.aspectRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _currentEditState.value.aspectRatio)

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap = _previewBitmap.asStateFlow()

    private var originalBitmap: Bitmap? = null
    private var currentMediaUri: Uri? = null

    private val _isPreviewUpdating = MutableStateFlow(false)
    val isPreviewUpdating = _isPreviewUpdating.asStateFlow()

    private val _isComparing = MutableStateFlow(false)
    val isComparing = _isComparing.asStateFlow()

    private val lutCache = LruCache<String, CubeLut>(20)

    private val _lutItems = MutableStateFlow<List<LutItem>>(emptyList())
    val lutItems = _lutItems.asStateFlow()

    private val _stickerItems = MutableStateFlow<List<StickerUiItem>>(emptyList())
    val stickerItems = _stickerItems.asStateFlow()

    private val _thermalLevel = MutableStateFlow(0)
    val thermalLevel = _thermalLevel.asStateFlow()

    object MathUtils {
        /**
         * Calculates how much to shrink the preview bitmap based on device thermal status (0..7).
         * Clamped to [0.3f, 1.0f] so Bitmap.createScaledBitmap never receives 0 width/height.
         */
        fun calculateThermalScaleFactor(level: Int): Float {
            val clamped = level.coerceIn(0, 7)
            return (1f - (clamped / 10f)).coerceIn(0.3f, 1.0f)
        }
    }

    init {
        loadLuts()
        loadStickers()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = application.getSystemService(Application.POWER_SERVICE) as? PowerManager
            pm?.addThermalStatusListener { status ->
                updateThermalLevel(status)
            }
        }

        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(
                currentEditState,
                isComparing
            ) { state, comparing ->
                Pair(state, comparing)
            }
                .debounce { _ ->
                    80L + (thermalLevel.value * 20L)
                }
                .collectLatest { (state, comparing) ->
                    if (comparing) {
                        _previewBitmap.value = originalBitmap
                    } else {
                        generatePreview(state, thermalLevel.value)
                    }
                }
        }
    }

    fun updateThermalLevel(level: Int) {
        _thermalLevel.value = level.coerceIn(0, 7)
    }

    private fun syncUndoRedoFlags() {
        _canUndo.value = currentEditIndex > 0
        _canRedo.value = currentEditIndex >= 0 && currentEditIndex < editHistory.size - 1
    }

    /**
     * Hit-test helper delegating to [EditStateManager.isPointInLayer].
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
        return editStateManager.isPointInLayer(x, y, layerX, layerY, width, height, rotation, scale)
    }

    /**
     * Links directly with [GalleryViewModel] using `mediaId` or `fallbackUri` to load any photo or video.
     */
    fun initializeEditorFromGallery(
        mediaId: Long,
        galleryViewModel: GalleryViewModel,
        fallbackUri: Uri? = null,
        isVideoHint: Boolean = false
    ) = viewModelScope.launch {
        val itemFromGallery = galleryViewModel.getMediaItemById(mediaId)
            ?: galleryViewModel.rawMedia.value.firstOrNull { it.id == mediaId }
            ?: fallbackUri?.let { uri ->
                galleryViewModel.rawMedia.value.firstOrNull { it.uri == uri }
                    ?: resolveMediaItemFromUri(uri, mediaId, isVideoHint)
            }

        if (itemFromGallery != null) {
            initializeEditor(itemFromGallery)
        } else {
            _events.send(GalleryEvent.ShowToast("Unable to load media for editing"))
        }
    }

    private suspend fun resolveMediaItemFromUri(
        uri: Uri,
        fallbackId: Long = -1L,
        isVideoHint: Boolean = false
    ): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val mimeType = resolver.getType(uri) ?: if (isVideoHint) "video/mp4" else "image/jpeg"
            val isVideo = isVideoHint || mimeType.startsWith("video/", ignoreCase = true)

            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATA
            )

            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getColumnIndex(MediaStore.MediaColumns._ID).let { if (it >= 0) c.getLong(it) else fallbackId }
                    val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).let { if (it >= 0) c.getString(it) ?: "Media" else "Media" }
                    val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE).let { if (it >= 0) c.getLong(it) else 0L }
                    val dateAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED).let { if (it >= 0) c.getLong(it) else System.currentTimeMillis() / 1000L }
                    val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH).let { if (it >= 0) c.getInt(it) else 0 }
                    val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT).let { if (it >= 0) c.getInt(it) else 0 }
                    val path = c.getColumnIndex(MediaStore.MediaColumns.DATA).let { if (it >= 0) c.getString(it) ?: "" else "" }

                    return@withContext MediaItem(
                        id = id,
                        uri = uri,
                        path = path,
                        relativePath = path.substringBeforeLast('/', ""),
                        name = name,
                        dateAdded = dateAdded,
                        size = size,
                        isVideo = isVideo,
                        duration = 0L,
                        width = width,
                        height = height,
                        mimeType = mimeType,
                        bucketId = "",
                        bucketName = "",
                        volumeName = "",
                        isFavorite = false,
                        isHidden = false
                    )
                }
            }

            MediaItem(
                id = if (fallbackId != -1L) fallbackId else runCatching { ContentUris.parseId(uri) }.getOrDefault(System.currentTimeMillis()),
                uri = uri,
                path = uri.path ?: "",
                relativePath = "",
                name = uri.lastPathSegment ?: "Media",
                dateAdded = System.currentTimeMillis() / 1000L,
                size = 0L,
                isVideo = isVideo,
                duration = 0L,
                width = 0,
                height = 0,
                mimeType = mimeType,
                bucketId = "",
                bucketName = "",
                volumeName = "",
                isFavorite = false,
                isHidden = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve MediaItem from uri: $uri", e)
            null
        }
    }

    /**
     * Prepares the editor when a photo or video [MediaItem] is opened.
     */
    fun initializeEditor(mediaItem: MediaItem) = viewModelScope.launch {
        _currentMediaItem.value = mediaItem

        val durationMs = if (mediaItem.isVideo) {
            if (mediaItem.duration > 0L) {
                mediaItem.duration
            } else {
                withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(getApplication(), mediaItem.uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (_: Exception) {
                        0L
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }
        } else {
            0L
        }
        _videoDurationMs.value = durationMs

        editMutex.withLock {
            editHistory.clear()
            currentEditIndex = -1

            val initialState = EditState(
                contrast = 1f,
                saturation = 1f,
                brightness = 0f,
                exposure = 0f,
                highlights = 0f,
                shadows = 0f,
                temperature = 0f,
                tint = 0f,
                cropRect = RectF(0f, 0f, 1f, 1f),
                rotationDegrees = 0f,
                straightenDegrees = 0f,
                flipHorizontal = false,
                flipVertical = false,
                lutData = null,
                lutIntensity = 1f,
                filterId = null,
                textLayers = emptyList(),
                stickers = emptyList(),
                frames = emptyList(),
                trimStartMs = 0L,
                trimEndMs = durationMs
            )
            editStateManager.reset(initialState)
            editHistory.add(initialState)
            currentEditIndex = 0
            _currentEditState.value = initialState
            syncUndoRedoFlags()
        }

        if (!mediaItem.isVideo) {
            if (currentMediaUri == mediaItem.uri && originalBitmap != null) {
                generatePreview(_currentEditState.value, _thermalLevel.value)
                return@launch
            }

            currentMediaUri = mediaItem.uri

            withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val maxDimen = 1080

                    val sourceBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, mediaItem.uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true

                            if (info.size.width > maxDimen || info.size.height > maxDimen) {
                                val scale = minOf(
                                    maxDimen.toFloat() / info.size.width.coerceAtLeast(1),
                                    maxDimen.toFloat() / info.size.height.coerceAtLeast(1)
                                )
                                decoder.setTargetSize(
                                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                                )
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val fullBitmap = MediaStore.Images.Media.getBitmap(resolver, mediaItem.uri)

                        val exifRot = runCatching {
                            resolver.openInputStream(mediaItem.uri)?.use { input ->
                                val exif = ExifInterface(input)
                                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                    else -> 0f
                                }
                            } ?: 0f
                        }.getOrDefault(0f)

                        val orientedBitmap = if (exifRot != 0f) {
                            val m = Matrix().apply { postRotate(exifRot) }
                            Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, m, true).also {
                                if (it !== fullBitmap) fullBitmap.recycle()
                            }
                        } else {
                            fullBitmap
                        }

                        if (orientedBitmap.width > maxDimen || orientedBitmap.height > maxDimen) {
                            val scale = minOf(
                                maxDimen.toFloat() / orientedBitmap.width.coerceAtLeast(1),
                                maxDimen.toFloat() / orientedBitmap.height.coerceAtLeast(1)
                            )
                            val scaled = Bitmap.createScaledBitmap(
                                orientedBitmap,
                                (orientedBitmap.width * scale).roundToInt().coerceAtLeast(1),
                                (orientedBitmap.height * scale).roundToInt().coerceAtLeast(1),
                                true
                            )
                            if (scaled !== orientedBitmap) {
                                orientedBitmap.recycle()
                            }
                            scaled
                        } else {
                            orientedBitmap
                        }
                    }

                    val finalBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
                        val converted = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        if (converted !== sourceBitmap) {
                            sourceBitmap.recycle()
                        }
                        converted
                    } else {
                        sourceBitmap
                    }

                    setOriginalBitmap(finalBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init editor bitmap", e)
                    _events.send(GalleryEvent.ShowToast("Failed to load image for editing"))
                }
            }
        } else {
            currentMediaUri = mediaItem.uri
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(getApplication(), mediaItem.uri)
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val maxDimen = 1080
                        val scaledFrame = if (frame.width > maxDimen || frame.height > maxDimen) {
                            val scale = minOf(
                                maxDimen.toFloat() / frame.width.coerceAtLeast(1),
                                maxDimen.toFloat() / frame.height.coerceAtLeast(1)
                            )
                            Bitmap.createScaledBitmap(
                                frame,
                                (frame.width * scale).roundToInt().coerceAtLeast(1),
                                (frame.height * scale).roundToInt().coerceAtLeast(1),
                                true
                            ).also {
                                if (it !== frame) frame.recycle()
                            }
                        } else {
                            frame
                        }
                        val argbFrame = if (scaledFrame.config != Bitmap.Config.ARGB_8888) {
                            scaledFrame.copy(Bitmap.Config.ARGB_8888, true).also {
                                if (it !== scaledFrame) scaledFrame.recycle()
                            }
                        } else {
                            scaledFrame
                        }
                        setOriginalBitmap(argbFrame)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract initial video frame for editor", e)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }

    fun beginGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = true
            gestureInitialState = _currentEditState.value
        }
    }

    fun endGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = false
            val newState = _currentEditState.value
            if (gestureInitialState != null && gestureInitialState != newState) {
                pushToHistory(newState)
            }
            gestureInitialState = null
        }
    }

    fun updateEditState(update: (EditState) -> EditState) = viewModelScope.launch {
        editMutex.withLock {
            val newState = update(_currentEditState.value)
            if (newState == _currentEditState.value) return@withLock

            _currentEditState.value = newState

            if (!isGestureActive) {
                if (editHistory.lastOrNull() != newState) {
                    pushToHistory(newState)
                }
            }
        }
    }

    private fun pushToHistory(state: EditState) {
        if (editHistory.isNotEmpty() && editHistory.last() == state) return

        if (currentEditIndex < editHistory.size - 1) {
            editHistory.subList(currentEditIndex + 1, editHistory.size).clear()
        }

        editHistory.add(state)

        while (editHistory.size > 50) {
            editHistory.removeAt(0)
        }

        currentEditIndex = editHistory.lastIndex
        editStateManager.pushState(state)
        syncUndoRedoFlags()
    }

    fun undo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex > 0) {
                currentEditIndex--
                val restored = editHistory[currentEditIndex]
                editStateManager.undo()
                _currentEditState.value = restored
                syncUndoRedoFlags()
            }
        }
    }

    fun redo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex < editHistory.size - 1) {
                currentEditIndex++
                val restored = editHistory[currentEditIndex]
                editStateManager.redo()
                _currentEditState.value = restored
                syncUndoRedoFlags()
            }
        }
    }

    fun setOriginalBitmap(bitmap: Bitmap) {
        originalBitmap = bitmap
        viewModelScope.launch {
            generatePreview(_currentEditState.value, _thermalLevel.value)
        }
    }

    private suspend fun generatePreview(state: EditState, currentThermalLevel: Int) {
        val src = originalBitmap ?: return
        if (src.isRecycled) return

        Log.d(TAG, "Preview Filter = ${state.filterId}")
        Log.d(TAG, "Preview LUT = ${state.lutData != null}")
        Log.d(TAG, "Intensity = ${state.lutIntensity}")

        _isPreviewUpdating.value = true

        try {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                val scale = MathUtils.calculateThermalScaleFactor(currentThermalLevel)
                val workingBitmap = if (scale < 1.0f) {
                    val targetW = (src.width * scale).roundToInt().coerceAtLeast(1)
                    val targetH = (src.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, targetW, targetH, true)
                } else {
                    src
                }

                val newPreview = editingEngine.createPreview(
                    bitmap = workingBitmap,
                    state = state,
                    renderOverlays = false
                )

                _previewBitmap.value = newPreview

                if (workingBitmap !== src && workingBitmap !== newPreview) {
                    workingBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Failed to generate preview", e)
            }
        } finally {
            _isPreviewUpdating.value = false
        }
    }

    fun setComparing(value: Boolean) {
        _isComparing.value = value
    }

    // --- COLOR ADJUSTMENT HELPERS ---
    fun setBrightness(value: Float) = updateEditState { it.copy(brightness = value) }
    fun setContrast(value: Float) = updateEditState { it.copy(contrast = value) }
    fun setSaturation(value: Float) = updateEditState { it.copy(saturation = value) }
    fun setExposure(value: Float) = updateEditState { it.copy(exposure = value) }
    fun setHighlights(value: Float) = updateEditState { it.copy(highlights = value) }
    fun setShadows(value: Float) = updateEditState { it.copy(shadows = value) }
    fun setTemperature(value: Float) = updateEditState { it.copy(temperature = value) }
    fun setTint(value: Float) = updateEditState { it.copy(tint = value) }
    fun setStraightenDegrees(value: Float) = updateEditState { it.copy(straightenDegrees = value) }

    fun resetEditor() = updateEditState {
        EditState(
            contrast = 1f,
            saturation = 1f,
            trimStartMs = 0L,
            trimEndMs = _videoDurationMs.value
        )
    }

    fun resetCrop() = updateEditState {
        it.copy(
            cropRect = RectF(0f, 0f, 1f, 1f),
            aspectRatio = null,
            straightenDegrees = 0f
        )
    }

    fun setAspectRatio(ratio: Float?) = updateEditState { it.copy(aspectRatio = ratio) }

    fun updateCropRect(rect: RectF) {
        beginGesture()
        updateEditState { it.copy(cropRect = RectF(rect)) }
        endGesture()
    }

    fun rotateLeft() = updateEditState {
        it.copy(rotationDegrees = ((it.rotationDegrees - 90f) % 360f + 360f) % 360f)
    }

    fun rotateRight() = updateEditState {
        it.copy(rotationDegrees = ((it.rotationDegrees + 90f) % 360f + 360f) % 360f)
    }

    fun toggleFlipHorizontal() = updateEditState { it.copy(flipHorizontal = !it.flipHorizontal) }

    fun toggleFlipVertical() = updateEditState { it.copy(flipVertical = !it.flipVertical) }

    // --- TEXT LAYERS ---
    fun addText(text: String, color: Int = Color.WHITE, size: Float = 40f) = updateEditState { state ->
        val offset = (state.textLayers.size % 5) * 0.04f
        val cx = (0.5f + offset).coerceAtMost(0.8f)
        val cy = (0.5f + offset).coerceAtMost(0.8f)

        state.copy(
            textLayers = state.textLayers + TextLayer(
                id = UUID.randomUUID().toString(),
                text = text,
                color = color,
                size = size,
                x = cx,
                y = cy,
                rotation = 0f,
                opacity = 1f,
                isVisible = true
            )
        )
    }

    fun updateText(id: String, update: (TextLayer) -> TextLayer) {
        beginGesture()
        updateEditState { state ->
            state.copy(textLayers = state.textLayers.map { if (it.id == id) update(it) else it })
        }
        endGesture()
    }

    fun removeText(id: String) = updateEditState { state ->
        state.copy(textLayers = state.textLayers.filterNot { it.id == id })
    }

    fun duplicateText(id: String) = updateEditState { state ->
        val layer = state.textLayers.find { it.id == id } ?: return@updateEditState state
        state.copy(
            textLayers = state.textLayers + layer.copy(
                id = UUID.randomUUID().toString(),
                x = (layer.x + 0.05f).coerceIn(0f, 1f),
                y = (layer.y + 0.05f).coerceIn(0f, 1f)
            )
        )
    }

    fun moveTextLayer(id: String, moveUp: Boolean) = updateEditState { state ->
        val list = state.textLayers.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@updateEditState state

        val targetIndex = if (moveUp) index + 1 else index - 1
        if (targetIndex in list.indices) {
            Collections.swap(list, index, targetIndex)
        }
        state.copy(textLayers = list)
    }

    fun toggleTextVisibility(id: String) = updateText(id) { it.copy(isVisible = !it.isVisible) }

    fun clearText() = updateEditState { it.copy(textLayers = emptyList()) }

    // --- STICKERS ---
    private fun loadStickers() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<StickerUiItem>()

            assets.list("stickers")?.forEach { category ->
                assets.list("stickers/$category")?.forEach { subFolder ->
                    assets.list("stickers/$category/$subFolder")
                        ?.filter { it.endsWith(".svg", true) }
                        ?.forEach { file ->
                            val cleanName = file.substringBeforeLast(".")
                                .replace("-", " ")
                                .replaceFirstChar { it.uppercase() }
                            val cleanCat = category
                                .replace("-", " ")
                                .replaceFirstChar { it.uppercase() }
                            allItems.add(
                                StickerUiItem(
                                    name = cleanName,
                                    category = cleanCat,
                                    assetPath = "stickers/$category/$subFolder/$file",
                                    emoji = ""
                                )
                            )
                        }
                }
            }

            StickerUnicode.allEmojis.forEachIndexed { i, e ->
                allItems.add(
                    StickerUiItem(
                        name = "Emoji $i",
                        category = "Emoji",
                        assetPath = "",
                        emoji = e
                    )
                )
            }
            _stickerItems.value = allItems
        }.onFailure { Log.e(TAG, "Failed to load stickers", it) }
    }

    fun addSticker(assetPath: String, emoji: String = "") = updateEditState { state ->
        val offset = (state.stickers.size % 5) * 0.04f
        val cx = (0.5f + offset).coerceAtMost(0.8f)
        val cy = (0.5f + offset).coerceAtMost(0.8f)

        state.copy(
            stickers = state.stickers + StickerLayer(
                id = UUID.randomUUID().toString(),
                assetPath = assetPath,
                emoji = emoji,
                x = cx,
                y = cy,
                scale = 1f,
                rotation = 0f,
                opacity = 1f,
                isVisible = true
            )
        )
    }

    fun updateSticker(id: String, update: (StickerLayer) -> StickerLayer) {
        beginGesture()
        updateEditState { state ->
            state.copy(stickers = state.stickers.map { if (it.id == id) update(it) else it })
        }
        endGesture()
    }

    fun removeSticker(id: String) = updateEditState { state ->
        state.copy(stickers = state.stickers.filterNot { it.id == id })
    }

    fun duplicateSticker(id: String) = updateEditState { state ->
        val layer = state.stickers.find { it.id == id } ?: return@updateEditState state
        state.copy(
            stickers = state.stickers + layer.copy(
                id = UUID.randomUUID().toString(),
                x = (layer.x + 0.05f).coerceIn(0f, 1f),
                y = (layer.y + 0.05f).coerceIn(0f, 1f)
            )
        )
    }

    fun moveStickerLayer(id: String, moveUp: Boolean) = updateEditState { state ->
        val list = state.stickers.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@updateEditState state
        val targetIndex = if (moveUp) index + 1 else index - 1
        if (targetIndex in list.indices) {
            Collections.swap(list, index, targetIndex)
        }
        state.copy(stickers = list)
    }

    fun toggleStickerVisibility(id: String) = updateSticker(id) { it.copy(isVisible = !it.isVisible) }

    fun clearStickers() = updateEditState { it.copy(stickers = emptyList()) }

    // --- FILTERS (LUTS) ---
    private fun loadLuts() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<LutItem>()

            assets.list("luts")?.forEach { category ->
                assets.list("luts/$category")
                    ?.filter { it.endsWith(".cube", true) }
                    ?.forEach { file ->
                        val name = file.substringBeforeLast(".")
                        val preview = sequenceOf(
                            "thumbnails/$category/$name.jpg",
                            "thumbnails/$category/$name.png",
                            "thumbnails/$name.jpg"
                        ).firstNotNullOfOrNull { path ->
                            runCatching {
                                assets.open(path).use { BitmapFactory.decodeStream(it) }
                            }.getOrNull()
                        }

                        allItems.add(
                            LutItem(
                                name = name.replaceFirstChar { it.uppercase() },
                                path = "luts/$category/$file",
                                category = category,
                                thumbnail = preview
                            )
                        )
                    }
            }
            _lutItems.value = allItems
        }.onFailure { Log.e(TAG, "Failed to load LUTs", it) }
    }

    fun applyLut(item: LutItem) = viewModelScope.launch(Dispatchers.IO) {
        try {
            Log.d(TAG, "Clicked = ${item.name}")
            Log.d(TAG, "Path = ${item.path}")

            val cachedLut = lutCache.get(item.path)
            if (cachedLut != null) {
                updateEditState { it.copy(lutData = cachedLut, lutIntensity = 1f, filterId = item.name) }
                Log.d(TAG, "LUT Applied (Cached) : ${item.name}")
                return@launch
            }

            val newLut = editingEngine.loadLut(item.path)
            if (newLut != null) {
                lutCache.put(item.path, newLut)
                updateEditState { it.copy(lutData = newLut, lutIntensity = 1f, filterId = item.name) }
                Log.d(TAG, "LUT Applied : ${item.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LUT ${item.name}", e)
        }
    }

    fun setLutIntensity(value: Float) = updateEditState { it.copy(lutIntensity = value.coerceIn(0f, 1f)) }

    fun clearLut() {
        updateEditState {
            it.copy(
                lutData = null,
                lutIntensity = 1f,
                filterId = null
            )
        }
    }

    // --- VIDEO EDITING SPECIFICS ---
    fun setTrimRange(startMs: Long, endMs: Long) = updateEditState {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = if (endMs > 0L) endMs.coerceAtLeast(safeStart) else _videoDurationMs.value
        it.copy(trimStartMs = safeStart, trimEndMs = safeEnd)
    }

    fun commitTrim(startMs: Long, endMs: Long) = setTrimRange(startMs, endMs)

    fun setVideoVolume(volume: Float) = updateEditState {
        val clamped = volume.coerceIn(0f, 2f)
        it.copy(videoVolume = clamped, isMuted = clamped == 0f)
    }

    fun toggleVideoMute() = updateEditState {
        it.copy(isMuted = !it.isMuted)
    }

    fun exportFrame(
        uri: Uri = _currentMediaItem.value?.uri ?: Uri.EMPTY,
        posMs: Long,
        galleryViewModel: GalleryViewModel? = null
    ) = viewModelScope.launch(Dispatchers.IO) {
        if (uri == Uri.EMPTY) return@launch
        try {
            val file = editingEngine.extractFrame(uri, posMs)
            if (file != null) {
                MediaScannerConnection.scanFile(getApplication(), arrayOf(file.absolutePath), null) { _, _ -> }
                galleryViewModel?.onMediaExported(file, null, "Video frame saved to Gallery!")
                _events.send(GalleryEvent.ShowToast("Video frame saved to Gallery!"))
            } else {
                _events.send(GalleryEvent.ShowToast("Failed to capture frame"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export frame", e)
            _events.send(GalleryEvent.ShowToast("Error capturing frame"))
        }
    }

    // --- SAVING AND EXPORTING ---
    fun cancelCurrentOperation() {
        fileOperationJob?.cancel()
        editingEngine.cancelExport()
        _fileOperationState.value = FileOperationState.Idle
    }

    fun saveMedia(
        mediaItem: MediaItem? = _currentMediaItem.value,
        saveMode: SaveMode = SaveMode.SAVE_AS_NEW,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30,
        videoBitrate: Int = 15000000,
        exportAsSticker: Boolean = false,
        useH265: Boolean = false,
        galleryViewModel: GalleryViewModel? = null,
        onSuccess: ((File) -> Unit)? = null
    ) {
        val targetItem = mediaItem ?: _currentMediaItem.value
        if (targetItem == null) {
            viewModelScope.launch {
                _events.send(GalleryEvent.ShowToast("No media loaded to save"))
            }
            return
        }

        fileOperationJob?.cancel()

        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _fileOperationState.value = FileOperationState.Editing(0f)
            try {
                val file = editingEngine.saveMedia(
                    uri = targetItem.uri,
                    state = _currentEditState.value,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetFps = targetFps,
                    videoBitrate = videoBitrate,
                    isVideo = targetItem.isVideo,
                    asSticker = exportAsSticker,
                    useH265 = useH265
                ) { progress ->
                    _fileOperationState.value = FileOperationState.Editing(progress.coerceIn(0f, 1f))
                }

                if (file != null) {
                    val replaceOriginalId = if (saveMode == SaveMode.REPLACE_ORIGINAL) targetItem.id else null

                    if (galleryViewModel != null) {
                        galleryViewModel.onMediaExported(
                            newFile = file,
                            originalMediaId = replaceOriginalId,
                            customMessage = "Media saved successfully!"
                        )
                    } else {
                        MediaScannerConnection.scanFile(
                            getApplication(),
                            arrayOf(file.absolutePath),
                            null
                        ) { _, _ -> }
                    }

                    _events.send(GalleryEvent.ShowToast("Media saved successfully"))
                    _events.send(GalleryEvent.OperationSuccess)
                    withContext(Dispatchers.Main) {
                        onSuccess?.invoke(file)
                    }
                } else {
                    _events.send(GalleryEvent.ShowToast("Export failed or was cancelled."))
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Export failed", e)
                    _events.send(GalleryEvent.ShowToast("An error occurred during export."))
                }
            } finally {
                _fileOperationState.value = FileOperationState.Idle
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        editingEngine.cancelExport()
        _previewBitmap.value = null
        originalBitmap = null
        lutCache.evictAll()
        editHistory.clear()
    }
}