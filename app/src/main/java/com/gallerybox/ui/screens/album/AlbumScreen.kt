@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.album

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
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem as Media3Item
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.filter
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.gallerybox.data.Album
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.viewmodel.AlbumSort
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MergeMode
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.SecurityViewModel
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    data class Info(val album: Album) : AlbumUiDialog()
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
    val gb = m.totalMem / (1024.0 * 1024 * 1024)
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
        "video", "videos" -> album.id in listOf(ID_CAMERA, ID_WHATSAPP)
        "photo", "photos", "image" -> album.id in listOf(ID_CAMERA, ID_RECENT)
        "fav", "favorite", "heart" -> album.id == ID_FAVORITES
        "download" -> album.id == ID_DOWNLOADS
        "social", "chat" -> album.id in listOf(ID_WHATSAPP, ID_INSTAGRAM)
        else -> false
    }
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
    ).authenticate(
        androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("GalleryBox Security")
            .setSubtitle("Confirm identity to change lock settings")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
    )
}

private fun invalidateThumbCache(context: Context, uris: List<Uri>) {
    val cache = context.imageLoader.memoryCache
    val diskCache = context.imageLoader.diskCache
    uris.forEach { uri ->
        (180..480 step 40).forEach { size ->
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

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }
    var bubbleLabel by remember { mutableStateOf("") }

    val thumbHeightDp = 48.dp
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
                .width(52.dp)
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
                    .width(5.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(4.dp)
                    )
            )

            if (isDragging && bubbleLabel.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = with(density) { (-100.dp).roundToPx() },
                                y = (thumbOffsetPx - with(density) { 24.dp.toPx() }).toInt().coerceAtLeast(0)
                            )
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = bubbleLabel,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }

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
                    .padding(end = 2.dp)
                    .width(if (isDragging) 12.dp else 8.dp)
                    .height(thumbHeightDp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(7.dp)
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

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }

    val thumbHeightDp = 48.dp
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
                .width(52.dp)
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
                    .width(5.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(4.dp)
                    )
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
                    .padding(end = 2.dp)
                    .width(if (isDragging) 12.dp else 8.dp)
                    .height(thumbHeightDp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(7.dp)
                    )
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.HONEYCOMB_MR2)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    actions: AlbumActions
) {
    val context = LocalContext.current
    val deviceTier = remember { getDeviceTier(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    LaunchedEffect(Unit) { viewModel.forceSync() }

    val safTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSafTreeGranted(result.data?.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is GalleryEvent.LaunchIntent -> safTreeLauncher.launch(event.intent)
                else -> {}
            }
        }
    }

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
    var showSelectionMenu by remember { mutableStateOf(false) }
    var optimisticallyRemovedAlbums by remember { mutableStateOf(persistentSetOf<String>()) }

    LaunchedEffect(viewerState, isSelectionMode) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode)
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
        val virtualAlbumsMutable = vmAlbums.filter { it.id.startsWith("virtual_") && it.id != ID_FAVORITES && albumMatchesQuery(it, searchQuery) }.toMutableList()
        if (favAlbumStats != null) {
            val updatedFavAlbum = vmAlbums.find { it.id == ID_FAVORITES }?.copy(
                mediaCount = favAlbumStats.mediaCount,
                sizeBytes = favAlbumStats.sizeBytes,
                coverUri = favAlbumStats.coverUri
            ) ?: favAlbumStats

            if (albumMatchesQuery(updatedFavAlbum, searchQuery)) {
                virtualAlbumsMutable.add(updatedFavAlbum)
            }
        }
        val sortedVirtualAlbums = virtualAlbumsMutable.sortedBy {
            when (it.id) {
                ID_RECENT -> 0
                ID_FAVORITES -> 1
                ID_DOWNLOADS -> 2
                else -> 99
            }
        }
        val userAlbums = vmAlbums.filter { !it.id.startsWith("virtual_") && albumMatchesQuery(it, searchQuery) && !optimisticallyRemovedAlbums.contains(it.id) }
        val sortedUserAlbums = if (sortOption == AlbumSort.Custom) {
            userAlbums
        } else {
            userAlbums.sortedWith(Comparator { a, b ->
                if (a.isPinned != b.isPinned) {
                    b.isPinned.compareTo(a.isPinned)
                } else {
                    when (sortOption.name) {
                        "NameAsc" -> a.name.compareTo(b.name, ignoreCase = true)
                        "NameDesc" -> b.name.compareTo(a.name, ignoreCase = true)
                        "SizeDesc" -> b.sizeBytes.compareTo(a.sizeBytes)
                        "CountDesc" -> b.mediaCount.compareTo(a.mediaCount)
                        else -> 0
                    }
                }
            })
        }
        (sortedVirtualAlbums + sortedUserAlbums).toImmutableList()
    }

    val sdCardAlbums = remember(allMedia.size) {
        allMedia.filter { item ->
            if (item.volumeName.isNotBlank()) {
                item.volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY && item.volumeName != "external"
            } else {
                item.path.startsWith("/storage/") &&
                        !item.path.startsWith("/storage/emulated/") &&
                        !item.path.startsWith("/storage/self/")
            }
        }.map { it.bucketId }.toSet()
    }

    val dynamicList = remember { mutableStateListOf<Album>() }

    LaunchedEffect(displayAlbums) {
        if (!isDragging) {
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
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var columnCount by remember { mutableIntStateOf(prefs.getInt("gallery_album_grid_columns", 3)) }
    val gridState = rememberLazyGridState()

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.onRefreshGallery = { refreshAndClearCache(context, viewModel) }
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
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

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = persistentListOf<String>().toImmutableSet()
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
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    val isAllSelected = selectedIds.size == dynamicList.size && dynamicList.isNotEmpty()
                    val selectedAlbums = dynamicList.filter { selectedIds.contains(it.id) }
                    val selectedSize = selectedAlbums.sumOf { it.sizeBytes }

                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(text = "${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(text = Formatter.formatFileSize(context, selectedSize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection")
                                }
                            },
                            actions = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            selectedIds = if (isAllSelected) {
                                                persistentListOf<String>().toImmutableSet()
                                            } else {
                                                dynamicList.map { it.id }.toImmutableSet()
                                            }
                                        }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Checkbox(checked = isAllSelected, onCheckedChange = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "All", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )
                    }
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
                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
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
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 10.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarActionItem(icon = Icons.AutoMirrored.Outlined.DriveFileMove, label = "Move") {
                                activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, true)
                            }
                            BottomBarActionItem(icon = Icons.Outlined.FileCopy, label = "Copy") {
                                activeDialog = AlbumUiDialog.MoveCopy(dynamicList.filter { selectedIds.contains(it.id) }, false)
                            }
                            BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") {
                                shareMediaItems(context, allMedia.filter { selectedIds.contains(it.bucketId) })
                                isSelectionMode = false
                                selectedIds = persistentListOf<String>().toImmutableSet()
                            }
                            BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Trash", isDestructive = true) {
                                activeDialog = AlbumUiDialog.Delete(dynamicList.filter { selectedIds.contains(it.id) })
                            }
                            Box {
                                BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") {
                                    showSelectionMenu = true
                                }
                                DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }) {
                                    val allPinned = dynamicList.filter { selectedIds.contains(it.id) }.all { it.isPinned }
                                    if (selectedIds.size == 1) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                showSelectionMenu = false
                                                dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Rename(it) }
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (allPinned) "Unpin" else "Pin") },
                                        onClick = {
                                            showSelectionMenu = false
                                            dynamicList.filter { selectedIds.contains(it.id) }.forEach { viewModel.toggleAlbumPin(it) }
                                            isSelectionMode = false
                                            selectedIds = persistentListOf<String>().toImmutableSet()
                                        },
                                        leadingIcon = { Icon(if (allPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, null) }
                                    )
                                    if (selectedIds.size == 1) {
                                        DropdownMenuItem(
                                            text = { Text("Info") },
                                            onClick = {
                                                showSelectionMenu = false
                                                dynamicList.find { it.id == selectedIds.first() }?.let { activeDialog = AlbumUiDialog.Info(it) }
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Info, null) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Hide Album") },
                                        onClick = {
                                            showSelectionMenu = false
                                            val currentHidden = enginePrefs.getStringSet("hidden_albums", emptySet()) ?: emptySet()
                                            val newHidden = currentHidden + selectedIds
                                            enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                                            selectedIds.forEach { id -> viewModel.toggleHiddenAlbum(id) }
                                            isSelectionMode = false
                                            selectedIds = persistentListOf<String>().toImmutableSet()
                                            Toast.makeText(context, "Albums hidden", Toast.LENGTH_SHORT).show()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }
                                    )
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
                            onOrderSaved = { albums: List<Album> -> viewModel.saveCustomAlbumOrder(albums) },
                            onAlbumClick = { album ->
                                if (isSelectionMode) {
                                    val newSelection = if (selectedIds.contains(album.id)) {
                                        (selectedIds - album.id).toImmutableSet()
                                    } else {
                                        (selectedIds + album.id).toImmutableSet()
                                    }
                                    selectedIds = newSelection
                                    if (newSelection.isEmpty()) isSelectionMode = false
                                } else {
                                    actions.onAlbumClick(album)
                                }
                            },
                            onAlbumLongClick = { album ->
                                isSelectionMode = true
                                selectedIds = persistentSetOf(album.id)
                            },
                            onDragStateChange = { dragging ->
                                isDragging = dragging
                                if (!dragging) {
                                    viewModel.saveCustomAlbumOrder(dynamicList.toList())
                                }
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
                dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
            ) {
                Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
                    val albumItems = allMedia.filter { it.bucketId == dialog.album.id }
                    val oldestItem = albumItems.minByOrNull { it.dateAdded }
                    val dateStr = oldestItem?.let {
                        SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(it.dateAdded * 1000))
                    } ?: "Unknown"
                    val albumPath = oldestItem?.path?.let { File(it).parent } ?: "Unknown"

                    Text("Album Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    MetadataRow(icon = Icons.Outlined.Title, label = "Name", value = dialog.album.name)
                    MetadataRow(icon = Icons.Outlined.Storage, label = "Size", value = Formatter.formatFileSize(context, dialog.album.sizeBytes))
                    MetadataRow(icon = Icons.Outlined.PhotoLibrary, label = "Items", value = dialog.album.mediaCount.toString())
                    MetadataRow(icon = Icons.Outlined.Folder, label = "Path", value = albumPath)
                    MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Created On", value = dateStr)
                }
            }
        }
        is AlbumUiDialog.MoveCopy -> {
            ModalBottomSheet(
                onDismissRequest = { activeDialog = AlbumUiDialog.None },
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
            ) {
                val allAlbumsState by viewModel.allAlbumsState.collectAsState(initial = emptyList())
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = if (dialog.isMove) "Move To..." else "Copy To...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        item {
                            ListItem(
                                headlineContent = { Text("Create New Album", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                                leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { activeDialog = AlbumUiDialog.CreateAndMoveCopy(dialog.albums, dialog.isMove) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        items(allAlbumsState.filter { !it.id.startsWith("virtual_") && dialog.albums.none { sel -> sel.id == it.id } }.sortedBy { it.name.lowercase() }) { targetAlbum ->
                            ListItem(
                                headlineContent = { Text(targetAlbum.name, fontWeight = FontWeight.Medium) },
                                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                                modifier = Modifier.clickable {
                                    if (dialog.isMove) {
                                        optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { id -> id.id })
                                    }
                                    viewModel.mergeAlbums(
                                        sourceAlbumIds = dialog.albums.map { id -> id.id },
                                        targetAlbumId = targetAlbum.id,
                                        mergeMode = if (dialog.isMove) MergeMode.MOVE_AND_DELETE else MergeMode.COPY
                                    )
                                    refreshAndClearCache(context, viewModel, allMedia.filter { dialog.albums.any { a -> a.id == it.bucketId } }.map { it.uri })
                                    activeDialog = AlbumUiDialog.None
                                    isSelectionMode = false
                                    selectedIds = persistentListOf<String>().toImmutableSet()
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
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
                    val mediaIds = allMedia.filter { item -> dialog.albums.any { album -> album.id == item.bucketId } }.map { it.id }
                    if (dialog.isMove) {
                        viewModel.createAndMove(mediaIds, newName)
                    } else {
                        viewModel.createAndCopy(mediaIds, newName)
                    }
                    if (dialog.isMove) {
                        optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { id -> id.id })
                    }
                    refreshAndClearCache(context, viewModel, allMedia.filter { dialog.albums.any { a -> a.id == it.bucketId } }.map { it.uri })
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
                    viewModel.renameAlbum(dialog.album, newName)
                    refreshAndClearCache(context, viewModel, allMedia.filter { it.bucketId == dialog.album.id }.map { it.uri })
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
                    optimisticallyRemovedAlbums = optimisticallyRemovedAlbums.addAll(dialog.albums.map { it.id })
                    trashViewModel.confirmPendingAlbumTrash(dialog.albums, allMedia.toList())
                    refreshAndClearCache(context, viewModel, allMedia.filter { dialog.albums.any { a -> a.id == it.bucketId } }.map { it.uri })
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
    val haptic = LocalHapticFeedback.current
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

    val pagedMediaFlow = remember(albumId, mediaFilter, debouncedSearchQuery) {
        viewModel.getPagedMediaForAlbumStream(albumId, mediaFilter, debouncedSearchQuery)
            .map { pagingData ->
                pagingData.filter { it is GalleryGridItem.Media }
            }
    }
    val pagedMedia = pagedMediaFlow.collectAsLazyPagingItems()

    var selectedIds by remember { mutableStateOf<ImmutableSet<Long>>(persistentListOf<Long>().toImmutableSet()) }
    val dragSelection = remember { mutableSetOf<Long>() }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val g = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(g)
        if (!g) Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.onRefreshGallery = { refreshAndClearCache(context, viewModel) }
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
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

    LaunchedEffect(viewerState, isSelectionMode) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode)
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
    var detailColumns by remember { mutableIntStateOf(prefs.getInt("gallery_media_grid_columns", 4)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    BackHandler(enabled = localSearchQuery.isNotEmpty()) { localSearchQuery = "" }
    BackHandler(enabled = isSelectionMode) { isSelectionMode = false }
    BackHandler(enabled = activeDialog != DetailUiDialog.None) { activeDialog = DetailUiDialog.None }
    BackHandler(enabled = metadataItemToShow != null) { metadataItemToShow = null }
    BackHandler(enabled = viewerState is GalleryViewerState.Open) { viewModel.closeViewer() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    val mediaItems = remember(pagedMedia.itemSnapshotList) { pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>() }
                    val isAllSelected = selectedIds.size == mediaItems.size && mediaItems.isNotEmpty()
                    val selectedSize = selectedIds.sumOf { id -> mediaMap[id]?.size ?: mediaItems.find { it.item.id == id }?.item?.size ?: 0L }

                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(text = "${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(text = Formatter.formatFileSize(context, selectedSize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { isSelectionMode = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Selection")
                                }
                            },
                            actions = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            if (isAllSelected) {
                                                dragSelection.clear()
                                            } else {
                                                dragSelection.clear()
                                                mediaItems.forEach { dragSelection.add(it.item.id) }
                                            }
                                            selectedIds = dragSelection.toImmutableSet()
                                        }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Checkbox(checked = isAllSelected, onCheckedChange = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "All", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )
                    }
                } else {
                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = albumTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = actions.onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, "More")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Select items") },
                                            onClick = {
                                                isSelectionMode = true
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Checklist, null) }
                                        )
                                        if (!isVirtual) {
                                            DropdownMenuItem(
                                                text = { Text("Add Photos") },
                                                onClick = {
                                                    actions.onAddMediaToAlbum?.invoke(albumId)
                                                    showMenu = false
                                                },
                                                leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Sort Media") },
                                            onClick = {
                                                activeDialog = DetailUiDialog.Sort
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Grid Size") },
                                            onClick = {
                                                activeDialog = DetailUiDialog.GridSize
                                                showMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Default.Grid4x4, null) }
                                        )
                                        if (!isVirtual && album != null) {
                                            DropdownMenuItem(
                                                text = { Text("Hide Album") },
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
                                                text = { Text(if (album.isPinned) "Unpin Album" else "Pin Album") },
                                                onClick = {
                                                    viewModel.toggleAlbumPin(album)
                                                    showMenu = false
                                                },
                                                leadingIcon = { Icon(if (album.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = {
                                                    showRenameSheet = true
                                                    showMenu = false
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.Edit, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete Album", color = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    activeDialog = DetailUiDialog.DeleteAlbum
                                                    showMenu = false
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock") },
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
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                        )
                    }
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .navigationBarsPadding(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 10.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarActionItem(icon = Icons.AutoMirrored.Outlined.DriveFileMove, label = "Move") {
                                actions.onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), albumId)
                                isSelectionMode = false
                            }
                            BottomBarActionItem(icon = Icons.Outlined.FileCopy, label = "Copy") {
                                actions.onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), albumId)
                                isSelectionMode = false
                            }
                            BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") {
                                val itemsToShare = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().filter { selectedIds.contains(it.item.id) }.map { mediaMap[it.item.id] ?: it.item }
                                shareMediaItems(context, itemsToShare)
                            }
                            BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Trash", isDestructive = true) {
                                activeDialog = DetailUiDialog.Delete(selectedIds.toList())
                            }
                            Box {
                                BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") {
                                    showMediaSelectionMenu = true
                                }
                                DropdownMenu(expanded = showMediaSelectionMenu, onDismissRequest = { showMediaSelectionMenu = false }) {
                                    if (selectedIds.size == 1) {
                                        DropdownMenuItem(
                                            text = { Text("Details") },
                                            onClick = {
                                                showMediaSelectionMenu = false
                                                metadataItemToShow = mediaMap[selectedIds.first()]
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Info, null) }
                                        )
                                    }
                                    if (albumId == ID_HIDDEN) {
                                        DropdownMenuItem(
                                            text = { Text("Unhide") },
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
                                            text = { Text("Hide") },
                                            onClick = {
                                                showMediaSelectionMenu = false
                                                viewModel.hideItems(selectedIds.toList())
                                                Toast.makeText(context, "${selectedIds.size} items hidden", Toast.LENGTH_SHORT).show()
                                                val itemsToHide = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().filter { selectedIds.contains(it.item.id) }.map { it.item.uri }
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
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                if (pagedMedia.itemCount == 0 && localSearchQuery.isBlank() && mediaFilter == MediaTypeFilter.ALL) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ImageNotSupported,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(
                                text = "No photos here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isVirtual) {
                                Spacer(Modifier.height(32.dp))
                                Button(
                                    onClick = { actions.onAddMediaToAlbum?.invoke(albumId) },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier
                                        .height(52.dp)
                                        .padding(horizontal = 24.dp)
                                ) {
                                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add Photos")
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
                                } else {
                                    if (dragSelection.size < 5000) {
                                        dragSelection.add(item.id)
                                    }
                                }
                                selectedIds = dragSelection.toImmutableSet()
                            },
                            onSelectRange = { items: List<MediaItem> ->
                                var changed = false
                                for (item in items) {
                                    if (!dragSelection.contains(item.id) && dragSelection.size < 5000) {
                                        dragSelection.add(item.id)
                                        changed = true
                                    }
                                }
                                if (changed) selectedIds = dragSelection.toImmutableSet()
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
                                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    BasicTextField(
                                        value = localSearchQuery,
                                        onValueChange = { localSearchQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                            .height(48.dp)
                                            .clip(CircleShape)
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
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Box(Modifier.weight(1f)) {
                                                    if (localSearchQuery.isEmpty()) {
                                                        Text(
                                                            text = "Search photos inside album...",
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                            fontSize = 15.sp
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
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(16.dp)
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
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
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
                text = { Text("This will delete the manual album placeholder. Any physical media stored within this folder on your device will remain intact.") },
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
                    val snapshotItems = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().map { mediaMap[it.item.id] ?: it.item }
                    val itemsToTrash = snapshotItems.filter { dialog.mediaIds.contains(it.id) }
                    if (itemsToTrash.isNotEmpty()) {
                        trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                    }
                    refreshAndClearCache(context, viewModel, itemsToTrash.map { it.uri })
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
                viewModel.renameAlbum(album, newName)
                val snapshotItems = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().map { mediaMap[it.item.id] ?: it.item }
                refreshAndClearCache(context, viewModel, snapshotItems.filter { it.bucketId == album.id }.map { it.uri })
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

    val openViewerState =
        viewerState as? GalleryViewerState.Open
    val viewerItemId =
        openViewerState?.mediaId

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
                activeDialog =
                    DetailUiDialog.Delete(
                        listOf(item.id)
                    )
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
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelectAllForDate() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatelessAlbumGrid(
    gridState: LazyGridState, bottomPadding: Dp, columnCount: Int, dynamicList: SnapshotStateList<Album>, albumPreviews: ImmutableMap<String, ImmutableList<Uri>>,
    isSelectionMode: Boolean, selectedIds: ImmutableSet<String>, sortOption: AlbumSort, searchQuery: String, screenWidthDp: Float, deviceTier: DeviceTier, sdCardAlbums: Set<String>,
    onOrderSaved: (List<Album>) -> Unit, onAlbumClick: (Album) -> Unit, onAlbumLongClick: (Album) -> Unit, onDragStateChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var scrollVelocity by remember { mutableFloatStateOf(0f) }
    val actualColumns = columnCount.coerceAtLeast(1)

    var isScrollingFast by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) {
                    isScrollingFast = true
                } else {
                    delay(180)
                    isScrollingFast = false
                }
            }
    }

    val dynamicThumbSize = remember(actualColumns, screenWidthDp, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260f else 480f
        val raw = ((screenWidthDp / actualColumns) * 2).coerceIn(180f, maxSize).toInt()
        (raw / 40) * 40
    }

    LaunchedEffect(scrollVelocity) {
        if (scrollVelocity != 0f) {
            while (isActive) {
                val consumed = gridState.scrollBy(scrollVelocity)
                if (consumed != 0f) dragOffset += Offset(0f, consumed)
                delay(16)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(items = dynamicList, key = { _, album -> album.id }, contentType = { _, _ -> "album" }) { index, album ->
                val isBeingDragged = draggedIndex == index
                val reorderEnabled = sortOption == AlbumSort.Custom && searchQuery.isBlank()
                val isSelectedSolo = isSelectionMode && selectedIds.size == 1 && selectedIds.contains(album.id)
                val canDrag = reorderEnabled && isSelectedSolo

                fun checkAndPerformSwap() {
                    val layoutInfo = gridState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val draggedItemInfo = visibleItems.find { it.index == draggedIndex } ?: return
                    val draggedCenterX = draggedItemInfo.offset.x + (draggedItemInfo.size.width / 2) + dragOffset.x.roundToInt()
                    val draggedCenterY = draggedItemInfo.offset.y + (draggedItemInfo.size.height / 2) + dragOffset.y.roundToInt()
                    scrollVelocity = when {
                        draggedCenterY < layoutInfo.viewportStartOffset + 180 -> -15f
                        draggedCenterY > layoutInfo.viewportEndOffset - 180 -> 15f
                        else -> 0f
                    }
                    val targetItemInfo = visibleItems.find {
                        it.index != draggedIndex && it.index < dynamicList.size &&
                                draggedCenterX in it.offset.x..(it.offset.x + it.size.width) &&
                                draggedCenterY in it.offset.y..(it.offset.y + it.size.height)
                    }
                    if (targetItemInfo != null) {
                        val targetIndex = targetItemInfo.index
                        dragOffset -= Offset(
                            (targetItemInfo.offset.x - draggedItemInfo.offset.x).toFloat(),
                            (targetItemInfo.offset.y - draggedItemInfo.offset.y).toFloat()
                        )
                        val item = dynamicList.removeAt(draggedIndex)
                        dynamicList.add(targetIndex, item)
                        draggedIndex = targetIndex
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                val dragModifier = if (canDrag) {
                    Modifier.pointerInput(album.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (draggedIndex != -1) return@awaitEachGesture

                            val smoothThresholdPx = with(this) { 4.dp.toPx() }
                            var accumulated = Offset.Zero
                            var started = false
                            var lastSwapCheckMs = 0L

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                if (!started) {
                                    accumulated += delta
                                    if (accumulated.getDistance() > smoothThresholdPx) {
                                        started = true
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        draggedIndex = index
                                        dragOffset = accumulated
                                        onDragStateChange(true)
                                        change.consume()
                                        checkAndPerformSwap()
                                        lastSwapCheckMs = System.currentTimeMillis()
                                    }
                                } else {
                                    change.consume()
                                    dragOffset += delta
                                    val now = System.currentTimeMillis()
                                    val swapInterval = if (deviceTier == DeviceTier.LOW) 48 else 32
                                    if (now - lastSwapCheckMs > swapInterval) {
                                        checkAndPerformSwap()
                                        lastSwapCheckMs = now
                                    }
                                }
                                if (!event.changes.any { it.pressed }) break
                            }

                            if (started) {
                                draggedIndex = -1
                                dragOffset = Offset.Zero
                                scrollVelocity = 0f
                                onDragStateChange(false)
                                onOrderSaved(dynamicList.toList())
                            }
                        }
                    }
                } else Modifier

                val itemModifier = Modifier

                Box(modifier = itemModifier
                    .zIndex(if (isBeingDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isBeingDragged) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            if (deviceTier != DeviceTier.LOW) {
                                scaleX = 1.04f
                                scaleY = 1.04f
                                shadowElevation = 16.dp.toPx()
                            }
                        }
                    }
                ) {
                    OptimizedAlbumTile(
                        album = album,
                        previews = albumPreviews[album.id] ?: persistentListOf(),
                        isSelected = selectedIds.contains(album.id),
                        isSelectionMode = isSelectionMode,
                        canDrag = canDrag,
                        isSdCard = sdCardAlbums.contains(album.id),
                        deviceTier = deviceTier,
                        dragModifier = dragModifier,
                        thumbSize = dynamicThumbSize,
                        onClick = { onAlbumClick(album) },
                        onLongClick = { onAlbumLongClick(album) }
                    )
                }
            }
        }
        SamsungFastScrollbar(
            gridState = gridState,
            itemCount = dynamicList.size,
            deviceTier = deviceTier,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

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

    var isScrollingFast by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) {
                    isScrollingFast = true
                } else {
                    delay(180)
                    isScrollingFast = false
                }
            }
    }

    val dynamicThumbSize = remember(columnCount, screenWidthPx, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260 else 480
        val raw = (screenWidthPx / columnCount.coerceAtLeast(1)).coerceIn(160, maxSize)
        (raw / 40) * 40
    }

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastPointerPosition by remember { mutableStateOf<Offset?>(null) }

    fun indexAt(offset: Offset): Int {
        val layoutInfo = gridState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find {
            it.index > 0 &&
                    offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                    offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
        } ?: return -1
        return itemInfo.index - 1
    }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragLastIndex by remember { mutableIntStateOf(-1) }
    val currentOnSelectRange by rememberUpdatedState(onSelectRange)

    fun applyRangeSelect(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex < 0) return
        val lo = minOf(fromIndex, toIndex)
        val hi = maxOf(fromIndex, toIndex).coerceAtMost(pagedMedia.itemCount - 1)
        val itemsToSelect = mutableListOf<MediaItem>()
        for (i in lo..hi) {
            val gridItem = runCatching { pagedMedia.peek(i) }.getOrNull()
            if (gridItem is GalleryGridItem.Media) {
                itemsToSelect.add(mediaMap[gridItem.item.id] ?: gridItem.item)
            }
        }
        if (itemsToSelect.isNotEmpty()) {
            currentOnSelectRange(itemsToSelect)
        }
    }

    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentOnMediaLongClick by rememberUpdatedState(onMediaLongClick)

    fun beginDrag(offset: Offset) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!currentIsSelectionMode) {
            currentOnMediaLongClick()
        }
        val idx = indexAt(offset)
        dragAnchorIndex = idx
        dragLastIndex = idx
        lastPointerPosition = offset
        if (idx >= 0) applyRangeSelect(dragAnchorIndex, dragAnchorIndex)
    }

    fun updateDrag(position: Offset, boxHeightPx: Int, densityScale: Float) {
        lastPointerPosition = position
        val idx = indexAt(position)
        if (idx >= 0 && idx != dragLastIndex) {
            val from = if (dragAnchorIndex >= 0) dragLastIndex.takeIf { it >= 0 } ?: idx else idx
            applyRangeSelect(from, idx)
            dragLastIndex = idx
            if (dragAnchorIndex < 0) dragAnchorIndex = idx
        }

        val top10 = 10 * densityScale
        val top25 = 25 * densityScale
        val top40 = 40 * densityScale
        val y = position.y

        autoScrollSpeed = when {
            y < top10 -> -18f
            y < top25 -> -10f
            y < top40 -> -5f
            y > boxHeightPx - top10 -> 18f
            y > boxHeightPx - top25 -> 10f
            y > boxHeightPx - top40 -> 5f
            else -> 0f
        }
    }

    fun endDrag() {
        autoScrollSpeed = 0f
        lastPointerPosition = null
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (isActive) {
                gridState.scrollBy(autoScrollSpeed)
                lastPointerPosition?.let { pos ->
                    val idx = indexAt(pos)
                    if (idx >= 0 && idx != dragLastIndex) {
                        applyRangeSelect(dragLastIndex.takeIf { it >= 0 } ?: idx, idx)
                        dragLastIndex = idx
                    }
                }
                delay(16)
            }
        }
    }

    val slideModifier = if (isSelectionMode) {
        Modifier.pointerInput(pagedMedia.itemCount) {
            var lastDragUpdateMs = 0L
            detectDragGestures(
                onDragStart = { offset -> beginDrag(offset) },
                onDrag = { change, _ ->
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastDragUpdateMs > 32) {
                        updateDrag(change.position, size.height, density)
                        lastDragUpdateMs = now
                    }
                },
                onDragEnd = { endDrag() },
                onDragCancel = { endDrag() }
            )
        }
    } else {
        Modifier.pointerInput(pagedMedia.itemCount) {
            var lastDragUpdateMs = 0L
            detectDragGesturesAfterLongPress(
                onDragStart = { offset -> beginDrag(offset) },
                onDrag = { change, _ ->
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastDragUpdateMs > 32) {
                        updateDrag(change.position, size.height, density)
                        lastDragUpdateMs = now
                    }
                },
                onDragEnd = { endDrag() },
                onDragCancel = { endDrag() }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize().then(slideModifier),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount) else GridItemSpan(1)
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
                                    val snapshot = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().filter { it.item.dateHeader == gridItem.title }
                                    onSelectRange(snapshot.map { mediaMap[it.item.id] ?: it.item })
                                }
                            )
                        }
                        is GalleryGridItem.Media -> {
                            val currentItem = mediaMap[gridItem.item.id] ?: gridItem.item
                            val itemSelected = selectedIds.contains(currentItem.id)
                            val itemModifier = Modifier

                            ModernMediaGridTile(
                                modifier = itemModifier,
                                item = currentItem,
                                thumbSize = dynamicThumbSize,
                                isSelected = itemSelected,
                                isSelectionMode = isSelectionMode,
                                deviceTier = deviceTier,
                                onClick = {
                                    if (isSelectionMode) {
                                        onToggleSelection(currentItem)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else onMediaClick(currentItem)
                                },
                                onToggleFavorite = { onToggleFavorite(currentItem.id) }
                            )
                        }
                        null -> {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.LightGray.copy(alpha = 0.3f))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptimizedAlbumTile(
    album: Album, previews: ImmutableList<Uri>, isSelected: Boolean, isSelectionMode: Boolean, canDrag: Boolean, isSdCard: Boolean, deviceTier: DeviceTier,
    thumbSize: Int, dragModifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (canDrag) {
        Modifier.then(dragModifier).clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else {
        Modifier.combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
    }
    val tileShape = RoundedCornerShape(20.dp)

    Column(modifier = Modifier.fillMaxWidth().then(clickModifier)) {
        Surface(
            modifier = Modifier.aspectRatio(1f),
            shape = tileShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            val actualCoverUri = remember(album.coverUri, previews) {
                if (album.coverUri != Uri.EMPTY) album.coverUri else previews.firstOrNull()
            }
            val context = LocalContext.current
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (actualCoverUri == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoAlbum,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Empty Album",
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
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=0.4f))))
                    )
                }
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.1f))
                    )
                    Box(modifier = Modifier.padding(8.dp).align(Alignment.TopStart)) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(24.dp).shadow(2.dp, CircleShape)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    imageVector = Icons.Rounded.SdStorage,
                    contentDescription = "SD Card",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "${album.mediaCount} items",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun ModernMediaGridTile(
    modifier: Modifier = Modifier, item: MediaItem, thumbSize: Int, isSelected: Boolean, isSelectionMode: Boolean, deviceTier: DeviceTier,
    onClick: () -> Unit, onToggleFavorite: () -> Unit
) {
    val animatedRadius = if (isSelected) 16.dp else 12.dp
    val scale = if (isSelected) 0.85f else 1f

    val context = LocalContext.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(animatedRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
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
                        val brush = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))
                        onDrawBehind { drawRect(brush) }
                    }
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = formatDuration(item.duration),
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        SelectionOverlay(
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            cornerRadius = animatedRadius,
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
                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent)
            )

            val enterAnim = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else scaleIn() + fadeIn()
            val exitAnim = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else scaleOut() + fadeOut()

            AnimatedVisibility(
                visible = isSelected,
                enter = enterAnim,
                exit = exitAnim,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape)
                )
            }
            if (!isSelected) {
                Icon(
                    imageVector = Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).shadow(2.dp, CircleShape)
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ElevatedCard(
        modifier = Modifier
            .width(86.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(24.dp)
                )
            }
            if (label.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    maxLines = 1
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = " ",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun BottomBarActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val alphaColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(alphaColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = alphaColor,
                modifier = Modifier.size(20.dp)
            )
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = alphaColor,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(14.dp))
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Search albums, photos...",
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
                        FilledIconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoAlbum,
                    contentDescription = null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "No Albums",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Create albums to organize your memories.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Button(
                onClick = onCreateClick,
                modifier = Modifier
                    .height(58.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(4.dp),
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
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Sort Albums",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose album arrangement",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            AlbumSort.entries.forEach { option ->
                val isSelected = activeSort == option
                val sortLabel = when (option.name) {
                    "DateDesc" -> "Newest First"
                    "DateAsc" -> "Oldest First"
                    "NameAsc" -> "A → Z"
                    "NameDesc" -> "Z → A"
                    "SizeDesc" -> "Largest First"
                    "CountDesc" -> "Most Items"
                    "Custom" -> "Manual Order"
                    else -> option.name
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSortSelected(option) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = sortLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
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
fun ModernMediaSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Sort Media",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Arrange photos and videos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
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
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSortSelected(option) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = sortLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
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
fun ModernGridSheet(currentColumns: Int, max: Int = 8, onDismiss: () -> Unit, onUpdate: (Int) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridView,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Grid Layout",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose columns per row",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(max) { index ->
                    val col = index + 1
                    val isSelected = currentColumns == col
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                onUpdate(col)
                                onDismiss()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = col.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
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
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Create Album",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Organize your memories",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text("Album Name") },
                leadingIcon = { Icon(Icons.Rounded.Folder, null) }
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useSdCard = !useSdCard }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = useSdCard, onCheckedChange = { useSdCard = it })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Create on SD Card", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Store album externally",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if (text.isNotBlank()) onCreate(text.trim(), useSdCard) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Create Album", fontWeight = FontWeight.Bold)
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
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Update the name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
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
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.error.copy(alpha=0.2f), MaterialTheme.colorScheme.error.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Move to Trash",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$count item(s) selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
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
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Move to Trash", fontWeight = FontWeight.Bold)
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
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = "Albums",
                maxLines = 1,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Create Album") },
                        onClick = { onMenuAction("create"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("View Duplicates") },
                        onClick = { onMenuAction("duplicates"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Scan Library") },
                        onClick = { onMenuAction("scan"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.ImageSearch, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Grid Size") },
                        onClick = { onMenuAction("grid"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Rounded.GridView, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort") },
                        onClick = { onMenuAction("sort"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Sort, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Trash") },
                        onClick = { onMenuAction("trash"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Hidden Albums") },
                        onClick = { onMenuAction("hidden"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.VisibilityOff, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isAppLockEnabled) "Disable App Lock" else "Enable App Lock") },
                        onClick = { onMenuAction("toggle_lock"); showMenu = false },
                        leadingIcon = { Icon(imageVector = if (isAppLockEnabled) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null) }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ds = remember(item) {
        SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault()).format(Date(item.dateAdded * 1000))
    }
    val sz = remember(item) { Formatter.formatFileSize(context, item.size) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp, height = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=0.2f), MaterialTheme.colorScheme.primary.copy(alpha=0.05f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Media Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Information & metadata",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    MetadataRow(icon = Icons.Outlined.Title, label = "Name", value = item.name)
                    MetadataRow(icon = Icons.Outlined.Folder, label = "Path", value = item.path)
                    MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Date", value = ds)
                    MetadataRow(icon = Icons.Outlined.Storage, label = "Size", value = sz)
                    if (item.width > 0 && item.height > 0) {
                        MetadataRow(icon = Icons.Outlined.AspectRatio, label = "Resolution", value = "${item.width} × ${item.height}")
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
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int, mediaList: List<MediaItem>, mediaMap: Map<Long, MediaItem>, favoriteIds: List<Long>, sharedPlayer: Player,
    onPageChanged: (MediaItem) -> Unit, onClose: () -> Unit, onToggleFavorite: (Long) -> Unit, onEdit: (MediaItem) -> Unit, onPlayVideo: (String, List<String>) -> Unit, onDelete: (MediaItem) -> Unit, onMove: (MediaItem) -> Unit, onCopy: (MediaItem) -> Unit, onWallpaper: (MediaItem) -> Unit
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
            sharedPlayer.playWhenReady = false
            sharedPlayer.prepare()
        }
    }

    LaunchedEffect(st.currentPage, vid) {
        ctrl = true

        val current = mediaList.getOrNull(st.currentPage)
            ?: return@LaunchedEffect

        val resolvedCurrent = mediaMap[current.id] ?: current

        onPageChanged(resolvedCurrent)

        if (current.isVideo) {
            val videoIndex = vid.indexOfFirst { it.id == current.id }

            if (videoIndex >= 0 && videoIndex < sharedPlayer.mediaItemCount) {
                sharedPlayer.pause()
                sharedPlayer.seekTo(videoIndex, 0L)
                sharedPlayer.playWhenReady = false
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
                    videoItems = vid,
                    isCurrentPage = st.currentPage == p,
                    showControls = ctrl,
                    sharedPlayer = sharedPlayer,
                    onTap = { ctrl = !ctrl },
                    onPlay = { onPlayVideo(itm.uri.toString(), vid.map { it.uri.toString() }) }
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

        if (ctrl) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { performClose() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.size(48.dp))
                }
            }
        }

        if (ctrl && curr != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PremiumViewerAction(icon = Icons.Outlined.Edit, label = "Edit") { onEdit(curr) }
                        PremiumViewerAction(
                            icon = if (favoriteIds.contains(curr.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            label = if (favoriteIds.contains(curr.id)) "Unfavorite" else "Favorite",
                            tint = if (favoriteIds.contains(curr.id)) Color.Red else Color.White
                        ) {
                            onToggleFavorite(curr.id)
                        }
                        PremiumViewerAction(icon = Icons.Outlined.Share, label = "Share") {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = if (curr.isVideo) "video/*" else "image/*"
                                putExtra(Intent.EXTRA_STREAM, curr.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share Media"))
                        }
                        PremiumViewerAction(icon = Icons.Outlined.Delete, label = "Delete", tint = Color.Red) { onDelete(curr) }
                        PremiumViewerAction(icon = Icons.Default.MoreVert, label = "More") { more = true }
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
        ModalBottomSheet(onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("Details", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; meta = true }
                )
                ListItem(
                    headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; onMove(curr) }
                )
                ListItem(
                    headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) },
                    modifier = Modifier.clickable { more = false; onCopy(curr) }
                )
                if (curr.isVideo) {
                    ListItem(
                        headlineContent = { Text("Open In", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        modifier = Modifier.clickable {
                            more = false
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(curr.uri, "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) },
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
    videoItems: List<MediaItem>,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val ctx = LocalContext.current
    var m by rememberSaveable(item.id) { mutableStateOf(true) }

    LaunchedEffect(m) { sharedPlayer.volume = if (m) 0f else 1f }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            sharedPlayer.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            view.performClick()
                        }
                        false
                    }
                }
            },
            update = {
                if (it.player != sharedPlayer) it.player = sharedPlayer
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )
        if (showControls) {
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).clickable { sharedPlayer.pause(); onPlay() },
                    shape = RoundedCornerShape(50.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Play video", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = tint.copy(alpha = 0.95f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ZoomableImagePage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit, onZoomChanged: (Boolean) -> Unit, onControlsVisibilityChange: (Boolean) -> Unit) {
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