// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.viewmodel

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for moving files, asking the user for permission, and doing math with dates/times.
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * --- THE RECYCLING BIN MANAGER (TrashViewModel) ---
 * The ViewModel is the "Brain" or "Manager" of the Trash Bin screen.
 * Its job is to handle moving files into the trash, counting down 30 days until they are permanently deleted,
 * and restoring files if the user changes their mind.
 *
 * `@HiltViewModel` tells a factory tool called 'Hilt' to automatically build this manager
 * and hand it the tools it needs (like the Application context and the local Database).
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    application: Application,
    private val galleryDao: GalleryDao // Our local database that keeps a list of exactly what is inside the Trash Bin
) : AndroidViewModel(application) {

    // A tag used for logging. When we print secret messages to the developer console to find bugs,
    // it will have "TrashViewModel" next to it so we know where the message came from.
    private val TAG = "TrashViewModel"

    // The "Filing Cabinet Manager" inside Android that actually controls creating and deleting real files on the phone.
    private val resolver = application.contentResolver

    // A "Channel" is like a Walkie-Talkie. We can send a quick, one-time message (like "Show a Toast popup") to the screen.
    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // A Scoreboard that shows the progress bar (e.g., 50%) if we are deleting 1,000 files at once.
    private val _operationProgress = MutableStateFlow<Float?>(null)
    val operationProgress = _operationProgress.asStateFlow()

    // --- PENDING TASKS MEMORY ---
    // Android is very strict. We cannot just delete a user's photo without asking them.
    // When we ask, Android pauses our app and shows a system popup ("Allow app to move 5 photos to trash?").
    // These sticky notes remember exactly what we were trying to do so we can finish the job when the user clicks "Allow".

    // Remembering items we want to move TO the trash
    @Volatile private var pendingTrashEntities: List<TrashEntity> = emptyList()

    // Remembering items we want to PERMANENTLY DESTROY
    @Volatile private var pendingDeleteEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingDeleteAction: (() -> Unit)? = null // A piece of code to run after the delete finishes

    // Remembering items we want to RESTORE out of the trash
    @Volatile private var pendingRestoreEntities: List<TrashEntity> = emptyList()
    @Volatile private var pendingRestoreOnComplete: (() -> Unit)? = null

    // Hooks that let the Trash Manager tell other parts of the app to refresh their lists.
    // E.g. "I just restored a song, tell the Music screen to refresh so it shows up!"
    var onRefreshGallery: (suspend () -> Unit)? = null
    var onRefreshMusic: (suspend () -> Unit)? = null
    var onRefreshDocuments: (suspend () -> Unit)? = null

    /**
     * Calculates how many days are left until a file is permanently deleted forever.
     * Items stay in the trash for 30 days.
     */
    fun calculateDaysLeft(deletedTimestamp: Long): Int {
        val currentTime = System.currentTimeMillis() // Exact current time
        // Calculate the difference in milliseconds, then convert it to Days.
        val daysPassed = TimeUnit.MILLISECONDS.toDays(currentTime - deletedTimestamp).toInt()
        // Subtract from 30. If the math accidentally goes negative (like -2 days left), force it to be 0.
        return (30 - daysPassed).coerceAtLeast(0)
    }

    /**
     * The user selected entire albums (folders) and clicked "Delete".
     * This function unpacks the folders, finds all the individual photos inside them, and prepares to trash them.
     */
    fun confirmPendingAlbumTrash(albums: List<Album>, allMedia: List<MediaItem>) {
        val albumIds = albums.map { it.id } // Get a list of the folder names

        // Go through every single photo on the phone and check if it belongs inside the folders we want to delete.
        val itemsToTrash = allMedia.filter { item ->
            albumIds.any { albumId ->
                // "Virtual Albums" don't actually exist as real folders. We have to use logic to figure out if a photo belongs in them.
                when (albumId) {
                    "virtual_favorites" -> item.isFavorite
                    "virtual_videos" -> item.isVideo
                    "virtual_screenshots" -> item.path.contains("Screenshot", true) || item.path.contains("Screenshots", true)
                    "virtual_downloads" -> item.path.contains("Download", true)
                    "virtual_whatsapp" -> item.path.contains("WhatsApp", true)
                    "virtual_instagram" -> item.path.contains("Instagram", true)
                    "virtual_recent" -> true // If they try to delete the "Recent" folder, it literally selects every photo on the phone.
                    "virtual_camera" -> item.bucketName.contains("Camera", true) || item.bucketName.contains("DCIM", true)
                    else -> item.bucketId == albumId // Normal, physical folder check
                }
            }
        }

        // Now that we have the exact list of individual photos, send them to the trash function.
        if (itemsToTrash.isNotEmpty()) {
            confirmPendingGalleryTrash(itemsToTrash)
        }
    }

    /**
     * The Grand Central Station for Android Permissions.
     * Whenever the user clicks "Allow" or "Deny" on an Android popup, this function wakes up and decides what to do next.
     */
    fun onPermissionResultGlobal(granted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (granted) { // The user clicked "Allow"!
            when {
                // If we were trying to move things INTO the trash...
                pendingTrashEntities.isNotEmpty() -> {
                    galleryDao.insertTrashItemsBulk(pendingTrashEntities) // Save them in our database Trash Bin list
                    withContext(Dispatchers.Main) { // Switch back to the main UI thread to update the screen
                        // Tell the rest of the app to refresh their lists
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        _events.send(GalleryEvent.OperationSuccess) // Show a success checkmark
                    }
                }
                // If we were trying to RESTORE things OUT of the trash...
                pendingRestoreEntities.isNotEmpty() -> {
                    galleryDao.deleteTrashItems(pendingRestoreEntities.map { it.id }) // Remove them from our Trash Bin list
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        pendingRestoreOnComplete?.invoke() // Run any extra code the screen asked us to run
                        _events.send(GalleryEvent.OperationSuccess)
                    }
                }
                // If we were trying to PERMANENTLY DESTROY things...
                pendingDeleteEntities.isNotEmpty() -> {
                    galleryDao.deleteTrashItems(pendingDeleteEntities.map { it.id }) // Remove them from our Trash Bin list completely
                    withContext(Dispatchers.Main) {
                        onRefreshGallery?.invoke()
                        onRefreshMusic?.invoke()
                        onRefreshDocuments?.invoke()
                        pendingDeleteAction?.invoke()
                        _events.send(GalleryEvent.OperationSuccess)
                    }
                }
            }
        } else { // The user clicked "Deny"
            withContext(Dispatchers.Main) {
                // Complain to the user based on what they denied
                when {
                    pendingDeleteEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Delete permission denied"))
                    pendingRestoreEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Restore permission denied"))
                    pendingTrashEntities.isNotEmpty() -> _events.send(GalleryEvent.ShowToast("Trash permission denied"))
                }
            }
        }

        // Clean off our sticky notes, the job is done.
        pendingTrashEntities = emptyList()
        pendingRestoreEntities = emptyList()
        pendingDeleteEntities = emptyList()
        pendingDeleteAction = null
        pendingRestoreOnComplete = null
    }

    /**
     * "Stories" (The AI generated photo scrapbooks) aren't real files. They are just lists of photo IDs saved in our database.
     * So we don't need to ask Android for permission to delete them. We just move the list into the Trash Bin list directly.
     */
    fun moveStoriesToTrash(stories: List<UiStory>) = viewModelScope.launch(Dispatchers.IO) {
        val trashItems = stories.map { story ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(),
                originalPath = "${story.subtitle}|||${story.items.joinToString(",") { it.id.toString() }}", // Sneakily pack all the photo IDs into a single text line
                contentUri = story.coverUri.toString(),
                mediaType = "story",
                name = "${story.id}|||${story.title}",
                size = 0L // It's just text, it takes up 0 bytes
            )
        }
        galleryDao.insertTrashItemsBulk(trashItems) // Add to Trash Bin list
        galleryDao.deleteStories(stories.map { it.id }) // Remove from normal Story list

        withContext(Dispatchers.Main) {
            onRefreshGallery?.invoke()
            _events.send(GalleryEvent.OperationSuccess)
        }
    }

    /**
     * Un-trashes files and puts them back where they came from.
     */
    fun restoreTrashItems(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" } // Virtual items
        val mediaItems = items.filter { it.mediaType != "story" } // Real, physical files (Photos/Videos)

        // Stories are virtual, restore immediately. No OS permission involved.
        if (stories.isNotEmpty()) {
            val restored = mutableListOf<Long>()
            stories.forEach { item ->
                try {
                    // Unpack the sneaky text line we made earlier to rebuild the Story
                    val nameParts = item.name.split("|||")
                    val storyId = nameParts.getOrElse(0) { "story_${System.currentTimeMillis()}" }
                    val title = nameParts.getOrElse(1) { "Restored Story" }
                    val pathParts = item.originalPath.split("|||")
                    val subtitle = pathParts.getOrElse(0) { "" }
                    val mediaIdsJson = "[" + pathParts.getOrElse(1) { "" } + "]"

                    // Put it back in the normal Story list
                    galleryDao.insertStory(StoryEntity(storyId, title, subtitle, item.contentUri, mediaIdsJson, System.currentTimeMillis(), "MANUAL"))
                    restored.add(item.id)
                } catch (e: Exception) { Log.e(TAG, "Failed to restore story", e) }
            }
            // Remove them from the Trash Bin list
            if (restored.isNotEmpty()) galleryDao.deleteTrashItems(restored)
        }

        // If there were only Stories to restore, we are done!
        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) { onRefreshGallery?.invoke(); _events.send(GalleryEvent.OperationSuccess) }
            return@launch
        }

        // If there are real physical files to restore, we have to ask Android for permission to touch them again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                // Create the system popup. (false = "Please untrash these")
                val intentSender = MediaStore.createTrashRequest(resolver, uris, false).intentSender

                // Write our sticky notes to remember what we are doing
                pendingRestoreEntities = mediaItems

                // Send the popup to the screen
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create restore request", e)
                withContext(Dispatchers.Main) { _events.send(GalleryEvent.ShowToast("Failed to restore items")) }
            }
        } else {
            // Older versions of Android didn't even have a physical trash bin!
            // So if you 'trashed' something, we were just hiding it. Restoring it is impossible via the OS.
            withContext(Dispatchers.Main) { _events.send(GalleryEvent.ShowToast("These items can't be restored on this Android version")) }
        }
    }

    /**
     * DESTROY FOREVER.
     * Deletes the files from the phone entirely. They cannot be recovered.
     */
    fun permanentlyDeleteTrash(items: List<TrashEntity>) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch

        val stories = items.filter { it.mediaType == "story" }
        val mediaItems = items.filter { it.mediaType != "story" }

        // Stories are virtual, delete them immediately from our list
        if (stories.isNotEmpty()) {
            galleryDao.deleteTrashItems(stories.map { it.id })
        }

        if (mediaItems.isEmpty()) {
            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                _events.send(GalleryEvent.OperationSuccess)
            }
            return@launch
        }

        // For modern Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = mediaItems.map { Uri.parse(it.contentUri) }
                // Create a special popup asking the user if they REALLY want to delete the files forever.
                val intentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender

                // Write sticky notes
                pendingDeleteEntities = mediaItems
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request", e)
                _events.send(GalleryEvent.ShowToast("Failed to initiate delete request: ${e.javaClass.simpleName}"))
            }
        } else {
            // For Android 10 and below, we don't need a special popup. We just delete them directly.
            var successCount = 0
            val successfulDeletes = mutableListOf<Long>()
            mediaItems.forEach { item ->
                try {
                    // Try to delete the file
                    val deletedRows = resolver.delete(Uri.parse(item.contentUri), null, null)
                    if (deletedRows > 0) { // Success!
                        successCount++
                        successfulDeletes.add(item.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for ${item.contentUri}", e)
                }
            }

            // Remove the successfully deleted files from our Trash Bin list
            if (successfulDeletes.isNotEmpty()) {
                galleryDao.deleteTrashItems(successfulDeletes)
            }

            withContext(Dispatchers.Main) {
                onRefreshGallery?.invoke()
                if (successCount == mediaItems.size) {
                    _events.send(GalleryEvent.OperationSuccess)
                } else {
                    _events.send(GalleryEvent.ShowToast("Deleted $successCount out of ${mediaItems.size} items"))
                }
            }
        }
    }

    /**
     * Prepares photos or videos to be moved into the trash bin.
     */
    fun confirmPendingGalleryTrash(itemsToTrash: List<MediaItem>) = viewModelScope.launch(Dispatchers.IO) {
        if (itemsToTrash.isEmpty()) return@launch

        // Pack the photos into neat boxes that our Trash Bin database understands
        val mappedEntities = itemsToTrash.map { media ->
            TrashEntity(
                deletedTimestamp = System.currentTimeMillis(), // Record the exact moment they were trashed
                originalPath = media.path,
                contentUri = media.uri.toString(),
                mediaType = if (media.isVideo) "video" else "image",
                name = media.name,
                size = media.size
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Ask Android for permission to put them in the physical trash bin
                val intentSender = MediaStore.createTrashRequest(resolver, itemsToTrash.map { it.uri }, true).intentSender

                // Save sticky notes
                pendingTrashEntities = mappedEntities

                // Show popup
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create gallery trash request", e)
                _events.send(GalleryEvent.ShowToast("Failed to initiate trash request"))
            }
        } else {
            // Older phones don't have a physical trash bin, so we just do a "soft delete" by hiding them.
            urisFallbackDelete(uris = itemsToTrash.map { it.uri }, mappedEntities) { onRefreshGallery?.invoke() }
        }
    }

    // --- MUSIC TRASH METHODS ---
    // These do the exact same things as the Gallery methods above, but specifically for MP3 audio files.
    // They are separated because the Music player screen needs different refresh signals than the Photo Gallery screen.

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
            }
        } else {
            urisFallbackDelete(uris = itemsToTrash.map { Uri.parse(it.uri) }, mappedEntities) { onRefreshMusic?.invoke() }
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
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music trash request", e)
            }
        } else {
            urisFallbackDelete(uris = songs.map { Uri.parse(it.uri) }, mappedEntities) { onRefreshMusic?.invoke() }
        }
    }

    fun restoreSongs(items: List<TrashEntity>, onComplete: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        if (items.isEmpty()) return@launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intentSender = MediaStore.createTrashRequest(resolver, items.map { Uri.parse(it.contentUri) }, false).intentSender
                pendingRestoreEntities = items
                pendingRestoreOnComplete = onComplete
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create music restore request", e)
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        } else {
            withContext(Dispatchers.Main) {
                _events.send(GalleryEvent.ShowToast("These items can't be restored on this Android version"))
                onComplete?.invoke()
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
                _events.send(GalleryEvent.RequestPermission(intentSender))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request for music", e)
                withContext(Dispatchers.Main) { onComplete?.invoke() }
            }
        } else {
            val successfulDeletes = mutableListOf<Long>()
            items.forEach { item ->
                try {
                    val deleted = resolver.delete(Uri.parse(item.contentUri), null, null)
                    if (deleted > 0) successfulDeletes.add(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for music", e)
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

    /**
     * FALLBACK FOR OLD PHONES (Android 10 and below)
     * Because old phones don't have a physical "Trash Bin" built into the OS, we just delete the files directly.
     * We don't need a system popup permission, so we can just do it instantly.
     */
    private suspend fun urisFallbackDelete(uris: List<Uri>, entities: List<TrashEntity>, onExecuted: suspend () -> Unit) {
        val successfulUris = mutableListOf<Uri>()

        uris.forEach { uri ->
            try {
                // Delete it instantly
                if (resolver.delete(uri, null, null) > 0) {
                    successfulUris.add(uri) // Remember that it worked
                }
            } catch (_: Exception) {}
        }

        // Only add the files that actually successfully deleted to our Trash Bin list database
        val verifiedEntities = entities.filter { entity ->
            successfulUris.any { it.toString() == entity.contentUri }
        }

        if (verifiedEntities.isNotEmpty()) {
            galleryDao.insertTrashItemsBulk(verifiedEntities)
        }

        // Tell the screen we are done
        withContext(Dispatchers.Main) { onExecuted() }
    }
}