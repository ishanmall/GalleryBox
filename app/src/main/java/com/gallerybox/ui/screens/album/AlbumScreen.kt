@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gallerybox.ui.screens.album

// =========================================================================================
// 🧰 THE TOOLBOX (IMPORTS)
// =========================================================================================
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 🧠 GALLERY DATA & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.data.Album
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.viewmodel.AlbumSort
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.MergeMode
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.SecurityViewModel
import com.gallerybox.viewmodel.TrashViewModel

// ---------------------------------------------------------------------------
// 📱 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.WindowWidthSize
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState

const val ID_CAMERA = "virtual_camera"
const val ID_RECENT = "virtual_recent"
const val ID_FAVORITES = "virtual_favorites"
const val ID_VIDEOS = "virtual_videos"
const val ID_SCREENSHOTS = "virtual_screenshots"
const val ID_DOWNLOADS = "virtual_downloads"
const val ID_WHATSAPP = "virtual_whatsapp"
const val ID_INSTAGRAM = "virtual_instagram"
const val ID_HIDDEN = "virtual_hidden"

enum class DeviceTier { LOW, MID, HIGH }

private val monthYearFormatter by lazy { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
private val shortViewerDateFormatter by lazy { SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()) }

private val PremiumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
private val PremiumEnter = scaleIn(PremiumSpring) + fadeIn(tween(180, easing = FastOutSlowInEasing))
private val PremiumExit = scaleOut(tween(120)) + fadeOut(tween(120))

@Composable
fun ScaleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

enum class UiMediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

@Stable
data class AlbumActions(
    val onAlbumClick: (Album) -> Unit,
    val onNavigateToFavorites: () -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit = {},
    val onNavigateToDuplicates: () -> Unit,
    val onNavigateToScan: () -> Unit
)

@Stable
data class DetailActions(
    val onBack: () -> Unit,
    val onNavigateToPhotoEditor: (String, Long) -> Unit,
    val onNavigateToVideoEditor: (String, Long) -> Unit,
    val onNavigateToVideoPlayer: (String, List<String>) -> Unit,
    val onNavigateToMoveCopy: (String, String, String?) -> Unit,
    val onNavigateToTrash: () -> Unit,
    val onNavigateToHidden: () -> Unit,
    val onLockApp: () -> Unit = {},
    val onNavigateToWallpaper: (String, Long) -> Unit,
    val onAddMediaToAlbum: ((String) -> Unit)? = null,
    val onDeleteAlbum: ((String) -> Unit)? = null
)

sealed class AlbumUiDialog {
    data object None : AlbumUiDialog()
    data object GridSize : AlbumUiDialog()
    data object Sort : AlbumUiDialog()
    data object CreateAlbum : AlbumUiDialog()
    data class Rename(val album: Album) : AlbumUiDialog()
    data class Delete(val albums: List<Album>) : AlbumUiDialog()
    data class Info(val albums: List<Album>) : AlbumUiDialog()
    data class MoveCopy(val albums: List<Album>, val isMove: Boolean) : AlbumUiDialog()
    data class CreateAndMoveCopy(val albums: List<Album>, val isMove: Boolean) : AlbumUiDialog()
}

sealed class DetailUiDialog {
    data object None : DetailUiDialog()
    data object GridSize : DetailUiDialog()
    data object Sort : DetailUiDialog()
    data object DeleteAlbum : DetailUiDialog()
    data class Delete(val mediaIds: List<Long>, val directItems: List<MediaItem> = emptyList()) : DetailUiDialog()
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun getSmartName(item: MediaItem): String {
    val lower = item.name.lowercase()
    return when {
        "fdownloader" in lower -> "Downloaded Video"
        "instagram" in lower -> "Instagram Video"
        "whatsapp" in lower -> "WhatsApp Media"
        "screenshot" in lower -> "Screenshot"
        item.isVideo -> "Video"
        else -> "Photo"
    }
}

fun getFolderName(path: String): String {
    return try {
        File(path).parentFile?.name ?: "Unknown Folder"
    } catch (e: Exception) {
        "Unknown Folder"
    }
}

fun getDeviceTier(context: Context): DeviceTier {
    val m = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(m)
    val gb = m.totalMem / (1024.0 * 1024.0 * 1024.0)
    return when {
        gb <= 3.0 -> DeviceTier.LOW
        gb <= 8.0 -> DeviceTier.MID
        else -> DeviceTier.HIGH
    }
}

fun formatDuration(durationMs: Long): String {
    val t = durationMs / 1000
    val m = (t / 60) % 60
    val h = t / 3600
    val s = t % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun albumMatchesQuery(album: Album, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.lowercase().trim()
    if (album.name.lowercase().contains(q)) return true
    return when (q) {
        "video", "videos" -> album.id in listOf(ID_CAMERA, ID_WHATSAPP, ID_VIDEOS)
        "photo", "photos", "image" -> album.id in listOf(ID_CAMERA, ID_RECENT)
        "fav", "favorite", "heart" -> album.id == ID_FAVORITES
        "download" -> album.id == ID_DOWNLOADS
        "social", "chat" -> album.id in listOf(ID_WHATSAPP, ID_INSTAGRAM)
        else -> false
    }
}

private fun resolveMediaForAlbums(
    albums: List<Album>,
    allMedia: List<MediaItem>,
    favoriteIds: List<Long>
): List<MediaItem> {
    if (albums.isEmpty() || allMedia.isEmpty()) return emptyList()
    val favSet = favoriteIds.toHashSet()
    val albumIds = albums.map { it.id }.toHashSet()

    return allMedia.filter { item ->
        if (albumIds.contains(item.bucketId)) return@filter true
        albums.any { album ->
            when (album.id) {
                ID_RECENT -> true
                ID_FAVORITES -> favSet.contains(item.id) || item.isFavorite
                ID_VIDEOS -> item.isVideo
                ID_CAMERA -> item.bucketName.contains("Camera", true) || item.bucketName.contains("DCIM", true)
                ID_SCREENSHOTS -> item.bucketName.contains("Screenshot", true) || item.path.contains("Screenshot", true)
                ID_DOWNLOADS -> item.bucketName.contains("Download", true) || item.path.contains("Download", true)
                ID_WHATSAPP -> item.bucketName.contains("WhatsApp", true) || item.path.contains("WhatsApp", true)
                ID_INSTAGRAM -> item.bucketName.contains("Instagram", true) || item.path.contains("Instagram", true)
                else -> false
            }
        }
    }.distinctBy { it.id }
}

fun shareMediaItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent = Intent(if (items.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        val hasImg = items.any { !it.isVideo }
        val hasVid = items.any { it.isVideo }
        type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (items.size > 1) {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        } else {
            putExtra(Intent.EXTRA_STREAM, items.first().uri)
        }
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app found", Toast.LENGTH_SHORT).show()
    }
}

fun toggleAppLock(
    context: Context,
    securityViewModel: SecurityViewModel,
    currentState: Boolean,
    onStateChanged: (Boolean) -> Unit
) {
    val activity = context.findFragmentActivity()
    if (activity == null) {
        Toast.makeText(context, "Cannot authenticate (Context Invalid)", Toast.LENGTH_SHORT).show()
        return
    }
    if (!securityViewModel.canUseSystemAuthentication()) {
        Toast.makeText(context, "System authentication unavailable. Set up a screen lock first.", Toast.LENGTH_LONG).show()
        return
    }

    val promptBuilder = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("GalleryBox Security")
        .setSubtitle("Confirm identity to change lock settings")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        promptBuilder.setAllowedAuthenticators(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    } else {
        @Suppress("DEPRECATION")
        promptBuilder.setDeviceCredentialAllowed(true)
    }

    androidx.biometric.BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(context),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val newState = !currentState
                securityViewModel.setAppLockEnabled(newState)
                onStateChanged(newState)
                Toast.makeText(context, if (newState) "App Lock Enabled" else "App Lock Disabled", Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(context, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
            }
        }
    ).authenticate(promptBuilder.build())
}

private fun invalidateThumbCache(context: Context, uris: List<Uri>) {
    val cache = context.imageLoader.memoryCache
    val diskCache = context.imageLoader.diskCache
    uris.forEach { uri ->
        (160..480 step 40).forEach { size ->
            val key = "${uri}_thumb_${size}"
            cache?.remove(coil.memory.MemoryCache.Key(key))
            CoroutineScope(Dispatchers.IO).launch { diskCache?.remove(key) }
        }
    }
}

private fun refreshAndClearCache(context: Context, viewModel: GalleryViewModel, affectedUris: List<Uri> = emptyList()) {
    viewModel.refreshAfterFileOperation()
    if (affectedUris.isNotEmpty()) {
        invalidateThumbCache(context, affectedUris)
    }
}

// =========================================================================================
// 🏎️ SAMSUNG ONE UI CAPSULE FAST SCROLLBARS
// =========================================================================================
@Composable
fun PagedSamsungFastScrollbar(
    gridState: LazyGridState,
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    indexOffset: Int = 0,
    deviceTier: DeviceTier = DeviceTier.HIGH,
    modifier: Modifier = Modifier
) {
    val canScroll by remember {
        derivedStateOf {
            gridState.layoutInfo.totalItemsCount > gridState.layoutInfo.visibleItemsInfo.size
        }
    }

    if (!canScroll || pagedMedia.itemCount < 2) return

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }
    var bubbleLabel by remember { mutableStateOf("") }

