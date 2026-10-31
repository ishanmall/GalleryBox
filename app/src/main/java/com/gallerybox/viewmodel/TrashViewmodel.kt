@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.viewmodel

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    application: Application,
    private val galleryDao: GalleryDao
) : AndroidViewModel(application) {

    private val TAG = "TrashViewModel"
    private val resolver = application.contentResolver

    private val _events = MutableSharedFlow<GalleryEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val _operationProgress = MutableStateFlow<Float?>(null)
    val operationProgress = _operationProgress.asStateFlow()

    // --- PENDING TASKS MEMORY ---
    @Volatile private var pendingTrashEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingDeleteEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingDeleteAction: (() -> Unit)? = null
    @Volatile private var pendingRestoreEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingRestoreOnComplete: (() -> Unit)? = null

    var onRefreshGallery: (suspend () -> Unit)? = null
    var onRefreshMusic: (suspend () -> Unit)? = null
    var onRefreshDocuments: (suspend () -> Unit)? = null

    fun calculateDaysLeft(deletedTimestamp: Long): Int {
        val currentTime = System.currentTimeMillis()
        val daysPassed = TimeUnit.MILLISECONDS.toDays((currentTime - deletedTimestamp).coerceAtLeast(0L)).toInt()
        return (30 - daysPassed).coerceAtLeast(0)
    }

    /**
     * Unpacks selected albums and moves their items to Trash.
     */
    fun confirmPendingAlbumTrash(albums: List<Album>, allMedia: List<MediaItem>) {
        val albumIds = albums.map { it.id }

        val itemsToTrash = allMedia.filter { item ->
            albumIds.any { albumId ->
                when (albumId) {
                    "virtual_favorites" -> item.isFavorite
                    "virtual_videos" -> item.isVideo
                    "virtual_screenshots" -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true)
                    "virtual_downloads" -> item.path.contains("Download", true)
                    "virtual_whatsapp" -> item.path.contains("WhatsApp", true)
                    "virtual_instagram" -> item.path.contains("Instagram", true)
                    "virtual_recent" -> true
                    "virtual_camera" -> item.bucketName.contains("Camera", true) || item.bucketName.contains("DCIM", true)
                    else -> item.bucketId == albumId
                }
            }
        }

        if (itemsToTrash.isNotEmpty()) {
            confirmPendingGalleryTrash(itemsToTrash)
        }
    }

    /**
     * Called when the user clicks Allow or Deny on the Android system Trash / Restore / Delete dialog.
     */
    fun onPermissionResultGlobal(granted: Boolean) =
        viewModelScope.launch(Dispatchers.IO) {

            val trashSnapshot = pendingTrashEntities
            val restoreSnapshot = pendingRestoreEntities
            val deleteSnapshot = pendingDeleteEntities

            if (
                trashSnapshot.isEmpty() &&
                restoreSnapshot.isEmpty() &&
                deleteSnapshot.isEmpty()
            ) {
                Log.w(TAG, "Permission result received but no pending operation exists")
                return@launch
            }

            if (!granted) {
                val message = when {
                    trashSnapshot.isNotEmpty() -> "Trash permission denied"
                    restoreSnapshot.isNotEmpty() -> "Restore permission denied"
                    deleteSnapshot.isNotEmpty() -> "Delete permission denied"
                    else -> "Permission denied"
                }

                pendingTrashEntities = emptyList()
                pendingRestoreEntities = emptyList()
                pendingDeleteEntities = emptyList()
                pendingDeleteAction = null
                pendingRestoreOnComplete = null

                withContext(Dispatchers.Main) {
                    _events.emit(GalleryEvent.ShowToast(message))
                }
                return@launch
            }

            try {
                when {
                    // 1. Move Gallery media into Trash
                    trashSnapshot.isNotEmpty() -> {
                        Log.d(
                            TAG,
                            "Saving ${trashSnapshot.size} pending Trash items to Room"
                        )

                        // First save successfully in Room.
                        val insertedIds =
                            galleryDao.insertTrashItemsBulk(trashSnapshot)

                        Log.d(
                            TAG,
                            "Trash DB insertion completed: ${insertedIds.size} rows"
                        )

                        // Clear pending state ONLY after successful insertion.
                        pendingTrashEntities = emptyList()

                        withContext(Dispatchers.Main) {
                            onRefreshGallery?.invoke()
                            onRefreshMusic?.invoke()
                            onRefreshDocuments?.invoke()
                            _events.emit(GalleryEvent.OperationSuccess)
                        }
                    }

                    // 2. Restore items
                    restoreSnapshot.isNotEmpty() -> {
                        galleryDao.deleteTrashItems(
                            restoreSnapshot.map { it.id }
                        )

                        pendingRestoreEntities = emptyList()
                        val callback = pendingRestoreOnComplete
                        pendingRestoreOnComplete = null

                        withContext(Dispatchers.Main) {
                            onRefreshGallery?.invoke()
                            onRefreshMusic?.invoke()
                            onRefreshDocuments?.invoke()
                            callback?.invoke()
                            _events.emit(GalleryEvent.OperationSuccess)
                        }
                    }

                    // 3. Permanently delete items
                    deleteSnapshot.isNotEmpty() -> {
                        galleryDao.deleteTrashItems(
                            deleteSnapshot.map { it.id }
                        )

                        pendingDeleteEntities = emptyList()
                        val callback = pendingDeleteAction
                        pendingDeleteAction = null

                        withContext(Dispatchers.Main) {
                            onRefreshGallery?.invoke()
                            onRefreshMusic?.invoke()
                            onRefreshDocuments?.invoke()
                            callback?.invoke()
                            _events.emit(GalleryEvent.OperationSuccess)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete pending Trash operation", e)

                // Keep pending state intact if Room operation fails.
                withContext(Dispatchers.Main) {
                    _events.emit(
                        GalleryEvent.ShowToast(
                            "Failed to save Trash operation: ${e.localizedMessage ?: "Database error"}"
                        )
                    )
                }
            }
        }

    /**
     * Moves Stories into Trash.
     */
    fun moveStoriesToTrash(stories: List<UiStory>) = viewModelScope.launch(Dispatchers.IO) {
        val trashItems = stories.map { story ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = "${story.subtitle}|||${story.items.joinToString(",") { it.id.toString() }}",
                contentUri = story.coverUri.toString(),
                mediaType = "story",
                name = "${story.id}|||${story.title}",
                size = 0L
            )
        }
        galleryDao.insertTrashItemsBulk(trashItems)
        galleryDao.deleteStories(stories.map { it.id })

        withContext(Dispatchers.Main) {
            onRefreshGallery?.invoke()
            _events.emit(GalleryEvent.OperationSuccess)
        }
    }

    /**
     * Restores items out of the Trash Bin so they reappear in Pictures & Albums.
     */
    fun restoreTrashItems(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" }
        val mediaItems = items.filter { it.mediaType != "story" }

        if (stories.isNotEmpty()) {
            val restored = mutableListOf<Long>()
            stories.forEach { item ->
                try {
                    val nameParts = item.name.split("|||")
                    val storyId = nameParts.getOrElse(0) { "story_${System.currentTimeMillis()}" }
                    val title = nameParts.getOrElse(1) { "Restored Story" }
                    val pathParts = item.originalPath.split("|||")
                    val subtitle = pathParts.getOrElse(0) { "" }
                    val mediaIdsJson = "[" + pathParts.getOrElse(1) { "" } + "]"

                    galleryDao.insertStory(
                        StoryEntity(
                            storyId,
                            title,
                            subtitle,
                            item.contentUri,
                            mediaIdsJson,
                            System.currentTimeMillis(),
                            "MANUAL"
                        )
                    )
                    restored.add(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore story", e)
                }
            }
            if (restored.isNotEmpty()) galleryDao.deleteTrashItems(restored)
        }

        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
            return@launch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                // Try direct untrash first if our app owns the file
                val needPermissionItems = mutableListOf<TrashEntity>()
                val directlyRestoredIds = mutableListOf<Long>()

                mediaItems.forEach { entity ->
                    val uri = Uri.parse(entity.contentUri)
                    val restoredDirectly = runCatching {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_TRASHED, 0)
                        }
                        resolver.update(uri, values, null, null) > 0
                    }.getOrDefault(false)

                    if (restoredDirectly) {
                        directlyRestoredIds.add(entity.id)
                    } else {
                        needPermissionItems.add(entity)
                    }
                }

                if (directlyRestoredIds.isNotEmpty()) {
                    galleryDao.deleteTrashItems(directlyRestoredIds)
                }

                if (needPermissionItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        _events.emit(GalleryEvent.OperationSuccess)
                    }
                    return@launch
                }

                val intentSender = MediaStore.createTrashRequest(
                    resolver,
                    needPermissionItems.map { Uri.parse(it.contentUri) },
                    false
                ).intentSender

                pendingRestoreEntities = needPermissionItems
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create restore request, restoring from local Trash table", e)
                // If the file was soft-trashed only in Room DB, removing it from Trash table restores it to Gallery!
                galleryDao.deleteTrashItems(mediaItems.map { it.id })
                withContext(Dispatchers.Main) {
                    onRefreshGallery?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            // On Android 10 and below, removing from Trash table un-hides the item back into Gallery
            galleryDao.deleteTrashItems(mediaItems.map { it.id })
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    /**
     * Permanently deletes items from Trash (ONLY called from inside TrashScreen "Delete Forever" / "Empty Trash").
     */
    fun permanentlyDeleteTrash(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" }
        val mediaItems = items.filter { it.mediaType != "story" }

        if (stories.isNotEmpty()) {
            galleryDao.deleteTrashItems(stories.map { it.id })
        }

        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
            return@launch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                val intentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender
                pendingDeleteEntities = mediaItems
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request", e)
                galleryDao.deleteTrashItems(mediaItems.map { it.id })
                withContext(Dispatchers.Main) {
                    onRefreshGallery?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            val successfulDeletes = mutableListOf<Long>()
            mediaItems.forEach { item ->
                try {
                    resolver.delete(Uri.parse(item.contentUri), null, null)
                    successfulDeletes.add(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for ${item.contentUri}", e)
                    successfulDeletes.add(item.id)
                }
            }

            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    /**
     * Moves photos/videos from PictureScreen or AlbumDetailScreen into the Trash Bin (NEVER permanently deletes!).
     */
    fun confirmPendingGalleryTrash(itemsToTrash: List<MediaItem>) = viewModelScope.launch(Dispatchers.IO) {
        if (itemsToTrash.isEmpty()) return@launch

        val now = System.currentTimeMillis()
        val mappedEntities = itemsToTrash.distinctBy { it.id }.map { media ->
            TrashEntity(
                deletedTimestamp = now,
                originalPath = media.path,
                contentUri = media.uri.toString(),
                mediaType = if (media.isVideo) "video" else "image",
                name = media.name,
                size = media.size
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = itemsToTrash.distinctBy { it.id }.map { it.uri }
                val intentSender = MediaStore.createTrashRequest(resolver, uris, true).intentSender
                pendingTrashEntities = mappedEntities
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore.createTrashRequest failed, using app-level non-destructive Trash", e)
                // NEVER call resolver.delete() here! Insert into TrashEntity table so it moves to TrashScreen
                // and remains 100% recoverable!
                galleryDao.insertTrashItemsBulk(mappedEntities)
                withContext(Dispatchers.Main) {
                    onRefreshGallery?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            // On Android 10 and below, insert into TrashEntity table without deleting the file on disk
            // so the item is hidden from Gallery, appears in TrashScreen, and can be Restored!
            galleryDao.insertTrashItemsBulk(mappedEntities)
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    // --- MUSIC TRASH METHODS ---

    fun confirmPendingMusicTrash(itemsToTrash: List<AudioTrack>, onPermissionRequested: (IntentSenderRequest) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (itemsToTrash.isEmpty()) return@launch
        val mappedEntities = itemsToTrash.map { track ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = track.path,
                contentUri = track.uri,
                mediaType = "audio",
                name = track.title,
                size = 0L
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, itemsToTrash.map { Uri.parse(it.uri) }, true).intentSender
                pendingTrashEntities = mappedEntities
                withContext(Dispatchers.Main) {
                    onPermissionRequested(IntentSenderRequest.Builder(intentSender).build())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music trash request", e)
                galleryDao.insertTrashItemsBulk(mappedEntities)
                withContext(Dispatchers.Main) {
                    onRefreshMusic?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            galleryDao.insertTrashItemsBulk(mappedEntities)
            withContext(Dispatchers.Main) {
                onRefreshMusic?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    fun onPermissionResultMusic(granted: Boolean) {
        onPermissionResultGlobal(granted)
        if (granted) {
            viewModelScope.launch { onRefreshMusic?.invoke() }
        }
    }

    fun moveSongsToTrash(songs: List<AudioTrack>) = viewModelScope.launch(Dispatchers.IO) {
        if (songs.isEmpty()) return@launch
        val mappedEntities = songs.map {
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = it.path,
                contentUri = it.uri,
                mediaType = "audio",
                name = it.title,
                size = 0L
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, songs.map { Uri.parse(it.uri) }, true).intentSender
                pendingTrashEntities = mappedEntities
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music trash request", e)
                galleryDao.insertTrashItemsBulk(mappedEntities)
                withContext(Dispatchers.Main) {
                    onRefreshMusic?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            galleryDao.insertTrashItemsBulk(mappedEntities)
            withContext(Dispatchers.Main) {
                onRefreshMusic?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    fun restoreSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.contentUri) }, false).intentSender
                pendingRestoreEntities = items
                pendingRestoreOnComplete = onComplete
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music restore request", e)
                galleryDao.deleteTrashItems(items.map { it.id })
                withContext(Dispatchers.Main) {
                    onRefreshMusic?.invoke()
                    onComplete?.invoke()
                    _events.emit(GalleryEvent.OperationSuccess)
                }
            }
        } else {
            galleryDao.deleteTrashItems(items.map { it.id })
            withContext(Dispatchers.Main) {
                onRefreshMusic?.invoke()
                onComplete?.invoke()
                _events.emit(GalleryEvent.OperationSuccess)
            }
        }
    }

    fun permanentlyDeleteSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { Uri.parse(it.contentUri) }
                val intentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender
                pendingDeleteEntities = items
                pendingDeleteAction = onComplete
                _events.emit(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request for music", e)
                galleryDao.deleteTrashItems(items.map { it.id })
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        } else {
            val successfulDeletes = mutableListOf<Long>()
            items.forEach { item ->
                try {
                    resolver.delete(Uri.parse(item.contentUri), null, null)
                    successfulDeletes.add(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for music", e)
                    successfulDeletes.add(item.id)
                }
            }

            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshMusic?.invoke()
                onComplete?.invoke()
            }
        }
    }
}