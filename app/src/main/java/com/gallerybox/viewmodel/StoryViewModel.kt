@file:Suppress("unused", "MemberVisibilityCanBePrivate", "DEPRECATION")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

/**
 * =========================================================================================
 * 📖 THE OFFLINE SCRAPBOOK CREATOR (StoryViewModel)
 * =========================================================================================
 * Purely offline, deterministic rule-based engine that groups photos and videos by
 * calendar events, time-of-day, weekends, and folder metadata into curated Stories.
 * No AI, no ML, and no cloud services are used.
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    application: Application,
    private val dao: GalleryDao
) : AndroidViewModel(application) {

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val enginePrefs = application.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

    private val _hiddenAlbums = MutableStateFlow(loadHiddenAlbumsFromPrefs())
    val hiddenAlbums = _hiddenAlbums.asStateFlow()

    private val _hiddenItems = MutableStateFlow(loadHiddenItemsFromPrefs())

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "hidden_albums" -> _hiddenAlbums.value = loadHiddenAlbumsFromPrefs()
            "hidden_items" -> _hiddenItems.value = loadHiddenItemsFromPrefs()
        }
    }

    private fun loadHiddenAlbumsFromPrefs(): Set<String> =
        enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()

    private fun loadHiddenItemsFromPrefs(): Set<String> =
        enginePrefs.getStringSet("hidden_items", emptySet()) ?: emptySet()

    private val _mediaMap = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _generationProgress = MutableStateFlow(0)
    val generationProgress = _generationProgress.asStateFlow()

    private val _generationTotal = MutableStateFlow(0)
    val generationTotal = _generationTotal.asStateFlow()

    /**
     * Streams curated UiStory objects to the UI, filtering out hidden albums/items
     * and automatically repairing the cover URI if the original cover photo was removed.
     */
    val stories: StateFlow<List<UiStory>> =
        combine(dao.getStories(), _mediaMap, _hiddenAlbums, _hiddenItems) { entities, map, hiddenBuckets, hiddenItemIds ->
            entities
                .mapNotNull { entity ->
                    val ids = parseMediaIdsJson(entity.mediaIdsJson)

                    val items = ids
                        .mapNotNull { map[it] }
                        .filterNot { item ->
                            hiddenBuckets.contains(item.bucketId) || hiddenItemIds.contains(item.id.toString())
                        }
                        .sortedBy { it.dateAdded }

                    if (items.isEmpty()) {
                        null
                    } else {
                        val savedCoverUri = entity.coverUri.takeIf { it.isNotBlank() }?.toUri()
                        val coverStillValid = savedCoverUri != null && items.any { it.uri == savedCoverUri }
                        val resolvedCoverUri = if (coverStillValid && savedCoverUri != null) {
                            savedCoverUri
                        } else {
                            selectBestCover(items)?.uri ?: items.first().uri
                        }

                        val resolvedSubtitle = entity.subtitle?.takeIf { it.isNotBlank() }
                            ?: buildDateRangeSubtitle(items)

                        UiStory(
                            entity.id,
                            entity.title,
                            resolvedSubtitle,
                            resolvedCoverUri,
                            items
                        )
                    }
                }
                .sortedWith(
                    compareByDescending<UiStory> { story ->
                        story.items.maxOfOrNull { it.dateAdded } ?: 0L
                    }.thenByDescending { it.items.size }
                )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    init {
        enginePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        loadStoryMedia()
        startPeriodicMemoryWatcher()
    }

    override fun onCleared() {
        super.onCleared()
        enginePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun parseMediaIdsJson(json: String): List<Long> {
        return try {
            json.removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun excludeHidden(items: List<MediaItem>): List<MediaItem> {
        val hiddenBuckets = _hiddenAlbums.value
        val hiddenIds = _hiddenItems.value
        if (hiddenBuckets.isEmpty() && hiddenIds.isEmpty()) return items
        return items.filterNot { item ->
            hiddenBuckets.contains(item.bucketId) || hiddenIds.contains(item.id.toString())
        }
    }

    private fun loadStoryMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = excludeHidden(scanMediaStoreMetadata())
            _mediaMap.value = items.associateBy { it.id }

            if (items.size >= MIN_ITEMS_PER_STORY) {
                triggerOfflineStoryGeneration(items, force = false)
            }
        }
    }

    private fun startPeriodicMemoryWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(PERIODIC_CHECK_MS)

                if (!_isGenerating.value) {
                    val lastScanned = enginePrefs.getLong("last_memory_scan_time", 0L)

                    if (System.currentTimeMillis() - lastScanned >= DAILY_REFRESH_MS) {
                        val items = excludeHidden(scanMediaStoreMetadata())
                        _mediaMap.value = items.associateBy { it.id }
                        if (items.size >= MIN_ITEMS_PER_STORY) {
                            triggerOfflineStoryGeneration(items, force = true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Queries MediaStore for image and video metadata without decoding bitmaps.
     * Cross-references local Room favorites so user-favorited photos get prioritized.
     */
    private suspend fun scanMediaStoreMetadata(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val context = getApplication<Application>()

        val localFavoriteIds = runCatching { dao.getFavoriteIds().first().toHashSet() }.getOrDefault(emptySet())
        val secureVaultIds = runCatching { dao.getSecureMediaIds().first().toHashSet() }.getOrDefault(emptySet())
        val trashUris = runCatching { dao.getTrash().first().map { it.contentUri }.toHashSet() }.getOrDefault(emptySet())

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = mutableListOf(
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
            MediaStore.MediaColumns.DURATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_FAVORITE)
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(uri, projection.toTypedArray(), selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val widthCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val volCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) else -1
                val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val favCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
                val trashCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1

                while (cursor.moveToNext()) {
                    if (trashCol != -1 && cursor.getInt(trashCol) == 1) continue

                    val id = cursor.getLong(idCol)
                    if (secureVaultIds.contains(id)) continue

                    val name = cursor.getString(nameCol) ?: ""
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    val relPath = if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else path.substringBeforeLast('/', "")

                    if (name.startsWith(".") || relPath.split("/").any { it.startsWith(".") }) continue

                    val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    val size = cursor.getLong(sizeCol)

                    // Skip tiny cached icons/thumbnails so they don't pollute user stories
                    if (size < 10_240L || (width in 1..199 && height in 1..199)) continue

                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val isVideo = mimeType.startsWith("video")
                    val volumeName = if (volCol != -1) cursor.getString(volCol) ?: "" else ""

                    val baseUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeName.isNotBlank()) {
                        if (isVideo) MediaStore.Video.Media.getContentUri(volumeName)
                        else MediaStore.Images.Media.getContentUri(volumeName)
                    } else {
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val contentUri = ContentUris.withAppendedId(baseUri, id)
                    if (trashUris.contains(contentUri.toString())) continue

                    val rawDate = cursor.getLong(dateAddedCol)
                    val normalizedDateSec = when {
                        rawDate > 1_000_000_000_000L -> rawDate / 1000L
                        rawDate > 0L -> rawDate
                        else -> System.currentTimeMillis() / 1000L
                    }

                    val isFav = localFavoriteIds.contains(id) || (favCol != -1 && cursor.getInt(favCol) == 1)

                    items.add(
                        MediaItem(
                            id = id,
                            uri = contentUri,
                            path = path,
                            name = name,
                            bucketId = cursor.getString(bucketIdCol) ?: "",
                            bucketName = cursor.getString(bucketNameCol) ?: "",
                            mimeType = mimeType,
                            dateAdded = normalizedDateSec,
                            width = width,
                            height = height,
                            size = size,
                            duration = if (durationCol != -1) cursor.getLong(durationCol) else 0L,
                            isVideo = isVideo,
                            isFavorite = isFav,
                            relativePath = relPath,
                            volumeName = volumeName
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
     * Creates a custom manual story from user-selected media items.
     */
    fun createManualStory(
        mediaIds: List<Long>,
        title: String
    ) {
        val distinctIds = mediaIds.distinct()
        val cleanTitle = title.trim()

        if (distinctIds.isEmpty() || cleanTitle.isBlank()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_mediaMap.value.isEmpty()) {
                    val fresh = excludeHidden(scanMediaStoreMetadata())
                    _mediaMap.value = fresh.associateBy { it.id }
                }

                val items = distinctIds.mapNotNull { _mediaMap.value[it] }
                if (items.isEmpty()) {
                    _events.trySend(GalleryEvent.ShowToast("Selected photos are still loading"))
                    return@launch
                }

                val bestCover = selectBestCover(items)
                val coverUri = bestCover?.uri?.toString()
                    ?: items.first().uri.toString()

                val subtitle = buildDateRangeSubtitle(items)

                val entity = StoryEntity(
                    id = "manual_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    title = cleanTitle,
                    subtitle = subtitle,
                    coverUri = coverUri,
                    mediaIdsJson = distinctIds.joinToString(",", "[", "]"),
                    createdAt = System.currentTimeMillis(),
                    storyType = "MANUAL"
                )

                dao.insertStory(entity)
                _events.trySend(GalleryEvent.ShowToast("Memory '$cleanTitle' created!"))
            } catch (_: Exception) {
                _events.trySend(GalleryEvent.ShowToast("Failed to create memory"))
            }
        }
    }

    fun deleteStory(storyId: String) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteStory(storyId)
                _events.trySend(GalleryEvent.ShowToast("Memory removed"))
            } catch (_: Exception) {}
        }

    fun refreshMemories() =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                enginePrefs.edit { putLong("last_memory_scan_time", 0L) }
                val items = excludeHidden(scanMediaStoreMetadata())
                _mediaMap.value = items.associateBy { it.id }
                triggerOfflineStoryGeneration(items, force = true)
            } catch (_: Exception) {}
        }

    /**
     * =========================================================================================
     * 🏭 THE OFFLINE STORY FACTORY (`triggerOfflineStoryGeneration`)
     * =========================================================================================
     * 1. Excludes screenshots and items already assigned to manual stories.
     * 2. Collapses rapid burst shots (taken within 3 seconds of each other) to keep the best shot.
     * 3. Groups chronological media into natural event clusters (same day, within 1 hour).
     * 4. Merges nearby small clusters (within 3 days) and slices large events into 10..50 item stories.
     * 5. Generates descriptive, rule-based titles and date-range subtitles without AI/ML.
     */
    private fun triggerOfflineStoryGeneration(
        mediaList: List<MediaItem>,
        force: Boolean = false
    ) {
        if (_isGenerating.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastScanned = enginePrefs.getLong("last_memory_scan_time", 0L)
                if (!force && System.currentTimeMillis() - lastScanned < DAILY_REFRESH_MS) {
                    return@launch
                }

                _isGenerating.value = true
                _generationProgress.value = 0
                _generationTotal.value = mediaList.size

                val manualMediaIds = dao.getStoriesSync()
                    .filter { it.storyType == "MANUAL" }
                    .flatMap { entity -> parseMediaIdsJson(entity.mediaIdsJson) }
                    .toSet()

                // Filter out manual-story items and pure screenshot folders for cleaner memories
                val candidateMedia = mediaList
                    .filter { item ->
                        item.id > 0 &&
                                item.id !in manualMediaIds &&
                                !item.bucketName.equals("Screenshots", ignoreCase = true) &&
                                !item.name.startsWith("Screenshot_", ignoreCase = true)
                    }
                    .distinctBy { it.id }
                    .ifEmpty {
                        // Fallback if the user's library is mostly screenshots
                        mediaList.filter { it.id > 0 && it.id !in manualMediaIds }.distinctBy { it.id }
                    }

                if (candidateMedia.size < MIN_ITEMS_PER_STORY) {
                    _generationProgress.value = mediaList.size
                    return@launch
                }

                val chronologicalMedia = deduplicateRapidBursts(candidateMedia.sortedBy { it.dateAdded })
                _generationTotal.value = chronologicalMedia.size

                val naturalClusters = mutableListOf<List<MediaItem>>()
                var currentCluster = mutableListOf<MediaItem>()

                var lastItemTime = 0L
                var lastItemDay = -1
                var lastItemYear = -1

                val calendar = Calendar.getInstance()

                // PHASE 1: Natural Event Clustering
                for ((idx, item) in chronologicalMedia.withIndex()) {
                    if (idx % 100 == 0) {
                        _generationProgress.value = (idx * 0.6f).toInt()
                        yield()
                    }

                    val timeMs = item.dateAdded * 1000L
                    calendar.timeInMillis = timeMs

                    val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
                    val currentYear = calendar.get(Calendar.YEAR)

                    if (currentCluster.isEmpty()) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                        continue
                    }

                    val timeDiff = abs(timeMs - lastItemTime)
                    val sameDay = currentDay == lastItemDay && currentYear == lastItemYear

                    if (sameDay && timeDiff <= EVENT_THRESHOLD_MS) {
                        currentCluster.add(item)
                        lastItemTime = timeMs
                    } else {
                        if (currentCluster.isNotEmpty()) {
                            naturalClusters.add(currentCluster.distinctBy { it.id })
                        }
                        currentCluster = mutableListOf(item)
                        lastItemTime = timeMs
                        lastItemDay = currentDay
                        lastItemYear = currentYear
                    }
                }

                if (currentCluster.isNotEmpty()) {
                    naturalClusters.add(currentCluster.distinctBy { it.id })
                }

                // PHASE 2: Sizing Scrapbooks into 10..50 item stories
                val storyClusters = mutableListOf<List<MediaItem>>()
                var pendingSmall = mutableListOf<MediaItem>()

                for ((clusterIdx, naturalCluster) in naturalClusters.withIndex()) {
                    if (clusterIdx % 20 == 0) {
                        _generationProgress.value =
                            (chronologicalMedia.size * 0.6f + (clusterIdx.toFloat() / naturalClusters.size.coerceAtLeast(1)) * chronologicalMedia.size * 0.3f).toInt()
                        yield()
                    }

                    val items = naturalCluster.distinctBy { it.id }
                    if (items.isEmpty()) continue

                    if (pendingSmall.isNotEmpty()) {
                        val gapMs = abs(items.first().dateAdded - pendingSmall.last().dateAdded) * 1000L
                        if (gapMs > MAX_MERGE_GAP_MS) {
                            pendingSmall = mutableListOf()
                        }
                    }

                    if (items.size < MIN_ITEMS_PER_STORY) {
                        pendingSmall.addAll(items)
                        if (pendingSmall.size >= MIN_ITEMS_PER_STORY) {
                            while (pendingSmall.size >= MIN_ITEMS_PER_STORY) {
                                val takeCount = min(MAX_ITEMS_PER_STORY, pendingSmall.size)
                                storyClusters.add(pendingSmall.subList(0, takeCount).distinctBy { it.id })
                                pendingSmall = pendingSmall.subList(takeCount, pendingSmall.size).toMutableList()
                            }
                        }
                        continue
                    }

                    if (pendingSmall.isNotEmpty()) {
                        val combined = (pendingSmall + items).distinctBy { it.id }
                        var offset = 0
                        while (combined.size - offset >= MIN_ITEMS_PER_STORY) {
                            val remaining = combined.size - offset
                            val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)
                            storyClusters.add(combined.subList(offset, offset + chunkSize))
                            offset += chunkSize
                        }
                        pendingSmall = if (offset < combined.size) {
                            combined.subList(offset, combined.size).toMutableList()
                        } else {
                            mutableListOf()
                        }
                        continue
                    }

                    var offset = 0
                    while (offset < items.size) {
                        val remaining = items.size - offset
                        if (remaining < MIN_ITEMS_PER_STORY) {
                            pendingSmall.addAll(items.subList(offset, items.size))
                            break
                        }
                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)
                        storyClusters.add(items.subList(offset, offset + chunkSize).distinctBy { it.id })
                        offset += chunkSize
                    }
                }

                if (pendingSmall.size >= MIN_ITEMS_PER_STORY) {
                    var offset = 0
                    while (pendingSmall.size - offset >= MIN_ITEMS_PER_STORY) {
                        val remaining = pendingSmall.size - offset
                        val chunkSize = min(MAX_ITEMS_PER_STORY, remaining)
                        storyClusters.add(pendingSmall.subList(offset, offset + chunkSize).distinctBy { it.id })
                        offset += chunkSize
                    }
                }

                // PHASE 3: Add deterministic special collections ("Throwback / On This Day" & "Favorite Moments")
                val specialEntities = mutableListOf<StoryEntity>()
                val generationId = System.currentTimeMillis()

                // A. "On This Day / Throwback" (photos taken within +/- 3 days of today's date in previous years)
                val throwbackItems = findOnThisDayMedia(chronologicalMedia)
                if (throwbackItems.size >= MIN_ITEMS_PER_STORY) {
                    val slicedThrowback = throwbackItems.take(MAX_ITEMS_PER_STORY)
                    specialEntities.add(
                        buildEntity(
                            idString = "auto_throwback_$generationId",
                            title = "On This Week",
                            subtitle = buildDateRangeSubtitle(slicedThrowback),
                            items = slicedThrowback,
                            isManual = false
                        )
                    )
                }

                // B. "Favorite Highlights" (if the user has >= 10 favorited items)
                val favoriteItems = chronologicalMedia.filter { it.isFavorite }.takeLast(MAX_ITEMS_PER_STORY)
                if (favoriteItems.size >= MIN_ITEMS_PER_STORY) {
                    specialEntities.add(
                        buildEntity(
                            idString = "auto_favorites_$generationId",
                            title = "Favorite Highlights",
                            subtitle = buildDateRangeSubtitle(favoriteItems),
                            items = favoriteItems,
                            isManual = false
                        )
                    )
                }

                val validClusters = storyClusters
                    .map { it.distinctBy { item -> item.id } }
                    .filter { it.size in MIN_ITEMS_PER_STORY..MAX_ITEMS_PER_STORY }

                val maxEventCount = (MAX_AUTO_STORIES - specialEntities.size).coerceAtLeast(1)
                val finalClustersToSave = validClusters
                    .sortedByDescending { cluster -> cluster.maxOfOrNull { it.dateAdded } ?: 0L }
                    .take(maxEventCount)

                val usedTitlesCount = mutableMapOf<String, Int>()
                val generatedEventStories = finalClustersToSave.mapIndexed { index, cluster ->
                    val (rawTitle, smartSubtitle) = generateSmartTitleAndSubtitle(cluster)
                    val occurrence = (usedTitlesCount[rawTitle] ?: 0) + 1
                    usedTitlesCount[rawTitle] = occurrence
                    val uniqueTitle = if (occurrence == 1) rawTitle else "$rawTitle #$occurrence"

                    buildEntity(
                        idString = "auto_event_${generationId}_$index",
                        title = uniqueTitle,
                        subtitle = smartSubtitle,
                        items = cluster,
                        isManual = false
                    )
                }

                _generationProgress.value = chronologicalMedia.size
                yield()

                val allNewAutoStories = specialEntities + generatedEventStories

                val existing = dao.getStoriesSync()
                existing
                    .filter { it.storyType != "MANUAL" }
                    .forEach { dao.deleteStory(it.id) }

                allNewAutoStories.forEach { entity ->
                    try {
                        dao.insertStory(entity)
                    } catch (_: Exception) {}
                }

                enginePrefs.edit {
                    putLong("last_memory_scan_time", System.currentTimeMillis())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Collapses rapid-fire camera bursts (3+ photos taken within 2 seconds) by keeping
     * the highest-scored frame so stories don't show 15 identical frames in a row.
     */
    private fun deduplicateRapidBursts(sortedItems: List<MediaItem>): List<MediaItem> {
        if (sortedItems.size <= MIN_ITEMS_PER_STORY * 2) return sortedItems
        val result = mutableListOf<MediaItem>()
        var burstGroup = mutableListOf<MediaItem>()

        for (item in sortedItems) {
            if (burstGroup.isEmpty()) {
                burstGroup.add(item)
                continue
            }
            val prev = burstGroup.last()
            val timeDiffSec = abs(item.dateAdded - prev.dateAdded)
            if (!item.isVideo && !prev.isVideo && timeDiffSec <= 2L && item.bucketId == prev.bucketId) {
                burstGroup.add(item)
            } else {
                if (burstGroup.size <= 2) {
                    result.addAll(burstGroup)
                } else {
                    // Keep the best 2 frames from a rapid burst
                    val bestTwo = burstGroup.sortedByDescending { scoreMediaQuality(it) }.take(2).sortedBy { it.dateAdded }
                    result.addAll(bestTwo)
                }
                burstGroup = mutableListOf(item)
            }
        }
        if (burstGroup.isNotEmpty()) {
            if (burstGroup.size <= 2) {
                result.addAll(burstGroup)
            } else {
                result.addAll(burstGroup.sortedByDescending { scoreMediaQuality(it) }.take(2).sortedBy { it.dateAdded })
            }
        }
        return if (result.size >= MIN_ITEMS_PER_STORY) result else sortedItems
    }

    /**
     * Finds media items captured within +/- 3 days of the current calendar day in previous years.
     */
    private fun findOnThisDayMedia(items: List<MediaItem>): List<MediaItem> {
        val nowCal = Calendar.getInstance()
        val currentYear = nowCal.get(Calendar.YEAR)
        val currentDayOfYear = nowCal.get(Calendar.DAY_OF_YEAR)

        val itemCal = Calendar.getInstance()
        return items.filter { item ->
            itemCal.timeInMillis = item.dateAdded * 1000L
            val yr = itemCal.get(Calendar.YEAR)
            val doy = itemCal.get(Calendar.DAY_OF_YEAR)
            yr < currentYear && abs(doy - currentDayOfYear) <= 3
        }
    }

    /**
     * Purely offline, rule-based title and subtitle generator using Calendar math and folder names.
     */
    private fun generateSmartTitleAndSubtitle(cluster: List<MediaItem>): Pair<String, String> {
        val sorted = cluster.sortedBy { it.dateAdded }
        val firstMs = (sorted.firstOrNull()?.dateAdded ?: 0L) * 1000L
        val lastMs = (sorted.lastOrNull()?.dateAdded ?: 0L) * 1000L

        val calStart = Calendar.getInstance().apply { timeInMillis = firstMs }
        val calEnd = Calendar.getInstance().apply { timeInMillis = lastMs }
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)

        val startDay = calStart.get(Calendar.DAY_OF_YEAR)
        val endDay = calEnd.get(Calendar.DAY_OF_YEAR)
        val year = calStart.get(Calendar.YEAR)
        val spanDays = abs(endDay - startDay) + 1

        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(firstMs))
        val shortMonthDay = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(firstMs))

        // 1. Check if the cluster predominantly belongs to a custom user-named folder
        val genericBuckets = setOf(
            "camera", "dcim", "pictures", "0", "internal", "downloads", "download",
            "whatsapp images", "whatsapp video", "whatsapp", "instagram", "screenshots",
            "movies", "video", "videos", "bluetooth", "restored"
        )
        val dominantBucket = sorted
            .map { it.bucketName.trim() }
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in genericBuckets && !it.matches(Regex("^\\d+$")) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= sorted.size / 2 }
            ?.key

        val title = when {
            dominantBucket != null -> {
                if (year < nowYear) "$dominantBucket • $year" else "$dominantBucket Highlights"
            }
            spanDays >= 2 -> {
                val dayOfWeekStart = calStart.get(Calendar.DAY_OF_WEEK)
                val isWeekendSpan = (dayOfWeekStart == Calendar.FRIDAY || dayOfWeekStart == Calendar.SATURDAY) && spanDays <= 3
                if (isWeekendSpan) {
                    "Weekend in $monthName"
                } else {
                    "$monthName Getaway"
                }
            }
            else -> {
                val dayOfWeek = calStart.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
                val avgHour = sorted.map {
                    Calendar.getInstance().apply { timeInMillis = it.dateAdded * 1000L }.get(Calendar.HOUR_OF_DAY)
                }.average()

                when {
                    isWeekend && avgHour >= 17 -> "Weekend Evening • $shortMonthDay"
                    isWeekend -> "Weekend Moments • $shortMonthDay"
                    avgHour < 11 -> "Morning Moments • $shortMonthDay"
                    avgHour in 11.0..16.5 -> "Afternoon Highlights • $shortMonthDay"
                    avgHour in 16.5..20.5 -> "Golden Hour • $shortMonthDay"
                    else -> "Night Memories • $shortMonthDay"
                }
            }
        }

        val subtitle = buildDateRangeSubtitle(sorted)
        return title to subtitle
    }

    private fun buildDateRangeSubtitle(items: List<MediaItem>): String {
        if (items.isEmpty()) return ""
        val sorted = items.sortedBy { it.dateAdded }
        val firstDate = Date(sorted.first().dateAdded * 1000L)
        val lastDate = Date(sorted.last().dateAdded * 1000L)

        val dayFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val shortDayFmt = SimpleDateFormat("MMM d", Locale.getDefault())

        val firstStr = dayFmt.format(firstDate)
        val lastStr = dayFmt.format(lastDate)

        val datePart = if (firstStr == lastStr) {
            firstStr
        } else {
            val cal1 = Calendar.getInstance().apply { time = firstDate }
            val cal2 = Calendar.getInstance().apply { time = lastDate }
            if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)) {
                "${shortDayFmt.format(firstDate)} –$lastStr"
            } else {
                "$firstStr –$lastStr"
            }
        }
        return "$datePart • ${items.size} items"
    }

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

    private fun scoreMediaQuality(item: MediaItem): Double {
        var score = 0.0
        if (item.isFavorite) score += 50.0
        if (!item.mimeType.startsWith("video")) score += 20.0

        val resolution = (item.width.toLong() * item.height.toLong()) / 100_000.0
        score += min(resolution, 40.0)

        val ratio = if (item.height > 0) {
            item.width.toDouble() / item.height.toDouble()
        } else {
            1.0
        }

        if (ratio in 0.5..0.85 || ratio in 1.25..1.85) score += 15.0
        if (item.path.lowercase(Locale.ROOT).contains("screenshot") ||
            item.name.lowercase(Locale.ROOT).contains("screenshot")
        ) {
            score -= 60.0
        }
        return score
    }

    private fun selectBestCover(items: List<MediaItem>): MediaItem? {
        if (items.isEmpty()) return null
        return items.maxByOrNull { scoreMediaQuality(it) }
    }

    companion object {
        const val MAX_AUTO_STORIES = 30
        const val MIN_ITEMS_PER_STORY = 10
        const val MAX_ITEMS_PER_STORY = 50
        const val EVENT_THRESHOLD_MS = 60L * 60L * 1000L
        const val DAILY_REFRESH_MS = 24L * 60L * 60L * 1000L
        const val PERIODIC_CHECK_MS = 60L * 60L * 1000L
        const val MAX_MERGE_GAP_MS = 3L * 24L * 60L * 60L * 1000L
    }
}