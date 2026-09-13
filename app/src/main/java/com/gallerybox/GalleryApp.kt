package com.gallerybox

// --- IMPORTS ---
// This is the toolbox. We are bringing in tools for memory management, fetching images,
// notifications, and handling background tasks.
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.Options
import coil.size.Dimension
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * --- THE HEADQUARTERS (Application Class) ---
 * The `Application` class is the very first thing that wakes up when the user taps the app icon.
 * It runs before any screens (Activities) are shown. We use it to set up global rules for the whole app.
 *
 * `@HiltAndroidApp` tells a system called 'Hilt' to get ready. Hilt is like a factory manager
 * that automatically builds tools (like databases and audio engines) and hands them to the screens that need them.
 */
@HiltAndroidApp
class GalleryApp : Application(), ImageLoaderFactory {

    /**
     * `onCreate` is the morning alarm. It rings the exact moment the app launches.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel() // Register our notification settings with Android immediately.
    }

    /**
     * THE ART CURATOR (ImageLoader).
     * Our app uses a tool called "Coil" to load thousands of pictures into grids without crashing the phone.
     * Here, we give Coil its strict rules on how much phone memory it is allowed to use.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Short-term memory (RAM). We tell Coil it can never use more than 20% of the phone's total RAM for images.
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            // Long-term memory (Hard Drive Space). We give Coil a small folder to save downloaded or processed images
            // so they load instantly next time. It can use up to 3% of the phone's total storage space.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.03)
                    .build()
            }
            // Special Glasses (Components). We give Coil special abilities to understand different file types.
            .components {
                add(MediaStoreThumbnailFetcher.Factory(this@GalleryApp)) // Our custom tool to load tiny thumbnails really fast
                add(VideoFrameDecoder.Factory()) // Ability to pull a single picture frame out of a video file

                // Ability to play moving GIFs
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory()) // For newer Android phones
                } else {
                    add(GifDecoder.Factory()) // For older Android phones
                }
            }
            .crossfade(false) // Turn off fade-in animations so the grid scrolls as fast as possible
            .build()
    }

    /**
     * THE RESERVED HIGHWAY LANE (Notification Channel).
     * Modern Android requires apps to register "Channels" before they can send notifications.
     * This allows users to go into settings and mute specific *types* of notifications without muting the whole app.
     */
    private fun createNotificationChannel() {
        // Channels were introduced in Android 8.0 (Oreo). Older phones don't need this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "music_channel"
            val channelName = "Music Playback"
            val importance = NotificationManager.IMPORTANCE_LOW // "LOW" means it shows up silently, without vibrating or dinging.

            // Create the channel and set its rules
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "Controls for media playback"
                setSound(null, null) // No annoying notification sounds
                setShowBadge(false) // Don't put a little red dot on the app icon on the home screen
            }

            // Hand the finished channel rules to the Android operating system
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/**
 * --- THE FAST-TRACK COURIER (MediaStoreThumbnailFetcher) ---
 * When displaying 50 photos on the screen at once, loading 50 full-sized 10-Megabyte camera files will melt the phone.
 * Android actually secretly generates tiny, low-quality preview pictures (Thumbnails) for every photo you take.
 * This class is a custom courier that bypasses the giant original files and asks Android specifically for those tiny, fast previews.
 */
class MediaStoreThumbnailFetcher(
    private val data: Uri, // The digital address of the photo
    private val options: Options, // Instructions on how big the image should be
    private val context: Context
) : Fetcher {

    // The actual background job to go fetch the image
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {

        // Use a "Permit" (like a bouncer at a club). We only let 6 image loads happen at the exact same time.
        // If a 7th tries to load, it has to wait in line. This keeps the phone from stuttering.
        thumbnailSemaphore.withPermit {
            coroutineContext.ensureActive() // Check if the user already scrolled past this image. If so, cancel the load.

            // Figure out the requested size. If none is given, assume a small 256x256 square.
            val targetW = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_THUMB_SIZE
            val targetH = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_THUMB_SIZE

            val bitmap: Bitmap? = try {
                // Fetching the tiny preview is different depending on how old the Android phone is.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Modern Android 10+
                    context.contentResolver.loadThumbnail(data, android.util.Size(targetW, targetH), null)
                } else {
                    // Older Android
                    val id = ContentUris.parseId(data)
                    val isVideo = data.toString().contains("/video/") // Check if we are grabbing a video preview or a photo preview
                    if (isVideo) {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                }
            } catch (e: Exception) {
                null // If the file is corrupted or missing, just return nothing instead of crashing
            }

            coroutineContext.ensureActive() // Check one last time if the user scrolled away while we were loading it.

            // If we successfully got a picture, package it up and hand it back to the screen to display.
            if (bitmap != null) {
                DrawableResult(
                    drawable = bitmap.toDrawable(context.resources), // Convert raw pixels into a displayable picture
                    isSampled = true, // Tells the app this is a shrunken preview, not the original size
                    dataSource = DataSource.DISK // Tells the app we got this from the phone's hard drive
                )
            } else {
                null
            }
        }
    }

    /**
     * The Factory determines IF this courier should be used.
     * Before Coil loads an image, it asks this Factory: "Hey, can you handle this file?"
     */
    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            // We only handle local phone files (MediaStore). If it's a web link (http://), we say "No, let someone else handle it."
            if (data.scheme != "content" || data.authority != MediaStore.AUTHORITY) return null
            return MediaStoreThumbnailFetcher(data, options, context)
        }
    }

    companion object {
        // The default size (in pixels) for our grid previews
        private const val DEFAULT_THUMB_SIZE = 256

        // THE BOUNCER. A Semaphore acts like toll booth lanes.
        // We only open 6 lanes at a time. If you scroll wildly and ask for 50 images,
        // 6 go through, and 44 wait in a neat line so the phone doesn't freeze.
        private val thumbnailSemaphore = Semaphore(permits = 6)
    }
}