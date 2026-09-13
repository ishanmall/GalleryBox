// These annotations at the very top tell the compiler to ignore certain warnings
// (like using experimental features or old versions of Android code).
@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gallerybox.viewmodel

// --- IMPORTS ---
// Imports are like fetching tools from a toolbox. We need these specific tools
// (like Bitmaps for images, Coroutines for background tasks, etc.) to make this file work.
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.*
import com.gallerybox.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/**
 * A data class is simply a container that holds information.
 * Think of `LutItem` as a label on a folder containing a specific photo filter (LUT).
 * It holds the filter's name, where the file is located (path), its category, and a tiny preview image (thumbnail).
 */
data class LutItem(val name: String, val path: String, val category: String, val thumbnail: Bitmap? = null)

/**
 * --- WHAT IS A VIEW MODEL? ---
 * A ViewModel acts as the "brain" for a specific screen in the app (in this case, the Editor screen).
 * If the screen rotates or the phone goes to sleep, the screen might be destroyed and recreated,
 * but the ViewModel survives. It holds all the data (like the image being edited) safe and sound.
 *
 * @HiltViewModel tells a tool called 'Hilt' to automatically build this brain and hand it
 * whatever tools it needs (like the 'Application' context, the 'EditingEngine', and 'GalleryDao').
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    application: Application,
    private val editingEngine: EditingEngine, // The tool that actually does the heavy lifting of editing images/videos
    private val galleryDao: GalleryDao      // The tool used to talk to the local database (saving/loading info)
) : AndroidViewModel(application) {

    // A tag used for logging. When we print messages to the developer console to find bugs,
    // it will have "EditorViewModel" next to it so we know where the message came from.
    private val TAG = "EditorViewModel"

    // --- CHANNELS AND FLOWS (THE COMMUNICATION SYSTEM) ---
    // A Channel is like a walkie-talkie. We send a message (like "Show a Toast/Popup"),
    // and the screen receives it once and handles it.
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // A StateFlow is like a sports scoreboard. It always shows the current score (state).
    // Anyone looking at the scoreboard instantly knows what's happening.
    // Here, we track if the app is currently saving a file (Idle vs Editing).
    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState = _fileOperationState.asStateFlow()

    // A 'Job' is a background task. We keep track of the saving job so we can cancel it if the user hits "Cancel".
    private var fileOperationJob: Job? = null

    // --- HISTORY MANAGEMENT (UNDO / REDO) ---
    // A Mutex is like a "Talking Stick" in a meeting. Only the person holding the stick can talk.
    // This prevents two background tasks from trying to change the edit history at the exact same millisecond and crashing.
    private val editMutex = Mutex()

    // A list containing every step of the edits (e.g., Step 1: brightness up, Step 2: crop).
    private val editHistory = mutableListOf<EditState>()
    private var currentEditIndex = -1 // Tracks where we currently are in the history list.

    // Tracks if the user's finger is currently dragging on the screen (e.g., dragging a sticker).
    private var isGestureActive = false
    private var gestureInitialState: EditState? = null

    // The main scoreboard that holds the *current* state of the photo (brightness, contrast, stickers, etc.)
    private val _currentEditState = MutableStateFlow(EditState())
    val currentEditState = _currentEditState.asStateFlow()

    // A specific scoreboard just for the image's aspect ratio (like 16:9 or 4:3).
    // It automatically updates whenever the main `_currentEditState` changes.
    val aspectRatio: StateFlow<Float?> = _currentEditState
        .map { it.aspectRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _currentEditState.value.aspectRatio)

    // Holds the actual digital canvas (Bitmap) that is shown on the screen right now.
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap = _previewBitmap.asStateFlow()

    // Holds the pure, untouched original image so we always have it to fall back on.
    private var originalBitmap: Bitmap? = null
    // The file path/location of the media currently being edited.
    private var currentMediaUri: Uri? = null

    // A simple true/false flag that tells the screen to show a loading spinner if we are busy updating the image.
    private val _isPreviewUpdating = MutableStateFlow(false)
    val isPreviewUpdating = _isPreviewUpdating.asStateFlow()

    // True if the user is pressing a "Compare" button to see the original image vs the edited one.
    private val _isComparing = MutableStateFlow(false)
    val isComparing = _isComparing.asStateFlow()

    // LruCache is like a memory box that holds a maximum of 20 filters (LUTs).
    // If we add a 21st, it throws away the oldest one to save phone memory.
    private val lutCache = LruCache<String, CubeLut>(20)

    // Lists holding all available filters and stickers for the user to pick from.
    private val _lutItems = MutableStateFlow<List<LutItem>>(emptyList())
    val lutItems = _lutItems.asStateFlow()
    private val _stickerItems = MutableStateFlow<List<StickerUiItem>>(emptyList())
    val stickerItems = _stickerItems.asStateFlow()

    // Tracks device temperature from 0 (cool) to 7 (burning hot).
    // If the phone gets hot, we lower the preview quality to stop it from overheating.
    private val _thermalLevel = MutableStateFlow(0)
    val thermalLevel = _thermalLevel.asStateFlow()

    // A helper box of math formulas.
    object MathUtils {
        // Calculates how much to shrink the image based on phone heat.
        // Heat level 0 = 1.0 (100% size). Heat level 7 = 0.0 (though we clamp it).
        fun calculateThermalScaleFactor(level: Int): Float = 1f - (level.coerceIn(0, 7) / 7f)
    }

    /**
     * The `init` block is the very first thing that runs when this ViewModel brain is created.
     * It's the "setup" phase.
     */
    init {
        loadLuts()     // Load all filters from the app's files
        loadStickers() // Load all stickers from the app's files

        // 'viewModelScope.launch' means "Start a new worker in the background so the app doesn't freeze".
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            // We constantly watch TWO things: The current edits, and whether the user is holding the "Compare" button.
            combine(
                currentEditState,
                isComparing
            ) { state, comparing ->
                Pair(state, comparing)
            }
                // 'debounce' is a delay. If the user slides a brightness slider really fast,
                // we don't want to calculate the image 100 times a second. We wait until they pause for a tiny moment.
                // If the phone is hot (thermalLevel), we force them to wait even longer to cool the phone down.
                .debounce { _ ->
                    80L + (thermalLevel.value * 20L)
                }
                // When we finally have the updated info, we act on it.
                .collectLatest { (state, comparing) ->
                    if (comparing) {
                        // If holding compare, show the untouched original image
                        _previewBitmap.value = originalBitmap
                    } else {
                        // Otherwise, apply the edits and generate a new preview image
                        generatePreview(state, thermalLevel.value)
                    }
                }
        }
    }

    // Updates the phone's heat level (called by the Android system outside of this file)
    fun updateThermalLevel(level: Int) {
        _thermalLevel.value = level.coerceIn(0, 7)
    }

    /**
     * Prepares the editor when a new photo or video is opened.
     */
    fun initializeEditor(mediaItem: MediaItem) = viewModelScope.launch {
        // Grab the talking stick (mutex) so nothing else interferes while we set up.
        editMutex.withLock {
            editHistory.clear() // Wipe the undo history
            currentEditIndex = -1

            // Create a brand new, completely blank slate of edits (zero brightness, no rotation, etc.)
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
                frames = emptyList()
            )
            // Save this blank slate as "Step 0" in our history
            editHistory.add(initialState)
            currentEditIndex = 0
            _currentEditState.value = initialState
        }

        // If it's a photo (not a video), we need to load it into memory.
        if (!mediaItem.isVideo) {
            // If the user opened the exact same photo we already have loaded, just refresh the preview and stop here.
            if (currentMediaUri == mediaItem.uri && originalBitmap != null) {
                generatePreview(_currentEditState.value, _thermalLevel.value)
                return@launch
            }

            currentMediaUri = mediaItem.uri

            // Switching to 'Dispatchers.IO' is like sending this task to the warehouse workers.
            // Reading files from the phone's hard drive takes time, and we can't do it on the main display thread.
            withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val maxDimen = 1080 // We won't load images larger than 1080p to save RAM memory so the app doesn't crash.

                    // Loading the image is different depending on how old the Android phone is.
                    val sourceBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // For modern Android phones (Android 9 Pie and above)
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, mediaItem.uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                            decoder.isMutableRequired = true // We need permission to change the pixels

                            // If the image is huge (like 4K), shrink it down to 1080p while it's loading.
                            if (info.size.width > maxDimen || info.size.height > maxDimen) {
                                val scale = minOf(
                                    maxDimen.toFloat() / info.size.width,
                                    maxDimen.toFloat() / info.size.height
                                )
                                decoder.setTargetSize(
                                    (info.size.width * scale).toInt(),
                                    (info.size.height * scale).toInt()
                                )
                            }
                        }
                    } else {
                        // For older Android phones
                        @Suppress("DEPRECATION")
                        val fullBitmap = MediaStore.Images.Media.getBitmap(resolver, mediaItem.uri)

                        // Manually shrink it if it's too big
                        if (fullBitmap.width > maxDimen || fullBitmap.height > maxDimen) {
                            val scale = minOf(
                                maxDimen.toFloat() / fullBitmap.width,
                                maxDimen.toFloat() / fullBitmap.height
                            )
                            val scaled = Bitmap.createScaledBitmap(
                                fullBitmap,
                                (fullBitmap.width * scale).toInt(),
                                (fullBitmap.height * scale).toInt(),
                                true
                            )
                            fullBitmap.recycle() // Throw away the huge version to free up memory immediately
                            scaled
                        } else {
                            fullBitmap
                        }
                    }

                    // Android has different color formats. ARGB_8888 is the standard high-quality format we need.
                    // If the image loaded as something else, we convert it here.
                    val finalBitmap = if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
                        val converted = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        sourceBitmap.recycle()
                        converted
                    } else {
                        sourceBitmap
                    }

                    // Finally, save this prepared image as our base original.
                    setOriginalBitmap(finalBitmap)
                } catch (e: Exception) {
                    // If anything goes wrong (e.g., file corrupted), log the error.
                    Log.e(TAG, "Failed to init editor bitmap", e)
                }
            }
        }
    }

    /**
     * Called when the user presses their finger down on the screen to start dragging/editing.
     */
    fun beginGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = true
            gestureInitialState = _currentEditState.value // Remember what it looked like before they dragged
        }
    }

    /**
     * Called when the user lifts their finger off the screen.
     */
    fun endGesture() = viewModelScope.launch {
        editMutex.withLock {
            isGestureActive = false
            val newState = _currentEditState.value
            // If they actually changed something while dragging, save that change to the Undo history.
            if (gestureInitialState != null && gestureInitialState != newState) {
                pushToHistory(newState)
            }
            gestureInitialState = null
        }
    }

    /**
     * This is a master function for making ANY change to the image state.
     * It takes an 'update' function as a parameter (like passing a specific instruction on what to change).
     */
    fun updateEditState(update: (EditState) -> EditState) = viewModelScope.launch {
        editMutex.withLock {
            val newState = update(_currentEditState.value) // Apply the change
            if (newState == _currentEditState.value) return@withLock // If nothing actually changed, do nothing.

            _currentEditState.value = newState // Update the main scoreboard

            // If the user isn't currently dragging their finger, save this change to history right away.
            // (If they are dragging, we wait for 'endGesture' so we don't save 100 history steps for one drag).
            if (!isGestureActive) {
                if (editHistory.lastOrNull() != newState) {
                    pushToHistory(newState)
                }
            }
        }
    }

    /**
     * Adds a new step to the history list.
     */
    private fun pushToHistory(state: EditState) {
        if (editHistory.isNotEmpty() && editHistory.last() == state) return

        // If the user pressed "Undo" three times, and then makes a NEW edit,
        // we have to delete the "alternate future" ahead of them.
        if (currentEditIndex < editHistory.size - 1) {
            editHistory.subList(currentEditIndex + 1, editHistory.size).clear()
        }

        editHistory.add(state)

        // We only remember the last 50 edits. If we go over, delete the oldest one so the phone doesn't run out of memory.
        while (editHistory.size > 50) editHistory.removeAt(0)

        currentEditIndex = editHistory.lastIndex // Point our current tracker to the very end of the list.
    }

    /**
     * Steps backwards in time by 1 edit.
     */
    fun undo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex > 0) {
                currentEditIndex--
                _currentEditState.value = editHistory[currentEditIndex]
            }
        }
    }

    /**
     * Steps forwards in time by 1 edit (if you previously hit undo).
     */
    fun redo() = viewModelScope.launch {
        editMutex.withLock {
            if (currentEditIndex < editHistory.size - 1) {
                currentEditIndex++
                _currentEditState.value = editHistory[currentEditIndex]
            }
        }
    }

    /**
     * Updates the base image we are working on.
     */
    fun setOriginalBitmap(bitmap: Bitmap) {
        val previous = originalBitmap
        originalBitmap = bitmap
        viewModelScope.launch {
            generatePreview(_currentEditState.value, _thermalLevel.value)

            // "Recycling" a bitmap is like throwing a digital canvas in the trash immediately.
            // Images take up massive amounts of memory, so we must clean up old ones immediately.
            if (previous !== bitmap && previous !== _previewBitmap.value) {
                previous?.recycle()
            }
        }
    }

    /**
     * Takes the original image, applies all the edits (brightness, crop, etc.),
     * and produces a temporary image to show on the screen.
     */
    private suspend fun generatePreview(state: EditState, currentThermalLevel: Int) {
        val src = originalBitmap ?: return // If there's no image loaded, do nothing.

        // Print debug info for the developer
        Log.d(TAG, "Preview Filter = ${state.filterId}")
        Log.d(TAG, "Preview LUT = ${state.lutData != null}")
        Log.d(TAG, "Intensity = ${state.lutIntensity}")

        _isPreviewUpdating.value = true // Turn on the loading spinner

        try {
            // Do this on the "Default" worker thread (good for heavy math and image processing)
            // 'limitedParallelism(1)' means only do one image generation at a time, creating a neat queue.
            withContext(Dispatchers.Default.limitedParallelism(1)) {

                // If the phone is hot, shrink the image before we process it so the math is faster.
                val scale = MathUtils.calculateThermalScaleFactor(currentThermalLevel)
                val workingBitmap = if (scale < 1.0f) {
                    Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                } else {
                    src // If cool, use full size
                }

                // Send the image and edits to the EditingEngine (the core math engine) to process.
                val newPreview = editingEngine.createPreview(
                    bitmap = workingBitmap,
                    state = state,
                    renderOverlays = false // Don't burn text/stickers into the image yet, the UI draws those on top.
                )

                _previewBitmap.value = newPreview // Update the screen with the new edited image

                // Clean up the temporary shrunken image if we made one.
                if (workingBitmap !== src && workingBitmap !== newPreview) {
                    workingBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            // A CancellationException just means the user changed an edit slider super fast and we cancelled
            // the old calculation to start a new one. That's normal. Anything else is a real error.
            if (e !is CancellationException) {
                Log.e(TAG, "Failed to generate preview", e)
            }
        } finally {
            _isPreviewUpdating.value = false // Turn off the loading spinner
        }
    }

    // Turns the "Compare" mode on or off
    fun setComparing(value: Boolean) {
        _isComparing.value = value
    }

    // Wipes all color edits but keeps cropping/stickers.
    fun resetEditor() = updateEditState { EditState(contrast = 1f, saturation = 1f) }

    // Wipes all cropping and rotating.
    fun resetCrop() = updateEditState { it.copy(cropRect = RectF(0f, 0f, 1f, 1f), aspectRatio = null, straightenDegrees = 0f) }

    // Locks the crop to a specific shape (like a square for Instagram)
    fun setAspectRatio(ratio: Float?) = updateEditState { it.copy(aspectRatio = ratio) }

    // Updates the boundaries of the crop box
    fun updateCropRect(rect: RectF) {
        beginGesture()
        updateEditState { it.copy(cropRect = RectF(rect)) }
        endGesture()
    }

    // Rotates the image 90 degrees counter-clockwise
    fun rotateLeft() = updateEditState {
        // The math here ensures the angle always stays between 0 and 360 smoothly
        it.copy(rotationDegrees = ((it.rotationDegrees - 90f) % 360f + 360f) % 360f)
    }

    // Rotates the image 90 degrees clockwise
    fun rotateRight() = updateEditState {
        it.copy(rotationDegrees = ((it.rotationDegrees + 90f) % 360f + 360f) % 360f)
    }

    // Flips the image left/right like a mirror
    fun toggleFlipHorizontal() = updateEditState { it.copy(flipHorizontal = !it.flipHorizontal) }

    // Flips the image upside down
    fun toggleFlipVertical() = updateEditState { it.copy(flipVertical = !it.flipVertical) }

    // --- TEXT LAYERS ---

    // Adds a piece of text to the screen
    fun addText(text: String, color: Int = Color.WHITE, size: Float = 40f) = updateEditState { state ->
        // This math cascades the new text slightly down and to the right so they don't stack directly on top of each other.
        val offset = (state.textLayers.size % 5) * 0.04f
        val cx = (0.5f + offset).coerceAtMost(0.8f)
        val cy = (0.5f + offset).coerceAtMost(0.8f)

        // Copy the current state, but add this new text layer to the list of texts.
        state.copy(
            textLayers = state.textLayers + TextLayer(
                id = UUID.randomUUID().toString(), // Give it a completely unique random ID
                text = text,
                color = color,
                size = size,
                x = cx, // X is horizontal position
                y = cy, // Y is vertical position
                rotation = 0f,
                opacity = 1f,
                isVisible = true
            )
        )
    }

    // Finds a specific text by its ID and updates it (like moving it or changing its color)
    fun updateText(id: String, update: (TextLayer) -> TextLayer) {
        beginGesture()
        // We look through all text layers. If the ID matches, apply the update. If not, leave it alone.
        updateEditState { state -> state.copy(textLayers = state.textLayers.map { if (it.id == id) update(it) else it }) }
        endGesture()
    }

    // Deletes a text layer
    fun removeText(id: String) = updateEditState { state -> state.copy(textLayers = state.textLayers.filterNot { it.id == id }) }

    // Makes a copy of an existing text layer
    fun duplicateText(id: String) = updateEditState { state ->
        val layer = state.textLayers.find { it.id == id } ?: return@updateEditState state
        state.copy(
            textLayers = state.textLayers + layer.copy(
                id = UUID.randomUUID().toString(), // Give the clone a new unique ID
                x = (layer.x + 0.05f).coerceIn(0f, 1f), // Shift it slightly so it's not hiding exactly behind the original
                y = (layer.y + 0.05f).coerceIn(0f, 1f)
            )
        )
    }

    // Moves a text layer forward or backwards (like bringing a sticker in front of another sticker)
    fun moveTextLayer(id: String, moveUp: Boolean) = updateEditState { state ->
        val list = state.textLayers.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@updateEditState state

        val targetIndex = if (moveUp) index + 1 else index - 1
        // If the swap target is within bounds, swap them in the list.
        if (targetIndex in list.indices) Collections.swap(list, index, targetIndex)
        state.copy(textLayers = list)
    }

    // Hides or shows a text layer without deleting it
    fun toggleTextVisibility(id: String) = updateText(id) { it.copy(isVisible = !it.isVisible) }

    // Wipes all text
    fun clearText() = updateEditState { it.copy(textLayers = emptyList()) }

    // --- STICKERS ---

    // Reads all sticker files saved inside the app's internal folders
    private fun loadStickers() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<StickerUiItem>()

            // It looks inside the "stickers" folder, finds categories, then finds subfolders, then grabs the ".svg" image files.
            assets.list("stickers")?.forEach { category ->
                assets.list("stickers/$category")?.forEach { subFolder ->
                    assets.list("stickers/$category/$subFolder")?.filter { it.endsWith(".svg", true) }?.forEach { file ->
                        // Cleans up the file name so it looks nice in the UI (e.g., "cool-dog.svg" becomes "Cool dog")
                        val cleanName = file.substringBeforeLast(".").replace("-", " ").replaceFirstChar { it.uppercase() }
                        val cleanCat = category.replace("-", " ").replaceFirstChar { it.uppercase() }
                        allItems.add(StickerUiItem(name = cleanName, category = cleanCat, assetPath = "stickers/$category/$subFolder/$file", emoji = ""))
                    }
                }
            }
            // Add standard text emojis as stickers too
            StickerUnicode.allEmojis.forEachIndexed { i, e ->
                allItems.add(StickerUiItem(name = "Emoji $i", category = "Emoji", assetPath = "", emoji = e))
            }
            _stickerItems.value = allItems // Update the UI scoreboard so the screen shows the stickers
        }.onFailure { Log.e(TAG, "Failed to load stickers", it) }
    }

    // Logic for adding, updating, removing, and duplicating stickers (exact same concept as Text Layers above)
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
        updateEditState { state -> state.copy(stickers = state.stickers.map { if (it.id == id) update(it) else it }) }
        endGesture()
    }

    fun removeSticker(id: String) = updateEditState { state -> state.copy(stickers = state.stickers.filterNot { it.id == id }) }
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
        if (targetIndex in list.indices) Collections.swap(list, index, targetIndex)
        state.copy(stickers = list)
    }
    fun toggleStickerVisibility(id: String) = updateSticker(id) { it.copy(isVisible = !it.isVisible) }
    fun clearStickers() = updateEditState { it.copy(stickers = emptyList()) }

    // --- FILTERS (LUTS) ---
    // A LUT (Look Up Table) is a complex color filter. It maps existing colors to new colors to create cinematic looks.

    // Reads filter files from the app's internal folders
    private fun loadLuts() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val assets = getApplication<Application>().assets
            val allItems = mutableListOf<LutItem>()

            assets.list("luts")?.forEach { category ->
                assets.list("luts/$category")?.filter { it.endsWith(".cube", true) }?.forEach { file ->
                    val name = file.substringBeforeLast(".")

                    // Tries to find a tiny thumbnail image for the filter so the user can see a preview in the list.
                    val preview = sequenceOf("thumbnails/$category/$name.jpg", "thumbnails/$category/$name.png", "thumbnails/$name.jpg")
                        .firstNotNullOfOrNull { path -> runCatching { assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull() }

                    allItems.add(LutItem(name.replaceFirstChar { it.uppercase() }, "luts/$category/$file", category, preview))
                }
            }
            _lutItems.value = allItems
        }.onFailure { Log.e(TAG, "Failed to load LUTs", it) }
    }

    // When the user taps a filter in the list, apply it.
    fun applyLut(item: LutItem) = viewModelScope.launch(Dispatchers.IO) {
        try {
            Log.d(TAG, "Clicked = ${item.name}")
            Log.d(TAG, "Path = ${item.path}")

            // First, check the LruCache (our memory box). If we've used this filter recently, load it instantly.
            val cachedLut = lutCache.get(item.path)
            if (cachedLut != null) {
                updateEditState { it.copy(lutData = cachedLut, lutIntensity = 1f, filterId = item.name) }
                Log.d(TAG, "LUT Applied (Cached) : ${item.name}")
                Log.d(TAG, "State LUT = ${_currentEditState.value.lutData != null}")
                return@launch
            }

            // If it's not in the memory box, use the EditingEngine to read the raw .cube file and parse the math.
            val newLut = editingEngine.loadLut(item.path)
            Log.d(TAG, "Loaded = ${newLut != null}")
            Log.d(TAG, "Size = ${newLut?.size}")
            Log.d(TAG, "Data = ${newLut?.data?.size}")

            if (newLut != null) {
                lutCache.put(item.path, newLut) // Put it in the memory box for next time
                updateEditState { it.copy(lutData = newLut, lutIntensity = 1f, filterId = item.name) }
                Log.d(TAG, "LUT Applied : ${item.name}")
                Log.d(TAG, "State LUT = ${_currentEditState.value.lutData != null}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LUT ${item.name}", e)
        }
    }

    // Changes how strong the filter is (0.0 to 1.0)
    fun setLutIntensity(value: Float) = updateEditState { it.copy(lutIntensity = value) }

    // Removes the filter entirely
    fun clearLut() {
        lutCache.evictAll() // Dump the memory box to free up space
        updateEditState {
            it.copy(
                lutData = null,
                lutIntensity = 1f,
                filterId = null
            )
        }
    }

    // --- VIDEO EDITING SPECIFICS ---

    // Sets the start and end points for trimming a video (in milliseconds)
    fun setTrimRange(startMs: Long, endMs: Long) = updateEditState { it.copy(trimStartMs = startMs, trimEndMs = endMs) }

    fun commitTrim(startMs: Long, endMs: Long) = updateEditState {
        it.copy(trimStartMs = startMs, trimEndMs = endMs)
    }

    // Extracts a single picture frame from a video at a specific time stamp
    fun exportFrame(uri: Uri, posMs: Long) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val file = editingEngine.extractFrame(uri, posMs)
            if (file != null) {
                // MediaScannerConnection tells the Android phone's Gallery app: "Hey, I just saved a new picture, please show it!"
                MediaScannerConnection.scanFile(getApplication(), arrayOf(file.absolutePath), null) { _, _ -> }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export frame", e)
        }
    }

    // --- SAVING AND EXPORTING ---

    // Stops any current saving process if the user hits the cancel button
    fun cancelCurrentOperation() {
        fileOperationJob?.cancel() // Stop the background worker
        editingEngine.cancelExport() // Tell the math engine to stop
        _fileOperationState.value = FileOperationState.Idle // Reset the scoreboard
    }

    // The big function that permanently saves the final edited photo or video to the phone
    fun saveMedia(mediaItem: MediaItem, targetWidth: Int = 1920, targetHeight: Int = 1080, targetFps: Int = 30, videoBitrate: Int = 15000000, exportAsSticker: Boolean = false) {
        fileOperationJob?.cancel() // Stop any previous saves just in case

        // Start a new background worker in the 'Warehouse' (Dispatchers.IO)
        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _fileOperationState.value = FileOperationState.Editing(0f) // Tell the UI to show a "Saving: 0%" loading bar
            try {
                // Hand all the instructions (state, width, height, etc.) to the Engine to do the actual saving.
                val file = editingEngine.saveMedia(
                    uri = mediaItem.uri,
                    state = _currentEditState.value,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetFps = targetFps,
                    videoBitrate = videoBitrate,
                    isVideo = mediaItem.isVideo,
                    asSticker = exportAsSticker
                ) { progress ->
                    // This block runs repeatedly while saving, sending the progress (e.g., 50%, 60%) to the UI scoreboard.
                    _fileOperationState.value = FileOperationState.Editing(progress)
                }

                if (file != null) {
                    // Tell the phone's gallery a new file exists
                    MediaScannerConnection.scanFile(getApplication(), arrayOf(file.absolutePath), null) { _, _ -> }
                    // Send a message over our walkie-talkie (Channel) to show a little popup on screen
                    _events.send(GalleryEvent.ShowToast("Media saved successfully"))
                } else {
                    _events.send(GalleryEvent.ShowToast("Export failed or was cancelled."))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _events.send(GalleryEvent.ShowToast("An error occurred during export."))
            } finally {
                // Whether it succeeded, failed, or was cancelled, reset the state back to Idle.
                _fileOperationState.value = FileOperationState.Idle
            }
        }
    }

    /**
     * `onCleared` is a special function called by Android right before this ViewModel is destroyed
     * (for example, when the user closes the app or navigates completely away from the editor screen).
     * This is where we take out the trash so the app doesn't leak memory and crash the phone.
     */
    override fun onCleared() {
        super.onCleared()
        editingEngine.cancelExport() // Stop any ongoing saves

        // Throw away the digital canvases (Bitmaps)
        _previewBitmap.value?.let {
            if (it !== originalBitmap) {
                it.recycle()
            }
        }
        originalBitmap?.recycle()

        // Empty out our memory boxes
        lutCache.evictAll()
        editHistory.clear()
    }
}