// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling a strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gallerybox.viewmodel

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for databases, background tasks (Coroutines), file reading, and time math.
import android.app.Application
import android.content.Context
import android.content.ContentUris
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.MediaItem
import com.gallerybox.data.StoryEntity
import com.gallerybox.data.UiStory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import kotlin.math.*

/**
 * --- THE SCRAPBOOK CREATOR (StoryViewModel) ---
 * The ViewModel is the "Brain" or "Manager" of the Memories/Stories screen.
 * Its job is to look at thousands of loose photos, group them by time and date,
 * and stitch them together into neat little "Scrapbooks" (Stories) that the user can watch.
 *
 * `@HiltViewModel` tells a factory tool called 'Hilt' to automatically build this manager
 * and hand it the tools it needs (like the Application context and the `GalleryDao` database tool).
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    application: Application,
    private val dao: GalleryDao // Our local database to save the stories we build
) : AndroidViewModel(application) {

    // A "Channel" is like a Walkie-Talkie. The Manager can use it to send a quick, one-time message
    // (like "Show a Toast saying 'Memory Created!'") to the screen.
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // A digital notebook (SharedPreferences) to remember small settings, like which albums the user wants to hide.
    private val enginePrefs = application.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

    // A "Scoreboard" that constantly shows a list of the folder IDs the user has hidden (like the "Screenshots" folder).
    private val _hiddenAlbums = MutableStateFlow(loadHiddenAlbumsFromPrefs())
    val hiddenAlbums = _hiddenAlbums.asStateFlow()

    // A sensor that listens to the digital notebook. If the user hides a new album, it updates the scoreboard instantly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hidden_albums") {
            _hiddenAlbums.value = loadHiddenAlbumsFromPrefs()
        }
    }

    // Helper function to read the notebook.
    private fun loadHiddenAlbumsFromPrefs(): Set<String> =
        enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()

    // A massive internal dictionary holding every single photo/video on the phone so we can quickly look them up by their ID number.
    private val _mediaMap = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())

    // Scoreboards to track the progress when the app is actively building scrapbooks.
    private val _isGenerating = MutableStateFlow(false) // "Is the factory running?"
    val isGenerating = _isGenerating.asStateFlow()

    private val _generationProgress = MutableStateFlow(0) // "How many photos have we sorted?"
    val generationProgress = _generationProgress.asStateFlow()

    private val _generationTotal = MutableStateFlow(0) // "How many total photos do we have to sort?"
    val generationTotal = _generationTotal.asStateFlow()

    /**
     * THE FINAL PRODUCT (stories)
     * This takes the raw story outlines from our database, grabs the actual photos from our dictionary,
     * strips out anything the user hid, and packages them into clean `UiStory` boxes that the screen can easily display.
     */
    val stories: StateFlow<List<UiStory>> =
        // We combine three things: The raw database outlines, the massive photo dictionary, and the list of hidden albums.
        combine(dao.getStories(), _mediaMap, _hiddenAlbums) { entities, map, hidden ->
            entities
                .mapNotNull { entity ->
                    // The database saves photo IDs as a text list like "[101, 102, 103]".
                    // This math turns that text back into real numbers.
                    val ids = try {
                        entity.mediaIdsJson
                            .removePrefix("[")
                            .removeSuffix("]")
                            .split(",")
                            .mapNotNull { it.trim().toLongOrNull() }
                            .distinct()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    // Grab the actual photo files from the dictionary and remove any hidden ones.
                    val items = ids
                        .mapNotNull { map[it] }
                        .filterNot { hidden.contains(it.bucketId) }
                        .sortedBy { it.dateAdded } // Sort oldest to newest

                    // If all the photos in the story were deleted or hidden, throw the story away.
                    if (items.isEmpty()) {
                        null
                    } else {
                        // Otherwise, build the final Scrapbook!
                        UiStory(
                            entity.id,
                            entity.title,
                            entity.subtitle ?: "",
                            entity.coverUri.toUri(),
                            items
                        )
                    }
                }
                // Sort the scrapbooks themselves so the newest ones are at the top of the screen.
                .sortedByDescending { story ->
                    story.items.maxOfOrNull { it.dateAdded } ?: 0L
                }
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default) // Do all this heavy math in the background warehouse
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    /**
     * THE MORNING ROUTINE (`init`)
     * This runs exactly once when the Manager wakes up.
     */
    init {
        // Start listening to the digital notebook for hidden albums.
        enginePrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Go scan the entire phone to build our dictionary of photos.
        loadStoryMedia()

        // Start a ticking clock to rebuild the scrapbooks every 24 hours.
        startPeriodicMemoryWatcher()
    }

    /**
     * Taking out the trash when the app shuts down.
     */
    override fun onCleared() {
        super.onCleared()
        enginePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** Helper to filter out any photos that belong to albums the user chose to hide. */
    private fun excludeHidden(items: List<MediaItem>): List<MediaItem> {
        val hidden = _hiddenAlbums.value
        if (hidden.isEmpty()) return items
        return items.filterNot { hidden.contains(it.bucketId) }
    }

    /**
     * Background worker that searches the phone, builds the dictionary, and starts
     * generating stories if the user has more than 20 photos.
     */
    private fun loadStoryMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = excludeHidden(scanMediaStoreMetadata())
            _mediaMap.value = items.associateBy { it.id }

            // If they have enough photos, start building scrapbooks!
            if (items.size > 20) {
                triggerOfflineStoryGeneration(items, force = false)
            }
        }
    }

    /**
     * A ticking clock that wakes up every hour to check if 24 hours have passed since we last built scrapbooks.
     * If 24 hours *have* passed, it triggers a completely fresh build to include any photos taken today.
     */
    private fun startPeriodicMemoryWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(PERIODIC_CHECK_MS) // Sleep for 1 hour

                // If the factory isn't currently running...
                if (!_isGenerating.value) {
                    val prefs = getApplication<Application>()
                        .getSharedPreferences(
                            "gallery_engine_prefs",
                            Context.MODE_PRIVATE
                        )

                    val lastScanned = prefs.getLong("last_memory_scan_time", 0L)

                    // If it's been more than 24 hours (DAILY_REFRESH_MS)...
                    if (System.currentTimeMillis() - lastScanned >= DAILY_REFRESH_MS) {
                        // Rescan the phone to find new photos...
                        val items = excludeHidden(scanMediaStoreMetadata())
                        _mediaMap.value = items.associateBy { it.id }
                        // ...and force the factory to build new stories.
                        triggerOfflineStoryGeneration(items, force = true)
                    }
                }
            }
        }
    }

    /**
     * THE DETECTIVE (`scanMediaStoreMetadata`)
     * Looks through the deep Android database (`MediaStore`) to find every single image and video file on the phone.
     * We don't load the actual image pixels (which would crash the phone), just the text data (date, size, name).
     */
    private fun scanMediaStoreMetadata(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val context = getApplication<Application>()

        val uri = MediaStore.Files.getContentUri("external") // Look at external storage

        // The specific pieces of information we want Android to tell us about each file.
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_FAVORITE
        )

        // The filter: "Only give us files that are either an IMAGE or a VIDEO."
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC" // Newest first

        try {
            // Execute the query!
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->

                // Find out which column holds which piece of data
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val favCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)

                // Go through the results row by row and pack the data into our clean `MediaItem` boxes.
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val isVideo = mimeType.startsWith("video")
                    val contentUri = if (isVideo) {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }

                    items.add(
                        MediaItem(
                            id = id,
                            uri = contentUri,
                            path = cursor.getString(dataCol) ?: "",
                            name = cursor.getString(nameCol) ?: "",
                            bucketId = cursor.getString(bucketIdCol) ?: "",
                            bucketName = cursor.getString(bucketNameCol) ?: "",
                            mimeType = mimeType,
                            dateAdded = cursor.getLong(dateAddedCol),
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol),
                            size = cursor.getLong(sizeCol),
                            duration = cursor.getLong(durationCol),
                            isVideo = isVideo,
                            isFavorite = cursor.getInt(favCol) == 1,
                            relativePath = cursor.getString(relPathCol) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    /**
     * Creates a custom scrapbook when the user manually selects photos and clicks "Create Memory".
     */
    fun createManualStory(
        mediaIds: List<Long>,
        title: String
    ) {
        val distinctIds = mediaIds.distinct() // Remove accidental duplicates

        if (distinctIds.isEmpty() || title.isBlank()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Find the actual files for the IDs they selected
                val items = distinctIds.mapNotNull {
                    _mediaMap.value[it]
                }

                if (items.isEmpty()) {
                    return@launch
                }

                // Pick the prettiest photo to act as the cover image
                val bestCover = selectBestCover(items)

                val coverUri = bestCover?.uri?.toString()
                    ?: ContentUris
                        .withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            distinctIds.first()
                        )
                        .toString()

                // Create the outline for our database
                val entity = StoryEntity(
                    id = "manual_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    title = title,
                    subtitle = "${distinctIds.size} selected items",
                    coverUri = coverUri,
                    mediaIdsJson = distinctIds.joinToString(",", "[", "]"),
                    createdAt = System.currentTimeMillis(),
                    storyType = "MANUAL"
                )

                dao.insertStory(entity) // Save it!

                _events.trySend(GalleryEvent.ShowToast("Memory created successfully!"))
            } catch (_: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Failed to create memory"))
            }
        }
    }

    // Deletes a specific story from the database. It DOES NOT delete the actual photos!
    fun deleteStory(storyId: String) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteStory(storyId)
                _events.trySend(GalleryEvent.ShowToast("Memory removed"))
            } catch (_: Exception) {}
        }

    // Forces the factory to rebuild everything right now.
    fun refreshMemories() =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

                // Trick the app into thinking it's been a long time since we last built stories
                prefs.edit { putLong("last_memory_scan_time", 0L) }

                val items = excludeHidden(scanMediaStoreMetadata())
                _mediaMap.value = items.associateBy { it.id }

                triggerOfflineStoryGeneration(items, force = true)
            } catch (_: Exception) {}
        }

    /**
     * THE FACTORY (`triggerOfflineStoryGeneration`).
     * This is the complex algorithm that looks at all the photos, groups them by date,
     * and decides which ones should become Stories.
     */
    private fun triggerOfflineStoryGeneration(
        mediaList: List<MediaItem>,
        force: Boolean = false
    ) {
        if (_isGenerating.value) return // Don't start a second factory line if one is already running.

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

                val lastScanned = prefs.getLong("last_memory_scan_time", 0L)

                // If it hasn't been 24 hours, don't run the heavy math (unless forced)
                if (!force && System.currentTimeMillis() - lastScanned < DAILY_REFRESH_MS) {
                    return@launch
                }

                _isGenerating.value = true // Turn on the factory lights
                _generationProgress.value = 0
                _generationTotal.value = mediaList.size

                // We don't want to use photos that the user already put into a "Manual" story.
                val manualMediaIds = dao.getStoriesSync()
                    .filter { it.storyType == "MANUAL" }
                    .flatMap { entity ->
                        entity.mediaIdsJson.removePrefix("[").removeSuffix("]")
                            .split(",").mapNotNull { it.trim().toLongOrNull() }
                    }
                    .toSet()

                // Filter out manual photos and bad data.
                val uniqueMedia = mediaList
                    .filter { it.id > 0 && it.id !in manualMediaIds }
                    .distinctBy { it.id }

                if (uniqueMedia.isEmpty()) {
                    _generationProgress.value = 0
                    return@launch
                }

                // Sort everything from oldest to newest so we can track events over time.
                val chronologicalMedia = uniqueMedia.sortedBy { it.dateAdded }

                val naturalClusters = mutableListOf<List<MediaItem>>() // The piles of photos grouped by event
                var currentCluster = mutableListOf<MediaItem>() // The pile we are currently adding to

                var lastItemTime = 0L
                var lastItemDay = -1
                var lastItemYear = -1

                val calendar = Calendar.getInstance()

                // PHASE 1: Group photos that were taken on the same day and within an hour of each other.
                for (item in chronologicalMedia) {

                    val timeMs = item.dateAdded * 1000L
                    calendar.timeInMillis = timeMs

                    val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
                    val currentYear = calendar.get(Calendar.YEAR)

                    // If the pile is empty, just add the photo.
                    if (currentCluster.isEmpty()) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                        continue
                    }

                    val timeDiff = abs(timeMs - lastItemTime)
                    val sameDay = currentDay == lastItemDay && currentYear == lastItemYear

                    // If this photo was taken on the exact same day, and within 1 hour (EVENT_THRESHOLD_MS)
                    // of the previous photo, it belongs to the same event/pile!
                    if (sameDay && timeDiff <= EVENT_THRESHOLD_MS) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                    } else {
                        // Too much time passed. This is a new event.
                        // Put the finished pile on the shelf, and start a new pile.
                        if (currentCluster.isNotEmpty()) {
                            naturalClusters.add(currentCluster.distinctBy { it.id })
                        }

                        currentCluster = mutableListOf(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                    }
                }

                // Put the very last pile on the shelf
                if (currentCluster.isNotEmpty()) {
                    naturalClusters.add(currentCluster.distinctBy { it.id })
                }

                // ---------------------------------------------------------
                // PHASE 2: Sizing the Scrapbooks.
                // We don't want a scrapbook with only 2 photos, and we don't want one with 200 photos.
                // Every automatic Story must be between 10 and 50 photos.
                // ---------------------------------------------------------
                val storyClusters = mutableListOf<List<MediaItem>>() // The final, perfectly sized piles
                var pendingSmall = mutableListOf<MediaItem>() // A waiting room for tiny piles of photos

                for (naturalCluster in naturalClusters) {
                    val items = naturalCluster.distinctBy { it.id }

                    if (items.isEmpty()) {
                        continue
                    }

                    // If we have tiny piles waiting, check if they are too old.
                    // We don't want to stitch photos from January with photos from August.
                    if (pendingSmall.isNotEmpty()) {
                        val gapMs = abs((items.first().dateAdded - pendingSmall.last().dateAdded)) * 1000L
                        if (gapMs > MAX_MERGE_GAP_MS) { // If it's been more than 3 days, throw the tiny pile away.
                            pendingSmall = mutableListOf()
                        }
                    }

                    /*
                     * If this pile has fewer than 10 photos, put it in the waiting room
                     * until we find more photos from the next few days to combine it with.
                     */
                    if (items.size < MIN_ITEMS_PER_STORY) {
                        pendingSmall.addAll(items)
                        continue
                    }

                    /*
                     * If we found a big pile, let's mix the waiting room photos into it first.
                     */
                    if (pendingSmall.isNotEmpty()) {
                        val combined = pendingSmall + items

                        if (combined.size >= MIN_ITEMS_PER_STORY) {
                            var offset = 0

                            // Slice the giant combined pile into 50-photo chunks.
                            while (combined.size - offset >= MIN_ITEMS_PER_STORY) {
                                val remaining = combined.size - offset

                                val chunkSize = when {
                                    remaining >= MAX_ITEMS_PER_STORY -> MAX_ITEMS_PER_STORY // Take exactly 50
                                    remaining >= MIN_ITEMS_PER_STORY -> remaining // Take the rest
                                    else -> break
                                }

                                val chunk = combined.subList(offset, offset + chunkSize)

                                storyClusters.add(chunk.distinctBy { it.id })
                                offset += chunkSize
                            }

                            // Put any leftovers back in the waiting room
                            pendingSmall = if (offset < combined.size) {
                                combined.subList(offset, combined.size).toMutableList()
                            } else {
                                mutableListOf()
                            }

                        } else {
                            pendingSmall = combined.toMutableList()
                        }

                        continue
                    }

                    /*
                     * If the waiting room was empty, just slice up the big pile into 50-photo chunks.
                     */
                    var offset = 0

                    while (offset < items.size) {
                        val remaining = items.size - offset

                        if (remaining < MIN_ITEMS_PER_STORY) {
                            pendingSmall.addAll(items.subList(offset, items.size))
                            break
                        }

                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)

                        storyClusters.add(
                            items
                                .subList(offset, offset + chunkSize)
                                .distinctBy { it.id }
                        )

                        offset += chunkSize
                    }
                }

                /*
                 * Try to make one final Story from whatever is left in the waiting room.
                 */
                if (pendingSmall.size >= MIN_ITEMS_PER_STORY) {
                    var offset = 0

                    while (pendingSmall.size - offset >= MIN_ITEMS_PER_STORY) {
                        val remaining = pendingSmall.size - offset
                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)

                        storyClusters.add(
                            pendingSmall
                                .subList(offset, offset + chunkSize)
                                .distinctBy { it.id }
                        )

                        offset += chunkSize
                    }
                }

                /*
                 * Do a final safety check to ensure every pile is strictly between 10 and 50 photos.
                 */
                val validClusters = storyClusters
                    .map { it.distinctBy { item -> item.id } }
                    .filter { it.size in MIN_ITEMS_PER_STORY..MAX_ITEMS_PER_STORY }

                _generationProgress.value = uniqueMedia.size
                yield() // Give the phone's processor a tiny break to catch its breath

                // Sort the perfectly sized piles so the newest ones are first, and only keep the top 30.
                val finalClustersToSave = validClusters
                    .sortedByDescending { cluster -> cluster.maxOfOrNull { it.dateAdded } ?: 0L }
                    .take(MAX_AUTO_STORIES)

                val generationId = System.currentTimeMillis()

                // Turn the piles into Database entries
                val generatedStories = finalClustersToSave
                    .mapIndexed { index, cluster ->
                        buildEntity(
                            idString = "auto_event_${generationId}_$index",
                            title = "Memory ${index + 1}",
                            subtitle = "",
                            items = cluster,
                            isManual = false
                        )
                    }

                // Delete all the old automatic stories from the database to make room for these fresh ones.
                val existing = dao.getStoriesSync()

                existing
                    .filter { it.storyType != "MANUAL" }
                    .forEach { dao.deleteStory(it.id) }

                // Save the new stories!
                generatedStories.forEach { entity ->
                    try {
                        dao.insertStory(entity)
                    } catch (_: Exception) {}
                }

                // Update the digital notebook to remember exactly when we finished building.
                prefs.edit {
                    putLong("last_memory_scan_time", System.currentTimeMillis())
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false // Turn off the factory lights
            }
        }
    }

    /**
     * Packages a raw pile of photos into a structured box (`StoryEntity`) ready for the database.
     */
    private fun buildEntity(
        idString: String,
        title: String,
        subtitle: String,
        items: List<MediaItem>,
        isManual: Boolean = false
    ): StoryEntity {
        val validItems = items
            .filter { it.id > 0 }
            .distinctBy { it.id }

        // Find the absolute best photo to use as the cover image
        val bestCover = selectBestCover(validItems)

        val coverUri = bestCover?.uri?.toString()
            ?: validItems.firstOrNull()?.uri?.toString()
            ?: ""

        return StoryEntity(
            id = idString,
            title = title,
            subtitle = subtitle,
            coverUri = coverUri,
            mediaIdsJson = validItems.joinToString(",", "[", "]") { it.id.toString() },
            createdAt = System.currentTimeMillis(),
            storyType = if (isManual) "MANUAL" else "AUTO"
        )
    }

    /**
     * THE ART CRITIC.
     * Looks at a pile of photos and assigns each one a "Score" based on how pretty it is.
     * The photo with the highest score becomes the cover image for the Story.
     */
    private fun selectBestCover(items: List<MediaItem>): MediaItem? {
        if (items.isEmpty()) {
            return null
        }

        return items.maxByOrNull { item ->
            var score = 0.0

            if (item.isFavorite) score += 50.0 // The user favorited it, it must be good!
            if (!item.mimeType.startsWith("video")) score += 20.0 // Videos make bad static covers, prefer photos.

            // High-resolution (HD/4K) photos get more points than blurry, tiny photos.
            val resolution = (item.width * item.height) / 100000.0
            score += min(resolution, 40.0) // Cap the resolution bonus so 8K photos don't always win

            val ratio = if (item.height > 0) {
                item.width.toDouble() / item.height
            } else {
                1.0
            }

            // Normal portrait or landscape photos get a bonus. Extremely tall or wide panoramic photos do not.
            if (ratio in 0.5..0.8 || ratio in 1.3..1.8) score += 15.0
            // Screenshots are ugly. Heavily penalize them so they are almost never the cover.
            if (item.path.lowercase().contains("screenshot")) score -= 50.0

            score // Return the final score
        }
    }

    // Rules for the Factory
    companion object {
        const val MAX_AUTO_STORIES = 30 // Don't build more than 30 automatic stories.
        const val MIN_ITEMS_PER_STORY = 10 // A story must have at least 10 photos.
        const val MAX_ITEMS_PER_STORY = 50 // A story can't have more than 50 photos.
        const val EVENT_THRESHOLD_MS = 60L * 60L * 1000L // 1 hour (Determines if photos belong to the same event)
        const val DAILY_REFRESH_MS = 24L * 60L * 60L * 1000L // 24 hours (How often to rebuild stories)
        const val PERIODIC_CHECK_MS = 60L * 60L * 1000L // 1 hour (How often the ticking clock checks the time)
        const val MAX_MERGE_GAP_MS = 3L * 24L * 60L * 60L * 1000L // 3 days (Max gap allowed when combining tiny piles)
    }
}