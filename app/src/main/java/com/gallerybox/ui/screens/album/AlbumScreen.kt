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
    data class Delete(val mediaIds: List<Long>) : DetailUiDialog()
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
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    val intentSenderLauncherViewModel = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onPermissionResult(granted)
        if (!granted) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(trashViewModel, lifecycleOwner) {
        trashViewModel.onRefreshGallery = { refreshAndClearCache(context, viewModel) }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            trashViewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.RequestPermission -> intentSenderLauncherTrash.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.OperationSuccess -> {
                        isSelectionMode = false
                        selectedIds = persistentListOf<Long>().toImmutableSet()
                        viewModel.closeViewer()
                        Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    }
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                when (event) {
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    is GalleryEvent.RequestPermission -> intentSenderLauncherViewModel.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.LaunchIntent -> safTreeLauncherDetail.launch(event.intent)
                    is GalleryEvent.OperationSuccess -> {
                        isSelectionMode = false
                        dragSelection.clear()
                        selectedIds = persistentListOf<Long>().toImmutableSet()
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
    BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }
    BackHandler(enabled = metadataItemToShow != null) { metadataItemToShow = null }
    BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

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
                                    activeDialog = DetailUiDialog.Delete(selectedIds.toList())
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
    }

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
                    val snapshotItems = pagedMedia.itemSnapshotList.items
                        .filterIsInstance<GalleryGridItem.Media>()
                        .map { mediaMap[it.item.id] ?: it.item }
                    val itemsToTrash = snapshotItems.filter { dialog.mediaIds.contains(it.id) }
                    if (itemsToTrash.isNotEmpty()) {
                        trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                    }
                    invalidateThumbCache(context, itemsToTrash.map { it.uri })
                    activeDialog = DetailUiDialog.None
                    isSelectionMode = false
                    viewModel.closeViewer()
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
                val snapshotItems = pagedMedia.itemSnapshotList.items
                    .filterIsInstance<GalleryGridItem.Media>()
                    .map { mediaMap[it.item.id] ?: it.item }
                viewModel.renameAlbum(album, newName)
                invalidateThumbCache(context, snapshotItems.filter { it.bucketId == album.id }.map { it.uri })
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

    val snapshotItems = remember(pagedMedia.itemSnapshotList) {
        pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().map { mediaMap[it.item.id] ?: it.item }
    }

    val openViewerState = viewerState as? GalleryViewerState.Open
    val viewerItemId = openViewerState?.mediaId

    val viewerItems = remember(
        snapshotItems,
        openedMediaItem,
        viewerItemId
    ) {
        buildList<MediaItem> {
            addAll(snapshotItems)

            val opened = openedMediaItem

            if (
                opened != null &&
                none { existing -> existing.id == opened.id }
            ) {
                add(0, opened)
            }
        }
    }

    if (
        viewerState is GalleryViewerState.Open &&
        viewerItems.isNotEmpty()
    ) {
        val stableStartIndex =
            viewerItems
                .indexOfFirst { media ->
                    media.id == viewerItemId
                }
                .coerceAtLeast(0)

        FullscreenMediaPager(
            initialIndex = stableStartIndex,
            mediaList = viewerItems,
            mediaMap = mediaMap,
            favoriteIds = favoriteIds,
            sharedPlayer = viewModel.getPlayer(),
            onPageChanged = { item ->
                openedMediaItem = item
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
                actions.onNavigateToVideoPlayer(
                    uri,
                    playlist
                )
            },
            onDelete = { item: MediaItem ->
                activeDialog = DetailUiDialog.Delete(listOf(item.id))
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
        }

        val resolvedItems = pagedMedia.itemSnapshotList.items
            .filterIsInstance<GalleryGridItem.Media>()
            .filter { targetIds.contains(it.item.id) }
            .map { currentMediaMap[it.item.id] ?: it.item }

        currentOnSelectRange(resolvedItems)
    }

    fun updateDrag(position: Offset, boxHeightPx: Int, densityScale: Float) {
        lastPointerPosition = position
        val idx = indexAt(position)
        if (idx >= 0 && idx != dragLastIndex) {
            applyRangeSelect(dragAnchorIndex, idx)
            dragLastIndex = idx
        }

        val top10 = 10 * densityScale
        val top25 = 25 * densityScale
        val top40 = 40 * densityScale

        val y = position.y

        autoScrollSpeed = when {
            y < top10 -> -24f
            y < top25 -> -14f
            y < top40 -> -6f
            y > boxHeightPx - top10 -> 24f
            y > boxHeightPx - top25 -> 14f
            y > boxHeightPx - top40 -> 6f
            else -> 0f
        }
    }

    fun endDrag() {
        autoScrollSpeed = 0f
        lastPointerPosition = null
        dragAnchorIndex = -1
        dragLastIndex = -1
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScrollSpeed)
                lastPointerPosition?.let { pos ->
                    val idx = indexAt(pos)
                    if (idx >= 0 && idx != dragLastIndex) {
                        applyRangeSelect(dragAnchorIndex, idx)
                        dragLastIndex = idx
                    }
                }
                delay(16)
            }
        }
    }

    val slideModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

            val anchor = indexAt(down.position)
            if (anchor < 0) return@awaitEachGesture

            val anchorGridItem = runCatching { pagedMedia.peek(anchor) }.getOrNull() as? GalleryGridItem.Media ?: return@awaitEachGesture
            longPress.consume()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

            dragAnchorIndex = anchor
            dragLastIndex = anchor
            dragBaseSelection = currentSelectedIds
            dragIsAdditive = !currentSelectedIds.contains(anchorGridItem.item.id)
            lastPointerPosition = down.position

            if (!currentIsSelectionMode) {
                currentOnMediaLongClick()
            }
            applyRangeSelect(dragAnchorIndex, dragLastIndex)

            var lastDragUpdateMs = 0L
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break

                    val now = System.currentTimeMillis()
                    if (now - lastDragUpdateMs >= 16L) {
                        updateDrag(change.position, size.height, density.density)
                        lastDragUpdateMs = now
                    }
                }
            } finally {
                endDrag()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount.coerceAtLeast(1)),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .then(slideModifier),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 16.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                header()
            }
            if (pagedMedia.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                        Text(text = "No matching items found", color = Color.Gray, fontSize = 15.sp)
                    }
                }
            } else {
                items(
                    count = pagedMedia.itemCount,
                    span = { index ->
                        if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount.coerceAtLeast(1)) else GridItemSpan(1)
                    },
                    key = { index ->
                        when (val item = pagedMedia.peek(index)) {
                            is GalleryGridItem.Media -> item.item.id
                            is GalleryGridItem.Header -> "header_${item.id}"
                            else -> index
                        }
                    },
                    contentType = { index ->
                        if (pagedMedia.peek(index) is GalleryGridItem.Header) "header" else "media"
                    }
                ) { index ->
                    val gridItem = pagedMedia[index]
                    when (gridItem) {
                        is GalleryGridItem.Header -> {
                            ModernDateHeader(
                                title = gridItem.title,
                                onSelectAllForDate = {
                                    if (!isSelectionMode) onMediaLongClick()
                                    val snapshot = pagedMedia.itemSnapshotList.items
                                        .filterIsInstance<GalleryGridItem.Media>()
                                        .filter { it.item.dateHeader == gridItem.title }
                                    val combinedIds = selectedIds + snapshot.map { it.item.id }
                                    val combinedItems = pagedMedia.itemSnapshotList.items
                                        .filterIsInstance<GalleryGridItem.Media>()
                                        .filter { combinedIds.contains(it.item.id) }
                                        .map { mediaMap[it.item.id] ?: it.item }
                                    onSelectRange(combinedItems)
                                }
                            )
                        }
                        is GalleryGridItem.Media -> {
                            val currentItem = mediaMap[gridItem.item.id] ?: gridItem.item
                            val itemSelected = selectedIds.contains(currentItem.id)

                            ModernMediaGridTile(
                                modifier = Modifier,
                                item = currentItem,
                                thumbSize = dynamicThumbSize,
                                isSelected = itemSelected,
                                isSelectionMode = isSelectionMode,
                                deviceTier = deviceTier,
                                onClick = {
                                    if (isSelectionMode) {
                                        onToggleSelection(currentItem)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else {
                                        onMediaClick(currentItem)
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!isSelectionMode) onMediaLongClick()
                                    onToggleSelection(currentItem)
                                },
                                onToggleFavorite = { onToggleFavorite(currentItem.id) }
                            )
                        }
                        null -> {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }
        }

        PagedSamsungFastScrollbar(
            gridState = gridState,
            pagedMedia = pagedMedia,
            indexOffset = 1,
            deviceTier = deviceTier,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/**
 * =========================================================================================
 * 🗂️ SAMSUNG ONE UI 7 ALBUM TILE (Primary Cover Focus, SD Card Icon Only, No SD Text)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptimizedAlbumTile(
    album: Album,
    previews: ImmutableList<Uri>,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    canDrag: Boolean,
    isSdCard: Boolean,
    deviceTier: DeviceTier,
    thumbSize: Int,
    modifier: Modifier = Modifier
) {
    val tileScale by animateFloatAsState(
        targetValue = if (isSelected) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "album_tile_scale"
    )
    val tileShape = RoundedCornerShape(16.dp)

    val categoryBadgeIcon: ImageVector? = remember(album.id, album.name) {
        when {
            album.id == ID_FAVORITES -> Icons.Rounded.Favorite
            album.id == ID_RECENT -> Icons.Rounded.Schedule
            album.id == ID_VIDEOS -> Icons.Rounded.Videocam
            album.id == ID_CAMERA || album.name.equals("Camera", true) -> Icons.Rounded.PhotoCamera
            album.id == ID_SCREENSHOTS || album.name.contains("Screenshot", true) -> Icons.Rounded.Screenshot
            album.id == ID_DOWNLOADS || album.name.contains("Download", true) -> Icons.Rounded.Download
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = tileScale
                scaleY = tileScale
            }
            .then(modifier)
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(tileShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, tileShape)
                    } else {
                        Modifier
                    }
                )
        ) {
            val actualCoverUri = remember(album.coverUri, previews) {
                if (album.coverUri != Uri.EMPTY) album.coverUri else previews.firstOrNull()
            }
            val context = LocalContext.current

            if (actualCoverUri == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoAlbum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Empty",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val clampedSize = remember(thumbSize) { thumbSize.coerceIn(160, 480) }

                val request = remember(actualCoverUri, clampedSize, deviceTier) {
                    ImageRequest.Builder(context)
                        .data(actualCoverUri)
                        .size(clampedSize)
                        .memoryCacheKey("${actualCoverUri}_thumb_$clampedSize")
                        .diskCacheKey("${actualCoverUri}_thumb_$clampedSize")
                        .bitmapConfig(if (deviceTier == DeviceTier.LOW) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .precision(Precision.INEXACT)
                        .allowHardware(deviceTier != DeviceTier.LOW)
                        .crossfade(120)
                        .build()
                }
                val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
                val placeholderPainter = remember(surfaceColor) { ColorPainter(surfaceColor) }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    placeholder = placeholderPainter,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.65f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.42f)
                            )
                        )
                )
            }

            if (categoryBadgeIcon != null) {
                Icon(
                    imageVector = categoryBadgeIcon,
                    contentDescription = null,
                    tint = if (album.id == ID_FAVORITES) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(16.dp)
                )
            }

            if (album.isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(14.dp)
                )
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) Color.Black.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.06f))
                )
                Box(modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.White, CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = "Unselected",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = album.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isSdCard) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.SdStorage,
                    contentDescription = "SD Card",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "${album.mediaCount}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernMediaGridTile(
    modifier: Modifier = Modifier,
    item: MediaItem,
    thumbSize: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    deviceTier: DeviceTier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "media_tile_scale"
    )
    val tileRadius = 10.dp

    val context = LocalContext.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(tileRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(tileRadius))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        val effectiveSize = remember(thumbSize) {
            thumbSize.coerceIn(160, 480)
        }

        val request = remember(item.id, effectiveSize, deviceTier) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(effectiveSize)
                .memoryCacheKey("${item.id}_thumb_$effectiveSize")
                .diskCacheKey("${item.id}_thumb_$effectiveSize")
                .bitmapConfig(if (deviceTier == DeviceTier.LOW) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.DISABLED)
                .precision(Precision.INEXACT)
                .allowHardware(deviceTier != DeviceTier.LOW)
                .crossfade(0)
                .build()
        }

        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val placeholder = remember(surfaceColor) { ColorPainter(surfaceColor) }

        AsyncImage(
            model = request,
            placeholder = placeholder,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize()
        )

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val brush = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.68f))
                        onDrawBehind { drawRect(brush) }
                    }
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    text = formatDuration(item.duration),
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (item.isFavorite && !isSelectionMode) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = "Favorite",
                tint = Color(0xFFFF4D6D),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(14.dp)
            )
        }

        SelectionOverlay(
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            cornerRadius = tileRadius,
            deviceTier = deviceTier
        )
    }
}

@Composable
fun SelectionOverlay(isSelected: Boolean, isSelectionMode: Boolean, cornerRadius: Dp, deviceTier: DeviceTier) {
    if (isSelectionMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.Black.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.05f))
            )

            val enterAnim = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else scaleIn() + fadeIn()
            val exitAnim = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else scaleOut() + fadeOut()

            AnimatedVisibility(
                visible = isSelected,
                enter = enterAnim,
                exit = exitAnim,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(21.dp)
                        .background(Color.White, CircleShape)
                )
            }
            if (!isSelected) {
                Icon(
                    imageVector = Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(21.dp)
                )
            }
        }
    }
}

@Composable
fun SamsungFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label.ifEmpty { " " },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                maxLines = 1
            )
        }
    }
}

@Composable
fun BottomBarActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val alphaColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = alphaColor,
            modifier = Modifier.size(21.dp)
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = alphaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.width(4.dp))
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Search albums, photos...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(32.dp)
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
    }
}

@Composable
fun EmptyAlbumsOverlay(onCreateClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.PhotoAlbum,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "No Albums",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Create albums to organize your memories.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Create Album",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumSortSheet(activeSort: AlbumSort, onDismiss: () -> Unit, onSortSelected: (AlbumSort) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 36.dp,
                height = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Sort Albums",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Choose album arrangement",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            AlbumSort.entries.forEach { option ->
                val isSelected = activeSort == option
                val sortLabel = when (option.name) {
                    "DateDesc" -> "Newest First"
                    "DateAsc" -> "Oldest First"
                    "NameAsc" -> "Name (A → Z)"
                    "NameDesc" -> "Name (Z → A)"
                    "SizeDesc" -> "Largest First"
                    "CountDesc" -> "Most Items"
                    "Custom" -> "Custom Order (Drag to Reorder)"
                    else -> option.name
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSortSelected(option) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = sortLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernMediaSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 36.dp,
                height = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Sort Media",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Arrange photos and videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PhotoSort.entries.forEach { option ->
                val isSelected = activeSort == option
                val sortLabel = when (option) {
                    PhotoSort.DateDesc -> "Newest First"
                    PhotoSort.DateAsc -> "Oldest First"
                    PhotoSort.NameAsc -> "Name (A → Z)"
                    PhotoSort.NameDesc -> "Name (Z → A)"
                    PhotoSort.SizeDesc -> "Largest First"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSortSelected(option) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = sortLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernGridSheet(currentColumns: Int, max: Int = 8, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Grid Density",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Choose columns per row",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(max) { index ->
                    val col = index + 1
                    val isSelected = currentColumns == col
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onUpdate(col)
                                onDismiss()
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = col.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (col == 1) "col" else "cols",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernCreateAlbumSheet(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var useSdCard by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Create Album",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Organize your memories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text("Album Name") },
                leadingIcon = { Icon(Icons.Outlined.Folder, null) }
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useSdCard = !useSdCard }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = useSdCard, onCheckedChange = { useSdCard = it })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Create on SD Card", fontWeight = FontWeight.Medium)
                        Text(
                            text = "Store album externally",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { if (text.isNotBlank()) onCreate(text.trim(), useSdCard) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = text.isNotBlank()
                ) {
                    Text("Create Album", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernInputSheet(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enter album folder name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = text.isNotBlank()
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernMoveToTrashSheet(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Move to Trash",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$count item(s) selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Items will be moved to the Trash. You can restore them within 30 days.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Move to Trash", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernAlbumTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    isAppLockEnabled: Boolean,
    onSearchClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                text = "Albums",
                maxLines = 1,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        actions = {
            IconButton(onClick = { onMenuAction("create") }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Create Album",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
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
                        text = { Text("Create Album", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("create"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.CreateNewFolder, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Grid Size", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("grid"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.GridView, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort Albums", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("sort"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Sort, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("View Duplicates", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("duplicates"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Scan Library", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("scan"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.ImageSearch, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Hidden Albums", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("hidden"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.VisibilityOff, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Trash", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("trash"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock", fontWeight = FontWeight.Medium) },
                        onClick = { onMenuAction("toggle_lock"); showMenu = false },
                        leadingIcon = { Icon(imageVector = if (isAppLockEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ds = remember(item) {
        SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(item.dateAdded * 1000L))
    }
    val sz = remember(item) { Formatter.formatFileSize(context, item.size) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Media Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Information & metadata",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    MetadataRow(icon = Icons.Outlined.Title, label = "Name", value = item.name)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    MetadataRow(icon = Icons.Outlined.Folder, label = "Path", value = item.path)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Date", value = ds)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    MetadataRow(icon = Icons.Outlined.Storage, label = "Size", value = sz)
                    if (item.width > 0 && item.height > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        MetadataRow(icon = Icons.Outlined.AspectRatio, label = "Resolution", value = "${item.width} × ${item.height}")
                    }
                    if (item.isVideo && item.duration > 0L) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        MetadataRow(icon = Icons.Outlined.Timer, label = "Duration", value = formatDuration(item.duration))
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ FULL SCREEN VIEWER OVERLAY (Samsung One UI Cinema Pager)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    favoriteIds: List<Long>,
    sharedPlayer: Player,
    onPageChanged: (MediaItem) -> Unit,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onEdit: (MediaItem) -> Unit,
    onPlayVideo: (String, List<String>) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val ctx = LocalContext.current
    val vi = LocalView.current

    val safe = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val st = rememberPagerState(initialPage = safe) { mediaList.size }

    var ctrl by remember { mutableStateOf(true) }
    var meta by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val act = remember { ctx.findActivity() }
    val vid = remember(mediaList) { mediaList.filter { it.isVideo } }

    LaunchedEffect(initialIndex, mediaList.size) {
        if (st.currentPage != initialIndex && initialIndex in mediaList.indices) {
            st.scrollToPage(initialIndex)
        }
    }

    val performClose = {
        sharedPlayer.playWhenReady = false
        sharedPlayer.pause()
        sharedPlayer.stop()
        sharedPlayer.clearMediaItems()
        onClose()
    }

    val currId = mediaList.getOrNull(st.currentPage.coerceIn(0, maxOf(mediaList.lastIndex, 0)))?.id
    LaunchedEffect(currId) {
        if (currId == null && mediaList.isNotEmpty()) performClose()
    }

    val curr = remember(currId, mediaMap, mediaList) {
        currId?.let { mediaMap[it] ?: mediaList.find { i -> i.id == it } }
    }

    LaunchedEffect(vid) {
        if (vid.isNotEmpty()) {
            sharedPlayer.setMediaItems(vid.map { Media3Item.fromUri(it.uri) })
            sharedPlayer.repeatMode = Player.REPEAT_MODE_ONE
            sharedPlayer.playWhenReady = false
            sharedPlayer.prepare()
        }
    }

    LaunchedEffect(st.currentPage, vid) {
        ctrl = true

        val current = mediaList.getOrNull(st.currentPage) ?: return@LaunchedEffect
        val resolvedCurrent = mediaMap[current.id] ?: current

        onPageChanged(resolvedCurrent)

        if (current.isVideo) {
            val videoIndex = vid.indexOfFirst { it.id == current.id }

            if (videoIndex >= 0 && videoIndex < sharedPlayer.mediaItemCount) {
                sharedPlayer.seekTo(videoIndex, 0L)
                sharedPlayer.playWhenReady = true
                sharedPlayer.play()
            }
        } else {
            sharedPlayer.pause()
            sharedPlayer.playWhenReady = false
        }
    }

    DisposableEffect(act) {
        val w = act?.window
        if (w != null) {
            val c = WindowCompat.getInsetsController(w, vi)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            ctrl = true
            meta = false
            more = false
            w?.let { WindowCompat.getInsetsController(it, vi).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    BackHandler(enabled = !ctrl) { ctrl = true }
    BackHandler(enabled = ctrl) { performClose() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = st,
            pageSpacing = 18.dp,
            key = { mediaList[it].id },
            modifier = Modifier.fillMaxSize()
        ) { p ->
            val itm = mediaList[p]
            if (itm.isVideo) {
                VideoPreviewPage(
                    item = itm,
                    isCurrentPage = st.currentPage == p,
                    showControls = ctrl,
                    sharedPlayer = sharedPlayer,
                    onTap = { ctrl = !ctrl },
                    onPlay = {
                        sharedPlayer.pause()
                        onPlayVideo(itm.uri.toString(), vid.map { it.uri.toString() })
                    }
                )
            } else {
                ZoomableImagePage(
                    item = itm,
                    onTap = { ctrl = !ctrl },
                    onDismiss = { performClose() },
                    onZoomChanged = {},
                    onControlsVisibilityChange = { ctrl = it }
                )
            }
        }

        AnimatedVisibility(
            visible = ctrl,
            enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { -it / 3 }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { -it / 3 }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Black.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { performClose() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = curr?.let { shortViewerDateFormatter.format(Date(it.dateAdded * 1000L)) } ?: "",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        curr?.bucketName?.takeIf { it.isNotBlank() }?.let { albumName ->
                            Text(
                                text = albumName,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = { meta = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Details",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = ctrl && curr != null,
            enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 3 }),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 3 }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            curr?.let { currentItem ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Surface(
                        color = Color(0xCC12141A),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PremiumViewerAction(icon = Icons.Outlined.Edit, label = "Edit") { onEdit(currentItem) }
                            PremiumViewerAction(
                                icon = if (favoriteIds.contains(currentItem.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                label = if (favoriteIds.contains(currentItem.id)) "Unfavorite" else "Favorite",
                                tint = if (favoriteIds.contains(currentItem.id)) Color(0xFFFF4D6D) else Color.White
                            ) {
                                onToggleFavorite(currentItem.id)
                            }
                            PremiumViewerAction(icon = Icons.Outlined.Share, label = "Share") {
                                ctx.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = if (currentItem.isVideo) "video/*" else "image/*"
                                            putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Share Media"
                                    )
                                )
                            }
                            PremiumViewerAction(icon = Icons.Outlined.Delete, label = "Delete", tint = Color(0xFFFF6B6B)) { onDelete(currentItem) }
                            PremiumViewerAction(icon = Icons.Outlined.MoreVert, label = "More") { more = true }
                        }
                    }
                }
            }
        }
    }

    if (meta && curr != null) {
        MediaMetadataSheet(item = curr) { meta = false }
    }

    if (more && curr != null) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { more = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    width = 36.dp,
                    height = 4.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                )
            }
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("Details", fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; meta = true }
                )
                ListItem(
                    headlineContent = { Text("Move to Album", fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; onMove(curr) }
                )
                ListItem(
                    headlineContent = { Text("Copy to Album", fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; onCopy(curr) }
                )
                if (curr.isVideo) {
                    ListItem(
                        headlineContent = { Text("Open In", fontWeight = FontWeight.Medium) },
                        leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        modifier = Modifier.clickable {
                            more = false
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(curr.uri, "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            )
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.Wallpaper, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; onWallpaper(curr) }
                )
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val ctx = LocalContext.current
    var isMuted by rememberSaveable(item.id) { mutableStateOf(false) }

    val d = LocalDensity.current
    val conf = LocalConfiguration.current
    val wPx = with(d) { conf.screenWidthDp.dp.roundToPx() }
    val hPx = with(d) { conf.screenHeightDp.dp.roundToPx() }

    LaunchedEffect(isMuted, isCurrentPage) {
        if (isCurrentPage) {
            sharedPlayer.volume = if (isMuted) 0f else 1f
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = remember(item.id, wPx, hPx) {
                ImageRequest.Builder(ctx)
                    .data(item.uri)
                    .size(Size(wPx, hPx))
                    .precision(Precision.INEXACT)
                    .memoryCacheKey("full_${item.id}")
                    .crossfade(true)
                    .build()
            },
            placeholder = null,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        AndroidView(
            factory = {
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)

                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            view.performClick()
                        }
                        false
                    }
                }
            },
            update = { playerView ->
                if (isCurrentPage) {
                    if (playerView.player != sharedPlayer) {
                        playerView.player = sharedPlayer
                    }
                } else {
                    if (playerView.player == sharedPlayer) {
                        playerView.player = null
                    }
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScaleButton(
                        onClick = {
                            sharedPlayer.pause()
                            onPlay()
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xCC12141A),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play video",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Play video",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    ScaleButton(onClick = { isMuted = !isMuted }) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = CircleShape,
                            color = Color(0xCC12141A),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                            shadowElevation = 4.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isMuted) {
                                        Icons.Rounded.VolumeOff
                                    } else {
                                        Icons.Rounded.VolumeUp
                                    },
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    ScaleButton(onClick = onClick) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = tint.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * --- THE MICROSCOPE (ZoomableImagePage) ---
 */
@Composable
fun ZoomableImagePage(
    item: MediaItem,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onControlsVisibilityChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val hap = LocalHapticFeedback.current
    val conf = LocalConfiguration.current

    val wPx = with(d) { conf.screenWidthDp.dp.roundToPx() }
    val hPx = with(d) { conf.screenHeightDp.dp.roundToPx() }

    val thr = remember(conf.screenHeightDp, d) { with(d) { conf.screenHeightDp.dp.toPx() * 0.25f } }

    var sc by remember { mutableFloatStateOf(1f) }
    var oX by remember { mutableFloatStateOf(0f) }
    var oY by remember { mutableFloatStateOf(0f) }
    var bA by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(sc) { onZoomChanged(sc > 1.05f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bA))
            .graphicsLayer {
                val ds = 1f - (abs(oY) / 2200f)
                scaleX = sc * ds
                scaleY = scaleX
                alpha = (1f - (abs(oY) / 850f)).coerceIn(0f, 1f)
                translationX = oX
                translationY = oY
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        sc = if (sc > 1f) 1f else 2.5f
                        oX = 0f
                        oY = 0f
                    },
                    onLongPress = { hap.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()

                        if (abs(zoom - 1f) > 0.005f) {
                            sc = (sc * zoom).coerceIn(1f, 4f)
                        }

                        if (sc > 1.05f) {
                            event.changes.forEach {
                                if (it.positionChange() != Offset.Zero) {
                                    it.consume()
                                }
                            }
                            val mx = (size.width * (sc - 1)) / 2f
                            val my = (size.height * (sc - 1)) / 2f
                            oX = (oX + pan.x).coerceIn(-mx, mx)
                            oY = (oY + pan.y).coerceIn(-my, my)
                        } else {
                            val isV = abs(pan.y) > abs(pan.x)
                            if (isV && event.changes.size == 1) {
                                oY += pan.y
                                bA = (1f - abs(oY) / 900f).coerceIn(0.35f, 1f)
                                event.changes.forEach {
                                    if (it.positionChange() != Offset.Zero) {
                                        it.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (sc <= 1.05f) {
                        if (abs(oY) > thr) {
                            hap.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        } else {
                            oY = 0f
                            bA = 1f
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = remember(item.id, wPx, hPx) {
                ImageRequest.Builder(ctx)
                    .data(item.uri)
                    .size(Size(wPx, hPx))
                    .precision(Precision.INEXACT)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .memoryCacheKey("full_${item.id}")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .error(android.R.drawable.ic_menu_report_image)
                    .build()
            },
            placeholder = null,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}