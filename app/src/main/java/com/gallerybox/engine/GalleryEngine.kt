@file:Suppress("unused", "DEPRECATION", "UnstableApiUsage", "MemberVisibilityCanBePrivate")
@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.gallerybox.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gallerybox.data.MediaItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.log2
import kotlin.random.Random

@UnstableApi
@Singleton
class GalleryEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

    /**
     * Provides a robust PagingSource to load media directly from MediaStore in chunks.
     * Use this in the ViewModel instead of `fetchAllMedia()` to prevent memory explosion on large galleries.
     */
    fun getMediaPagingSource(): PagingSource<GalleryPagingSource.Cursor, MediaItem> {
        return GalleryPagingSource(context, this)
    }

    @Deprecated("Loads entire gallery into memory. Use getMediaPagingSource() for UI displaying.")
    suspend fun fetchAllMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fetchMedia(null, null, null, null)
        } else {
            // LIMIT-based paged fallback for pre-R devices instead of loading everything into a single cursor
            // This prevents the 1-3 sec freeze caused by CursorWindow allocation on massive sets
            val allMedia = mutableListOf<MediaItem>()
            var afterDate: Long? = null
            var afterId: Long? = null
            while (isActive) {
                val chunk = fetchMedia(null, afterDate, afterId, 1000)
                if (chunk.isEmpty()) break
                allMedia.addAll(chunk)
                val lastItem = chunk.last()
                afterDate = lastItem.dateAdded
                afterId = lastItem.id
            }
            allMedia
        }
    }

    suspend fun fetchIncrementalMedia(lastGeneration: Long): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fetchMedia(lastGeneration, null, null, null)
        } else {
            // On pre-R devices, we don't have generation tracking, so incremental syncs fall back
            // to pulling just the latest N items instead of doing a full table scan every time.
            fetchMedia(null, null, null, 500)
        }
    }

    suspend fun fetchMedia(minGeneration: Long?, afterDateAdded: Long?, afterId: Long?, limit: Int?): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()
        val resolver = context.contentResolver

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val proj = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                add(MediaStore.Files.FileColumns.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_FAVORITE)
                add(MediaStore.MediaColumns.IS_TRASHED)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }
        }

        var sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val args = mutableListOf<String>()

        if (minGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sel += " AND ${MediaStore.MediaColumns.GENERATION_ADDED} > ?"
            args.add(minGeneration.toString())
        }

        // Keyset instead of OFFSET: strictly "older than the last row we saw"
        if (afterDateAdded != null && afterId != null) {
            sel += " AND (${MediaStore.Files.FileColumns.DATE_ADDED} < ? OR " +
                    "(${MediaStore.Files.FileColumns.DATE_ADDED} = ? AND ${MediaStore.Files.FileColumns._ID} < ?))"
            args.add(afterDateAdded.toString())
            args.add(afterDateAdded.toString())
            args.add(afterId.toString())
        }

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && limit != null) {
            val bundle = Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns._ID))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            }
            resolver.query(uri, proj.toTypedArray(), bundle, null)
        } else {
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC" +
                    if (limit != null) " LIMIT $limit" else ""
            resolver.query(uri, proj.toTypedArray(), sel, if (args.isEmpty()) null else args.toTypedArray(), sortOrder)
        }

        cursor?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val sizeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val typeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bIdC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bNameC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val durC = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val relC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val volC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) else -1
            val dataC = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Files.FileColumns.DATA) else -1
            val trashC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            val favC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val wC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.WIDTH) else -1
            val hC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.HEIGHT) else -1

            val hiddenItemsSet = getHiddenItems()

            while (c.moveToNext()) {
                if (trashC != -1 && c.getInt(trashC) == 1) continue
                if (mediaList.size % 200 == 0) coroutineContext.ensureActive()

                val type = c.getInt(typeC)
                val isImg = type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                val isV = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                if (!isImg && !isV) continue

                val name = c.getString(nameC) ?: "Unknown"
                val mimeType = c.getString(mimeC)?.lowercase(Locale.ROOT) ?: ""
                val id = c.getLong(idC)
                val volumeName = if (volC != -1) c.getString(volC) ?: "" else ""

                val path = if (dataC != -1) c.getString(dataC) ?: "" else ""
                val relP = if (relC != -1 && c.getString(relC) != null) {
                    c.getString(relC)!!
                } else {
                    path.substringBeforeLast('/', "")
                }

                val isFileHidden = name.startsWith(".") || relP.split("/").any { it.startsWith(".") }
                if (isFileHidden) continue
                if (hiddenItemsSet.contains(id.toString())) continue

                // Proper multi-volume URI support
                val baseUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeName.isNotBlank()) {
                    if (isImg) MediaStore.Images.Media.getContentUri(volumeName) else MediaStore.Video.Media.getContentUri(volumeName)
                } else {
                    if (isImg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val cUri = ContentUris.withAppendedId(baseUri, id)
                val dSec = c.getLong(dateC)

                mediaList.add(
                    MediaItem(
                        id = id,
                        uri = cUri,
                        path = path,
                        relativePath = relP,
                        name = name,
                        mimeType = mimeType,
                        size = c.getLong(sizeC),
                        dateAdded = dSec,
                        isVideo = isV,
                        isHidden = false,
                        isFavorite = if (favC != -1) c.getInt(favC) == 1 else false,
                        bucketId = c.getString(bIdC) ?: "unknown",
                        bucketName = c.getString(bNameC) ?: "Internal",
                        duration = if (isV && durC != -1) c.getLong(durC) else 0L,
                        width = if (wC != -1) c.getInt(wC) else 0,
                        height = if (hC != -1) c.getInt(hC) else 0,
                        volumeName = volumeName
                    )
                )
            }
        } ?: throw IOException("MediaStore query returned null cursor")

        return@withContext mediaList
    }

    fun getHiddenItems(): Set<String> = prefs.getStringSet("hidden_items", emptySet()) ?: emptySet()
    fun getHiddenAlbums(): Set<String> = prefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()

    fun hideItems(ids: List<Long>) {
        prefs.edit { putStringSet("hidden_items", (getHiddenItems() + ids.map { it.toString() }).toSet()) }
    }

    fun hideAlbums(ids: List<String>) {
        prefs.edit { putStringSet("hidden_albums", (getHiddenAlbums() + ids).toSet()) }
    }

    fun calculateInSampleSize(origW: Int, origH: Int, targetW: Int, targetH: Int): Int {
        if (origH <= targetH && origW <= targetW) return 1
        val ratio = Math.max(origW.toFloat() / targetW, origH.toFloat() / targetH)
        return Math.pow(2.0, floor(log2(ratio.toDouble()))).toInt().coerceAtLeast(1)
    }

    private fun getQuickHash(item: MediaItem): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(item.uri)?.use { inp ->
                val buf = ByteArray(4096)
                val read = inp.read(buf)
                if (read != -1) md.update(buf, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun getFullHash(item: MediaItem): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(item.uri)?.use { inp ->
                val buf = ByteArray(8192)
                var read: Int
                while (inp.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun findDuplicates(media: List<MediaItem>): List<List<MediaItem>> = withContext(Dispatchers.IO) {
        val bySize = media.groupBy { it.size }.filter { it.value.size > 1 }.values
        val byRes = bySize.flatMap { group -> group.groupBy { "${it.width}x${it.height}" }.filter { it.value.size > 1 }.values }

        val duplicates = mutableListOf<List<MediaItem>>()
        val cores = Runtime.getRuntime().availableProcessors()
        val activeThreads = maxOf(1, cores - 1)
        val dispatcher = Dispatchers.IO.limitedParallelism(activeThreads)

        byRes.forEach { group ->
            // Staged duplicate detection: Pass 1 - Quick hash (first 4KB)
            val byQuickHash = ConcurrentHashMap<String, MutableList<MediaItem>>()
            withContext(dispatcher) {
                group.map { item ->
                    async {
                        val qHash = getQuickHash(item)
                        if (qHash.isNotEmpty()) {
                            byQuickHash.getOrPut(qHash) { mutableListOf() }.add(item)
                        }
                    }
                }.awaitAll()
            }

            // Staged duplicate detection: Pass 2 - Full hash only for quick-hash collisions
            byQuickHash.values.filter { it.size > 1 }.forEach { collisionGroup ->
                val byFullHash = ConcurrentHashMap<String, MutableList<MediaItem>>()
                withContext(dispatcher) {
                    collisionGroup.map { item ->
                        async {
                            val hash = getFullHash(item)
                            if (hash.isNotEmpty()) {
                                byFullHash.getOrPut(hash) { mutableListOf() }.add(item)
                            }
                        }
                    }.awaitAll()
                }
                duplicates.addAll(byFullHash.values.filter { it.size > 1 })
            }
        }
        return@withContext duplicates
    }
}

class GalleryPagingSource(
    private val context: Context,
    private val engine: GalleryEngine
) : PagingSource<GalleryPagingSource.Cursor, MediaItem>() {

    data class Cursor(val dateAdded: Long, val id: Long)

    override suspend fun load(params: LoadParams<Cursor>): LoadResult<Cursor, MediaItem> {
        val after = params.key
        return try {
            val media = engine.fetchMedia(
                minGeneration = null,
                afterDateAdded = after?.dateAdded,
                afterId = after?.id,
                limit = params.loadSize
            )
            val last = media.lastOrNull()
            LoadResult.Page(
                data = media,
                prevKey = null, // DESC-only feed; no meaningful "newer" page to prepend
                nextKey = if (media.isEmpty() || media.size < params.loadSize) null
                else Cursor(last!!.dateAdded, last.id)
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Cursor, MediaItem>): Cursor? = null
}

class VideoPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val rFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 5000, 500, 1000)
            .build()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(rFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
            }

        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<Media3Item>
            ): ListenableFuture<MutableList<Media3Item>> {
                return Futures.immediateFuture(
                    mediaItems.map {
                        if (it.localConfiguration != null) it else Media3Item.fromUri(it.mediaId)
                    }.toMutableList()
                )
            }
        }

        mediaSession = MediaSession.Builder(this, player!!)
            .setId("GalleryBox_Video")
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}

sealed class MediaOpResult {
    data class Success(
        val uris: List<Uri> = emptyList(),
        val copiedCount: Int = 0,
        val skippedCount: Int = 0,
        val deletedCount: Int = 0
    ) : MediaOpResult()

    data class Failed(val reason: String) : MediaOpResult()

    data class PermissionRequired(
        val intentSender: IntentSender,
        val pendingRollbackUris: List<Uri> = emptyList(),
        val autoDeleteHandledByOs: Boolean = false,
        val isWriteRequest: Boolean = false
    ) : MediaOpResult()

    data class SafPermissionRequired(
        val intent: Intent,
        val pendingRollbackUris: List<Uri> = emptyList()
    ) : MediaOpResult()

    data class AlreadyExists(val message: String) : MediaOpResult()
    data object Cancelled : MediaOpResult()
}

data class TargetAlbum(
    val id: String = "",
    val name: String,
    val relativePath: String?,
    val mediaType: Int? = null,
    val bucketId: String = "",
    val coverUri: Uri = Uri.EMPTY,
    val volumeName: String? = null,
    val isSdCard: Boolean = false
)

@Singleton
class MediaOperationEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)

    fun markAlbumAsSdCard(bucketId: String) {
        if (bucketId.isBlank()) return
        val current = prefs.getStringSet("sd_card_album_ids", emptySet()) ?: emptySet()
        prefs.edit { putStringSet("sd_card_album_ids", current + bucketId) }
    }

    fun isSdCardAlbum(bucketId: String): Boolean =
        (prefs.getStringSet("sd_card_album_ids", emptySet()) ?: emptySet()).contains(bucketId)

    fun saveSafTreeUri(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("saf_tree_uri", uri.toString()).apply()
    }

    private fun getSafTreeUri(): Uri? {
        val uriStr = prefs.getString("saf_tree_uri", null) ?: return null
        return Uri.parse(uriStr)
    }

    private fun createSafIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun getOrCreateSafDirectory(relativePath: String): DocumentFile? {
        val treeUri = getSafTreeUri() ?: return null
        var currentDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            var nextDoc = currentDoc.findFile(part)
            if (nextDoc == null) {
                nextDoc = currentDoc.createDirectory(part)
            }
            if (nextDoc == null) return null
            currentDoc = nextDoc
        }
        return currentDoc
    }

    private fun getSafDocumentFile(file: File): DocumentFile? {
        val treeUri = getSafTreeUri() ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        val parts = file.absolutePath.split("/").filter { it.isNotEmpty() }
        var currentDoc: DocumentFile? = rootDoc

        for (i in parts.indices) {
            val part = parts[i]
            val next = currentDoc?.findFile(part)
            if (next != null) {
                currentDoc = next
            }
        }
        return if (currentDoc?.isFile == true) currentDoc else null
    }

    private fun getResolvedRelativePath(targetAlbum: TargetAlbum, isVideo: Boolean): String {
        val path = targetAlbum.relativePath
        if (!path.isNullOrBlank()) {
            return path.trim().trimEnd('/') + "/"
        }
        return if (isVideo) "Movies/${targetAlbum.name}/" else "Pictures/${targetAlbum.name}/"
    }

    suspend fun createAlbum(targetAlbum: TargetAlbum): MediaOpResult = withContext(Dispatchers.IO) {
        val safePath = getResolvedRelativePath(targetAlbum, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android Q+: Relies on MediaStore relative path mapping during media insertion.
            return@withContext MediaOpResult.Success()
        }

        val root = Environment.getExternalStorageDirectory()
        val albumDir = File(root, safePath)

        if (!albumDir.exists() && !albumDir.mkdirs()) {
            val safDir = getOrCreateSafDirectory(safePath)
            if (safDir == null) {
                val safUri = getSafTreeUri()
                if (safUri == null) {
                    return@withContext MediaOpResult.SafPermissionRequired(createSafIntent())
                }
                return@withContext MediaOpResult.Failed("Could not create physical directory on external storage.")
            }
        }
        MediaOpResult.Success()
    }

    suspend fun copyMedia(
        items: List<MediaItem>,
        targetAlbum: TargetAlbum,
        onProgress: (phase: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): MediaOpResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext MediaOpResult.Success()

        val resolver = context.contentResolver
        val newUris = mutableListOf<Uri>()
        val existingNames = fetchExistingItemNames(targetAlbum).toMutableSet()
        val skipped = 0

        try {
            for ((index, item) in items.withIndex()) {
                if (!isActive) throw CancellationException("Operation cancelled by user")
                onProgress("Copying", index + 1, items.size)

                val uniqueName = getUniqueName(item.name, existingNames)
                existingNames.add(uniqueName)
                val relativePath = getResolvedRelativePath(targetAlbum, item.isVideo)

                var destUri: Uri? = null

                if (!targetAlbum.isSdCard) {
                    try {
                        val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            targetAlbum.volumeName?.takeIf { it.isNotBlank() && it in MediaStore.getExternalVolumeNames(context) } ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        } else {
                            null
                        }

                        val destCollection = if (item.isVideo) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(volumeName!!)
                            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(volumeName!!)
                            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }

                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
                            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            } else {
                                val root = Environment.getExternalStorageDirectory()
                                val destDir = File(root, relativePath)
                                if (!destDir.exists()) destDir.mkdirs()
                                put(MediaStore.Files.FileColumns.DATA, File(destDir, uniqueName).absolutePath)
                            }
                        }
                        destUri = resolver.insert(destCollection, contentValues)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (destUri == null) {
                    val safDir = getOrCreateSafDirectory(relativePath)
                    if (safDir != null) {
                        val baseName = uniqueName.substringBeforeLast(".", uniqueName)
                        val safFile = safDir.createFile(item.mimeType, baseName)
                        destUri = safFile?.uri
                    } else if (getSafTreeUri() == null) {
                        return@withContext MediaOpResult.SafPermissionRequired(createSafIntent(), newUris)
                    }
                }

                if (destUri == null) throw IOException("Failed to create destination record for $uniqueName")
                newUris.add(destUri)

                resolver.openInputStream(item.uri)?.use { input ->
                    resolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                } ?: throw IOException("Failed to open streams for copying $uniqueName")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && destUri.scheme == "content" && destUri.authority == MediaStore.AUTHORITY) {
                    val updateValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(destUri, updateValues, null, null)
                }
            }

            for ((index, destUri) in newUris.withIndex()) {
                if (!isActive) throw CancellationException("Operation cancelled by user")
                onProgress("Verifying", index + 1, newUris.size)

                val originalItem = items[index]

                // Rigorous File Size Verification
                resolver.openFileDescriptor(destUri, "r")?.use { pfd ->
                    val copiedSize = pfd.statSize
                    if (copiedSize == 0L || (originalItem.size > 0 && copiedSize != originalItem.size)) {
                        throw IOException("Copied file size mismatch or unreadable: expected ${originalItem.size}, got $copiedSize")
                    }
                } ?: throw IOException("Failed to open file descriptor for newly copied file verification")
            }

        } catch (e: CancellationException) {
            rollback(newUris)
            return@withContext MediaOpResult.Cancelled
        } catch (e: Exception) {
            rollback(newUris)
            return@withContext MediaOpResult.Failed(e.localizedMessage ?: "Unknown error during copy")
        }

        MediaOpResult.Success(newUris, newUris.size, skipped, 0)
    }

    suspend fun moveMedia(
        items: List<MediaItem>,
        targetAlbum: TargetAlbum,
        onProgress: (phase: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): MediaOpResult = withContext(Dispatchers.IO) {
        val itemsToMove = items.filter { item ->
            val isSameBucket = targetAlbum.bucketId.isNotBlank() && item.bucketId == targetAlbum.bucketId
            !isSameBucket
        }
        val skippedSameAlbumCount = items.size - itemsToMove.size

        if (itemsToMove.isEmpty()) {
            return@withContext MediaOpResult.AlreadyExists("All selected items are already in this album.")
        }

        val copyResult = copyMedia(itemsToMove, targetAlbum, onProgress)
        if (copyResult !is MediaOpResult.Success) return@withContext copyResult

        val copiedUris = copyResult.uris
        val originalUris = itemsToMove.map { it.uri }
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(resolver, originalUris)
            return@withContext MediaOpResult.PermissionRequired(pendingIntent.intentSender, copiedUris, autoDeleteHandledByOs = true)
        } else {
            var failureCount = 0
            for ((index, item) in itemsToMove.withIndex()) {
                onProgress("Deleting", index + 1, itemsToMove.size)
                var deleted = false

                try {
                    val rows = resolver.delete(item.uri, null, null)
                    if (rows > 0) deleted = true
                } catch (e: RecoverableSecurityException) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        return@withContext MediaOpResult.PermissionRequired(e.userAction.actionIntent.intentSender, copiedUris)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!deleted && item.path.isNotEmpty()) {
                    val file = File(item.path)
                    if (file.exists() && file.delete()) deleted = true

                    if (!deleted) {
                        val safDoc = getSafDocumentFile(file)
                        if (safDoc != null && safDoc.delete()) {
                            deleted = true
                        } else if (getSafTreeUri() == null) {
                            return@withContext MediaOpResult.SafPermissionRequired(createSafIntent(), copiedUris)
                        }
                    }
                }

                if (!deleted) failureCount++
            }

            if (failureCount > 0) {
                rollback(copiedUris)
                return@withContext MediaOpResult.Failed("Failed to delete $failureCount original items. Move reverted.")
            }

            return@withContext MediaOpResult.Success(copiedUris, copiedUris.size, skippedSameAlbumCount, originalUris.size)
        }
    }

    suspend fun resumeDelete(uris: List<Uri>): MediaOpResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var failureCount = 0
        for (uri in uris) {
            var deleted = false
            try {
                if (resolver.delete(uri, null, null) > 0) deleted = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (!deleted) {
                var path = ""
                try {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) path = c.getString(0) ?: ""
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (path.isNotEmpty()) {
                    val file = File(path)
                    if (file.exists() && file.delete()) deleted = true

                    if (!deleted) {
                        val safDoc = getSafDocumentFile(file)
                        if (safDoc != null && safDoc.delete()) {
                            deleted = true
                        }
                    }
                }
            }

            if (!deleted) failureCount++
        }

        if (failureCount > 0) return@withContext MediaOpResult.Failed("Failed to delete $failureCount items.")
        MediaOpResult.Success(uris, 0, 0, uris.size)
    }

    suspend fun rollback(uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        val resolver = context.contentResolver

        for (uri in uris) {
            try {
                if (resolver.delete(uri, null, null) == 0) {
                    val docFile = DocumentFile.fromSingleUri(context, uri)
                    docFile?.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun fetchExistingItemNames(targetAlbum: TargetAlbum): List<String> = withContext(Dispatchers.IO) {
        val existingNames = mutableListOf<String>()
        val picPath = getResolvedRelativePath(targetAlbum, false)
        val movPath = getResolvedRelativePath(targetAlbum, true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val root = Environment.getExternalStorageDirectory()
                File(root, picPath).listFiles()?.forEach { file -> existingNames.add(file.name) }
                if (picPath != movPath) {
                    File(root, movPath).listFiles()?.forEach { file -> existingNames.add(file.name) }
                }
            } catch (e: Exception) { e.printStackTrace() }
            return@withContext existingNames
        }

        val resolver = context.contentResolver
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf("$picPath%", "$movPath%")

        try {
            resolver.query(uri, proj, sel, args, null)?.use { c ->
                val nameC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) c.getString(nameC)?.let { existingNames.add(it) }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return@withContext existingNames
    }

    private fun getUniqueName(originalName: String, existingNames: Set<String>): String {
        if (!existingNames.contains(originalName)) return originalName

        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = originalName.substringAfterLast(".", "")
        val dotExt = if (ext.isNotEmpty()) ".$ext" else ""

        var counter = 1
        var newName = "$nameWithoutExt ($counter)$dotExt"
        while (existingNames.contains(newName)) {
            counter++
            newName = "$nameWithoutExt ($counter)$dotExt"
        }
        return newName
    }
}


object NotificationHelper {
    const val WORK_NAME_SCHEDULER = "DailySchedulerWork"
    const val WORK_NAME_DISPLAY = "DisplayNotificationWork"

    fun enableDailyNotifications(context: Context) {
        val periodicRequest = PeriodicWorkRequestBuilder<DailySchedulerWorker>(24, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_SCHEDULER,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    fun disableDailyNotifications(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(WORK_NAME_SCHEDULER)
        workManager.cancelUniqueWork(WORK_NAME_DISPLAY)
    }
}

class DailySchedulerWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val randomDelayMinutes = Random.nextLong(0, 23 * 60)

        val displayRequest = OneTimeWorkRequestBuilder<NotificationDisplayWorker>()
            .setInitialDelay(randomDelayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            NotificationHelper.WORK_NAME_DISPLAY,
            ExistingWorkPolicy.REPLACE,
            displayRequest
        )

        return Result.success()
    }
}

class NotificationDisplayWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val quotes = listOf(
        "Capture the moment before it's gone.",
        "Your memories are waiting to be revisited.",
        "A picture is a poem without words.",
        "Take a moment to look back at your best days.",
        "Every picture tells a story. What's yours today?",
        "Time flies, but memories in GalleryBox last forever."
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        showNotification()
        Result.success()
    }

    private fun showNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "daily_reminder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Daily Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily random quotes and app reminders"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val randomQuote = quotes.random()

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("GalleryBox Reminder")
            .setContentText(randomQuote)
            .setStyle(NotificationCompat.BigTextStyle().bigText(randomQuote))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (pendingIntent != null) {
            notificationBuilder.setContentIntent(pendingIntent)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}