@file:Suppress(
    "UnsafeOptInUsageError",
    "UnstableApiUsage",
    "OPT_IN_USAGE",
    "unused",
    "DEPRECATION",
    "BlockingMethodInNonBlockingContext",
    "MemberVisibilityCanBePrivate",
    "OVERRIDE_DEPRECATION"
)
@file:SuppressLint("UnsafeOptInUsageError")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.viewmodel

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gallerybox.data.Album
import com.gallerybox.data.AlbumEntity
import com.gallerybox.data.EditState
import com.gallerybox.data.FavoriteEntity
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.ManualAlbumEntity
import com.gallerybox.data.MediaItem
import com.gallerybox.data.TrashEntity
import com.gallerybox.engine.EditingEngine
import com.gallerybox.engine.GalleryEngine
import com.gallerybox.engine.MediaOpResult
import com.gallerybox.engine.MediaOperationEngine
import com.gallerybox.engine.TargetAlbum
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.ui.screens.trash.TrashCleanupWorker
import com.gallerybox.ui.screens.videoplayer.PremiumRepeatMode
import com.gallerybox.ui.screens.wallpaper.WallpaperTransform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.log2

const val ID_CAMERA = "virtual_camera"
const val ID_RECENT = "virtual_recent"
const val ID_FAVORITES = "virtual_favorites"
const val ID_SCREENSHOTS = "virtual_screenshots"
const val ID_DOWNLOADS = "virtual_downloads"
const val ID_WHATSAPP = "virtual_whatsapp"
const val ID_INSTAGRAM = "virtual_instagram"
const val ID_VIDEOS = "virtual_videos"
const val ID_HIDDEN = "virtual_hidden"

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

sealed class GalleryEvent {
    data class ShowToast(val message: String) : GalleryEvent()
    data class RequestPermission(val intentSender: IntentSender) : GalleryEvent()
    data object OperationSuccess : GalleryEvent()
    data class LaunchIntent(val intent: Intent) : GalleryEvent()
}

sealed interface GalleryViewerState {
    data object Closed : GalleryViewerState
    data object Loading : GalleryViewerState
    data class Open(
        val mediaId: Long,
        val isVideo: Boolean,
        val uri: Uri,
        val zoom: Float = 1f,
        val rotation: Float = 0f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val playbackPosition: Long = 0L,
        val playWhenReady: Boolean = true,
        val playbackSpeed: Float = 1f,
        val controlsVisible: Boolean = true
    ) : GalleryViewerState
    data class Error(val message: String) : GalleryViewerState
}

sealed class FileOperationState {
    data object Idle : FileOperationState()
    data class Processing(
        val phase: String,
        val progressPercentage: Float,
        val itemsProcessed: Int,
        val totalItems: Int
    ) : FileOperationState()
    data object WaitingForPermission : FileOperationState()
    data class Editing(val progress: Float) : FileOperationState()
}

enum class SaveMode { SAVE_AS_NEW, REPLACE_ORIGINAL }
enum class LockType { NONE, PIN, PATTERN }
enum class AlbumSort { DateDesc, DateAsc, NameAsc, NameDesc, SizeDesc, CountDesc, Custom }
enum class PhotoSort { NameAsc, NameDesc, SizeDesc, DateAsc, DateDesc }
enum class MediaTypeFilter { ALL, PHOTOS, VIDEOS }
enum class MergeMode { MOVE, COPY, MOVE_AND_DELETE }