    val thumbHeightDp = 50.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    val scrollChannel = remember { Channel<Int>(Channel.CONFLATED) }

    LaunchedEffect(Unit) {
        for (targetIndex in scrollChannel) {
            runCatching {
                val maxLoaded = (gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                val safeTarget = targetIndex.coerceIn(0, maxLoaded)
                gridState.scrollToItem(index = safeTarget, scrollOffset = 0)
            }
        }
    }

    LaunchedEffect(gridState.isScrollInProgress, isDragging) {
        if (gridState.isScrollInProgress || isDragging) {
            visible = true
        } else {
            delay(1000)
            visible = false
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { index ->
            if (!isDragging && trackHeightPx > 0f) {
                val count = pagedMedia.itemCount.coerceAtLeast(1)
                val adjusted = (index - indexOffset).coerceIn(0, count - 1)
                val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                val fraction = if (count > 1) adjusted.toFloat() / (count - 1).toFloat() else 0f
                thumbOffsetPx = (fraction * maxThumbOffset).coerceIn(0f, maxThumbOffset)
            }
        }
    }

    var lastDragUpdateMs by remember { mutableLongStateOf(0L) }

    fun labelForIndex(index: Int): String {
        val safeIndex = index.coerceIn(0, (pagedMedia.itemCount - 1).coerceAtLeast(0))
        for (i in safeIndex downTo maxOf(0, safeIndex - 40)) {
            val item = runCatching { pagedMedia.peek(i) }.getOrNull()
            if (item is GalleryGridItem.Header) return item.title
        }
        return ""
    }

    fun jumpTo(offsetY: Float) {
        if (trackHeightPx <= 0f) return

        val now = System.currentTimeMillis()
        if (now - lastDragUpdateMs < 33L) return
        lastDragUpdateMs = now

        val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val clamped = offsetY.coerceIn(0f, maxThumbOffset)
        thumbOffsetPx = clamped

        val fraction = if (maxThumbOffset > 0f) clamped / maxThumbOffset else 0f
        val count = pagedMedia.itemCount.coerceAtLeast(1)
        val targetIndex = (fraction * (count - 1)).toInt().coerceIn(0, count - 1)

        bubbleLabel = labelForIndex(targetIndex)
        scrollChannel.trySend(targetIndex + indexOffset)
    }

    AnimatedVisibility(
        visible = visible || isDragging,
        enter = fadeIn(tween(if (deviceTier == DeviceTier.LOW) 60 else 100)),
        exit = fadeOut(tween(if (deviceTier == DeviceTier.LOW) 120 else 180)),
        modifier = modifier.zIndex(20f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(56.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            lastDragUpdateMs = 0L
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            jumpTo(offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            jumpTo(change.position.y)
                        },
                        onDragEnd = {
                            isDragging = false
                            bubbleLabel = ""
                        },
                        onDragCancel = {
                            isDragging = false
                            bubbleLabel = ""
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(4.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        CircleShape
                    )
            )

            if (isDragging && bubbleLabel.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = with(density) { (-110.dp).roundToPx() },
                                y = (thumbOffsetPx + with(density) { 10.dp.toPx() }).toInt().coerceAtLeast(0)
                            )
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = bubbleLabel,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            val thumbWidth by animateDpAsState(
                targetValue = if (isDragging) 12.dp else 7.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "paged_scroll_thumb_w"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val maxOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                        IntOffset(
                            x = 0,
                            y = thumbOffsetPx.coerceIn(0f, maxOffset).toInt()
                        )
                    }
                    .padding(end = 3.dp)
                    .width(thumbWidth)
                    .height(thumbHeightDp)
                    .shadow(if (isDragging) 4.dp else 1.dp, CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
fun SamsungFastScrollbar(
    gridState: LazyGridState,
    itemCount: Int,
    indexOffset: Int = 0,
    deviceTier: DeviceTier = DeviceTier.HIGH,
    modifier: Modifier = Modifier
) {
    val canScroll by remember {
        derivedStateOf {
            gridState.layoutInfo.totalItemsCount > gridState.layoutInfo.visibleItemsInfo.size
        }
    }

    if (!canScroll || itemCount < 2) return

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }

    val thumbHeightDp = 50.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    val scrollChannel = remember { Channel<Int>(Channel.CONFLATED) }

    LaunchedEffect(Unit) {
        for (targetIndex in scrollChannel) {
            runCatching {
                val maxLoaded = (gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                val safeTarget = targetIndex.coerceIn(0, maxLoaded)
                gridState.scrollToItem(index = safeTarget, scrollOffset = 0)
            }
        }
    }

    LaunchedEffect(gridState.isScrollInProgress, isDragging) {
        if (gridState.isScrollInProgress || isDragging) {
            visible = true
        } else {
            delay(1000)
            visible = false
        }
    }

    val currentItemCount by rememberUpdatedState(itemCount)

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { index ->
            if (!isDragging && trackHeightPx > 0f) {
                val count = currentItemCount.coerceAtLeast(1)
                val adjusted = (index - indexOffset).coerceIn(0, count - 1)
                val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                val fraction = if (count > 1) adjusted.toFloat() / (count - 1).toFloat() else 0f
                thumbOffsetPx = (fraction * maxThumbOffset).coerceIn(0f, maxThumbOffset)
            }
        }
    }

    var lastDragUpdateMs by remember { mutableLongStateOf(0L) }

    fun jumpTo(offsetY: Float) {
        if (trackHeightPx <= 0f) return

        val now = System.currentTimeMillis()
        if (now - lastDragUpdateMs < 33L) return
        lastDragUpdateMs = now

        val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val clamped = offsetY.coerceIn(0f, maxThumbOffset)
        thumbOffsetPx = clamped

        val fraction = if (maxThumbOffset > 0f) clamped / maxThumbOffset else 0f
        val count = currentItemCount.coerceAtLeast(1)
        val targetIndex = (fraction * (count - 1)).toInt().coerceIn(0, count - 1)

        scrollChannel.trySend(targetIndex + indexOffset)
    }

    AnimatedVisibility(
        visible = visible || isDragging,
        enter = fadeIn(tween(if (deviceTier == DeviceTier.LOW) 60 else 100)),
        exit = fadeOut(tween(if (deviceTier == DeviceTier.LOW) 120 else 180)),
        modifier = modifier.zIndex(20f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(56.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            lastDragUpdateMs = 0L
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            jumpTo(offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            jumpTo(change.position.y)
                        },
                        onDragEnd = {
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(4.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        CircleShape
                    )
            )

            val thumbWidth by animateDpAsState(
                targetValue = if (isDragging) 12.dp else 7.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "album_scroll_thumb_w"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val maxOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                        IntOffset(
                            x = 0,
                            y = thumbOffsetPx.coerceIn(0f, maxOffset).toInt()
                        )
                    }
                    .padding(end = 3.dp)
                    .width(thumbWidth)
                    .height(thumbHeightDp)
                    .shadow(if (isDragging) 4.dp else 1.dp, CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ MAIN ALBUM LIST SCREEN (Samsung One UI 7 Style)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    onMainSelectionModeChanged: (Boolean) -> Unit = {},
    actions: AlbumActions
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val adaptiveState = rememberAdaptiveState()
    val controlWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(640.dp) else Modifier.fillMaxWidth()

    val deviceTier = remember { getDeviceTier(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val rawAlbumPreviews by viewModel.albumPreviewMap.collectAsState()
    val sortOption by viewModel.albumSort.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val allMedia by viewModel.media.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<ImmutableSet<String>>(persistentListOf<String>().toImmutableSet()) }

    var activeDialog by remember { mutableStateOf<AlbumUiDialog>(AlbumUiDialog.None) }
    var optimisticallyRemovedAlbums by remember { mutableStateOf(persistentSetOf<String>()) }

    LaunchedEffect(Unit) { viewModel.forceSync() }

    val safTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val grantedUri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        viewModel.onSafTreeGranted(grantedUri)
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onPermissionResult(granted)
        if (!granted) {
            optimisticallyRemovedAlbums = persistentSetOf<String>()
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.LaunchIntent -> safTreeLauncher.launch(event.intent)
                    is GalleryEvent.OperationSuccess -> {
                        isSelectionMode = false
                        selectedIds = persistentListOf<String>().toImmutableSet()
                        refreshAndClearCache(context, viewModel)
                    }
                }
            }
        }
    }

    LaunchedEffect(isSelectionMode) {
        onMainSelectionModeChanged(isSelectionMode)
    }

    LaunchedEffect(viewerState) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open)
    }

    LaunchedEffect(vmAlbums) {
        if (optimisticallyRemovedAlbums.isNotEmpty()) {
            delay(2500)
            optimisticallyRemovedAlbums = persistentSetOf<String>()
        }
    }

    val albumPreviews = remember(rawAlbumPreviews) {
        rawAlbumPreviews.mapValues { it.value.toImmutableList() }.toImmutableMap()
    }

    val favAlbumStats = remember(favoriteIds, allMedia.size) {
        val favMedia = allMedia.filter { favoriteIds.contains(it.id) }
        if (favMedia.isNotEmpty()) {
            Album(
                ID_FAVORITES,
                "Favorites",
                favMedia.firstOrNull()?.uri ?: Uri.EMPTY,
                favMedia.size,
                favMedia.sumOf { it.size },
                true
            )
        } else null
    }

    val displayAlbums = remember(vmAlbums, searchQuery, sortOption, favAlbumStats, optimisticallyRemovedAlbums) {
        val filteredAlbums = vmAlbums
            .filter { albumMatchesQuery(it, searchQuery) && !optimisticallyRemovedAlbums.contains(it.id) }
            .toMutableList()

        if (favAlbumStats != null) {
            val favIndex = filteredAlbums.indexOfFirst { it.id == ID_FAVORITES }
            if (favIndex != -1) {
                filteredAlbums[favIndex] = filteredAlbums[favIndex].copy(
                    mediaCount = favAlbumStats.mediaCount,
                    sizeBytes = favAlbumStats.sizeBytes,
                    coverUri = favAlbumStats.coverUri
                )
            } else if (albumMatchesQuery(favAlbumStats, searchQuery)) {
                filteredAlbums.add(favAlbumStats)
            }
        }

        if (sortOption == AlbumSort.Custom) {
            val recentIndex = filteredAlbums.indexOfFirst { it.id == ID_RECENT }
            if (recentIndex > 0) {
                val recentAlbum = filteredAlbums.removeAt(recentIndex)
                filteredAlbums.add(0, recentAlbum)
            }
            filteredAlbums.map {
                if (it.id == ID_RECENT || it.id == ID_FAVORITES) it.copy(isPinned = false)
                else it
            }.toImmutableList()
        } else {
            filteredAlbums.sortedWith(Comparator { a, b ->
                if (a.id == ID_RECENT && b.id != ID_RECENT) return@Comparator -1
                if (b.id == ID_RECENT && a.id != ID_RECENT) return@Comparator 1

                val aPinned = a.isPinned || a.id == ID_RECENT || a.id == ID_FAVORITES
                val bPinned = b.isPinned || b.id == ID_RECENT || b.id == ID_FAVORITES
                if (aPinned != bPinned) {
                    bPinned.compareTo(aPinned)
                } else {
                    when (sortOption.name) {
                        "NameAsc" -> a.name.compareTo(b.name, ignoreCase = true)
                        "NameDesc" -> b.name.compareTo(a.name, ignoreCase = true)
                        "SizeDesc" -> b.sizeBytes.compareTo(a.sizeBytes)
                        "CountDesc" -> b.mediaCount.compareTo(a.mediaCount)
                        else -> 0
                    }
                }
            }).map {
                if (it.id == ID_RECENT || it.id == ID_FAVORITES) it.copy(isPinned = true)
                else it
            }.toImmutableList()
        }
    }

    val sdCardAlbums = remember(allMedia.size, vmAlbums.size) {
        val safPrefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val markedSdIds = safPrefs.getStringSet("sd_card_album_ids", emptySet()) ?: emptySet()
        val detectedSdIds = allMedia.filter { item ->
            if (item.volumeName.isNotBlank()) {
                item.volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY && item.volumeName != "external"
            } else {
                item.path.startsWith("/storage/") &&
                        !item.path.startsWith("/storage/emulated/") &&
                        !item.path.startsWith("/storage/self/")
            }
        }.map { it.bucketId }.toSet()
        detectedSdIds + markedSdIds
    }

    val dynamicList = remember { mutableStateListOf<Album>() }
    var lastReorderAtMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(displayAlbums, isDragging) {
        if (isDragging) return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - lastReorderAtMs
        if (elapsed < 2000L) {
            delay(2000L - elapsed)
        }
        val oldIds = dynamicList.map { it.id }
        val newIds = displayAlbums.map { it.id }
        if (oldIds != newIds) {
            dynamicList.clear()
            dynamicList.addAll(displayAlbums)
        } else {
            displayAlbums.forEachIndexed { i, a ->
                if (dynamicList[i] != a) {
                    dynamicList[i] = a
                }
            }
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    var columnCount by remember {
        mutableIntStateOf(
            prefs.getInt("gallery_album_grid_columns", 3)
        )
    }
    val gridState = rememberLazyGridState()

    val intentSenderLauncherTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) {
            optimisticallyRemovedAlbums = persistentSetOf<String>()
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(trashViewModel, lifecycleOwner) {
        trashViewModel.onRefreshGallery = { refreshAndClearCache(context, viewModel) }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            trashViewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.RequestPermission -> intentSenderLauncherTrash.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.OperationSuccess -> {
                        isSelectionMode = false
                        selectedIds = persistentListOf<String>().toImmutableSet()
                        Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    }
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = persistentListOf<String>().toImmutableSet()
        isSelectionMode = false
    }

    BackHandler(enabled = activeDialog != AlbumUiDialog.None) {
        activeDialog = AlbumUiDialog.None
    }

    BackHandler(enabled = viewerState is GalleryViewerState.Open) {
        viewModel.closeViewer()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    val isAllSelected = selectedIds.size == dynamicList.size && dynamicList.isNotEmpty()
                    val selectedAlbums = dynamicList.filter { selectedIds.contains(it.id) }
                    val selectedSize = selectedAlbums.sumOf { it.sizeBytes }

                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = if (selectedIds.isEmpty()) "Select albums" else "${selectedIds.size} selected",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                if (selectedIds.isNotEmpty()) {
                                    Text(
                                        text = Formatter.formatFileSize(context, selectedSize),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                selectedIds = persistentListOf<String>().toImmutableSet()
                                isSelectionMode = false
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Selection",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        actions = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedIds = if (isAllSelected) {
                                            persistentListOf<String>().toImmutableSet()
                                        } else {
                                            dynamicList.map { it.id }.toImmutableSet()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = "Select All",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "All",
                                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background
                        )
                    )
                } else if (isSearchActive) {
                    SearchTopBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onClose = {
                            isSearchActive = false
                            searchQuery = ""
                        }
                    )
                } else {
                    ModernAlbumTopBar(
                        scrollBehavior = scrollBehavior,
                        isAppLockEnabled = isAppLockEnabled,
                        onSearchClick = { isSearchActive = true },
                        onMenuAction = { action: String ->
                            when (action) {
                                "grid" -> activeDialog = AlbumUiDialog.GridSize
                                "sort" -> activeDialog = AlbumUiDialog.Sort
                                "create" -> activeDialog = AlbumUiDialog.CreateAlbum
                                "trash" -> actions.onNavigateToTrash()
                                "hidden" -> actions.onNavigateToHidden()
                                "duplicates" -> actions.onNavigateToDuplicates()
                                "scan" -> actions.onNavigateToScan()
                                "toggle_lock" -> toggleAppLock(context, securityViewModel, isAppLockEnabled) { isAppLockEnabled = it }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else slideInVertically(initialOffsetY = { it }) + fadeIn(tween(150)),
                    exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(120)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    var showAlbumSelectionMore by remember { mutableStateOf(false) }
                    val selectedAlbums = remember(dynamicList.toList(), selectedIds) {
                        dynamicList.filter { selectedIds.contains(it.id) }
                    }
                    val selectedPhysicalAlbums = remember(selectedAlbums) {
                        selectedAlbums.filter { !it.id.startsWith("virtual_") }
                    }
                    val singleSelectedAlbum = selectedAlbums.singleOrNull()
                    val canRenameSingle = singleSelectedAlbum != null && !singleSelectedAlbum.id.startsWith("virtual_")

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .then(controlWidthModifier)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .navigationBarsPadding(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                            shadowElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BottomBarActionItem(
                                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                    label = "Move",
                                    enabled = selectedAlbums.isNotEmpty()
                                ) {
                                    if (selectedAlbums.isNotEmpty()) {
                                        activeDialog = AlbumUiDialog.MoveCopy(selectedAlbums, isMove = true)
                                    }
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.FileCopy,
                                    label = "Copy",
                                    enabled = selectedAlbums.isNotEmpty()
                                ) {
                                    if (selectedAlbums.isNotEmpty()) {
                                        activeDialog = AlbumUiDialog.MoveCopy(selectedAlbums, isMove = false)
                                    }
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.Share,
                                    label = "Share",
                                    enabled = selectedAlbums.isNotEmpty()
                                ) {
                                    val itemsToShare = resolveMediaForAlbums(selectedAlbums, allMedia, favoriteIds)
                                    shareMediaItems(context, itemsToShare)
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.Delete,
                                    label = "Trash",
                                    isDestructive = true,
                                    enabled = selectedAlbums.isNotEmpty()
                                ) {
                                    if (selectedAlbums.isNotEmpty()) {
                                        activeDialog = AlbumUiDialog.Delete(selectedAlbums)
                                    }
                                }
                                if (canRenameSingle) {
                                    BottomBarActionItem(icon = Icons.Outlined.Edit, label = "Rename") {
                                        activeDialog = AlbumUiDialog.Rename(singleSelectedAlbum!!)
                                    }
                                }
                                Box {
                                    BottomBarActionItem(
                                        icon = Icons.Outlined.MoreVert,
                                        label = "More",
                                        enabled = selectedAlbums.isNotEmpty()
                                    ) {
                                        showAlbumSelectionMore = true
                                    }
                                    DropdownMenu(
                                        expanded = showAlbumSelectionMore,
                                        onDismissRequest = { showAlbumSelectionMore = false },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Details", fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                showAlbumSelectionMore = false
                                                if (selectedAlbums.isNotEmpty()) {
                                                    activeDialog = AlbumUiDialog.Info(selectedAlbums)
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Info, null) }
                                        )
                                        if (selectedPhysicalAlbums.isNotEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("Hide Albums", fontWeight = FontWeight.Medium) },
                                                onClick = {
                                                    showAlbumSelectionMore = false
                                                    val idsToHide = selectedPhysicalAlbums.map { it.id }.toSet()
                                                    val currentHidden = enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
                                                    enginePrefs.edit().putStringSet("hidden_albums", currentHidden + idsToHide).apply()
                                                    idsToHide.forEach { id -> viewModel.toggleHiddenAlbum(id) }
                                                    isSelectionMode = false
                                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                                    Toast.makeText(context, "Albums hidden", Toast.LENGTH_SHORT).show()
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }
                                            )
                                        }
                                        if (singleSelectedAlbum != null && !singleSelectedAlbum.id.startsWith("virtual_")) {
                                            DropdownMenuItem(
                                                text = { Text(if (singleSelectedAlbum.isPinned) "Unpin Album" else "Pin Album", fontWeight = FontWeight.Medium) },
                                                onClick = {
                                                    showAlbumSelectionMore = false
                                                    viewModel.toggleAlbumPin(singleSelectedAlbum)
                                                    isSelectionMode = false
                                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Outlined.PushPin,
                                                        null
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                if (dynamicList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        EmptyAlbumsOverlay(onCreateClick = { activeDialog = AlbumUiDialog.CreateAlbum })
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        StatelessAlbumGrid(
                            gridState = gridState,
                            bottomPadding = padding.calculateBottomPadding(),
                            columnCount = columnCount,
                            dynamicList = dynamicList,
                            albumPreviews = albumPreviews,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds,
                            sortOption = sortOption,
                            searchQuery = searchQuery,
                            screenWidthDp = screenWidthDp,
                            deviceTier = deviceTier,
                            sdCardAlbums = sdCardAlbums,
                            onOrderSaved = { orderedList ->
                                lastReorderAtMs = System.currentTimeMillis()
                                viewModel.saveCustomAlbumOrder(orderedList)
                            },
                            onAlbumClick = { album ->
                                actions.onAlbumClick(album)
                            },
                            onSelectionChange = { newSelection ->
                                selectedIds = newSelection
                            },
                            onSelectionModeChange = { mode ->
                                isSelectionMode = mode
                            },
                            onDragStateChange = { dragging ->
                                isDragging = dragging
                            }
                        )
                    }
                }
            }
        }
    }

    when (val dialog = activeDialog) {
        is AlbumUiDialog.Info -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = {
                    BottomSheetDefaults.DragHandle(
                        width = 36.dp,
                        height = 4.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                    )
                }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                ) {
                    if (dialog.albums.size == 1) {
                        val album = dialog.albums.first()
                        val albumItems = resolveMediaForAlbums(listOf(album), allMedia, favoriteIds)
                        val oldestItem = albumItems.minByOrNull { it.dateAdded }
                        val dateStr = oldestItem?.let {
                            SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000L))
                        } ?: "Unknown"
                        val albumPath = oldestItem?.path?.let { File(it).parent }
                            ?: if (album.id.startsWith("virtual_")) "Smart Collection" else "Pictures/${album.name}"
                        val storageLocation = if (sdCardAlbums.contains(album.id)) "SD Card" else "Internal Storage"

                        Text("Album Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                MetadataRow(icon = Icons.Outlined.Title, label = "Name", value = album.name)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                MetadataRow(icon = Icons.Outlined.Storage, label = "Size ($storageLocation)", value = Formatter.formatFileSize(context, album.sizeBytes))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                MetadataRow(icon = Icons.Outlined.PhotoLibrary, label = "Items", value = album.mediaCount.toString())
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                MetadataRow(icon = Icons.Outlined.Folder, label = "Path", value = albumPath)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Created On", value = dateStr)
                            }
                        }
                    } else {
                        val totalMediaCount = dialog.albums.sumOf { it.mediaCount }
                        val totalSizeBytes = dialog.albums.sumOf { it.sizeBytes }

                        Text("${dialog.albums.size} Albums Selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                MetadataRow(icon = Icons.Outlined.PhotoLibrary, label = "Total Items", value = totalMediaCount.toString())
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                MetadataRow(icon = Icons.Outlined.Storage, label = "Total Size", value = Formatter.formatFileSize(context, totalSizeBytes))
                            }
                        }
                    }
                }
            }
        }
        is AlbumUiDialog.MoveCopy -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = {
                    BottomSheetDefaults.DragHandle(
                        width = 36.dp,
                        height = 4.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                    )
                }
            ) {
                val allAlbumsState by viewModel.allAlbumsState.collectAsState(initial = emptyList())
                val candidateTargets = remember(allAlbumsState, dialog.albums) {
                    allAlbumsState
                        .filter { !it.id.startsWith("virtual_") && dialog.albums.none { sel -> sel.id == it.id } }
                        .sortedBy { it.name.lowercase() }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (dialog.isMove) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.FileCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = if (dialog.isMove) "Move to Album" else "Copy to Album",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Supports Internal Storage & SD Card folders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Surface(
                                onClick = { activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.albums, dialog.isMove) },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CreateNewFolder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Create New Album",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "Internal Storage or SD Card",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        items(candidateTargets, key = { it.id }) { targetAlbum ->
                            val isTargetSd = sdCardAlbums.contains(targetAlbum.id)
                            Surface(
                                onClick = {
                                    val resolvedItems = resolveMediaForAlbums(dialog.albums, allMedia, favoriteIds)
                                    val physicalSourceIds = dialog.albums.filter { !it.id.startsWith("virtual_") }.map { it.id }

                                    if (physicalSourceIds.size == dialog.albums.size && physicalSourceIds.isNotEmpty()) {
                                        viewModel.mergeAlbums(
                                            sourceAlbumIds = physicalSourceIds,
                                            targetAlbumId = targetAlbum.id,
                                            mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY
                                        )
                                    } else if (resolvedItems.isNotEmpty()) {
                                        val mediaIds = resolvedItems.map { it.id }
                                        if (dialog.isMove) {
                                            viewModel.moveData(mediaIds, targetAlbum.id)
                                        } else {
                                            viewModel.copyData(mediaIds, targetAlbum.id)
                                        }
                                    }
                                    invalidateThumbCache(context, resolvedItems.map { it.uri })
                                    activeDialog = AlbumUiDialog.None
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(targetAlbum.coverUri)
                                            .size(160)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = targetAlbum.name,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 15.sp
                                            )
                                            if (isTargetSd) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Outlined.SdStorage,
                                                    contentDescription = "SD Card",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${targetAlbum.mediaCount} items",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        is AlbumUiDialog.CreateAndMoveCopy -> {
            val initialName = if (dialog.albums.size == 1) "${dialog.albums.first().name} Copy" else "New Album"
            ModernInputSheet(
                title = if (dialog.isMove) "New Album & Move" else "New Album & Copy",
                initial = initialName,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onConfirm = { newName: String ->
                    val resolvedItems = resolveMediaForAlbums(dialog.albums, allMedia, favoriteIds)
                    val mediaIds = resolvedItems.map { it.id }
                    if (dialog.isMove) {
                        viewModel.createAndMove(mediaIds, newName)
                    } else {
                        viewModel.createAndCopy(mediaIds, newName)
                    }
                    invalidateThumbCache(context, resolvedItems.map { it.uri })
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                }
            )
        }
        is AlbumUiDialog.Rename -> {
            ModernInputSheet(
                title = "Rename Album",
                initial = dialog.album.name,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onConfirm = { newName: String ->
                    val affectedUris = allMedia.filter { it.bucketId == dialog.album.id }.map { it.uri }
                    viewModel.renameAlbum(dialog.album, newName)
                    invalidateThumbCache(context, affectedUris)
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                }
            )
        }
        is AlbumUiDialog.Delete -> {
            ModernMoveToTrashSheet(
                count = dialog.albums.size,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onConfirm = {
                    val resolvedItems = resolveMediaForAlbums(dialog.albums, allMedia, favoriteIds)
                    optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(
                        dialog.albums.filter { !it.id.startsWith("virtual_") }.map { it.id }
                    )
                    trashViewModel.confirmPendingAlbumTrash(dialog.albums, allMedia.toList())
                    invalidateThumbCache(context, resolvedItems.map { it.uri })
                    activeDialog = AlbumUiDialog.None
                    isSelectionMode = false
                    selectedIds = persistentListOf<String>().toImmutableSet()
                }
            )
        }
        is AlbumUiDialog.CreateAlbum -> {
            ModernCreateAlbumSheet(
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onCreate = { name: String, sd: Boolean ->
                    viewModel.createAlbum(name, sd)
                    activeDialog = AlbumUiDialog.None
                }
            )
        }
        is AlbumUiDialog.Sort -> {
            ModernAlbumSortSheet(
                activeSort = sortOption,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onSortSelected = { sort: AlbumSort ->
                    viewModel.updateAlbumSort(sort)
                    activeDialog = AlbumUiDialog.None
                }
            )
        }
        is AlbumUiDialog.GridSize -> {
            ModernGridSheet(
                currentColumns = columnCount,
                max = 8,
                onDismiss = { activeDialog = AlbumUiDialog.None },
                onUpdate = { cols: Int ->
                    columnCount = cols
                    prefs.edit().putInt("gallery_album_grid_columns", cols).apply()
                    activeDialog = AlbumUiDialog.None
                }
            )
        }
        else -> {}
    }
}

/**
 * =========================================================================================
 * 🙈 HIDE / UNHIDE ALBUMS SCREEN (Samsung One UI 7 Style)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val adaptiveState = rememberAdaptiveState()
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val rawMedia by viewModel.rawMedia.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

    val allPossibleAlbums = remember(rawMedia) {
        rawMedia.groupBy { it.bucketId }.mapNotNull { (id, items) ->
            if (id.startsWith("virtual_")) return@mapNotNull null
            val first = items.firstOrNull() ?: return@mapNotNull null

            Album(
                id = id,
                name = first.bucketName.ifBlank { "Unknown" },
                coverUri = first.uri,
                mediaCount = items.size,
                sizeBytes = items.sumOf { it.size },
                isPinned = false
            )
        }.sortedBy { it.name.lowercase() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Hide or Unhide Albums",
                            fontWeight = FontWeight.Bold,
                            fontSize = (20 * adaptiveState.textScaleFactor).sp
                        )
                        Text(
                            text = "${hiddenAlbums.size} album(s) currently hidden",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(600.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (allPossibleAlbums.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No Albums Found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                LazyColumn(
                    modifier = contentWidthModifier,
                    contentPadding = PaddingValues(
                        start = adaptiveState.recommendedPadding,
                        end = adaptiveState.recommendedPadding,
                        top = 16.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allPossibleAlbums, key = { it.id }) { album ->
                        val isHidden = hiddenAlbums.contains(album.id)

                        val toggleVisibility = {
                            viewModel.toggleHiddenAlbum(album.id)
                            val newHidden = if (isHidden) {
                                hiddenAlbums - album.id
                            } else {
                                hiddenAlbums + album.id
                            }
                            enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { toggleVisibility() },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isHidden) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                0.5.dp,
                                if (isHidden) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(album.coverUri)
                                        .size(200)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                )

                                Spacer(Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = (16 * adaptiveState.textScaleFactor).sp
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${album.mediaCount} items • ${Formatter.formatShortFileSize(context, album.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = (12 * adaptiveState.textScaleFactor).sp
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                Switch(
                                    checked = isHidden,
                                    onCheckedChange = { toggleVisibility() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ ALBUM DETAILS SCREEN (When you open a specific folder)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: DetailActions
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val adaptiveState = rememberAdaptiveState()
    val controlWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(600.dp) else Modifier.fillMaxWidth()

    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val deviceTier = remember { getDeviceTier(context) }
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val mediaMap by viewModel.mediaMap.collectAsState()
    val vmAlbums by viewModel.albumsState.collectAsState(initial = emptyList())
    val viewerState by viewModel.viewerState.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    var activeDialog by remember { mutableStateOf<DetailUiDialog>(DetailUiDialog.None) }
    var metadataItemToShow by remember { mutableStateOf<MediaItem?>(null) }
    var isAppLockEnabled by remember { mutableStateOf(securityViewModel.isAppLockEnabled()) }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(localSearchQuery) {
        delay(250)
        debouncedSearchQuery = localSearchQuery
    }

    var currentPhotoSort by rememberSaveable { mutableStateOf(PhotoSort.DateDesc) }
    var mediaFilter by remember { mutableStateOf(MediaTypeFilter.ALL) }
    var isSelectionMode by remember { mutableStateOf(false) }

    var openedMediaItem by remember { mutableStateOf<MediaItem?>(null) }

    val pagedMediaFlow: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<GalleryGridItem>> = remember(albumId, mediaFilter, currentPhotoSort, debouncedSearchQuery) {
        viewModel.getPagedMediaForAlbumStream(albumId, mediaFilter, debouncedSearchQuery, currentPhotoSort)
            .map { pagingData: androidx.paging.PagingData<GalleryGridItem> ->
                pagingData.filter { item -> item is GalleryGridItem.Media }
            }
    }
    val pagedMedia = pagedMediaFlow.collectAsLazyPagingItems()

    var selectedIds by remember { mutableStateOf<ImmutableSet<Long>>(persistentListOf<Long>().toImmutableSet()) }
    val dragSelection = remember { mutableSetOf<Long>() }

    val safTreeLauncherDetail = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val grantedUri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        viewModel.onSafTreeGranted(grantedUri)
    }

    val intentSenderLauncherTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        viewModel.onPermissionResult(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    val intentSenderLauncherViewModel = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onPermissionResult(granted)
        trashViewModel.onPermissionResultGlobal(granted)
        if (!granted) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(trashViewModel, lifecycleOwner) {
        trashViewModel.onRefreshGallery = { refreshAndClearCache(context, viewModel) }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            trashViewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.RequestPermission -> {
                        runCatching {
                            val player = viewModel.getPlayer()
                            player.pause()
                            player.stop()
                        }
                        intentSenderLauncherTrash.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    }
                    is GalleryEvent.OperationSuccess -> {
                        activeDialog = DetailUiDialog.None
                        isSelectionMode = false
                        dragSelection.clear()
                        selectedIds = persistentListOf<Long>().toImmutableSet()
                        openedMediaItem = null
                        viewModel.closeViewer()
                        refreshAndClearCache(context, viewModel)
                        Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    }
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    is GalleryEvent.RequestPermission -> {
                        runCatching {
                            val player = viewModel.getPlayer()
                            player.pause()
                            player.stop()
                        }
                        intentSenderLauncherViewModel.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    }
                    is GalleryEvent.LaunchIntent -> safTreeLauncherDetail.launch(event.intent)
                    is GalleryEvent.OperationSuccess -> {
                        activeDialog = DetailUiDialog.None
                        isSelectionMode = false
                        dragSelection.clear()
                        selectedIds = persistentListOf<Long>().toImmutableSet()
                        openedMediaItem = null
                        viewModel.closeViewer()
                        refreshAndClearCache(context, viewModel)
                    }
                }
            }
        }
    }

    LaunchedEffect(viewerState) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open)
    }

    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            dragSelection.clear()
            selectedIds = persistentListOf<Long>().toImmutableSet()
        }
    }

    val album = remember(vmAlbums, albumId) {
        when (albumId) {
            ID_RECENT -> Album(ID_RECENT, "Recent", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_FAVORITES -> Album(ID_FAVORITES, "Favorites", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_VIDEOS -> Album(ID_VIDEOS, "Videos", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_SCREENSHOTS -> Album(ID_SCREENSHOTS, "Screenshots", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_WHATSAPP -> Album(ID_WHATSAPP, "WhatsApp", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_INSTAGRAM -> Album(ID_INSTAGRAM, "Instagram", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_DOWNLOADS -> Album(ID_DOWNLOADS, "Downloads", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_HIDDEN -> Album(ID_HIDDEN, "Hidden", Uri.EMPTY, 0, 0L, isPinned = true)
            ID_CAMERA -> Album(ID_CAMERA, "Camera", Uri.EMPTY, 0, 0L, isPinned = true)
            else -> vmAlbums.find { it.id == albumId }
        }
    }

    val albumTitle = remember(albumId, album?.name) {
        album?.name ?: "Album"
    }

    val isVirtual = albumId.startsWith("virtual_")
    var showMediaSelectionMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameSheet by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    var detailColumns by remember {
        mutableIntStateOf(
            prefs.getInt("gallery_media_grid_columns", 4)
        )
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    BackHandler(enabled = localSearchQuery.isNotEmpty()) { localSearchQuery = "" }
    BackHandler(enabled = isSelectionMode) { isSelectionMode = false }
    BackHandler(enabled = viewerState is GalleryViewerState.Open && activeDialog == DetailUiDialog.None && metadataItemToShow == null) {
        openedMediaItem = null
        viewModel.closeViewer()
    }
    BackHandler(enabled = metadataItemToShow != null) { metadataItemToShow = null }
    BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    val mediaItems = remember(pagedMedia.itemSnapshotList) { pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>() }
                    val isAllSelected = selectedIds.size == mediaItems.size && mediaItems.isNotEmpty()
                    val selectedSize = selectedIds.sumOf { id -> mediaMap[id]?.size ?: mediaItems.find { it.item.id == id }?.item?.size ?: 0L }

                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = if (selectedIds.isEmpty()) "Select items" else "${selectedIds.size} selected",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                if (selectedIds.isNotEmpty()) {
                                    Text(
                                        text = Formatter.formatFileSize(context, selectedSize),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { isSelectionMode = false }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close Selection",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        actions = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isAllSelected) {
                                            dragSelection.clear()
                                        } else {
                                            dragSelection.clear()
                                            mediaItems.forEach { dragSelection.add(it.item.id) }
                                        }
                                        selectedIds = dragSelection.toImmutableSet()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAllSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = "Select All",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "All",
                                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background
                        )
                    )
                } else {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = albumTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = actions.onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        actions = {
                            Box(modifier = Modifier.padding(end = 4.dp)) {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = "More",
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Select items", fontWeight = FontWeight.Medium) },
                                        onClick = {
                                            isSelectionMode = true
                                            showMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Checklist, null) }
                                    )
                                    if (!isVirtual) {
                                        DropdownMenuItem(
                                            text = { Text("Add Photos", fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                actions.onAddMediaToAlbum?.invoke(albumId)
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Sort Media", fontWeight = FontWeight.Medium) },
                                        onClick = {
                                            activeDialog = DetailUiDialog.Sort
                                            showMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Grid Size", fontWeight = FontWeight.Medium) },
                                        onClick = {
                                            activeDialog = DetailUiDialog.GridSize
                                            showMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.GridView, null) }
                                    )
                                    if (!isVirtual && album != null) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        DropdownMenuItem(
                                            text = { Text("Hide Album", fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                viewModel.toggleHiddenAlbum(album.id)
                                                val currentHidden = enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
                                                enginePrefs.edit().putStringSet("hidden_albums", currentHidden + album.id).apply()
                                                Toast.makeText(context, "Album Hidden", Toast.LENGTH_SHORT).show()
                                                showMenu = false
                                                actions.onBack()
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (album.isPinned) "Unpin Album" else "Pin Album", fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                viewModel.toggleAlbumPin(album)
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename", fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                showRenameSheet = true
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete Album", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                                            onClick = {
                                                activeDialog = DetailUiDialog.DeleteAlbum
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                        )
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    DropdownMenuItem(
                                        text = { Text(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock", fontWeight = FontWeight.Medium) },
                                        onClick = {
                                            showMenu = false
                                            toggleAppLock(context, securityViewModel, isAppLockEnabled) { isAppLockEnabled = it }
                                        },
                                        leadingIcon = { Icon(if (isAppLockEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null) }
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else slideInVertically(initialOffsetY = { it }) + fadeIn(tween(150)),
                    exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(120)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .then(controlWidthModifier)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .navigationBarsPadding(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                            shadowElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BottomBarActionItem(
                                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                    label = "Move",
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId)
                                    isSelectionMode = false
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.FileCopy,
                                    label = "Copy",
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId)
                                    isSelectionMode = false
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.Share,
                                    label = "Share",
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    val itemsToShare = pagedMedia.itemSnapshotList.items
                                        .filterIsInstance<GalleryGridItem.Media>()
                                        .filter { selectedIds.contains(it.item.id) }
                                        .map { mediaMap[it.item.id] ?: it.item }
                                    shareMediaItems(context, itemsToShare)
                                }
                                BottomBarActionItem(
                                    icon = Icons.Outlined.Delete,
                                    label = "Trash",
                                    isDestructive = true,
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    val selectedMediaItems = pagedMedia.itemSnapshotList.items
                                        .filterIsInstance<GalleryGridItem.Media>()
                                        .filter { selectedIds.contains(it.item.id) }
                                        .map { mediaMap[it.item.id] ?: it.item }
                                    activeDialog = DetailUiDialog.Delete(selectedIds.toList(), selectedMediaItems)
                                }
                                Box {
                                    BottomBarActionItem(
                                        icon = Icons.Outlined.MoreVert,
                                        label = "More",
                                        enabled = selectedIds.isNotEmpty()
                                    ) {
                                        showMediaSelectionMenu = true
                                    }
                                    DropdownMenu(
                                        expanded = showMediaSelectionMenu,
                                        onDismissRequest = { showMediaSelectionMenu = false },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        if (selectedIds.size == 1) {
                                            DropdownMenuItem(
                                                text = { Text("Details", fontWeight = FontWeight.Medium) },
                                                onClick = {
                                                    showMediaSelectionMenu = false
                                                    metadataItemToShow = mediaMap[selectedIds.first()]
                                                        ?: pagedMedia.itemSnapshotList.items
                                                            .filterIsInstance<GalleryGridItem.Media>()
                                                            .firstOrNull { it.item.id == selectedIds.first() }?.item
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.Info, null) }
                                            )
                                        }
                                        if (albumId == ID_HIDDEN) {
                                            DropdownMenuItem(
                                                text = { Text("Unhide", fontWeight = FontWeight.Medium) },
                                                onClick = {
                                                    showMediaSelectionMenu = false
                                                    viewModel.unhideMedia(selectedIds.toList())
                                                    Toast.makeText(context, "Items restored", Toast.LENGTH_SHORT).show()
                                                    isSelectionMode = false
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.Visibility, null) }
                                            )
                                        } else {
                                            DropdownMenuItem(
                                                text = { Text("Hide", fontWeight = FontWeight.Medium) },
                                                onClick = {
                                                    showMediaSelectionMenu = false
                                                    viewModel.hideItems(selectedIds.toList())
                                                    Toast.makeText(context, "${selectedIds.size} items hidden", Toast.LENGTH_SHORT).show()
                                                    val itemsToHide = pagedMedia.itemSnapshotList.items
                                                        .filterIsInstance<GalleryGridItem.Media>()
                                                        .filter { selectedIds.contains(it.item.id) }
                                                        .map { it.item.uri }
                                                    refreshAndClearCache(context, viewModel, itemsToHide)
                                                    isSelectionMode = false
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                if (pagedMedia.itemCount == 0 && localSearchQuery.isBlank() && mediaFilter == MediaTypeFilter.ALL) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.ImageNotSupported,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "No photos here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!isVirtual) {
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = { actions.onAddMediaToAlbum?.invoke(albumId) },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .padding(horizontal = 24.dp)
                                ) {
                                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add Photos", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        StatelessMediaGrid(
                            gridState = gridState,
                            bottomPadding = padding.calculateBottomPadding(),
                            pagedMedia = pagedMedia,
                            mediaMap = mediaMap,
                            columnCount = detailColumns,
                            screenWidthPx = screenWidthPx,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds,
                            deviceTier = deviceTier,
                            onToggleSelection = { item: MediaItem ->
                                if (dragSelection.contains(item.id)) {
                                    dragSelection.remove(item.id)
                                } else if (dragSelection.size < 5000) {
                                    dragSelection.add(item.id)
                                }
                                selectedIds = dragSelection.toImmutableSet()
                            },
                            onSelectRange = { items: List<MediaItem> ->
                                dragSelection.clear()
                                items.take(5000).forEach { dragSelection.add(it.id) }
                                selectedIds = dragSelection.toImmutableSet()
                            },
                            onSelectAll = { isAllSelected: Boolean ->
                                if (isAllSelected) {
                                    dragSelection.clear()
                                } else {
                                    dragSelection.clear()
                                    val mediaItems = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>()
                                    mediaItems.forEach { dragSelection.add(it.item.id) }
                                }
                                selectedIds = dragSelection.toImmutableSet()
                            },
                            onMediaClick = { item: MediaItem ->
                                openedMediaItem = item
                                viewModel.openViewer(item)
                            },
                            onMediaLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                }
                            },
                            onToggleFavorite = { id: Long -> viewModel.toggleFavorite(id) },
                            header = {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    BasicTextField(
                                        value = localSearchQuery,
                                        onValueChange = { localSearchQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(23.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        decorationBox = { innerTextField ->
                                            Row(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Box(Modifier.weight(1f)) {
                                                    if (localSearchQuery.isEmpty()) {
                                                        Text(
                                                            text = "Search inside $albumTitle...",
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                                if (localSearchQuery.isNotEmpty()) {
                                                    IconButton(
                                                        onClick = { localSearchQuery = "" },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Close,
                                                            contentDescription = "Clear",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SamsungFilterChip(selected = mediaFilter == MediaTypeFilter.ALL, label = "All") { mediaFilter = MediaTypeFilter.ALL }
                                            SamsungFilterChip(selected = mediaFilter == MediaTypeFilter.PHOTOS, label = "Photos") { mediaFilter = MediaTypeFilter.PHOTOS }
                                            SamsungFilterChip(selected = mediaFilter == MediaTypeFilter.VIDEOS, label = "Videos") { mediaFilter = MediaTypeFilter.VIDEOS }
                                        }
                                        Surface(
                                            onClick = { activeDialog = DetailUiDialog.Sort },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = when (currentPhotoSort) {
                                                        PhotoSort.DateDesc -> "Newest"
                                                        PhotoSort.DateAsc -> "Oldest"
                                                        PhotoSort.NameAsc -> "A-Z"
                                                        PhotoSort.NameDesc -> "Z-A"
                                                        PhotoSort.SizeDesc -> "Size"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        val snapshotItems = remember(pagedMedia.itemSnapshotList, mediaMap) {
            pagedMedia.itemSnapshotList.items
                .filterIsInstance<GalleryGridItem.Media>()
                .map { mediaMap[it.item.id] ?: it.item }
        }

        val openViewerState = viewerState as? GalleryViewerState.Open
        val viewerItemId = openViewerState?.mediaId

        val viewerItems = remember(snapshotItems, openedMediaItem, viewerItemId) {
            buildList<MediaItem> {
                addAll(snapshotItems)
                val opened = openedMediaItem
                if (opened != null && none { existing -> existing.id == opened.id }) {
                    add(0, opened)
                }
            }
        }

        if (viewerState is GalleryViewerState.Open && viewerItems.isNotEmpty()) {
            val stableStartIndex = viewerItems
                .indexOfFirst { media -> media.id == viewerItemId }
                .coerceAtLeast(0)

            FullscreenMediaPager(
                initialIndex = stableStartIndex,
                mediaList = viewerItems,
                mediaMap = mediaMap,
                favoriteIds = favoriteIds,
                sharedPlayer = viewModel.getPlayer(),
                onPageChanged = { item ->
                    openedMediaItem = item
                    viewModel.openViewer(item)
                },
                onClose = {
                    openedMediaItem = null
                    viewModel.closeViewer()
                },
                onToggleFavorite = { id: Long ->
                    viewModel.toggleFavorite(id)
                },
                onEdit = { item: MediaItem ->
                    viewModel.closeViewer()
                    openedMediaItem = null

                    if (item.isVideo) {
                        actions.onNavigateToVideoEditor(
                            item.uri.toString(),
                            item.id
                        )
                    } else {
                        actions.onNavigateToPhotoEditor(
                            item.uri.toString(),
                            item.id
                        )
                    }
                },
                onPlayVideo = { uri: String, playlist: List<String> ->
                    viewModel.setVideoPlaylist(playlist)
                    openedMediaItem = null
                    viewModel.closeViewer()
                    actions.onNavigateToVideoPlayer(uri, playlist)
                },
                onDelete = { item: MediaItem ->
                    // Release ExoPlayer lock before opening the Trash confirmation sheet
                    runCatching {
                        val player = viewModel.getPlayer()
                        player.pause()
                        player.stop()
                        player.clearMediaItems()
                    }
                    activeDialog = DetailUiDialog.Delete(listOf(item.id), listOf(item))
                },
                onMove = { item: MediaItem ->
                    viewModel.closeViewer()
                    openedMediaItem = null

                    actions.onNavigateToMoveCopy(
                        "MOVE",
                        item.id.toString(),
                        albumId
                    )
                },
                onCopy = { item: MediaItem ->
                    viewModel.closeViewer()
                    openedMediaItem = null

                    actions.onNavigateToMoveCopy(
                        "COPY",
                        item.id.toString(),
                        albumId
                    )
                },
                onWallpaper = { item: MediaItem ->
                    viewModel.closeViewer()
                    openedMediaItem = null

                    actions.onNavigateToWallpaper(
                        item.uri.toString(),
                        item.id
                    )
                }
            )
        }

        // Dialogs placed AFTER FullscreenMediaPager so BottomSheets render above the viewer cleanly
        when (val dialog = activeDialog) {
            is DetailUiDialog.DeleteAlbum -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = DetailUiDialog.None },
                    icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("Delete Album?") },
                    text = { Text("This will delete the album or move its items to Trash.") },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                actions.onDeleteAlbum?.invoke(albumId)
                                activeDialog = DetailUiDialog.None
                                actions.onBack()
                            }
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeDialog = DetailUiDialog.None }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            is DetailUiDialog.Delete -> {
                ModernMoveToTrashSheet(
                    count = dialog.mediaIds.size,
                    onDismiss = { activeDialog = DetailUiDialog.None },
                    onConfirm = {
                        val currentSnapshotItems = pagedMedia.itemSnapshotList.items
                            .filterIsInstance<GalleryGridItem.Media>()
                            .map { mediaMap[it.item.id] ?: it.item }

                        val itemsToTrash = if (dialog.directItems.isNotEmpty()) {
                            dialog.directItems
                        } else {
                            currentSnapshotItems.filter { dialog.mediaIds.contains(it.id) }
                                .ifEmpty {
                                    dialog.mediaIds.mapNotNull { id ->
                                        mediaMap[id] ?: openedMediaItem?.takeIf { it.id == id }
                                    }
                                }
                        }

                        // Close viewer and release player BEFORE trashing so Pager never crashes on deleted item
                        activeDialog = DetailUiDialog.None
                        isSelectionMode = false
                        openedMediaItem = null
                        runCatching {
                            val player = viewModel.getPlayer()
                            player.pause()
                            player.stop()
                            player.clearMediaItems()
                        }
                        viewModel.closeViewer()

                        if (itemsToTrash.isNotEmpty()) {
                            trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                            invalidateThumbCache(context, itemsToTrash.map { it.uri })
                        }
                    }
                )
            }
            is DetailUiDialog.GridSize -> {
                ModernGridSheet(
                    currentColumns = detailColumns,
                    max = 8,
                    onDismiss = { activeDialog = DetailUiDialog.None },
                    onUpdate = { cols: Int ->
                        detailColumns = cols
                        prefs.edit().putInt("gallery_media_grid_columns", cols).apply()
                        activeDialog = DetailUiDialog.None
                    }
                )
            }
            is DetailUiDialog.Sort -> {
                ModernMediaSortSheet(
                    activeSort = currentPhotoSort,
                    onDismiss = { activeDialog = DetailUiDialog.None },
                    onSortSelected = { sort: PhotoSort ->
                        currentPhotoSort = sort
                        activeDialog = DetailUiDialog.None
                    }
                )
            }
            else -> {}
        }

        if (showRenameSheet && album != null) {
            ModernInputSheet(
                title = "Rename Album",
                initial = album.name,
                onDismiss = { showRenameSheet = false },
                onConfirm = { newName: String ->
                    val currentSnapshotItems = pagedMedia.itemSnapshotList.items
                        .filterIsInstance<GalleryGridItem.Media>()
                        .map { mediaMap[it.item.id] ?: it.item }
                    viewModel.renameAlbum(album, newName)
                    invalidateThumbCache(context, currentSnapshotItems.filter { it.bucketId == album.id }.map { it.uri })
                    showRenameSheet = false
                }
            )
        }

        metadataItemToShow?.let {
            MediaMetadataSheet(
                item = it,
                onDismiss = { metadataItemToShow = null }
            )
        }
    }
}

@Composable
fun ModernDateHeader(modifier: Modifier = Modifier, title: String, onSelectAllForDate: () -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelectAllForDate() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = Icons.Rounded.DoneAll,
            contentDescription = "Select Date Group",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * =========================================================================================
 * 🔲 STATELESS ALBUM GRID (Fixed Drag-and-Reorder + Non-Pinned Top Recent)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessAlbumGrid(
    gridState: LazyGridState,
    bottomPadding: Dp,
    columnCount: Int,
    dynamicList: SnapshotStateList<Album>,
    albumPreviews: ImmutableMap<String, ImmutableList<Uri>>,
    isSelectionMode: Boolean,
    selectedIds: ImmutableSet<String>,
    sortOption: AlbumSort,
    searchQuery: String,
    screenWidthDp: Float,
    deviceTier: DeviceTier,
    sdCardAlbums: Set<String>,
    onOrderSaved: (List<Album>) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onSelectionChange: (ImmutableSet<String>) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onDragStateChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var draggedAlbumId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var scrollVelocity by remember { mutableFloatStateOf(0f) }
    var lastSwapTimeMs by remember { mutableLongStateOf(0L) }

    val actualColumns = columnCount.coerceAtLeast(1)

    val currentSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentSortOption by rememberUpdatedState(sortOption)
    val currentSearchQuery by rememberUpdatedState(searchQuery)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnOrderSaved by rememberUpdatedState(onOrderSaved)

    val dynamicThumbSize = remember(actualColumns, screenWidthDp, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260f else 480f
        val raw = ((screenWidthDp / actualColumns) * 2)
            .coerceIn(180f, maxSize)
            .toInt()
        (raw / 40) * 40
    }

    fun checkAndPerformSwap() {
        val activeDragId = draggedAlbumId ?: return
        val currentIdx = dynamicList.indexOfFirst { it.id == activeDragId }
        if (currentIdx == -1) return

        draggedIndex = currentIdx

        val layoutInfo = gridState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val draggedItemInfo = visibleItems.find { it.key == activeDragId }
            ?: visibleItems.find { it.index == currentIdx }
            ?: return

        val draggedCenterX =
            draggedItemInfo.offset.x + draggedItemInfo.size.width / 2f + dragOffset.x
        val draggedCenterY =
            draggedItemInfo.offset.y + draggedItemInfo.size.height / 2f + dragOffset.y

        scrollVelocity = when {
            draggedCenterY < layoutInfo.viewportStartOffset + 160f -> -16f
            draggedCenterY > layoutInfo.viewportEndOffset - 160f -> 16f
            else -> 0f
        }

        val now = System.currentTimeMillis()
        if (now - lastSwapTimeMs < 90L) return

        val targetItemInfo = visibleItems.find { itemInfo ->
            itemInfo.index != currentIdx &&
                    itemInfo.index in 0 until dynamicList.size &&
                    dynamicList[itemInfo.index].id != ID_RECENT &&
                    draggedCenterX.roundToInt() in
                    itemInfo.offset.x..(itemInfo.offset.x + itemInfo.size.width) &&
                    draggedCenterY.roundToInt() in
                    itemInfo.offset.y..(itemInfo.offset.y + itemInfo.size.height)
        }

        if (targetItemInfo != null) {
            val targetIndex = targetItemInfo.index
            val offsetDiff = Offset(
                (targetItemInfo.offset.x - draggedItemInfo.offset.x).toFloat(),
                (targetItemInfo.offset.y - draggedItemInfo.offset.y).toFloat()
            )

            val movedItem = dynamicList.removeAt(currentIdx)
            dynamicList.add(targetIndex, movedItem)

            draggedIndex = targetIndex
            dragOffset -= offsetDiff
            lastSwapTimeMs = now
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(scrollVelocity) {
        if (scrollVelocity != 0f) {
            while (isActive) {
                val consumed = gridState.scrollBy(scrollVelocity)
                if (consumed != 0f) {
                    dragOffset += Offset(0f, consumed)
                    checkAndPerformSwap()
                }
                delay(16)
            }
        }
    }

    val gridGestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPressChange =
                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

            val hitItemInfo = gridState.layoutInfo.visibleItemsInfo.find { info ->
                down.position.x.roundToInt() in
                        info.offset.x..(info.offset.x + info.size.width) &&
                        down.position.y.roundToInt() in
                        info.offset.y..(info.offset.y + info.size.height)
            } ?: return@awaitEachGesture

            val pressedAlbum =
                dynamicList.getOrNull(hitItemInfo.index) ?: return@awaitEachGesture

            longPressChange.consume()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

            val wasSelectionMode = currentSelectionMode
            val initialSelected = currentSelectedIds

            val canReorderThisAlbum =
                currentSortOption == AlbumSort.Custom &&
                        currentSearchQuery.isBlank() &&
                        initialSelected.size <= 1 &&
                        pressedAlbum.id != ID_RECENT

            if (!canReorderThisAlbum) {
                if (!wasSelectionMode) {
                    currentOnSelectionModeChange(true)
                    currentOnSelectionChange(persistentSetOf(pressedAlbum.id))
                } else {
                    val updated = if (initialSelected.contains(pressedAlbum.id)) {
                        initialSelected - pressedAlbum.id
                    } else {
                        initialSelected + pressedAlbum.id
                    }.toImmutableSet()

                    currentOnSelectionChange(updated)
                    if (updated.isEmpty()) currentOnSelectionModeChange(false)
                }

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
                return@awaitEachGesture
            }

            if (!wasSelectionMode) {
                currentOnSelectionModeChange(true)
                currentOnSelectionChange(persistentSetOf(pressedAlbum.id))
            }

            var hasDragged = false
            var accumulatedMove = Offset.Zero
            val slop = viewConfiguration.touchSlop

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                    if (!change.pressed) {
                        change.consume()
                        break
                    }

                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        change.consume()

                        if (!hasDragged) {
                            accumulatedMove += delta

                            if (accumulatedMove.getDistance() > slop) {
                                hasDragged = true
                                currentOnSelectionModeChange(true)
                                currentOnSelectionChange(persistentSetOf(pressedAlbum.id))

                                val resolvedIdx =
                                    dynamicList.indexOfFirst { it.id == pressedAlbum.id }

                                if (resolvedIdx != -1) {
                                    draggedAlbumId = pressedAlbum.id
                                    draggedIndex = resolvedIdx
                                    dragOffset = accumulatedMove
                                    lastSwapTimeMs = 0L
                                    currentOnDragStateChange(true)
                                    checkAndPerformSwap()
                                }
                            }
                        } else {
                            dragOffset += delta
                            checkAndPerformSwap()
                        }
                    }
                }
            } finally {
                val didDrag = hasDragged

                draggedIndex = -1
                draggedAlbumId = null
                dragOffset = Offset.Zero
                scrollVelocity = 0f
                lastSwapTimeMs = 0L

                if (didDrag) {
                    currentOnDragStateChange(false)
                    currentOnOrderSaved(dynamicList.toList())
                    currentOnSelectionModeChange(true)
                    currentOnSelectionChange(persistentSetOf(pressedAlbum.id))
                } else if (wasSelectionMode) {
                    val updated = if (initialSelected.contains(pressedAlbum.id)) {
                        initialSelected - pressedAlbum.id
                    } else {
                        initialSelected + pressedAlbum.id
                    }.toImmutableSet()

                    currentOnSelectionModeChange(updated.isNotEmpty())
                    currentOnSelectionChange(updated)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(actualColumns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .then(gridGestureModifier),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 16.dp + bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = dynamicList,
                key = { _, album -> album.id },
                contentType = { _, _ -> "album" }
            ) { index, album ->
                val isBeingDragged =
                    draggedAlbumId == album.id ||
                            (draggedAlbumId == null && draggedIndex == index)

                val reorderEnabled =
                    sortOption == AlbumSort.Custom &&
                            searchQuery.isBlank() &&
                            selectedIds.size <= 1 &&
                            album.id != ID_RECENT

                val clickModifier = Modifier.clickable {
                    if (currentSelectionMode) {
                        val newSelection =
                            if (currentSelectedIds.contains(album.id)) {
                                currentSelectedIds - album.id
                            } else {
                                currentSelectedIds + album.id
                            }

                        currentOnSelectionChange(newSelection.toImmutableSet())
                        if (newSelection.isEmpty()) {
                            currentOnSelectionModeChange(false)
                        }
                    } else {
                        onAlbumClick(album)
                    }
                }

                Box(
                    modifier = Modifier
                        .zIndex(if (isBeingDragged) 5f else 0f)
                        .graphicsLayer {
                            if (isBeingDragged) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y

                                if (deviceTier != DeviceTier.LOW) {
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                    shadowElevation = 12.dp.toPx()
                                }
                            }
                        }
                ) {
                    OptimizedAlbumTile(
                        album = album,
                        previews = albumPreviews[album.id] ?: persistentListOf(),
                        isSelected = selectedIds.contains(album.id),
                        isSelectionMode = isSelectionMode,
                        canDrag = reorderEnabled,
                        isSdCard = sdCardAlbums.contains(album.id),
                        deviceTier = deviceTier,
                        modifier = clickModifier,
                        thumbSize = dynamicThumbSize
                    )
                }
            }
        }

        SamsungFastScrollbar(
            gridState = gridState,
            itemCount = dynamicList.size,
            deviceTier = deviceTier,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
    }
}

/**
 * =========================================================================================
 * 🔲 STATELESS MEDIA GRID (Inside AlbumDetailScreen — Fixed Drag-to-Select)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessMediaGrid(
    gridState: LazyGridState,
    bottomPadding: Dp,
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    mediaMap: Map<Long, MediaItem>,
    columnCount: Int,
    screenWidthPx: Int,
    isSelectionMode: Boolean,
    selectedIds: ImmutableSet<Long>,
    deviceTier: DeviceTier,
    onToggleSelection: (MediaItem) -> Unit,
    onSelectRange: (List<MediaItem>) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onMediaLongClick: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    header: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val dynamicThumbSize = remember(columnCount, screenWidthPx, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260 else 480
        val raw = (screenWidthPx / columnCount.coerceAtLeast(1)).coerceIn(160, maxSize)
        (raw / 40) * 40
    }

    val currentMediaMap by rememberUpdatedState(mediaMap)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentOnSelectRange by rememberUpdatedState(onSelectRange)
    val currentOnMediaLongClick by rememberUpdatedState(onMediaLongClick)

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastPointerPosition by remember { mutableStateOf<Offset?>(null) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragLastIndex by remember { mutableIntStateOf(-1) }
    var dragBaseSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dragIsAdditive by remember { mutableStateOf(true) }

    fun indexAt(offset: Offset): Int {
        val layoutInfo = gridState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find {
            it.index > 0 &&
                    offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                    offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
        } ?: return -1
        return itemInfo.index - 1
    }

    fun applyRangeSelect(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex < 0 || pagedMedia.itemCount == 0) return
        val lo = minOf(fromIndex, toIndex).coerceAtLeast(0)
        val hi = maxOf(fromIndex, toIndex).coerceAtMost(pagedMedia.itemCount - 1)

        val targetIds = dragBaseSelection.toMutableSet()
        for (i in lo..hi) {
            val gridItem = runCatching { pagedMedia.peek(i) }.getOrNull()
            if (gridItem is GalleryGridItem.Media) {
                if (dragIsAdditive) {
                    if (targetIds.size < 5000) targetIds.add(gridItem.item.id)
                } else {
                    targetIds.remove(gridItem.item.id)
                }
            }