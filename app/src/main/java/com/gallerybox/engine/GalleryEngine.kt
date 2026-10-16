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
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.Log
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
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

// =========================================================================================
// 🧠 CORE GALLERY ENGINE (MediaStore Indexing, Incremental Sync & Duplicate Hasher)
// =========================================================================================
@UnstableApi
@Singleton
class GalleryEngine @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE)

    fun getMediaPagingSource(): PagingSource<GalleryPagingSource.Cursor, MediaItem> {
        return GalleryPagingSource(context, this)
    }

    suspend fun fetchAllMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fetchMedia(null, null, null, null)
        } else {
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
            fetchMedia(null, null, null, 500)
        }
    }

    suspend fun fetchMedia(
        minGeneration: Long?,
        afterDateAdded: Long?,
        afterId: Long?,
        limit: Int?
    ): List<MediaItem> = withContext(Dispatchers.IO) {
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
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATA
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

        var sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val args = mutableListOf<String>()

        if (minGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sel += " AND (${MediaStore.MediaColumns.GENERATION_ADDED} > ? OR ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?)"
            args.add(minGeneration.toString())
            args.add(minGeneration.toString())
        }

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
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns._ID)
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
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
            val dataC = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val trashC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1
            val favC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) else -1
            val wC = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val hC = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

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

                val baseUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeName.isNotBlank()) {
                    if (isImg) MediaStore.Images.Media.getContentUri(volumeName) else MediaStore.Video.Media.getContentUri(volumeName)
                } else {
                    if (isImg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val cUri = ContentUris.withAppendedId(baseUri, id)
                val rawDate = c.getLong(dateC)
                val normalizedDateSec = when {
                    rawDate > 1_000_000_000_000L -> rawDate / 1000L
                    rawDate > 0L -> rawDate
                    else -> System.currentTimeMillis() / 1000L
                }

                val rawBucketName = c.getString(bNameC)
                val resolvedBucketName = rawBucketName?.takeIf { it.isNotBlank() }
                    ?: relP.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: File(path).parentFile?.name
                    ?: "Internal"

                mediaList.add(
                    MediaItem(
                        id = id,
                        uri = cUri,
                        path = path,
                        relativePath = relP,
                        name = name,
                        mimeType = mimeType,
                        size = c.getLong(sizeC),
                        dateAdded = normalizedDateSec,
                        isVideo = isV,
                        isHidden = false,
                        isFavorite = if (favC != -1) c.getInt(favC) == 1 else false,
                        bucketId = c.getString(bIdC) ?: resolvedBucketName.lowercase(Locale.ROOT).hashCode().toString(),
                        bucketName = resolvedBucketName,
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

    fun setHiddenAlbums(ids: Set<String>) {
        prefs.edit { putStringSet("hidden_albums", ids) }
    }

    fun hideItems(ids: List<Long>) {
        prefs.edit { putStringSet("hidden_items", (getHiddenItems() + ids.map { it.toString() }).toSet()) }
    }

    fun unhideItems(ids: List<Long>) {
        prefs.edit { putStringSet("hidden_items", (getHiddenItems() - ids.map { it.toString() }.toSet())) }
    }

    fun hideAlbums(ids: List<String>) {
        prefs.edit { putStringSet("hidden_albums", (getHiddenAlbums() + ids).toSet()) }
    }

    fun unhideAlbums(ids: List<String>) {
        prefs.edit { putStringSet("hidden_albums", (getHiddenAlbums() - ids.toSet())) }
    }

    fun calculateInSampleSize(origW: Int, origH: Int, targetW: Int, targetH: Int): Int {
        if (origH <= targetH && origW <= targetW) return 1
        val ratio = max(origW.toFloat() / targetW, origH.toFloat() / targetH)
        return 2.0.pow(floor(log2(ratio.toDouble()))).toInt().coerceAtLeast(1)
    }

    private fun getQuickHash(item: MediaItem): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(item.uri)?.use { inp ->
                val buf = ByteArray(8192)
                val read = inp.read(buf)
                if (read > 0) md.update(buf, 0, read)
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
                val buf = ByteArray(32 * 1024)
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
        val byRes = bySize.flatMap { group ->
            group.groupBy { "${it.width}x${it.height}" }.filter { it.value.size > 1 }.values
        }

        val duplicates = mutableListOf<List<MediaItem>>()
        val cores = Runtime.getRuntime().availableProcessors()
        val activeThreads = maxOf(1, cores - 1)
        val dispatcher = Dispatchers.IO.limitedParallelism(activeThreads)

        byRes.forEach { group ->
            val byQuickHash = ConcurrentHashMap<String, MutableList<MediaItem>>()
            withContext(dispatcher) {
                group.map { item ->
                    async {
                        val qHash = getQuickHash(item)
                        if (qHash.isNotEmpty()) {
                            byQuickHash.getOrPut(qHash) { Collections.synchronizedList(mutableListOf()) }.add(item)
                        }
                    }
                }.awaitAll()
            }

            byQuickHash.values.filter { it.size > 1 }.forEach { collisionGroup ->
                val byFullHash = ConcurrentHashMap<String, MutableList<MediaItem>>()
                withContext(dispatcher) {
                    collisionGroup.map { item ->
                        async {
                            val hash = getFullHash(item)
                            if (hash.isNotEmpty()) {
                                byFullHash.getOrPut(hash) { Collections.synchronizedList(mutableListOf()) }.add(item)
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
                prevKey = null,
                nextKey = if (media.isEmpty() || media.size < params.loadSize) null
                else Cursor(last!!.dateAdded, last.id)
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Cursor, MediaItem>): Cursor? = null
}

// =========================================================================================
// 🎬 BACKGROUND MEDIA3 PLAYBACK SERVICE
// =========================================================================================
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

// =========================================================================================
// 📦 FILE OPERATION MODELS & RESULTS
// =========================================================================================
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

// =========================================================================================
// 🚀 CROSS-STORAGE MEDIA OPERATION ENGINE (Phone↔Phone, Phone↔SD, SD↔Phone, SD↔SD)
// =========================================================================================
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

    fun isSdCardAlbum(bucketId: String, sampleVolumeName: String?): Boolean {
        if (isSdCardAlbum(bucketId)) return true
        if (sampleVolumeName.isNullOrBlank()) return false
        return sampleVolumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY && sampleVolumeName != "external"
    }

    fun saveSafTreeUri(uri: Uri) {
        val resolver = context.contentResolver

        val persistedPermission = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

        if (!persistedPermission) {
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }

        prefs.edit {
            putString("saf_tree_uri", uri.toString())
        }
    }

    private fun getSafTreeUri(): Uri? {
        val savedStr = prefs.getString("saf_tree_uri", null)
        if (!savedStr.isNullOrBlank()) {
            val parsed = Uri.parse(savedStr)
            val hasPerm = context.contentResolver.persistedUriPermissions.any {
                it.uri == parsed && it.isWritePermission
            }
            if (hasPerm) return parsed
        }

        val fallbackUri = context.contentResolver.persistedUriPermissions.firstOrNull { perm ->
            perm.isWritePermission &&
                    perm.uri.authority == "com.android.externalstorage.documents" &&
                    !perm.uri.toString().contains("primary%3A", ignoreCase = true) &&
                    !perm.uri.toString().contains("primary:", ignoreCase = true)
        }?.uri

        if (fallbackUri != null) {
            prefs.edit { putString("saf_tree_uri", fallbackUri.toString()) }
        }
        return fallbackUri
    }

    private fun createSafIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
    }

    private fun getOrCreateSafDirectory(relativePath: String): DocumentFile? {
        val treeUri = getSafTreeUri() ?: return null
        var currentDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!currentDoc.canWrite()) return null

        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val treeSubPath = treeDocId.substringAfter(':', "").trim('/')

        var effectiveRelPath = relativePath.trim('/')
        if (treeSubPath.isNotEmpty() && effectiveRelPath.startsWith(treeSubPath, ignoreCase = true)) {
            effectiveRelPath = effectiveRelPath.substring(treeSubPath.length).trim('/')
        }

        val parts = effectiveRelPath.split("/").filter { it.isNotBlank() }
        for (part in parts) {
            val cleanPart = part.trim()
            val existing = currentDoc.findFile(cleanPart)?.takeIf { it.isDirectory }
                ?: currentDoc.listFiles().firstOrNull { doc ->
                    doc.isDirectory && doc.name?.trim()?.equals(cleanPart, ignoreCase = true) == true
                }

            val nextDoc = existing ?: currentDoc.createDirectory(cleanPart) ?: return null
            currentDoc = nextDoc
        }
        return currentDoc
    }

    private fun fetchSafDirectoryNames(relativePath: String): Set<String> {
        val directory = getOrCreateSafDirectory(relativePath) ?: return emptySet()
        return directory.listFiles().mapNotNull { it.name }.toSet()
    }

    /**
     * Resolves a physical File on an SD card to its corresponding SAF DocumentFile.
     * Uses O(1) direct DocumentUri construction first, then falls back to directory traversal.
     */
    private fun getSafDocumentFile(file: File): DocumentFile? {
        val treeUri = getSafTreeUri() ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val volumePrefix = treeDocId.substringBefore(':', "")
        val treeRel = treeDocId.substringAfter(':', "").trim('/')

        val fullRelFromStorageRoot = file.absolutePath
            .removePrefix("/storage/")
            .substringAfter('/', "")
            .trim('/')

        if (fullRelFromStorageRoot.isBlank()) return null

        // 1. O(1) Direct DocumentUri lookup without calling listFiles()
        if (volumePrefix.isNotEmpty()) {
            val targetDocId = "$volumePrefix:$fullRelFromStorageRoot"
            val directDoc = runCatching {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                DocumentFile.fromSingleUri(context, docUri)
            }.getOrNull()
            if (directDoc != null && directDoc.exists() && directDoc.isFile) {
                return directDoc
            }
        }

        // 2. Fallback traversal from rootDoc
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var rel = fullRelFromStorageRoot
        if (treeRel.isNotEmpty() && rel.startsWith(treeRel, ignoreCase = true)) {
            rel = rel.substring(treeRel.length).trim('/')
        }
        if (rel.isBlank()) return null

        var cur: DocumentFile? = rootDoc
        for (part in rel.split("/").filter { it.isNotEmpty() }) {
            cur = cur?.findFile(part)
                ?: cur?.listFiles()?.firstOrNull { it.name?.equals(part, true) == true }
                        ?: return null
        }
        return if (cur?.isFile == true) cur else null
    }

    private fun getResolvedRelativePath(targetAlbum: TargetAlbum, isVideo: Boolean): String {
        val path = targetAlbum.relativePath
        if (!path.isNullOrBlank()) return path.trim().trimEnd('/') + "/"
        return "Pictures/${targetAlbum.name.trim()}/"
    }

    private fun resolveSafeMimeType(item: MediaItem, fileName: String): String {
        val rawMime = item.mimeType.trim().lowercase(Locale.ROOT)
        if (rawMime.startsWith("image/") || rawMime.startsWith("video/")) {
            return rawMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!fromExt.isNullOrBlank() && (fromExt.startsWith("image/") || fromExt.startsWith("video/"))) {
                return fromExt
            }
        }
        return if (item.isVideo) "video/mp4" else "image/jpeg"
    }

    private fun createSafFileSafely(safDir: DocumentFile, mimeType: String, uniqueName: String): DocumentFile? {
        val created = safDir.createFile(mimeType, uniqueName) ?: return null
        val actualName = created.name ?: return created
        if (!actualName.equals(uniqueName, ignoreCase = true) &&
            actualName.startsWith(uniqueName, ignoreCase = true)
        ) {
            runCatching { created.renameTo(uniqueName) }
        }
        return created
    }

    private fun openSourceInputStream(item: MediaItem): InputStream? {
        val resolver = context.contentResolver
        runCatching { resolver.openInputStream(item.uri) }.getOrNull()?.let { return it }

        if (item.path.isNotBlank()) {
            val file = File(item.path)
            if (file.exists() && file.canRead()) {
                runCatching { file.inputStream() }.getOrNull()?.let { return it }
            }
            val safDoc = getSafDocumentFile(file)
            if (safDoc != null) {
                runCatching { resolver.openInputStream(safDoc.uri) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun triggerMediaScannerForSafUris(uris: List<Uri>, mimeTypes: List<String>) {
        if (uris.isEmpty()) return
        val pathsToScan = mutableListOf<String>()
        val mimesToScan = mutableListOf<String>()

        for (i in uris.indices) {
            val u = uris[i]
            val docId = runCatching { DocumentsContract.getDocumentId(u) }.getOrNull() ?: continue
            val volumeId = docId.substringBefore(':', "")
            val relPath = docId.substringAfter(':', "")
            if (volumeId.isNotEmpty() && relPath.isNotEmpty()) {
                val physicalPath = if (volumeId.equals("primary", ignoreCase = true)) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/$relPath"
                } else {
                    "/storage/$volumeId/$relPath"
                }
                if (File(physicalPath).exists()) {
                    pathsToScan.add(physicalPath)
                    mimesToScan.add(mimeTypes.getOrElse(i) { "image/jpeg" })
                }
            }
        }

        if (pathsToScan.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    pathsToScan.toTypedArray(),
                    mimesToScan.toTypedArray(),
                    null
                )
            }
        }
    }

    suspend fun createAlbum(targetAlbum: TargetAlbum): MediaOpResult = withContext(Dispatchers.IO) {
        val safePath = getResolvedRelativePath(targetAlbum, false)

        if (targetAlbum.isSdCard || isSdCardAlbum(targetAlbum.bucketId, targetAlbum.volumeName)) {
            if (getSafTreeUri() == null) {
                return@withContext MediaOpResult.SafPermissionRequired(createSafIntent())
            }
            val safDir = getOrCreateSafDirectory(safePath)
                ?: return@withContext MediaOpResult.Failed("Could not create folder on SD card.")
            if (safDir.exists()) return@withContext MediaOpResult.Success()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return@withContext MediaOpResult.Success()
        }

        val root = Environment.getExternalStorageDirectory()
        val albumDir = File(root, safePath)

        if (!albumDir.exists() && !albumDir.mkdirs()) {
            val safDir = getOrCreateSafDirectory(safePath)
            if (safDir == null) {
                if (getSafTreeUri() == null) {
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
        val safCreatedUris = mutableListOf<Uri>()
        val safCreatedMimes = mutableListOf<String>()
        val existingNames = fetchExistingItemNames(targetAlbum).toMutableSet()
        val skipped = 0

        val isSdTarget = targetAlbum.isSdCard ||
                isSdCardAlbum(targetAlbum.bucketId, targetAlbum.volumeName)

        val preResolvedSafDir = if (isSdTarget) {
            if (getSafTreeUri() == null) {
                return@withContext MediaOpResult.SafPermissionRequired(createSafIntent(), emptyList())
            }
            val dir = getOrCreateSafDirectory(getResolvedRelativePath(targetAlbum, false))
            if (dir == null) {
                return@withContext MediaOpResult.Failed("Cannot access SD card folder: ${targetAlbum.name}")
            }
            dir
        } else null

        try {
            val copyBuffer = ByteArray(1024 * 1024)

            for ((index, item) in items.withIndex()) {
                if (!isActive) throw CancellationException("Operation cancelled by user")
                onProgress("Copying", index + 1, items.size)

                val fallbackItemName = item.name.ifBlank {
                    "media_${item.id}.${if (item.isVideo) "mp4" else "jpg"}"
                }
                val uniqueName = getUniqueName(fallbackItemName, existingNames)
                existingNames.add(uniqueName)
                val relativePath = getResolvedRelativePath(targetAlbum, item.isVideo)
                val safeMimeType = resolveSafeMimeType(item, uniqueName)

                var destUri: Uri? = null

                if (isSdTarget) {
                    val safDir = preResolvedSafDir
                        ?: getOrCreateSafDirectory(relativePath)
                        ?: run {
                            rollback(newUris)
                            return@withContext MediaOpResult.Failed("Cannot access SD card folder: ${targetAlbum.name}")
                        }

                    val createdDoc = createSafFileSafely(safDir, safeMimeType, uniqueName)
                    destUri = createdDoc?.uri
                    if (destUri != null) {
                        safCreatedUris.add(destUri)
                        safCreatedMimes.add(safeMimeType)
                    }
                } else {
                    try {
                        val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            targetAlbum.volumeName
                                ?.takeIf {
                                    it.isNotBlank() &&
                                            it in MediaStore.getExternalVolumeNames(context)
                                }
                                ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        } else {
                            null
                        }

                        val destCollection = if (item.isVideo) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.Video.Media.getContentUri(volumeName!!)
                            } else {
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.Images.Media.getContentUri(volumeName!!)
                            } else {
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }
                        }

                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
                            put(MediaStore.MediaColumns.MIME_TYPE, safeMimeType)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            } else {
                                val root = Environment.getExternalStorageDirectory()
                                val destDir = File(root, relativePath)

                                if (!destDir.exists() && !destDir.mkdirs()) {
                                    throw IOException("Could not create destination directory")
                                }

                                put(
                                    MediaStore.MediaColumns.DATA,
                                    File(destDir, uniqueName).absolutePath
                                )
                            }
                        }

                        destUri = resolver.insert(destCollection, contentValues)
                    } catch (e: Exception) {
                        Log.e("MediaOp", "MediaStore insert failed for $uniqueName", e)
                    }
                }

                if (destUri == null) {
                    rollback(newUris)
                    return@withContext MediaOpResult.Failed(
                        "Couldn't create \"$uniqueName\" in ${targetAlbum.name}"
                    )
                }

                newUris.add(destUri)

                val inputStream = openSourceInputStream(item)
                    ?: throw IOException("Cannot read source: ${item.name}")

                val outputStream = resolver.openOutputStream(destUri)
                    ?: throw IOException("Cannot write destination: $uniqueName")

                var bytesTransferred = 0L
                inputStream.use { input ->
                    outputStream.use { output ->
                        while (true) {
                            if (!isActive) throw CancellationException("Operation cancelled by user")
                            val read = input.read(copyBuffer)
                            if (read < 0) break
                            output.write(copyBuffer, 0, read)
                            bytesTransferred += read
                        }
                        output.flush()
                    }
                }

                if (bytesTransferred == 0L && item.size > 0L) {
                    throw IOException("Zero bytes written for ${item.name}")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    destUri.scheme == "content" &&
                    destUri.authority == MediaStore.AUTHORITY
                ) {
                    val updateValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(destUri, updateValues, null, null)
                }
            }

            if (safCreatedUris.isNotEmpty()) {
                triggerMediaScannerForSafUris(safCreatedUris, safCreatedMimes)
                if (targetAlbum.id.isNotBlank()) {
                    markAlbumAsSdCard(targetAlbum.id)
                }
                if (targetAlbum.bucketId.isNotBlank()) {
                    markAlbumAsSdCard(targetAlbum.bucketId)
                }
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
        val resolver = context.contentResolver

        val remainingUrisNeedOsConsent = mutableListOf<Uri>()
        var silentlyDeletedCount = 0

        for ((index, item) in itemsToMove.withIndex()) {
            onProgress("Cleaning up", index + 1, itemsToMove.size)
            var deleted = false

            try {
                if (resolver.delete(item.uri, null, null) > 0) {
                    deleted = true
                }
            } catch (e: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    if (item.path.isNotBlank()) {
                        val safDoc = getSafDocumentFile(File(item.path))
                        if (safDoc != null && safDoc.delete()) {
                            deleted = true
                            runCatching { resolver.delete(item.uri, null, null) }
                        }
                    }
                    if (!deleted) {
                        return@withContext MediaOpResult.PermissionRequired(
                            e.userAction.actionIntent.intentSender,
                            copiedUris
                        )
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}

            if (!deleted && item.path.isNotEmpty()) {
                val file = File(item.path)
                if (file.exists() && file.delete()) {
                    deleted = true
                    runCatching { resolver.delete(item.uri, null, null) }
                    runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) }
                } else {
                    val safDoc = getSafDocumentFile(file)
                    if (safDoc != null && safDoc.delete()) {
                        deleted = true
                        runCatching { resolver.delete(item.uri, null, null) }
                        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) }
                    }
                }
            }

            if (deleted) {
                silentlyDeletedCount++
            } else {
                remainingUrisNeedOsConsent.add(item.uri)
            }
        }

        if (remainingUrisNeedOsConsent.isEmpty()) {
            return@withContext MediaOpResult.Success(
                copiedUris,
                copiedUris.size,
                skippedSameAlbumCount,
                silentlyDeletedCount
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, remainingUrisNeedOsConsent)
                MediaOpResult.PermissionRequired(
                    pendingIntent.intentSender,
                    copiedUris,
                    autoDeleteHandledByOs = true
                )
            } catch (e: Exception) {
                rollback(copiedUris)
                MediaOpResult.Failed("Could not request delete permission: ${e.localizedMessage}")
            }
        } else {
            if (getSafTreeUri() == null) {
                return@withContext MediaOpResult.SafPermissionRequired(createSafIntent(), copiedUris)
            }
            rollback(copiedUris)
            return@withContext MediaOpResult.Failed("Failed to delete ${remainingUrisNeedOsConsent.size} original items. Move reverted.")
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
                    if (file.exists() && file.delete()) {
                        deleted = true
                        runCatching { resolver.delete(uri, null, null) }
                        runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
                    }

                    if (!deleted) {
                        val safDoc = getSafDocumentFile(file)
                        if (safDoc != null && safDoc.delete()) {
                            deleted = true
                            runCatching { resolver.delete(uri, null, null) }
                            runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
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
                val deletedRows = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
                if (deletedRows == 0) {
                    val docFile = DocumentFile.fromSingleUri(context, uri)
                    docFile?.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun fetchExistingItemNames(targetAlbum: TargetAlbum): List<String> = withContext(Dispatchers.IO) {
        if (targetAlbum.isSdCard ||
            isSdCardAlbum(targetAlbum.bucketId, targetAlbum.volumeName)
        ) {
            val existingNames = mutableSetOf<String>()

            existingNames += fetchSafDirectoryNames(
                getResolvedRelativePath(targetAlbum, false)
            )

            val videoPath = getResolvedRelativePath(targetAlbum, true)
            if (videoPath != getResolvedRelativePath(targetAlbum, false)) {
                existingNames += fetchSafDirectoryNames(videoPath)
            }

            return@withContext existingNames.toList()
        }

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
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext existingNames
    }

    private fun getUniqueName(originalName: String, existingNames: Set<String>): String {
        if (!existingNames.contains(originalName)) return originalName

        val hasDot = originalName.contains('.')
        val nameWithoutExt = if (hasDot) originalName.substringBeforeLast(".") else originalName
        val ext = if (hasDot) originalName.substringAfterLast(".", "") else ""
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

// =========================================================================================
// 🔔 DAILY MEMORY REMINDER WORKERS
// =========================================================================================
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