data class ExportAdvanced(val bitrate: Int = 10000000, val fps: Int = 30, val codec: String = "video/avc")
data class MediaIndexes(
    val all: List<MediaItem> = emptyList(),
    val photos: List<MediaItem> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val gifs: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList()
)
private data class FilterState(
    val f: MediaTypeFilter,
    val q: String,
    val s: List<Long>,
    val t: List<TrashEntity>,
    val h: Set<String>,
    val albumId: String? = null
)
data class VideoPlaybackState(
    val uri: String? = null,
    val position: Long = 0L,
    val speed: Float = 1f,
    val playWhenReady: Boolean = true
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    val dao: GalleryDao,
    val editingEngine: EditingEngine,
    val engine: GalleryEngine,
    val mediaOpEngine: MediaOperationEngine
) : AndroidViewModel(application), ComponentCallbacks2 {

    companion object {
        private const val TAG = "GalleryViewModel"
        private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        }
        fun sameDay(a: Long, b: Long): Boolean {
            val df = dateFormat.get() ?: return false
            return df.format(Date(a * 1000L)) == df.format(Date(b * 1000L))
        }
    }

    // --- SHARED WALLPAPER TRANSFORM STATE ---
    private val _transform = MutableStateFlow(WallpaperTransform())
    val transform: StateFlow<WallpaperTransform> = _transform.asStateFlow()

    fun applyGesture(
        zoom: Float,
        panX: Float,
        panY: Float
    ) {
        _transform.update { old ->
            old.copy(
                scale = (old.scale * zoom).coerceIn(1f, 8f),
                offsetX = old.offsetX + panX,
                offsetY = old.offsetY + panY
            )
        }
    }

    fun resetTransform() {
        _transform.value = WallpaperTransform()
    }

    // --- LINKED EDITOR STATE ---
    private val _activeEditingMedia = MutableStateFlow<MediaItem?>(null)
    val activeEditingMedia: StateFlow<MediaItem?> = _activeEditingMedia.asStateFlow()

    /**
     * Opens a [MediaItem] (photo or video) for editing and links it directly with [EditorViewModel].
     */
    fun openEditor(item: MediaItem, editorViewModel: EditorViewModel? = null) {
        if (sharedPlayer?.isPlaying == true) {
            sharedPlayer?.pause()
        }
        _activeEditingMedia.value = item
        editorViewModel?.updateThermalLevel(_thermalState.value)
        editorViewModel?.initializeEditor(item)
    }

    /**
     * Opens a media item by its `mediaId` for editing and links it with [EditorViewModel].
     */
    fun openEditorById(mediaId: Long, editorViewModel: EditorViewModel? = null) {
        val item = getMediaItemById(mediaId) ?: _rawMedia.value.firstOrNull { it.id == mediaId }
        if (item != null) {
            openEditor(item, editorViewModel)
        } else {
            _events.tryEmit(GalleryEvent.ShowToast("Media item not found"))
        }
    }

    fun closeEditor() {
        _activeEditingMedia.value = null
    }

    private val _events = MutableSharedFlow<GalleryEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val busyMutex = Mutex()
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val fileOpMutex = Mutex()
    private val fileOpOwner = Any()
    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState = _fileOperationState.asStateFlow()
    private var fileOperationJob: Job? = null

    private var pendingRollbackUris: List<Uri> = emptyList()
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingMoveIds: Set<Long> = emptySet()
    private var pendingMoveOperation = false
    private var pendingAutoDeleteHandledByOs = false
    private var pendingIsWriteRequest = false
    private var pendingOperationIds: List<Long> = emptyList()
    private var pendingOperationTargetAlbum: TargetAlbum? = null
    private var pendingOperationIsMove: Boolean = false
    private var pendingOperationOnComplete: ((MediaOpResult) -> Unit)? = null
    private var pendingSdAlbumName: String? = null
    private var pendingSdRenameAlbumId: String? = null
    private var pendingSdRenameOldName: String? = null
    private var pendingSdRenameNewName: String? = null

    @Volatile private var pendingInternalTrashEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingInternalTrashIds: Set<Long> = emptySet()

    private val _thermalState = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalState: StateFlow<Int> = _thermalState.asStateFlow()

    private var lastMediaStoreGeneration = 0L
    @Volatile private var lastSyncTime = 0L
    @Volatile private var lastFullScanTime = 0L

    @Volatile private var currentCacheSizeMB = 128
    @Volatile private var currentPageSize = 64

    private val activePagingSources = CopyOnWriteArrayList<MediaPagingSource>()

    private var sharedPlayer: ExoPlayer? = null
    private val prefs = application.getSharedPreferences("media3_prefs", Context.MODE_PRIVATE)

    private val _videoPlaylist = MutableStateFlow<List<String>>(emptyList())
    val videoPlaylist = _videoPlaylist.asStateFlow()
    private val _currentVideoIndex = MutableStateFlow(0)
    val currentVideoIndex = _currentVideoIndex.asStateFlow()
    private val _currentVideoUri = MutableStateFlow<String?>(null)
    val currentVideoUri = _currentVideoUri.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState = _playbackState.asStateFlow()
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()
    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition = _bufferedPosition.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(prefs.getFloat("speed", 1f))
    val playbackSpeed = _playbackSpeed.asStateFlow()
    private val _playbackPitch = MutableStateFlow(prefs.getFloat("pitch", 1f))
    val playbackPitch = _playbackPitch.asStateFlow()

    private val _autoPlayNext = MutableStateFlow(prefs.getBoolean("autoPlayNext", true))
    val autoPlayNext = _autoPlayNext.asStateFlow()
    private val _repeatMode = MutableStateFlow(PremiumRepeatMode.entries[prefs.getInt("autoRepeat", 0)])
    val repeatMode = _repeatMode.asStateFlow()
    private val _backgroundPlay = MutableStateFlow(prefs.getBoolean("backgroundPlay", false))
    val backgroundPlay = _backgroundPlay.asStateFlow()
    private val _audioDelayMs = MutableStateFlow(0f)
    val audioDelayMs = _audioDelayMs.asStateFlow()
    private val _sleepTimerMs = MutableStateFlow<Long?>(null)
    val sleepTimerMs = _sleepTimerMs.asStateFlow()
    private val _videoFormat = MutableStateFlow<Format?>(null)
    val videoFormat = _videoFormat.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            _isPlaying.value = sharedPlayer?.isPlaying == true
            if (state == Player.STATE_READY) {
                _duration.value = sharedPlayer?.duration?.coerceAtLeast(0L) ?: 0L
            }
            if (state == Player.STATE_ENDED) {
                if (_repeatMode.value == PremiumRepeatMode.ALL) {
                    playNextVideo()
                } else if (_repeatMode.value == PremiumRepeatMode.OFF && _autoPlayNext.value) {
                    playNextVideo()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: Media3Item?, reason: Int) {
            val newUri = mediaItem?.localConfiguration?.uri?.toString()
            if (newUri != null) {
                _currentVideoUri.value = newUri
                _currentVideoIndex.value = _videoPlaylist.value.indexOf(newUri).coerceAtLeast(0)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val selectedVideoFormat = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                ?.let { group ->
                    (0 until group.length)
                        .firstOrNull { group.isTrackSelected(it) }
                        ?.let { trackIndex -> group.getTrackFormat(trackIndex) }
                }
            if (selectedVideoFormat != null) {
                _videoFormat.value = selectedVideoFormat
            }
        }
    }

    private var positionUpdateJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun findPersistedSdCardTreeUri(): Uri? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.persistedUriPermissions.firstOrNull { perm ->
            perm.isWritePermission &&
                    perm.uri.authority == "com.android.externalstorage.documents" &&
                    !perm.uri.toString().contains("primary%3A") &&
                    !perm.uri.toString().contains("primary:")
        }?.uri
    }

    private fun createSdCardFolder(treeUri: Uri, trimmed: String): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return false
            if (!root.canWrite()) return false
            val picturesDir = root.findFile("Pictures") ?: root.createDirectory("Pictures") ?: return false
            val albumDir = picturesDir.findFile(trimmed) ?: picturesDir.createDirectory(trimmed)
            albumDir != null
        } catch (e: Exception) {
            Log.e("ALBUM_DEBUG", "SD card folder creation failed", e)
            false
        }
    }

    private fun renameSdCardFolder(oldName: String, newName: String): Boolean {
        val treeUri = findPersistedSdCardTreeUri() ?: return false
        return try {
            val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return false
            val picturesDir = root.findFile("Pictures") ?: return false
            val oldDir = picturesDir.findFile(oldName)
            if (oldDir != null) oldDir.renameTo(newName) else (picturesDir.createDirectory(newName) != null)
        } catch (e: Exception) {
            Log.e("ALBUM_DEBUG", "SD card rename failed", e)
            false
        }
    }

    fun onSafTreeGranted(uri: Uri?) {
        if (uri == null) {
            val pendingCreate = pendingSdAlbumName
            val pendingRename = pendingSdRenameAlbumId
            pendingSdAlbumName = null
            pendingSdRenameAlbumId = null
            pendingSdRenameOldName = null
            pendingSdRenameNewName = null
            val onComplete = pendingOperationOnComplete
            val rollback = pendingRollbackUris
            clearPendingOperationStates()
            _fileOperationState.value = FileOperationState.Idle
            _isBusy.value = false
            endFileOperation()
            if (rollback.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) { mediaOpEngine.rollback(rollback) }
            }
            onComplete?.invoke(MediaOpResult.Cancelled)
            _events.tryEmit(
                GalleryEvent.ShowToast(
                    if (pendingCreate != null || pendingRename != null) {
                        "SD card access is needed to create or rename albums there"
                    } else {
                        "SD card access denied. Operation cancelled."
                    }
                )
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaOpEngine.saveSafTreeUri(uri)
            } catch (e: Exception) {
                Log.e("ALBUM_DEBUG", "Failed to persist SAF permission", e)
            }

            val createName = pendingSdAlbumName
            val renameId = pendingSdRenameAlbumId
            val renameOldName = pendingSdRenameOldName
            val renameNewName = pendingSdRenameNewName

            pendingSdAlbumName = null
            pendingSdRenameAlbumId = null
            pendingSdRenameOldName = null
            pendingSdRenameNewName = null

            if (createName != null) {
                val created = createSdCardFolder(uri, createName)
                val newId = "manual_${System.currentTimeMillis()}"
                dao.insertManualAlbum(ManualAlbumEntity(id = newId, name = createName, hasBeenUsed = false))
                mediaOpEngine.markAlbumAsSdCard(newId)
                _events.emit(
                    if (created) GalleryEvent.ShowToast("Album '$createName' created on SD card!")
                    else GalleryEvent.ShowToast("Couldn't create the folder on SD card")
                )
            } else if (renameId != null && renameOldName != null && renameNewName != null) {
                val renamed = renameSdCardFolder(renameOldName, renameNewName)

                try {
                    dao.deleteManualAlbum(renameId)
                } catch (_: Exception) {
                }
                val newManualId = "manual_${System.currentTimeMillis()}"
                dao.insertManualAlbum(ManualAlbumEntity(id = newManualId, name = renameNewName, hasBeenUsed = false))
                mediaOpEngine.markAlbumAsSdCard(newManualId)

                _events.emit(
                    if (renamed) GalleryEvent.ShowToast("Album renamed")
                    else GalleryEvent.ShowToast("Renamed in app, but folder update failed on SD card")
                )
                endFileOperation()
            } else if (pendingOperationTargetAlbum != null && pendingOperationIds.isNotEmpty()) {
                saveSafTreeUri(uri)
            }
        }
    }

    private fun tryBeginFileOperation(): Boolean = fileOpMutex.tryLock(fileOpOwner)

    private fun endFileOperation() {
        runCatching { if (fileOpMutex.holdsLock(fileOpOwner)) fileOpMutex.unlock(fileOpOwner) }
    }

    fun getPlayer(): ExoPlayer {
        if (sharedPlayer == null) {
            sharedPlayer = ExoPlayer.Builder(getApplication())
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = true
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true
                    )
                    setHandleAudioBecomingNoisy(true)
                    volume = 1f
                    addListener(playerListener)
                    playbackParameters = PlaybackParameters(_playbackSpeed.value, _playbackPitch.value)
                }
            startPositionUpdates()
        }
        return sharedPlayer!!
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (isActive) {
                sharedPlayer?.let { p ->
                    _currentPosition.value = p.currentPosition
                    _bufferedPosition.value = p.bufferedPosition

                    _sleepTimerMs.value?.let { sleepTime ->
                        if (System.currentTimeMillis() >= sleepTime) {
                            p.pause()
                            _sleepTimerMs.value = null
                            _events.emit(GalleryEvent.ShowToast("Sleep timer ended"))
                        }
                    }
                }
                delay(if (_isPlaying.value) 200L else 500L)
            }
        }
    }

    fun resetPlayer(player: ExoPlayer) {
        if (sharedPlayer === player) {
            sharedPlayer?.pause()
            sharedPlayer?.playWhenReady = false
            sharedPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun stepFrame(player: Player, forward: Boolean) {
        try {
            val fps = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.mediaTrackGroup?.getFormat(0)?.frameRate?.takeIf { it > 0f } ?: 30f

            val frameDuration = (1000f / fps).toLong()
            val newPosition = if (forward) {
                player.currentPosition + frameDuration
            } else {
                player.currentPosition - frameDuration
            }
            val target = newPosition.coerceIn(0L, player.duration.coerceAtLeast(0L))

            player.seekTo(target)
            player.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Frame step failed", e)
        }
    }

    val usageStatsMap = dao.getAllUsageStats()
        .map { list -> list.associateBy { it.mediaId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _activeFilter = MutableStateFlow(MediaTypeFilter.ALL)
    val activeFilter = _activeFilter.asStateFlow()
    private val _activeSort = MutableStateFlow(PhotoSort.DateDesc)
    val activeSort = _activeSort.asStateFlow()
    private val _albumSort = MutableStateFlow(AlbumSort.Custom)
    val albumSort = _albumSort.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _viewerState = MutableStateFlow<GalleryViewerState>(GalleryViewerState.Closed)
    val viewerState = _viewerState.asStateFlow()
    private val _hiddenAlbums = MutableStateFlow<Set<String>>(emptySet())
    val hiddenAlbums = _hiddenAlbums.asStateFlow()

    private val _rawMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val rawMedia = _rawMedia.asStateFlow()
    private val _mediaIndexes = MutableStateFlow(MediaIndexes())
    private val _mediaMap = MutableStateFlow<HashMap<Long, MediaItem>>(HashMap())
    val mediaMap = _mediaMap.asStateFlow()

    private val searchCache = LruCache<String, List<MediaItem>>(50)
    private val sortedCache = LruCache<String, List<MediaItem>>(20)

    private val albumPreviewCacheMap = ConcurrentHashMap<String, MutableList<Uri>>()
    private val _albumPreviewCache = MutableStateFlow<Map<String, List<Uri>>>(emptyMap())
    val albumPreviewMap = _albumPreviewCache.asStateFlow()

    private val _allAlbumsState = MutableStateFlow<List<Album>>(emptyList())
    val allAlbumsState = _allAlbumsState.asStateFlow()
    private val _albumsState = MutableStateFlow<List<Album>>(emptyList())
    val albumsState = _albumsState.asStateFlow()

    val trashBin = dao.getTrash().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favoriteIds = dao.getFavoriteIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val secureIds = dao.getSecureMediaIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenMedia = combine(_rawMedia, secureIds) { raw, secure ->
        val secureSet = secure.toSet()
        raw.filter { secureSet.contains(it.id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val albumMeta = dao.getAlbumMeta().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val manualAlbums = dao.getManualAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _duplicates = MutableStateFlow<List<List<MediaItem>>>(emptyList())
    val duplicates = _duplicates.asStateFlow()

    val media: StateFlow<List<MediaItem>> = _mediaIndexes
        .map { it.all }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updatePlaybackState(uri: String?, position: Long, speed: Float, playWhenReady: Boolean) {
        if (uri != null) {
            prefs.edit { putLong(uri, position) }
        }
    }

    fun setVideoPlaylist(playlist: List<String>) {
        if (_videoPlaylist.value != playlist) {
            _videoPlaylist.value = playlist
        }
    }

    fun openVideo(uri: String) {
        val player = getPlayer()

        var playlist = _videoPlaylist.value

        if (playlist.isEmpty()) {
            playlist = _mediaIndexes.value.videos.map { it.uri.toString() }
            _videoPlaylist.value = playlist
        }

        if (playlist.isEmpty()) {
            Log.e(TAG, "Video playlist is empty")
            return
        }

        val index = playlist.indexOf(uri)

        if (index == -1) {
            Log.e(TAG, "Video not found in playlist: $uri")
            return
        }

        _currentVideoIndex.value = index
        _currentVideoUri.value = uri

        val mediaItems = playlist.map { Media3Item.fromUri(Uri.parse(it)) }
        val savedPosition = prefs.getLong(uri, 0L)

        player.stop()
        player.clearMediaItems()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        player.setMediaItems(mediaItems, index, savedPosition)
        player.volume = 1f
        player.playWhenReady = true
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val p = sharedPlayer ?: return
        if (_playbackState.value == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        sharedPlayer?.seekTo(positionMs)
    }

    fun seekForward(dynamicSeekMs: Long = 10000L) {
        val p = sharedPlayer ?: return
        p.seekTo((p.currentPosition + dynamicSeekMs).coerceAtMost(_duration.value))
    }

    fun seekBackward(dynamicSeekMs: Long = 10000L) {
        val p = sharedPlayer ?: return
        p.seekTo((p.currentPosition - dynamicSeekMs).coerceAtLeast(0L))
    }

    fun hasNextVideo(): Boolean {
        return _currentVideoIndex.value + 1 < _videoPlaylist.value.size
    }

    fun hasPreviousVideo(): Boolean {
        return _currentVideoIndex.value > 0
    }

    fun playNextVideo() {
        var next = _currentVideoIndex.value + 1
        while (next < _videoPlaylist.value.size && isHiddenUri(_videoPlaylist.value[next])) next++

        if (next >= _videoPlaylist.value.size) {
            if (_repeatMode.value == PremiumRepeatMode.ALL && _videoPlaylist.value.isNotEmpty()) {
                next = 0
                while (next < _videoPlaylist.value.size && isHiddenUri(_videoPlaylist.value[next])) next++
                if (next >= _videoPlaylist.value.size) return
            } else {
                return
            }
        }
        openVideo(_videoPlaylist.value[next])
    }

    fun playPreviousVideo() {
        if ((sharedPlayer?.currentPosition ?: 0) > 3000L) {
            sharedPlayer?.seekTo(0)
            return
        }
        var prev = _currentVideoIndex.value - 1
        while (prev >= 0 && isHiddenUri(_videoPlaylist.value[prev])) prev--
        if (prev < 0) {
            if (_repeatMode.value == PremiumRepeatMode.ALL && _videoPlaylist.value.isNotEmpty()) {
                prev = _videoPlaylist.value.lastIndex
                while (prev >= 0 && isHiddenUri(_videoPlaylist.value[prev])) prev--
                if (prev < 0) return
            } else {
                return
            }
        }
        openVideo(_videoPlaylist.value[prev])
    }

    private fun isHiddenUri(uriStr: String): Boolean {
        val bucketId = _rawMedia.value.firstOrNull { it.uri.toString() == uriStr }?.bucketId ?: return false
        return _hiddenAlbums.value.contains(bucketId)
    }

    private fun purgeHiddenFromPlayback() {
        val hidden = _hiddenAlbums.value
        if (hidden.isEmpty()) return

        val bucketByUri = _rawMedia.value.associate { it.uri.toString() to it.bucketId }

        val filteredPlaylist = _videoPlaylist.value.filterNot { uriStr ->
            bucketByUri[uriStr]?.let { hidden.contains(it) } == true
        }
        if (filteredPlaylist.size != _videoPlaylist.value.size) {
            _videoPlaylist.value = filteredPlaylist
        }

        val currentUri = _currentVideoUri.value
        val currentIsHidden = currentUri != null && bucketByUri[currentUri]?.let { hidden.contains(it) } == true

        if (currentIsHidden) {
            sharedPlayer?.pause()
            sharedPlayer?.stop()
            sharedPlayer?.clearMediaItems()
            _currentVideoUri.value = null
            _currentVideoIndex.value = 0

            if (_viewerState.value is GalleryViewerState.Open) {
                _viewerState.value = GalleryViewerState.Closed
            }

            viewModelScope.launch {
                _events.emit(GalleryEvent.ShowToast("Video hidden — album is no longer visible"))
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        sharedPlayer?.playbackParameters = PlaybackParameters(speed, _playbackPitch.value)
        prefs.edit { putFloat("speed", speed) }
    }

    fun increaseSpeed() {
        setPlaybackSpeed((_playbackSpeed.value * 2f).coerceAtMost(8f))
    }

    fun resetSpeed() {
        val savedSpeed = prefs.getFloat("speed", 1f)
        _playbackSpeed.value = savedSpeed
        sharedPlayer?.playbackParameters = PlaybackParameters(savedSpeed, _playbackPitch.value)
    }

    fun setAutoPlayNext(enabled: Boolean) {
        _autoPlayNext.value = enabled
        prefs.edit { putBoolean("autoPlayNext", enabled) }
        if (enabled) {
            cycleRepeatMode(PremiumRepeatMode.OFF)
        }
    }

    fun cycleRepeatMode(mode: PremiumRepeatMode? = null) {
        val newMode = mode ?: PremiumRepeatMode.entries[(_repeatMode.value.ordinal + 1) % PremiumRepeatMode.entries.size]
        _repeatMode.value = newMode
        prefs.edit { putInt("autoRepeat", newMode.ordinal) }
        sharedPlayer?.repeatMode = when (newMode) {
            PremiumRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PremiumRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PremiumRepeatMode.ALL -> Player.REPEAT_MODE_OFF
        }
        if (newMode != PremiumRepeatMode.OFF) {
            setAutoPlayNext(false)
        }
    }

    fun setBackgroundPlay(enabled: Boolean) {
        _backgroundPlay.value = enabled
        prefs.edit { putBoolean("backgroundPlay", enabled) }
    }

    fun startSleepTimer(minutes: Int) {
        _sleepTimerMs.value = System.currentTimeMillis() + (minutes * 60 * 1000L)
    }

    fun cancelSleepTimer() {
        _sleepTimerMs.value = null
    }

    fun remainingSleepTime(): Long? {
        return _sleepTimerMs.value?.let { maxOf(0L, it - System.currentTimeMillis()) }
    }

    fun setAudioDelay(delayMs: Float) {
        _audioDelayMs.value = delayMs
    }

    fun savePlaybackPosition() {
        val uri = _currentVideoUri.value ?: return
        val position = sharedPlayer?.currentPosition ?: return
        prefs.edit { putLong(uri, position) }
    }

    fun restorePlaybackPosition() {
        val uri = _currentVideoUri.value ?: return
        val position = prefs.getLong(uri, 0L)
        sharedPlayer?.seekTo(position)
    }

    private fun getThermalFactor(): Double {
        return when (_thermalState.value) {
            PowerManager.THERMAL_STATUS_NONE -> 1.0
            PowerManager.THERMAL_STATUS_LIGHT -> 0.9
            PowerManager.THERMAL_STATUS_MODERATE -> 0.75
            PowerManager.THERMAL_STATUS_SEVERE -> 0.5
            PowerManager.THERMAL_STATUS_CRITICAL -> 0.25
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> 0.1
            else -> 1.0
        }
    }

    private fun getMemoryFactor(): Double {
        val actManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.availMem.toDouble() / memInfo.totalMem.toDouble()
    }

    private fun recalculateDynamicScaling() {
        val actManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val ramMB = (memInfo.totalMem / (1024 * 1024)).toDouble()
        val thermal = getThermalFactor()
        val memory = memInfo.availMem.toDouble() / memInfo.totalMem.toDouble()
        currentCacheSizeMB = minOf((ramMB * thermal * memory).toInt(), 512)
        currentPageSize = 64
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            searchCache.resize((currentCacheSizeMB / 2).coerceIn(50, 500))
            sortedCache.resize(currentCacheSizeMB.coerceIn(20, 250))
        }
    }

    val processedMedia: StateFlow<List<MediaItem>> = combine(
        _mediaIndexes,
        _searchQuery.debounce(300),
        _activeFilter,
        _activeSort
    ) { indexes, query, filter, sort ->
        val q = query.trim().lowercase()
        val cacheKey = "${lastMediaStoreGeneration}_${filter.name}_${sort.name}_$q"
        val cached = sortedCache.get(cacheKey)
        if (cached != null) return@combine cached

        val usageStats = usageStatsMap.value

        var result = when (filter) {
            MediaTypeFilter.ALL -> indexes.all
            MediaTypeFilter.PHOTOS -> indexes.photos
            MediaTypeFilter.VIDEOS -> indexes.videos
        }

        if (q.isNotBlank()) {
            result = result.mapNotNull { item ->
                var score = 0.0
                if (item.name.lowercase().contains(q)) score += 5.0
                if (item.bucketName.lowercase().contains(q)) score += 3.0
                if (item.isFavorite) score += 2.0

                val ageDays = maxOf(0L, (System.currentTimeMillis() / 1000L - item.dateAdded)) / 86400.0
                val recencyScore = exp(-ageDays / 30.0)
                score += (2.0 * recencyScore)

                val usageData = usageStats[item.id]
                if (usageData != null) {
                    val daysSinceOpen = maxOf(0L, System.currentTimeMillis() - usageData.lastOpened) / 86400000.0
                    val decayedUsage = usageData.openCount * exp(-daysSinceOpen / 90.0)
                    score += log2(decayedUsage + 1.0)
                }
                if (score > 0) Pair(item, score) else null
            }.sortedByDescending { it.second }.map { it.first }
        }

        result = when (sort) {
            PhotoSort.NameAsc -> result.sortedBy { it.name }
            PhotoSort.NameDesc -> result.sortedByDescending { it.name }
            PhotoSort.SizeDesc -> result.sortedByDescending { it.size }
            PhotoSort.DateAsc -> result.sortedBy { it.dateAdded }
            PhotoSort.DateDesc -> result.sortedByDescending { it.dateAdded }
        }

        sortedCache.put(cacheKey, result)
        result
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pagedMedia: Flow<PagingData<GalleryGridItem>> = combine(
        _activeFilter,
        _searchQuery,
        secureIds,
        trashBin,
        _hiddenAlbums
    ) { f, q, sec, trash, hidden ->
        FilterState(f, q, sec, trash, hidden)
    }.distinctUntilChanged().flatMapLatest { state ->
        val pageSize = currentPageSize
        val prefetch = maxOf(20, (pageSize * 0.25 * getThermalFactor()).toInt())

        activePagingSources.removeAll { it.invalid }

        Pager(
            PagingConfig(
                pageSize = pageSize,
                prefetchDistance = prefetch,
                enablePlaceholders = false,
                initialLoadSize = pageSize,
                maxSize = pageSize * 4
            )
        ) {
            MediaPagingSource(
                contentResolver = getApplication<Application>().contentResolver,
                filter = state.f,
                query = state.q,
                albumId = null,
                hiddenAlbums = state.h,
                sort = _activeSort.value
            ).also { activePagingSources.add(it) }
        }.flow.map { pd ->
            val secSet = state.s.toHashSet()
            val trashSet = state.t.map { it.contentUri }.toHashSet()

            pd.filter { item ->
                item.uri != Uri.EMPTY && !secSet.contains(item.id) && !trashSet.contains(item.uri.toString())
            }
                .map { GalleryGridItem.Media(it) as GalleryGridItem }
                .run {
                    if (_activeSort.value == PhotoSort.DateDesc || _activeSort.value == PhotoSort.DateAsc) {
                        insertSeparators { b: GalleryGridItem?, a: GalleryGridItem? ->
                            val bMedia = b as? GalleryGridItem.Media
                            val aMedia = a as? GalleryGridItem.Media
                            if (aMedia == null) null
                            else if (bMedia == null || !sameDay(bMedia.item.dateAdded, aMedia.item.dateAdded)) {
                                val headerTitle = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
                                    .format(Date(aMedia.item.dateAdded * 1000L))
                                GalleryGridItem.Header("header_${aMedia.item.dateAdded}", headerTitle, 0)
                            } else null
                        }
                    } else this
                }
        }
    }.cachedIn(viewModelScope)

    fun getPagedMediaForAlbumStream(
        albumId: String,
        filter: MediaTypeFilter,
        query: String,
        sort: PhotoSort = PhotoSort.DateDesc
    ): Flow<PagingData<GalleryGridItem>> {
        return combine(secureIds, trashBin, _hiddenAlbums, favoriteIds) { sec, trash, hidden, favs ->
            FilterState(filter, query, if (albumId == ID_HIDDEN) favs else sec, trash, hidden, albumId)
        }.distinctUntilChanged().flatMapLatest { state ->
            val pageSize = currentPageSize
            val prefetch = maxOf(20, (pageSize * 0.25 * getThermalFactor()).toInt())

            activePagingSources.removeAll { it.invalid }

            Pager(
                PagingConfig(
                    pageSize = pageSize,
                    prefetchDistance = prefetch,
                    enablePlaceholders = false,
                    initialLoadSize = pageSize,
                    maxSize = pageSize * 4
                )
            ) {
                MediaPagingSource(
                    contentResolver = getApplication<Application>().contentResolver,
                    filter = state.f,
                    query = state.q,
                    albumId = albumId,
                    hiddenAlbums = if (albumId == ID_HIDDEN) emptySet() else state.h,
                    favoriteIds = favoriteIds.value,
                    secureIds = secureIds.value,
                    sort = sort
                ).also { activePagingSources.add(it) }
            }.flow.map { pd ->
                val secSet = secureIds.value.toHashSet()
                val trashSet = state.t.map { it.contentUri }.toHashSet()
                pd.filter { item ->
                    item.uri != Uri.EMPTY &&
                            !trashSet.contains(item.uri.toString()) &&
                            (albumId == ID_HIDDEN || !secSet.contains(item.id))
                }
                    .map { GalleryGridItem.Media(it) as GalleryGridItem }
                    .run {
                        if (sort == PhotoSort.DateDesc || sort == PhotoSort.DateAsc) {
                            insertSeparators { b: GalleryGridItem?, a: GalleryGridItem? ->
                                val bMedia = b as? GalleryGridItem.Media
                                val aMedia = a as? GalleryGridItem.Media
                                if (aMedia == null) null
                                else if (bMedia == null || !sameDay(bMedia.item.dateAdded, aMedia.item.dateAdded)) {
                                    val headerTitle = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
                                        .format(Date(aMedia.item.dateAdded * 1000L))
                                    GalleryGridItem.Header("header_${aMedia.item.dateAdded}", headerTitle, 0)
                                } else null
                            }
                        } else this
                    }
            }
        }.cachedIn(viewModelScope)
    }

    private var reloadJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        @Deprecated("Deprecated in Java")
        override fun onChange(selfChange: Boolean) {
            reloadJob?.cancel()
            reloadJob = viewModelScope.launch {
                delay(500)
                safeLoadLibrary()
            }
        }
    }

    init {
        application.registerComponentCallbacks(this)
        recalculateDynamicScaling()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.addThermalStatusListener { status ->
                _thermalState.value = status
                recalculateDynamicScaling()
            }
        }

        _hiddenAlbums.value = engine.getHiddenAlbums()

        val resolver = application.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.registerContentObserver(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                true,
                mediaObserver
            )
        }

        clearTempVaultCache()
        viewModelScope.launch { safeLoadLibrary() }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                schedulePeriodicTrashCleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Worker schedule fail", e)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            combine(trashBin, favoriteIds, secureIds, albumMeta, _hiddenAlbums, _albumSort, manualAlbums) { _ -> }
                .debounce(250)
                .collectLatest {
                    if (_rawMedia.value.isNotEmpty()) {
                        rebuildIndexesAndAlbums(_rawMedia.value)
                        invalidatePagingSources()
                    }
                }
        }

        viewModelScope.launch(Dispatchers.Default) {
            processedMedia.collect { list ->
                val newVideoPlaylist = list.filter { it.isVideo }.map { it.uri.toString() }

                if (_viewerState.value !is GalleryViewerState.Open || _videoPlaylist.value.size <= 1) {
                    if (_videoPlaylist.value != newVideoPlaylist && newVideoPlaylist.isNotEmpty()) {
                        _videoPlaylist.value = newVideoPlaylist

                        val currentOpenUri = _currentVideoUri.value
                        if (currentOpenUri != null) {
                            val updatedIndex = newVideoPlaylist.indexOfFirst { Uri.parse(it) == Uri.parse(currentOpenUri) }
                            if (updatedIndex != -1) {
                                _currentVideoIndex.value = updatedIndex
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        recalculateDynamicScaling()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            searchCache.evictAll()
            sortedCache.evictAll()
            albumPreviewCacheMap.clear()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        recalculateDynamicScaling()
    }

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    fun forceSync() {
        viewModelScope.launch { safeLoadLibrary(forceRefresh = true) }
    }

    fun refreshAfterFileOperation() {
        viewModelScope.launch {
            delay(500)
            safeLoadLibrary(forceRefresh = true)
            delay(900)
            safeLoadLibrary(forceRefresh = true)
        }
    }

    fun refreshData() = forceSync()

    @Volatile private var isReloading = false
    private suspend fun safeLoadLibrary(forceRefresh: Boolean = false) {
        if (isReloading) return
        val now = System.currentTimeMillis()

        if (!forceRefresh && _rawMedia.value.isNotEmpty() && (now - lastSyncTime < 5000L)) {
            _isBusy.value = false
            return
        }

        if (now - lastSyncTime < 150L && !forceRefresh) return

        val memoryPressure = 1.0 - getMemoryFactor()

        if (!forceRefresh && (_thermalState.value >= PowerManager.THERMAL_STATUS_SEVERE || memoryPressure > 0.85)) return

        lastSyncTime = now
        isReloading = true

        busyMutex.withLock {
            _isBusy.value = true
            try {
                val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val needsFullScan = forceRefresh || (now - lastFullScanTime > 30 * 60 * 1000L)

                val localMedia = if (isAndroid11Plus && !needsFullScan && _rawMedia.value.isNotEmpty()) {
                    val fetched = withContext(Dispatchers.IO) {
                        engine.fetchIncrementalMedia(lastMediaStoreGeneration)
                    }
                    lastMediaStoreGeneration = MediaStore.getGeneration(
                        getApplication<Application>(),
                        MediaStore.VOLUME_EXTERNAL
                    )
                    val merged = java.util.HashMap<Long, MediaItem>(_rawMedia.value.size + fetched.size)
                    _rawMedia.value.forEach { merged[it.id] = it }
                    fetched.forEach { merged[it.id] = it }
                    merged.values.toList()
                } else {
                    lastFullScanTime = now
                    withContext(Dispatchers.IO) { engine.fetchAllMedia() }.also {
                        if (isAndroid11Plus) {
                            lastMediaStoreGeneration = MediaStore.getGeneration(
                                getApplication<Application>(),
                                MediaStore.VOLUME_EXTERNAL
                            )
                        }
                    }
                }

                _rawMedia.value = localMedia
                rebuildIndexesAndAlbums(localMedia)
                invalidatePagingSources()
            } catch (e: Exception) {
                _events.tryEmit(GalleryEvent.ShowToast("Library load failed: ${e.localizedMessage}"))
            } finally {
                _isBusy.value = false
                isReloading = false
            }
        }
    }

    fun scanForDuplicates() = viewModelScope.launch(Dispatchers.Default) {
        val memoryPressure = 1.0 - getMemoryFactor()

        if (_thermalState.value >= PowerManager.THERMAL_STATUS_MODERATE || memoryPressure > 0.85) {
            _events.emit(GalleryEvent.ShowToast("System busy or warm. Scan paused."))
            return@launch
        }

        _isBusy.value = true
        val allImages = _mediaIndexes.value.photos
        val cores = Runtime.getRuntime().availableProcessors()
        val activeThreads = maxOf(1, ((cores - 1) * getThermalFactor()).toInt())

        val sizeGrouped = allImages.groupBy { it.size }.filter { it.value.size > 1 }.values.flatten()
        val partialHashes = ConcurrentHashMap<String, MutableList<MediaItem>>()

        withContext(Dispatchers.IO.limitedParallelism(activeThreads)) {
            sizeGrouped.map { item ->
                async {
                    try {
                        if (item.size < 25_000_000) {
                            val resolver = getApplication<Application>().contentResolver
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            resolver.openInputStream(item.uri)?.use { input ->
                                BitmapFactory.decodeStream(input, null, options)
                            }
                            val resolutionKey = "${options.outWidth}x${options.outHeight}"

                            val partialMd = MessageDigest.getInstance("SHA-256")
                            resolver.openInputStream(item.uri)?.use { input ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    partialMd.update(buffer, 0, bytesRead)
                                }
                            }
                            val partialHash = "$resolutionKey-" + partialMd.digest().joinToString("") { "%02x".format(it) }
                            partialHashes.getOrPut(partialHash) {
                                Collections.synchronizedList(mutableListOf())
                            }.add(item)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Partial hash failed for ${item.name}", e)
                    }
                }
            }.awaitAll()
        }

        val collisionCandidates = partialHashes.values.filter { it.size > 1 }
        val finalHashes = ConcurrentHashMap<String, MutableList<MediaItem>>()

        withContext(Dispatchers.IO.limitedParallelism(activeThreads)) {
            collisionCandidates.flatten().map { item ->
                async {
                    try {
                        val resolver = getApplication<Application>().contentResolver
                        val fullMd = MessageDigest.getInstance("SHA-256")
                        resolver.openInputStream(item.uri)?.use { input ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                fullMd.update(buffer, 0, bytesRead)
                            }
                        }
                        val fullHash = fullMd.digest().joinToString("") { "%02x".format(it) }
                        finalHashes.getOrPut(fullHash) {
                            Collections.synchronizedList(mutableListOf())
                        }.add(item)
                    } catch (e: Exception) {
                        Log.e(TAG, "Full hash failed for ${item.name}", e)
                    }
                }
            }.awaitAll()
        }

        _duplicates.value = finalHashes.values.filter { it.size > 1 }
        _isBusy.value = false
        _events.emit(GalleryEvent.ShowToast("Scan complete. Found ${_duplicates.value.size} duplicate sets."))
    }

    private fun cacheAlbumPreviews(validMedia: List<MediaItem>) {
        albumPreviewCacheMap.clear()

        for (item in validMedia) {
            val bList = albumPreviewCacheMap.getOrPut(item.bucketId) { mutableListOf() }
            if (bList.size < 4) bList.add(item.uri)

            val bName = item.bucketName
            if (bName.contains("Camera", true) || bName.contains("DCIM", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_CAMERA) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (albumPreviewCacheMap.getOrPut(ID_RECENT) { mutableListOf() }.size < 4) {
                albumPreviewCacheMap[ID_RECENT]!!.add(item.uri)
            }
            if (favoriteIds.value.contains(item.id) || item.isFavorite) {
                val l = albumPreviewCacheMap.getOrPut(ID_FAVORITES) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Screenshot", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_SCREENSHOTS) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("WhatsApp", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_WHATSAPP) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Instagram", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_INSTAGRAM) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
            if (bName.contains("Download", true)) {
                val l = albumPreviewCacheMap.getOrPut(ID_DOWNLOADS) { mutableListOf() }
                if (l.size < 4) l.add(item.uri)
            }
        }
        _albumPreviewCache.value = albumPreviewCacheMap.toMap()
    }

    fun toggleHiddenAlbum(albumId: String) {
        _hiddenAlbums.update { current ->
            val updated = if (current.contains(albumId)) current - albumId else current + albumId
            engine.setHiddenAlbums(updated)
            updated
        }
        invalidatePagingSources()
        purgeHiddenFromPlayback()
    }

    fun hideAlbums(albumIds: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            engine.hideAlbums(albumIds)
            _hiddenAlbums.update { it + albumIds }
        } finally {
            refreshAfterFileOperation()
            purgeHiddenFromPlayback()
        }
    }.let { Unit }

    fun clearDuplicates() {
        _duplicates.value = emptyList()
    }

    fun getPagedMediaForAlbum(albumId: String): Flow<PagingData<GalleryGridItem>> = _mediaIndexes.map { indexes ->
        val filtered = when (albumId) {
            ID_RECENT -> indexes.recent
            ID_FAVORITES -> indexes.all.filter { favoriteIds.value.contains(it.id) }
            else -> emptyList()
        }
        PagingData.from(filtered.map { GalleryGridItem.Media(it) })
    }

    private suspend fun updateMediaLocally(id: Long, update: (MediaItem) -> MediaItem) = withContext(Dispatchers.Default) {
        val current = _mediaMap.value[id] ?: return@withContext
        val updated = update(current)
        _mediaMap.value = java.util.HashMap(_mediaMap.value).apply { put(id, updated) }
        _mediaIndexes.update { old ->
            MediaIndexes(
                all = old.all.map { if (it.id == id) updated else it },
                photos = old.photos.map { if (it.id == id) updated else it },
                videos = old.videos.map { if (it.id == id) updated else it },
                gifs = old.gifs.map { if (it.id == id) updated else it },
                recent = old.recent.map { if (it.id == id) updated else it }
            )
        }
        sortedCache.evictAll()
        searchCache.evictAll()
        invalidatePagingSources()
    }

    private suspend fun removeMediaLocally(ids: Set<Long>) = withContext(Dispatchers.Default) {
        _mediaMap.value = java.util.HashMap(_mediaMap.value).apply { ids.forEach { remove(it) } }
        _mediaIndexes.update { old ->
            MediaIndexes(
                all = old.all.filterNot { ids.contains(it.id) },
                photos = old.photos.filterNot { ids.contains(it.id) },
                videos = old.videos.filterNot { ids.contains(it.id) },
                gifs = old.gifs.filterNot { ids.contains(it.id) },
                recent = old.recent.filterNot { ids.contains(it.id) }
            )
        }
        sortedCache.evictAll()
        searchCache.evictAll()
        invalidatePagingSources()
    }

    fun saveMedia(
        originalMediaId: Long,
        saveMode: SaveMode = SaveMode.SAVE_AS_NEW,
        editState: EditState,
        exportAsSticker: Boolean = false
    ) {
        if (!tryBeginFileOperation()) return
        val item = getMediaItemById(originalMediaId) ?: _rawMedia.value.firstOrNull { it.id == originalMediaId }
        if (item == null) {
            _events.tryEmit(GalleryEvent.ShowToast("Error: Cannot find original media"))
            endFileOperation()
            return
        }

        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _fileOperationState.value = FileOperationState.Editing(0f)
            try {
                delay(100)
                val newFile = editingEngine.saveMedia(
                    uri = item.uri,
                    state = editState,
                    isVideo = item.isVideo,
                    asSticker = exportAsSticker
                ) { progress ->
                    _fileOperationState.value = FileOperationState.Editing(progress)
                }
                if (newFile == null) {
                    _events.tryEmit(GalleryEvent.ShowToast("Export failed"))
                } else {
                    onMediaExported(
                        newFile,
                        if (saveMode == SaveMode.REPLACE_ORIGINAL) item.id else null,
                        "Saved to Gallery!"
                    )
                }
            } catch (e: Exception) {
                _events.tryEmit(GalleryEvent.ShowToast("Error saving edits"))
            } finally {
                _fileOperationState.value = FileOperationState.Idle
                _isBusy.value = false
                endFileOperation()
            }
        }
    }

    fun onMediaExported(
        newFile: File,
        originalMediaId: Long? = null,
        customMessage: String = "Media saved to gallery successfully!"
    ) {
        MediaScannerConnection.scanFile(
            getApplication<Application>(),
            arrayOf(newFile.absolutePath),
            null
        ) { _, _ ->
            refreshAfterFileOperation()
        }
        viewModelScope.launch(Dispatchers.Main) {
            _events.emit(GalleryEvent.ShowToast(customMessage))
            if (originalMediaId != null) {
                val originalItem = _mediaMap.value[originalMediaId]
                    ?: _rawMedia.value.firstOrNull { it.id == originalMediaId }
                if (originalItem != null) {
                    moveToTrashInternal(listOf(originalItem))
                } else {
                    refreshAfterFileOperation()
                }
            } else {
                refreshAfterFileOperation()
            }
        }
    }

    internal fun moveToTrashInternal(items: List<MediaItem>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch
        val resolver = getApplication<Application>().contentResolver
        val trashItems = items.map { media ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = media.path,
                contentUri = media.uri.toString(),
                mediaType = if (media.isVideo) "video" else "image",
                name = media.name,
                size = media.size
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, items.map { it.uri }, true).intentSender
                pendingInternalTrashEntities = trashItems
                pendingInternalTrashIds = items.map { it.id }.toSet()
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create trash request", e)
                _events.emit(GalleryEvent.ShowToast("Failed to move to trash"))
            }
        } else {
            val successfulUris = mutableListOf<Uri>()
            items.forEach { media ->
                try {
                    if (resolver.delete(media.uri, null, null) > 0) successfulUris.add(media.uri)
                } catch (_: Exception) {
                }
            }
            val verified = trashItems.filter { e -> successfulUris.any { it.toString() == e.contentUri } }
            if (verified.isNotEmpty()) dao.insertTrashItemsBulk(verified)
            removeMediaLocally(
                items.filter { m -> successfulUris.any { it.toString() == m.uri.toString() } }
                    .map { it.id }
                    .toSet()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        getApplication<Application>().unregisterComponentCallbacks(this)
        clearTempVaultCache()
        sharedPlayer?.release()
        sharedPlayer = null
        positionUpdateJob?.cancel()
    }

    private fun invalidatePagingSources() {
        activePagingSources.forEach { it.invalidate() }
        activePagingSources.clear()
    }

    private suspend fun rebuildIndexesAndAlbums(localMedia: List<MediaItem>) = withContext(Dispatchers.Default) {
        val map = java.util.HashMap<Long, MediaItem>(localMedia.size * 2)
        val photos = ArrayList<MediaItem>()
        val videos = ArrayList<MediaItem>()
        val gifs = ArrayList<MediaItem>()
        val recents = ArrayList<MediaItem>()

        val trashSet = trashBin.value.map { it.contentUri }.toSet()
        val secureSet = secureIds.value.map { it.toString() }.toSet()
        val favSet = favoriteIds.value.toSet()
        val hiddenAlbumSet = _hiddenAlbums.value
        val validMedia = ArrayList<MediaItem>(localMedia.size)

        for (item in localMedia) {
            if (
                trashSet.contains(item.uri.toString()) ||
                secureSet.contains(item.id.toString()) ||
                hiddenAlbumSet.contains(item.bucketId)
            ) continue

            val mappedItem = if (item.isFavorite == favSet.contains(item.id)) {
                item
            } else {
                item.copy(isFavorite = favSet.contains(item.id))
            }
            map[mappedItem.id] = mappedItem
            validMedia.add(mappedItem)

            if (mappedItem.isVideo) {
                videos.add(mappedItem)
            } else {
                if (mappedItem.mimeType.contains("gif", true)) gifs.add(mappedItem) else photos.add(mappedItem)
            }
        }

        validMedia.sortByDescending { it.dateAdded }
        recents.addAll(validMedia)

        val favorites = validMedia.filter { favSet.contains(it.id) }
        _mediaMap.value = map
        _mediaIndexes.value = MediaIndexes(validMedia, photos, videos, gifs, recents)

        val sortOption = _albumSort.value
        val isCustomOrder = sortOption == AlbumSort.Custom

        val metaMap = albumMeta.value.associateBy { it.id }
        val mappedAlbums = validMedia.groupBy { it.bucketId }.mapNotNull { (bucketId, items) ->
            val latestItem = items.maxByOrNull { it.dateAdded } ?: return@mapNotNull null
            val albumName = metaMap[bucketId]?.customName?.takeIf { it.isNotBlank() }
                ?: latestItem.bucketName.ifBlank { "Unknown" }
            if (albumName.matches(Regex("^\\d+$"))) return@mapNotNull null
            Album(
                bucketId,
                albumName,
                latestItem.uri,
                items.size,
                items.sumOf { it.size },
                metaMap[bucketId]?.isPinned == true
            )
        }

        val emptyManualAlbums = manualAlbums.value.mapNotNull { entity ->
            val existingPhysical = mappedAlbums.find { it.name.equals(entity.name, ignoreCase = true) }
            if (existingPhysical != null) {
                if (!entity.hasBeenUsed) {
                    launch { dao.updateAlbumUsed(entity.id, true) }
                }
                null
            } else {
                if (!entity.hasBeenUsed) {
                    val isPinned = metaMap[entity.id]?.isPinned == true
                    Album(
                        id = entity.id,
                        name = entity.name,
                        coverUri = if (entity.coverUri.isNotEmpty()) entity.coverUri.toUri() else Uri.EMPTY,
                        mediaCount = 0,
                        sizeBytes = 0L,
                        isPinned = isPinned
                    )
                } else {
                    launch { dao.deleteManualAlbum(entity.id) }
                    null
                }
            }
        }

        val combinedMappedAlbums = mappedAlbums + emptyManualAlbums

        val virtualAlbums = listOfNotNull(
            if (recents.isNotEmpty()) {
                Album(ID_RECENT, "Recent", recents.first().uri, recents.size, recents.sumOf { it.size }, !isCustomOrder)
            } else null,
            if (favorites.isNotEmpty()) {
                Album(
                    ID_FAVORITES,
                    "Favorites",
                    favorites.firstOrNull()?.uri ?: Uri.EMPTY,
                    favorites.size,
                    favorites.sumOf { it.size },
                    !isCustomOrder
                )
            } else null
        )

        val allCombined = virtualAlbums + combinedMappedAlbums

        val allAlbums = when (sortOption) {
            AlbumSort.Custom -> {
                allCombined.withIndex()
                    .sortedWith(
                        compareBy<IndexedValue<Album>> {
                            if (it.value.id == ID_RECENT) Int.MIN_VALUE
                            else metaMap[it.value.id]?.albumOrder ?: Int.MAX_VALUE
                        }.thenBy { it.index }
                    ).map { it.value }
            }
            AlbumSort.NameAsc -> allCombined.sortedWith(
                Comparator { a, b ->
                    if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                    if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1
                    if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
                    else a.name.compareTo(b.name, ignoreCase = true)
                }
            )
            AlbumSort.NameDesc -> allCombined.sortedWith(
                Comparator { a, b ->
                    if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                    if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1
                    if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
                    else b.name.compareTo(a.name, ignoreCase = true)
                }
            )
            AlbumSort.SizeDesc -> allCombined.sortedWith(
                Comparator { a, b ->
                    if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                    if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1
                    if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
                    else b.sizeBytes.compareTo(a.sizeBytes)
                }
            )
            AlbumSort.CountDesc -> allCombined.sortedWith(
                Comparator { a, b ->
                    if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                    if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1
                    if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
                    else b.mediaCount.compareTo(a.mediaCount)
                }
            )
            else -> allCombined.sortedWith(
                Comparator { a, b ->
                    if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                    if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1
                    if (a.isPinned != b.isPinned) b.isPinned.compareTo(a.isPinned)
                    else a.name.compareTo(b.name, ignoreCase = true)
                }
            )
        }

        _allAlbumsState.value = allAlbums
        _albumsState.value = allAlbums.filterNot { hiddenAlbumSet.contains(it.id) }
        cacheAlbumPreviews(validMedia)
    }

    fun toggleAlbumPin(album: Album) = viewModelScope.launch(Dispatchers.IO) {
        try {
            if (album.isPinned) dao.removePinnedAlbum(album.id)
            else dao.addPinnedAlbum(AlbumEntity(id = album.id, isPinned = true))
        } catch (_: Exception) {
        }
    }

    fun hideItems(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            engine.hideItems(ids)
        } finally {
            removeMediaLocally(ids.toSet())
        }
    }.let { Unit }

    fun unhideMedia(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        try {
            ids.forEach { dao.removeFromSecure(it) }
        } finally {
            refreshAfterFileOperation()
        }
    }.let { Unit }

    fun toggleFavorite(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val isFav = favoriteIds.value.contains(id)
        if (isFav) {
            dao.removeFavorite(id)
        } else {
            dao.addFavorite(FavoriteEntity(mediaId = id))
        }
        updateMediaLocally(id) { it.copy(isFavorite = !isFav) }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFilter(filter: MediaTypeFilter) {
        _activeFilter.value = filter
    }

    fun updateSort(sort: PhotoSort) {
        _activeSort.value = sort
    }

    fun updateAlbumSort(sort: AlbumSort) {
        if (_albumSort.value == sort) return
        _albumSort.value = sort
    }

    fun saveCustomAlbumOrder(orderedAlbums: List<Album>) = viewModelScope.launch(Dispatchers.IO) {
        val currentMetaMap = albumMeta.value.associateBy { it.id }

        val normalizedList = orderedAlbums.toMutableList()
        val recentIdx = normalizedList.indexOfFirst { it.id == ID_RECENT }
        if (recentIdx > 0) {
            val recent = normalizedList.removeAt(recentIdx)
            normalizedList.add(0, recent)
        }

        normalizedList.forEachIndexed { i, a ->
            val existing = currentMetaMap[a.id] ?: AlbumEntity(id = a.id)
            if (existing.albumOrder != i) {
                dao.insertAlbumMeta(existing.copy(albumOrder = i))
            }
        }

        if (_albumSort.value != AlbumSort.Custom) {
            _albumSort.value = AlbumSort.Custom
        }
    }

    fun reorderAlbums(fromAlbumId: String, toAlbumId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (fromAlbumId == ID_RECENT || toAlbumId == ID_RECENT) return@launch
        val albums = _albumsState.value.toMutableList()
        val fIdx = albums.indexOfFirst { it.id == fromAlbumId }
        val tIdx = albums.indexOfFirst { it.id == toAlbumId }
        if (fIdx == -1 || tIdx == -1 || fIdx == tIdx) return@launch

        val moved = albums.removeAt(fIdx)
        albums.add(tIdx, moved)
        albums.forEachIndexed { i, a ->
            dao.insertAlbumMeta((dao.getAlbumMetaSync(a.id) ?: AlbumEntity(id = a.id)).copy(albumOrder = i))
        }
        _albumSort.value = AlbumSort.Custom
    }

    fun moveAlbumUp(id: String) {
        val a = _albumsState.value
        val i = a.indexOfFirst { it.id == id }
        if (i > 0) reorderAlbums(id, a[i - 1].id)
    }

    fun moveAlbumDown(id: String) {
        val a = _albumsState.value
        val i = a.indexOfFirst { it.id == id }
        if (i != -1 && i < a.lastIndex) reorderAlbums(id, a[i + 1].id)
    }

    fun getMediaItemById(id: Long): MediaItem? = _mediaMap.value[id] ?: _rawMedia.value.firstOrNull { it.id == id }

    fun getMediaItemByUri(uri: Uri): MediaItem? = _rawMedia.value.firstOrNull { it.uri == uri }

    fun closeViewer() {
        _viewerState.value = GalleryViewerState.Closed
    }

    private fun clearPendingOperationStates() {
        pendingRollbackUris = emptyList()
        pendingDeleteUris = emptyList()
        pendingMoveIds = emptySet()
        pendingMoveOperation = false
        pendingAutoDeleteHandledByOs = false
        pendingIsWriteRequest = false
        pendingOperationIds = emptyList()
        pendingOperationTargetAlbum = null
        pendingOperationIsMove = false
        pendingOperationOnComplete = null
    }

    fun abortFileOperation() {
        val onComplete = pendingOperationOnComplete
        pendingInternalTrashEntities = emptyList()
        pendingInternalTrashIds = emptySet()
        clearPendingOperationStates()
        _fileOperationState.value = FileOperationState.Idle
        _isBusy.value = false
        endFileOperation()
        onComplete?.invoke(MediaOpResult.Cancelled)
    }

    fun cancelCurrentOperation() {
        editingEngine.cancelExport()
        fileOperationJob?.cancel()
        val rollback = pendingRollbackUris
        viewModelScope.launch(Dispatchers.IO) {
            if (rollback.isNotEmpty()) mediaOpEngine.rollback(rollback)
            abortFileOperation()
            refreshAfterFileOperation()
            _events.emit(GalleryEvent.ShowToast("Operation cancelled safely"))
        }
    }

    private suspend fun resolveRealAlbumByName(name: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val resolver = getApplication<Application>().contentResolver
            val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val proj = arrayOf(
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            val sel = "${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} = ? COLLATE NOCASE " +
                    "AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)"
            try {
                resolver.query(uri, proj, sel, arrayOf(name), null)?.use { c ->
                    if (c.moveToFirst()) {
                        val bucket = c.getString(0)
                        val rel = c.getString(1)
                        if (!bucket.isNullOrBlank() && !rel.isNullOrBlank()) {
                            return@withContext bucket to rel
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "resolveRealAlbumByName failed", e)
            }
            null
        }

    private suspend fun buildTargetAlbum(targetAlbumId: String, fallbackName: String? = null): TargetAlbum? {
        val sample = _rawMedia.value.firstOrNull { it.bucketId == targetAlbumId }
        val name = allAlbumsState.value.firstOrNull { it.id == targetAlbumId }?.name
            ?: fallbackName
            ?: sample?.bucketName
            ?: return null

        var resolvedBucketId = targetAlbumId
        var resolvedRelPath = sample?.relativePath?.takeIf { it.isNotBlank() }

        if (resolvedRelPath == null) {
            resolveRealAlbumByName(name)?.let { (realBucket, realPath) ->
                resolvedBucketId = realBucket
                resolvedRelPath = realPath
            }
        }

        if (resolvedRelPath == null) resolvedRelPath = "Pictures/$name/"

        val volumeName = sample?.volumeName?.takeIf { it.isNotBlank() } ?: resolveAlbumVolumeName(resolvedBucketId)
        val isSd = mediaOpEngine.isSdCardAlbum(resolvedBucketId, volumeName)

        return TargetAlbum(
            id = targetAlbumId,
            name = name,
            relativePath = resolvedRelPath,
            bucketId = resolvedBucketId,
            volumeName = if (isSd) null else volumeName,
            isSdCard = isSd
        )
    }

    fun mergeAlbums(
        sourceAlbumIds: List<String>,
        targetAlbumId: String,
        mergeMode: MergeMode = MergeMode.MOVE_AND_DELETE
    ) {
        if (!tryBeginFileOperation()) {
            _events.tryEmit(GalleryEvent.ShowToast("An operation is already in progress"))
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val mediaToProcess = _rawMedia.value.filter { sourceAlbumIds.contains(it.bucketId) }.map { it.id }
            if (mediaToProcess.isEmpty()) {
                _events.emit(GalleryEvent.ShowToast("No media found to merge"))
                endFileOperation()
                return@launch
            }

            dao.updateAlbumUsed(targetAlbumId, true)
            val targetAlbum = buildTargetAlbum(targetAlbumId) ?: run {
                endFileOperation()
                _events.emit(GalleryEvent.ShowToast("Cannot resolve destination album"))
                return@launch
            }

            val isMove = mergeMode == MergeMode.MOVE || mergeMode == MergeMode.MOVE_AND_DELETE

            performMediaOperation(mediaToProcess, targetAlbum, isMove) { result ->
                if (
                    mergeMode == MergeMode.MOVE_AND_DELETE &&
                    (result is MediaOpResult.Success || result is MediaOpResult.AlreadyExists)
                ) {
                    viewModelScope.launch {
                        sourceAlbumIds.forEach { albumId -> deleteEmptyAlbum(albumId) }
                        _events.emit(GalleryEvent.ShowToast("${sourceAlbumIds.size} albums merged successfully"))
                    }
                }
            }
        }
    }

    private suspend fun deleteEmptyAlbum(albumId: String) {
        val albumStillHasMedia = _rawMedia.value.any { it.bucketId == albumId }
        if (albumStillHasMedia) return

        try {
            dao.deleteAlbumMeta(albumId)
            try {
                dao.deleteManualAlbum(albumId)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete empty album metadata: $albumId", e)
        }
    }

    fun deleteAlbum(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mediaToTrash = _rawMedia.value.filter { item ->
                    when (albumId) {
                        ID_FAVORITES -> favoriteIds.value.contains(item.id)
                        ID_VIDEOS -> item.isVideo
                        ID_SCREENSHOTS -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true)
                        ID_DOWNLOADS -> item.path.contains("Download", true)
                        ID_WHATSAPP -> item.path.contains("WhatsApp", true)
                        ID_INSTAGRAM -> item.path.contains("Instagram", true)
                        ID_RECENT -> true
                        ID_CAMERA -> item.bucketName.contains("Camera", true) || item.bucketName.contains("DCIM", true)
                        else -> item.bucketId == albumId
                    }
                }

                if (mediaToTrash.isNotEmpty()) {
                    moveToTrashInternal(mediaToTrash)
                } else {
                    try {
                        dao.deleteManualAlbum(albumId)
                    } catch (_: Exception) {
                    }
                    try {
                        dao.deleteAlbumMeta(albumId)
                    } catch (_: Exception) {
                    }
                }
                _events.tryEmit(GalleryEvent.ShowToast("Album moved to Trash"))
            } catch (e: Exception) {
                Log.e(TAG, "Delete album failed", e)
                _events.tryEmit(GalleryEvent.ShowToast("Failed to move album to Trash"))
            }
        }
    }

    private suspend fun resolveAlbumVolumeName(bucketId: String): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        try {
            val resolver = getApplication<Application>().contentResolver
            val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.VOLUME_NAME),
                "${MediaStore.Files.FileColumns.BUCKET_ID} = ?",
                arrayOf(bucketId),
                null
            )?.use { c -> if (c.moveToFirst()) return@withContext c.getString(0) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve volume for $bucketId", e)
        }
        null
    }

    fun openViewer(mediaId: Long) {
        val item = getMediaItemById(mediaId)
        if (item == null) {
            _events.tryEmit(GalleryEvent.ShowToast("Media item is not loaded."))
            return
        }
        openViewer(item)
    }

    fun openViewer(item: MediaItem) {
        if (item.uri == Uri.EMPTY) {
            _events.tryEmit(GalleryEvent.ShowToast("Media file is unavailable."))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dao.incrementUsageStats(item.id, System.currentTimeMillis())
            }
        }

        val current = _viewerState.value
        if (current !is GalleryViewerState.Open || current.mediaId != item.id) {
            _viewerState.value = GalleryViewerState.Open(
                mediaId = item.id,
                isVideo = item.isVideo,
                uri = item.uri
            )
        }
    }

    fun moveData(ids: List<Long>, targetAlbumId: String) =
        runOp(ids, targetAlbumId, isMove = true)

    fun copyData(ids: List<Long>, targetAlbumId: String) =
        runOp(ids, targetAlbumId, isMove = false)

    private fun runOp(
        ids: List<Long>,
        targetAlbumId: String,
        isMove: Boolean
    ) {
        if (ids.isEmpty()) {
            _events.tryEmit(GalleryEvent.ShowToast("No items selected"))
            return
        }

        if (!tryBeginFileOperation()) {
            _events.tryEmit(GalleryEvent.ShowToast("An operation is already in progress"))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = buildTargetAlbum(targetAlbumId)

                if (target == null) {
                    _fileOperationState.value = FileOperationState.Idle
                    _isBusy.value = false
                    endFileOperation()
                    _events.emit(GalleryEvent.ShowToast("Album not found — refresh and try again"))
                    return@launch
                }

                dao.updateAlbumUsed(targetAlbumId, true)
                performMediaOperation(ids, target, isMove)
            } catch (e: Exception) {
                Log.e(TAG, "Could not resolve operation destination", e)
                _fileOperationState.value = FileOperationState.Idle
                _isBusy.value = false
                endFileOperation()
                _events.emit(
                    GalleryEvent.ShowToast("Could not prepare destination: ${e.localizedMessage ?: "Unknown error"}")
                )
            }
        }
    }

    fun createAndMove(ids: List<Long>, newAlbumName: String) {
        createAlbumAndRun(ids, newAlbumName, isMove = true)
    }

    fun createAndCopy(ids: List<Long>, newAlbumName: String) {
        createAlbumAndRun(ids, newAlbumName, isMove = false)
    }

    private fun createAlbumAndRun(
        ids: List<Long>,
        newAlbumName: String,
        isMove: Boolean
    ) {
        val name = newAlbumName.trim()

        if (name.isBlank() || ids.isEmpty()) {
            _events.tryEmit(
                GalleryEvent.ShowToast(
                    if (name.isBlank()) "Enter an album name"
                    else "No items selected"
                )
            )
            return
        }

        if (!tryBeginFileOperation()) {
            _events.tryEmit(GalleryEvent.ShowToast("An operation is already in progress"))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mappedExisting = _albumsState.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val target: TargetAlbum

                if (mappedExisting != null) {
                    target = buildTargetAlbum(mappedExisting.id) ?: throw Exception("Failed to resolve existing target structure")
                } else {
                    val osExisting = resolveRealAlbumByName(name)
                    if (osExisting != null) {
                        val (bucket, relativePath) = osExisting
                        val volume = resolveAlbumVolumeName(bucket)
                        val isSd = mediaOpEngine.isSdCardAlbum(bucket, volume)

                        target = TargetAlbum(
                            id = bucket,
                            name = name,
                            relativePath = relativePath,
                            bucketId = bucket,
                            volumeName = if (isSd) null else volume,
                            isSdCard = isSd
                        )
                    } else {
                        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val albumDir = File(root, name)

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            if (!albumDir.exists() && !albumDir.mkdirs()) {
                                throw IOException("Could not create album folder")
                            }
                        }

                        val bucketId = albumDir.absolutePath.lowercase(Locale.ROOT).hashCode().toString()

                        target = TargetAlbum(
                            id = bucketId,
                            name = name,
                            relativePath = "Pictures/$name/",
                            bucketId = bucketId,
                            volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL_PRIMARY else null,
                            isSdCard = false
                        )

                        dao.insertManualAlbum(ManualAlbumEntity(id = target.id, name = name, hasBeenUsed = true))
                    }
                }

                dao.updateAlbumUsed(target.id, true)
                performMediaOperation(ids, target, isMove)
            } catch (e: Exception) {
                Log.e(TAG, "Create-and-operate failed", e)
                _fileOperationState.value = FileOperationState.Idle
                _isBusy.value = false
                endFileOperation()
                _events.emit(GalleryEvent.ShowToast("Could not prepare album: ${e.localizedMessage ?: "Unknown error"}"))
            }
        }
    }

    fun createAlbum(name: String, sdCard: Boolean) {
        if (name.isBlank()) return
        val trimmed = name.trim()

        if (!sdCard) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val newAlbumId = "manual_${System.currentTimeMillis()}"
                    var physicallyCreated = true
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val dir = File(root, trimmed)
                        physicallyCreated = dir.exists() || dir.mkdirs()
                    }

                    dao.insertManualAlbum(ManualAlbumEntity(id = newAlbumId, name = trimmed, hasBeenUsed = false))

                    _events.tryEmit(
                        if (physicallyCreated) GalleryEvent.ShowToast("Album '$trimmed' created!")
                        else GalleryEvent.ShowToast("Album added, but folder creation failed on disk")
                    )
                } catch (e: Exception) {
                    _events.tryEmit(GalleryEvent.ShowToast("Failed to create album: ${e.message}"))
                }
            }
            return
        }

        val treeUri = findPersistedSdCardTreeUri()
        if (treeUri == null) {
            pendingSdAlbumName = trimmed
            viewModelScope.launch {
                _events.emit(GalleryEvent.ShowToast("Select your SD card's root so albums can be created there"))
                _events.emit(
                    GalleryEvent.LaunchIntent(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    )
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaOpEngine.saveSafTreeUri(treeUri)
            } catch (e: Exception) {
                Log.e("ALBUM_DEBUG", "Failed to persist SAF permission", e)
            }
            val created = createSdCardFolder(treeUri, trimmed)
            val newId = "manual_${System.currentTimeMillis()}"
            dao.insertManualAlbum(ManualAlbumEntity(id = newId, name = trimmed, hasBeenUsed = false))
            mediaOpEngine.markAlbumAsSdCard(newId)
            _events.emit(
                if (created) GalleryEvent.ShowToast("Album '$trimmed' created on SD card!")
                else GalleryEvent.ShowToast("Couldn't create the folder on SD card")
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    fun renameAlbum(album: Album, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == album.name) return

        val itemsInAlbum = _rawMedia.value.filter { it.bucketId == album.id }

        if (itemsInAlbum.isEmpty()) {
            if (!tryBeginFileOperation()) {
                _events.tryEmit(GalleryEvent.ShowToast("An operation is already in progress"))
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                var waitingForSaf = false
                try {
                    val sample = _rawMedia.value.firstOrNull { it.bucketId == album.id }
                    val isSd = mediaOpEngine.isSdCardAlbum(album.id, sample?.volumeName)
                    val isPrimary = !isSd

                    if (isPrimary) {
                        var renamed = true
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                            val oldDir = File(root, album.name)
                            val newDir = File(root, trimmed)
                            renamed = if (oldDir.exists()) oldDir.renameTo(newDir) else newDir.mkdirs()
                        }

                        try {
                            dao.deleteManualAlbum(album.id)
                        } catch (_: Exception) {
                        }
                        val newManualId = "manual_${System.currentTimeMillis()}"
                        dao.insertManualAlbum(ManualAlbumEntity(id = newManualId, name = trimmed, hasBeenUsed = false))

                        _events.emit(
                            GalleryEvent.ShowToast(if (renamed) "Album renamed" else "Renamed in app, but folder update failed")
                        )
                    } else {
                        val treeUri = findPersistedSdCardTreeUri()
                        if (treeUri == null) {
                            waitingForSaf = true
                            pendingSdRenameAlbumId = album.id
                            pendingSdRenameOldName = album.name
                            pendingSdRenameNewName = trimmed
                            _events.emit(GalleryEvent.ShowToast("Select your SD card's root to finish renaming"))
                            _events.emit(
                                GalleryEvent.LaunchIntent(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                    )
                                )
                            )
                        } else {
                            val renamed = renameSdCardFolder(album.name, trimmed)

                            try {
                                dao.deleteManualAlbum(album.id)
                            } catch (_: Exception) {
                            }
                            val newManualId = "manual_${System.currentTimeMillis()}"
                            dao.insertManualAlbum(ManualAlbumEntity(id = newManualId, name = trimmed, hasBeenUsed = false))
                            mediaOpEngine.markAlbumAsSdCard(newManualId)

                            _events.emit(
                                GalleryEvent.ShowToast(
                                    if (renamed) "Album renamed" else "Renamed in app, but folder update failed on SD card"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    _events.emit(GalleryEvent.ShowToast("Failed to rename: ${e.message}"))
                } finally {
                    if (!waitingForSaf) {
                        endFileOperation()
                    }
                }
            }
            return
        }

        if (!tryBeginFileOperation()) {
            _events.tryEmit(GalleryEvent.ShowToast("An operation is already in progress"))
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sample = _rawMedia.value.firstOrNull { it.bucketId == album.id }
            val isSd = mediaOpEngine.isSdCardAlbum(album.id, sample?.volumeName)
            val volumeName = if (isSd) null else resolveAlbumVolumeName(album.id)
            val targetAlbum = TargetAlbum(
                id = album.id,
                name = trimmed,
                relativePath = "Pictures/$trimmed/",
                bucketId = album.id,
                volumeName = volumeName,
                isSdCard = isSd
            )
            performMediaOperation(itemsInAlbum.map { it.id }, targetAlbum, isMove = true) { result ->
                if (result is MediaOpResult.Success || result is MediaOpResult.AlreadyExists) {
                    viewModelScope.launch(Dispatchers.IO) {
                        dao.insertAlbumMeta(
                            (dao.getAlbumMetaSync(album.id) ?: AlbumEntity(id = album.id)).copy(customName = trimmed)
                        )
                    }
                }
            }
        }
    }

    private suspend fun reconcileManualAlbumBucket(manualAlbumId: String, movedIds: List<Long>) {
        if (!manualAlbumId.startsWith("manual_") || movedIds.isEmpty()) return
        try {
            val fresh = withContext(Dispatchers.IO) { engine.fetchIncrementalMedia(lastMediaStoreGeneration) }
            val realBucketId = fresh.firstOrNull { it.id in movedIds }?.bucketId ?: return
            if (realBucketId.isBlank() || realBucketId == manualAlbumId) return
            val meta = dao.getAlbumMetaSync(manualAlbumId)
            if (meta != null) {
                dao.insertAlbumMeta(meta.copy(id = realBucketId))
                dao.deleteAlbumMeta(manualAlbumId)
            }
            dao.deleteManualAlbum(manualAlbumId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconcile manual album bucket for $manualAlbumId", e)
        }
    }

    private fun performMediaOperation(
        ids: List<Long>,
        targetAlbum: TargetAlbum,
        isMove: Boolean,
        onComplete: ((MediaOpResult) -> Unit)? = null
    ) {
        pendingOperationIds = ids
        pendingOperationTargetAlbum = targetAlbum
        pendingOperationIsMove = isMove
        pendingOperationOnComplete = onComplete

        fileOperationJob = viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            var waitingForPermission = false
            _fileOperationState.value = FileOperationState.Processing("Starting", 0f, 0, ids.size)
            try {
                val byId = _mediaMap.value
                val raw = _rawMedia.value
                val validItems = ids.mapNotNull { id -> byId[id] ?: raw.firstOrNull { it.id == id } }
                if (validItems.isEmpty()) {
                    _fileOperationState.value = FileOperationState.Idle
                    clearPendingOperationStates()
                    _events.emit(GalleryEvent.ShowToast("Items not loaded yet — try again"))
                    onComplete?.invoke(MediaOpResult.Failed("No valid items found"))
                    return@launch
                }

                val result = if (isMove) {
                    mediaOpEngine.moveMedia(validItems, targetAlbum) { phase, current, total ->
                        _fileOperationState.value = FileOperationState.Processing(
                            phase,
                            current.toFloat() / total,
                            current,
                            total
                        )
                    }
                } else {
                    mediaOpEngine.copyMedia(validItems, targetAlbum) { phase, current, total ->
                        _fileOperationState.value = FileOperationState.Processing(
                            phase,
                            current.toFloat() / total,
                            current,
                            total
                        )
                    }
                }

                when (result) {
                    is MediaOpResult.Success -> {
                        val verb = if (isMove) "Moved" else "Copied"

                        val msg = buildString {
                            if (result.copiedCount > 0) append("$verb ${result.copiedCount} items. ")
                            if (result.skippedCount > 0) append("Skipped ${result.skippedCount} items. ")
                        }.trim()

                        if (isMove) {
                            validItems.forEach { item ->
                                updateMediaLocally(item.id) {
                                    it.copy(
                                        bucketId = targetAlbum.bucketId,
                                        bucketName = targetAlbum.name,
                                        relativePath = targetAlbum.relativePath ?: it.relativePath
                                    )
                                }
                            }
                        }

                        reconcileManualAlbumBucket(targetAlbum.id, validItems.map { it.id })

                        refreshAfterFileOperation()
                        invalidatePagingSources()

                        _events.emit(GalleryEvent.ShowToast(msg.ifEmpty { "Operation successful" }))
                        _events.emit(GalleryEvent.OperationSuccess)
                        _fileOperationState.value = FileOperationState.Idle
                        clearPendingOperationStates()
                        onComplete?.invoke(result)
                    }
                    is MediaOpResult.PermissionRequired -> {
                        _fileOperationState.value = FileOperationState.WaitingForPermission
                        pendingRollbackUris = result.pendingRollbackUris
                        pendingDeleteUris = validItems.map { it.uri }
                        pendingMoveIds = validItems.map { it.id }.toSet()
                        pendingMoveOperation = isMove
                        pendingAutoDeleteHandledByOs = result.autoDeleteHandledByOs
                        pendingIsWriteRequest = result.isWriteRequest
                        pendingOperationOnComplete = onComplete
                        waitingForPermission = true
                        _events.emit(GalleryEvent.RequestPermission(result.intentSender))
                    }
                    is MediaOpResult.SafPermissionRequired -> {
                        _fileOperationState.value = FileOperationState.WaitingForPermission
                        pendingRollbackUris = result.pendingRollbackUris
                        pendingOperationOnComplete = onComplete
                        waitingForPermission = true
                        _events.emit(GalleryEvent.LaunchIntent(result.intent))
                    }
                    is MediaOpResult.Failed -> {
                        _fileOperationState.value = FileOperationState.Idle
                        _events.emit(GalleryEvent.ShowToast("Operation failed: ${result.reason}"))
                        clearPendingOperationStates()
                        onComplete?.invoke(result)
                    }
                    is MediaOpResult.Cancelled -> {
                        _fileOperationState.value = FileOperationState.Idle
                        _events.emit(GalleryEvent.ShowToast("Operation cancelled safely"))
                        clearPendingOperationStates()
                        onComplete?.invoke(result)
                    }
                    is MediaOpResult.AlreadyExists -> {
                        _events.emit(GalleryEvent.ShowToast(result.message))
                        _events.emit(GalleryEvent.OperationSuccess)
                        _fileOperationState.value = FileOperationState.Idle
                        clearPendingOperationStates()
                        onComplete?.invoke(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Media operation failed", e)
                _fileOperationState.value = FileOperationState.Idle
                clearPendingOperationStates()
                _events.emit(GalleryEvent.ShowToast("Operation failed: ${e.localizedMessage ?: "Unknown error"}"))
                onComplete?.invoke(MediaOpResult.Failed(e.localizedMessage ?: "Unknown error"))
            } finally {
                if (!waitingForPermission) {
                    _isBusy.value = false
                    endFileOperation()
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (pendingInternalTrashEntities.isNotEmpty()) {
            if (granted) {
                dao.insertTrashItemsBulk(pendingInternalTrashEntities)
                removeMediaLocally(pendingInternalTrashIds)
                _events.emit(GalleryEvent.OperationSuccess)
            } else {
                _events.emit(GalleryEvent.ShowToast("Trash permission denied"))
            }
            pendingInternalTrashEntities = emptyList()
            pendingInternalTrashIds = emptySet()
            return@launch
        }

        val onComplete = pendingOperationOnComplete
        try {
            if (granted) {
                _fileOperationState.value = FileOperationState.Processing("Finishing", 0f, 0, 1)

                if (pendingIsWriteRequest) {
                    val ids = pendingOperationIds
                    val target = pendingOperationTargetAlbum
                    val isMove = pendingOperationIsMove
                    clearPendingOperationStates()
                    if (target != null && ids.isNotEmpty()) {
                        performMediaOperation(ids, target, isMove, onComplete)
                    } else {
                        _fileOperationState.value = FileOperationState.Idle
                        _isBusy.value = false
                        endFileOperation()
                        onComplete?.invoke(MediaOpResult.Failed("Missing operation context"))
                    }
                    return@launch
                } else if (pendingMoveOperation && pendingAutoDeleteHandledByOs) {
                    pendingOperationTargetAlbum?.let { target ->
                        pendingMoveIds.forEach { id ->
                            updateMediaLocally(id) {
                                it.copy(
                                    bucketId = target.bucketId,
                                    bucketName = target.name,
                                    relativePath = target.relativePath ?: it.relativePath
                                )
                            }
                        }
                        reconcileManualAlbumBucket(target.id, pendingMoveIds.toList())
                    } ?: removeMediaLocally(pendingMoveIds)
                    refreshAfterFileOperation()
                    invalidatePagingSources()
                    _events.tryEmit(GalleryEvent.OperationSuccess)
                    _events.tryEmit(GalleryEvent.ShowToast("Operation completed successfully"))
                    onComplete?.invoke(MediaOpResult.Success(deletedCount = pendingMoveIds.size))
                } else if (pendingMoveOperation && pendingDeleteUris.isNotEmpty()) {
                    val result = mediaOpEngine.resumeDelete(pendingDeleteUris)
                    when (result) {
                        is MediaOpResult.Success -> {
                            pendingOperationTargetAlbum?.let { target ->
                                pendingMoveIds.forEach { id ->
                                    updateMediaLocally(id) {
                                        it.copy(
                                            bucketId = target.bucketId,
                                            bucketName = target.name,
                                            relativePath = target.relativePath ?: it.relativePath
                                        )
                                    }
                                }
                                reconcileManualAlbumBucket(target.id, pendingMoveIds.toList())
                            } ?: removeMediaLocally(pendingMoveIds)
                            refreshAfterFileOperation()
                            invalidatePagingSources()
                            _events.tryEmit(GalleryEvent.OperationSuccess)
                            _events.tryEmit(GalleryEvent.ShowToast("Operation completed successfully"))
                            onComplete?.invoke(result)
                        }
                        is MediaOpResult.Failed -> {
                            mediaOpEngine.rollback(pendingRollbackUris)
                            refreshAfterFileOperation()
                            _events.tryEmit(GalleryEvent.ShowToast(result.reason))
                            onComplete?.invoke(result)
                        }
                        else -> {}
                    }
                } else {
                    if (pendingMoveOperation && pendingMoveIds.isNotEmpty()) {
                        pendingOperationTargetAlbum?.let { target ->
                            pendingMoveIds.forEach { id ->
                                updateMediaLocally(id) {
                                    it.copy(
                                        bucketId = target.bucketId,
                                        bucketName = target.name,
                                        relativePath = target.relativePath ?: it.relativePath
                                    )
                                }
                            }
                            reconcileManualAlbumBucket(target.id, pendingMoveIds.toList())
                        } ?: removeMediaLocally(pendingMoveIds)
                    }
                    refreshAfterFileOperation()
                    invalidatePagingSources()
                    _events.tryEmit(GalleryEvent.OperationSuccess)
                    _events.tryEmit(GalleryEvent.ShowToast("Operation completed successfully"))
                    onComplete?.invoke(MediaOpResult.Success())
                }
            } else {
                if (pendingRollbackUris.isNotEmpty()) {
                    mediaOpEngine.rollback(pendingRollbackUris)
                }
                refreshAfterFileOperation()
                _events.tryEmit(GalleryEvent.ShowToast("Permission denied. Rollback complete."))
                onComplete?.invoke(MediaOpResult.Cancelled)
            }
        } finally {
            _fileOperationState.value = FileOperationState.Idle
            clearPendingOperationStates()
            _isBusy.value = false
            endFileOperation()
        }
    }

    fun saveSafTreeUri(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ids = pendingOperationIds
        val target = pendingOperationTargetAlbum
        val isMove = pendingOperationIsMove
        val onComplete = pendingOperationOnComplete
        val rollbackUris = pendingRollbackUris

        try {
            mediaOpEngine.saveSafTreeUri(uri)

            if (rollbackUris.isNotEmpty()) {
                mediaOpEngine.rollback(rollbackUris)
            }

            clearPendingOperationStates()

            if (target != null && ids.isNotEmpty()) {
                performMediaOperation(ids, target, isMove, onComplete)
            } else {
                _fileOperationState.value = FileOperationState.Idle
                _isBusy.value = false
                endFileOperation()

                _events.emit(GalleryEvent.ShowToast("Could not resume operation: missing destination"))
                onComplete?.invoke(MediaOpResult.Failed("Missing operation context"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume operation after SAF permission", e)

            runCatching {
                if (rollbackUris.isNotEmpty()) {
                    mediaOpEngine.rollback(rollbackUris)
                }
            }

            clearPendingOperationStates()
            _fileOperationState.value = FileOperationState.Idle
            _isBusy.value = false
            endFileOperation()

            _events.emit(
                GalleryEvent.ShowToast("Could not access the selected folder: ${e.localizedMessage ?: "Unknown error"}")
            )
            onComplete?.invoke(MediaOpResult.Failed(e.localizedMessage ?: "SAF permission failed"))
        }
    }

    fun deleteSecureMedia(ids: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        ids.forEach { dao.removeFromSecure(it) }
        _events.tryEmit(GalleryEvent.OperationSuccess)
        removeMediaLocally(ids.toSet())
    }

    suspend fun decryptToMemory(encryptedFilePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val uri = if (encryptedFilePath.startsWith("content://")) {
                Uri.parse(encryptedFilePath)
            } else {
                Uri.fromFile(File(encryptedFilePath))
            }
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun decryptThumbnailToMemory(encryptedFilePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bytes = decryptToMemory(encryptedFilePath) ?: return@withContext null
            if (bytes.size > 500 * 1024) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calculateInSampleSize(opts, 256, 256)
                opts.inJustDecodeBounds = false
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val out = ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.JPEG, 70, out)
                bm.recycle()
                out.toByteArray()
            } else {
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun decryptToTempFile(encryptedFilePath: String): File? = withContext(Dispatchers.IO) {
        try {
            val out = File(
                File(getApplication<Application>().cacheDir, "secure_vault_playback").apply { mkdirs() },
                "temp_${System.currentTimeMillis()}.mp4"
            )
            val resolver = getApplication<Application>().contentResolver
            val uri = if (encryptedFilePath.startsWith("content://")) {
                Uri.parse(encryptedFilePath)
            } else {
                Uri.fromFile(File(encryptedFilePath))
            }
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun deleteTempFile(file: File) {
        if (file.exists() && file.absolutePath.contains("secure_vault_playback")) file.delete()
    }

    fun clearTempVaultCache() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val cacheDir = File(getApplication<Application>().cacheDir, "secure_vault_playback")
            if (cacheDir.exists()) {
                val sixHoursAgo = System.currentTimeMillis() - (6 * 60 * 60 * 1000L)
                cacheDir.listFiles()?.forEach { if (it.lastModified() < sixHoursAgo) it.delete() }
            }
        } catch (_: Exception) {
        }
    }.let { Unit }

    fun schedulePeriodicTrashCleanup() = runCatching {
        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "TrashCleanupPeriodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashCleanupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        )
    }.let { Unit }

    fun getNextMediaUri(currentUri: String): String? =
        processedMedia.value.indexOfFirst { it.uri.toString() == currentUri }
            .takeIf { it != -1 && it < processedMedia.value.lastIndex }
            ?.let { processedMedia.value[it + 1].uri.toString() }

    fun getPreviousMediaUri(currentUri: String): String? =
        processedMedia.value.indexOfFirst { it.uri.toString() == currentUri }
            .takeIf { it > 0 }
            ?.let { processedMedia.value[it - 1].uri.toString() }

    fun preloadNextMedia(currentUri: String, preloadCallback: (Uri) -> Unit) {
        val nextIndex = processedMedia.value.indexOfFirst { it.uri.toString() == currentUri } + 1
        if (nextIndex in 1..processedMedia.value.lastIndex) {
            preloadCallback(processedMedia.value[nextIndex].uri)
        }
    }
}

class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val filter: MediaTypeFilter,
    private val query: String,
    private val albumId: String? = null,
    private val hiddenAlbums: Set<String> = emptySet(),
    private val favoriteIds: List<Long> = emptyList(),
    private val secureIds: List<Long> = emptyList(),
    private val sort: PhotoSort = PhotoSort.DateDesc
) : PagingSource<Int, MediaItem>() {

    private val sortColumn = when (sort) {
        PhotoSort.NameAsc, PhotoSort.NameDesc -> MediaStore.Files.FileColumns.DISPLAY_NAME
        PhotoSort.SizeDesc -> MediaStore.Files.FileColumns.SIZE
        else -> MediaStore.Files.FileColumns.DATE_ADDED
    }
    private val sortDesc = sort != PhotoSort.NameAsc && sort != PhotoSort.DateAsc

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? = state.anchorPosition?.let {
        state.closestPageToPosition(it)?.prevKey?.plus(state.config.pageSize)
            ?: state.closestPageToPosition(it)?.nextKey?.minus(state.config.pageSize)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> = withContext(Dispatchers.IO) {
        try {
            val position = params.key ?: 0
            val pageSize = params.loadSize
            val mediaList = ArrayList<MediaItem>(pageSize)

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            var selection = when (filter) {
                MediaTypeFilter.ALL -> "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1, 3))"
                MediaTypeFilter.PHOTOS ->
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                            "AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'application/%' " +
                            "AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'text/%'"
                MediaTypeFilter.VIDEOS ->
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            }

            val selectionArgsList = mutableListOf<String>()

            when (albumId) {
                ID_RECENT -> {}
                ID_CAMERA -> {
                    selection += " AND (${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%Camera%' OR ${MediaStore.Files.FileColumns.DATA} LIKE '%/DCIM/%')"
                }
                ID_VIDEOS -> {
                    selection += " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
                }
                ID_FAVORITES -> {
                    if (favoriteIds.isEmpty()) {
                        return@withContext LoadResult.Page(
                            emptyList(),
                            if (position == 0) null else position - pageSize,
                            null
                        )
                    }
                    val placeholders = favoriteIds.joinToString(",") { "?" }
                    selection += " AND ${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
                    selectionArgsList.addAll(favoriteIds.map { it.toString() })
                }
                ID_HIDDEN -> {
                    if (secureIds.isEmpty()) {
                        return@withContext LoadResult.Page(
                            emptyList(),
                            if (position == 0) null else position - pageSize,
                            null
                        )
                    }
                    val placeholders = secureIds.joinToString(",") { "?" }
                    selection += " AND ${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
                    selectionArgsList.addAll(secureIds.map { it.toString() })
                }
                ID_SCREENSHOTS -> {
                    selection += " AND (${MediaStore.Files.FileColumns.DATA} LIKE '%Screenshot%' OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%Screenshot%')"
                }
                ID_DOWNLOADS -> {
                    selection += " AND (${MediaStore.Files.FileColumns.DATA} LIKE '%Download%' OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%Download%')"
                }
                ID_WHATSAPP -> {
                    selection += " AND (${MediaStore.Files.FileColumns.DATA} LIKE '%WhatsApp%' OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%WhatsApp%')"
                }
                ID_INSTAGRAM -> {
                    selection += " AND (${MediaStore.Files.FileColumns.DATA} LIKE '%Instagram%' OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE '%Instagram%')"
                }
                null -> {}
                else -> {
                    selection += " AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
                    selectionArgsList.add(albumId)
                }
            }

            if (query.isNotBlank()) {
                selection += " AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE ?)"
                val safeQuery = "%$query%"
                selectionArgsList.add(safeQuery)
                selectionArgsList.add(safeQuery)
            }

            if (hiddenAlbums.isNotEmpty()) {
                val placeholders = hiddenAlbums.joinToString(",") { "?" }
                selection += " AND ${MediaStore.Files.FileColumns.BUCKET_ID} NOT IN ($placeholders)"
                selectionArgsList.addAll(hiddenAlbums)
            }

            val selArgsArray = if (selectionArgsList.isNotEmpty()) selectionArgsList.toTypedArray() else null

            val projection = mutableListOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.MediaColumns.DURATION
            ).apply {
                add(MediaStore.Files.FileColumns.DATA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    add(MediaStore.MediaColumns.VOLUME_NAME)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    if (selArgsArray != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selArgsArray)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        if (sortDesc) ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                        else ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, position)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                    }
                }
                contentResolver.query(uri, projection.toTypedArray(), bundle, null)?.use { cursor ->
                    processCursor(cursor, mediaList)
                }
            } else {
                val sortOrderString = if (sortDesc) "DESC" else "ASC"
                contentResolver.query(
                    uri,
                    projection.toTypedArray(),
                    selection,
                    selArgsArray,
                    "$sortColumn $sortOrderString LIMIT $pageSize OFFSET $position"
                )?.use { cursor ->
                    processCursor(cursor, mediaList)
                }
            }

            LoadResult.Page(
                mediaList,
                if (position == 0) null else position - pageSize,
                if (mediaList.isEmpty() || mediaList.size < pageSize) null else position + pageSize
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun processCursor(c: Cursor, list: MutableList<MediaItem>) {
        val iC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val mC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val dC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val sC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val nC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val bIC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
        val bNC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        val rpC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
        } else -1
        val pC = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
        val wC = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
        val hC = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
        val volC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
        } else -1
        val durC = c.getColumnIndex(MediaStore.MediaColumns.DURATION)

        val favSet = favoriteIds.toHashSet()

        while (c.moveToNext()) {
            val p = if (pC >= 0) {
                c.getString(pC) ?: ""
            } else if (rpC >= 0) {
                (c.getString(rpC) ?: "") + (c.getString(nC) ?: "")
            } else ""
            val rp = if (rpC >= 0) c.getString(rpC) ?: "" else p.substringBeforeLast('/')
            val m = c.getString(mC) ?: ""
            val rd = c.getLong(dC)

            val isV = m.startsWith("video/")

            val id = c.getLong(iC)
            val w = if (wC >= 0) c.getInt(wC) else 0
            val h = if (hC >= 0) c.getInt(hC) else 0

            val volName = if (volC >= 0) c.getString(volC) ?: "" else ""
            val baseUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volName.isNotBlank()) {
                if (isV) MediaStore.Video.Media.getContentUri(volName)
                else MediaStore.Images.Media.getContentUri(volName)
            } else {
                if (isV) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            list.add(
                MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(baseUri, id),
                    path = p,
                    relativePath = rp,
                    name = c.getString(nC) ?: "",
                    dateAdded = if (rd > 1000000000000L) rd / 1000L else if (rd > 0) rd else System.currentTimeMillis() / 1000L,
                    size = c.getLong(sC),
                    isVideo = isV,
                    duration = if (durC >= 0) c.getLong(durC) else 0L,
                    width = w,
                    height = h,
                    mimeType = m,
                    bucketId = c.getString(bIC) ?: "",
                    bucketName = c.getString(bNC) ?: "",
                    volumeName = volName,
                    isFavorite = favSet.contains(id),
                    isHidden = false
                )
            )
        }
    }
}