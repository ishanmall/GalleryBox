@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.picture

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem as Media3Item
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.gallerybox.data.MediaItem
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val PremiumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
private val PremiumEnter = scaleIn(PremiumSpring) + fadeIn(tween(180, easing = FastOutSlowInEasing))
private val PremiumExit = scaleOut(tween(120)) + fadeOut(tween(120))

enum class UiMediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

enum class DeviceTier { LOW, MID, HIGH }

fun isValidUri(context: Context, uri: Uri?): Boolean {
    return uri != null && uri != Uri.EMPTY
}

fun getSmartName(item: MediaItem): String {
    return item.name.lowercase().let {
        when {
            "fdownloader" in it -> "Downloaded Video"
            "instagram" in it -> "Instagram Video"
            "whatsapp" in it -> "WhatsApp Media"
            "screenshot" in it -> "Screenshot"
            item.isVideo -> "Video"
            else -> "Photo"
        }
    }
}

fun getFolderName(path: String): String {
    return try {
        java.io.File(path).parentFile?.name ?: "Unknown Folder"
    } catch(e: Exception) {
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

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private val metadataFormatter by lazy { SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault()) }
private val shortDateFormatter by lazy { SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()) }

sealed class GalleryGridItem {
    data class Header(val id: String, val title: String, val count: Int) : GalleryGridItem()
    data class Media(val item: MediaItem) : GalleryGridItem()
}

sealed class PictureUiDialog {
    data object None : PictureUiDialog()
    data object GridSize : PictureUiDialog()
    data object Sort : PictureUiDialog()
    data class TrashConfirm(val mediaItems: List<MediaItem>) : PictureUiDialog()
    data class MetadataInfo(val item: MediaItem) : PictureUiDialog()
    data class QuickAction(val item: MediaItem) : PictureUiDialog()
}

@Composable
fun SamsungFastScrollbar(
    gridState: LazyGridState,
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    indexOffset: Int = 0,
    deviceTier: DeviceTier = DeviceTier.HIGH,
    modifier: Modifier = Modifier
) {
    if (pagedMedia.itemCount < 20) return

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
        snapshotFlow { gridState.firstVisibleItemIndex }
            .filter { !isDragging && !gridState.isScrollInProgress && trackHeightPx > 0f }
            .collect { index ->
                val count = pagedMedia.itemCount.coerceAtLeast(1)
                val adjusted = (index - indexOffset).coerceIn(0, count - 1)

                val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                val fraction = if (count > 1) adjusted.toFloat() / (count - 1).toFloat() else 0f

                thumbOffsetPx = (fraction * maxThumbOffset).coerceIn(0f, maxThumbOffset)
            }
    }

    var lastDragUpdateMs by remember { mutableLongStateOf(0L) }

    fun labelForIndex(index: Int): String {
        val safeIndex = index.coerceIn(0, (pagedMedia.itemCount - 1).coerceAtLeast(0))
        for (i in safeIndex downTo maxOf(0, safeIndex - 40)) {
            val item = runCatching { pagedMedia.peek(i) }.getOrNull()
            if (item is GalleryGridItem.Header) {
                return item.title
            }
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
        enter = fadeIn(tween(100)),
        exit = fadeOut(tween(180)),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureScreen(
    initialUri: String? = null,
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    onNavigateToCamera: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToHidden: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    onNavigateToWallpaper: (String, Long) -> Unit,
    onNavigateToSlideshow: () -> Unit,
    onNavigateToScan: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToVideoPlayer: (String, List<String>) -> Unit,
    onNavigateToEditor: (String, Long) -> Unit,
    onNavigateToMoveCopy: (String, String, String?) -> Unit,
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val deviceTier = remember { getDeviceTier(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    val filters = remember { listOf(UiMediaFilter.ALL, UiMediaFilter.PHOTOS, UiMediaFilter.VIDEOS) }
    var activeFilter by rememberSaveable { mutableStateOf(UiMediaFilter.ALL) }

    val isBusy by viewModel.isBusy.collectAsState()
    val activeSort by viewModel.activeSort.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val mediaMap by viewModel.mediaMap.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val openViewerState = viewerState as? GalleryViewerState.Open
    val currentItem = openViewerState?.mediaId?.let { mediaMap[it] }
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) ?: context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    var columnCount by rememberSaveable { mutableIntStateOf(prefs.getInt("picture_grid_columns", 4)) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<PictureUiDialog>(PictureUiDialog.None) }

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var hasOpenedInitial by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(localSearchQuery) {
        delay(250)
        debouncedSearchQuery = localSearchQuery
        viewModel.setSearchQuery(debouncedSearchQuery)
    }

    LaunchedEffect(initialUri, mediaMap) {
        if (!initialUri.isNullOrEmpty() && !hasOpenedInitial && mediaMap.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val targetUriStr = initialUri.trim()
                val targetItem = mediaMap.values.find {
                    it.uri.toString() == targetUriStr || it.path == targetUriStr
                }
                if (targetItem != null) {
                    withContext(Dispatchers.Main) {
                        viewModel.openViewer(targetItem.id)
                        hasOpenedInitial = true
                    }
                }
            }
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartIntentSenderForResult()) { result ->
        trashViewModel.onPermissionResultGlobal(result.resultCode == Activity.RESULT_OK)
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.onRefreshGallery = { viewModel.refreshAfterFileOperation() }
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> {
                    activeDialog = PictureUiDialog.None
                    isSelectionMode = false
                    selectedIds = emptySet()
                    viewModel.closeViewer()
                    if (snackbarHostState.showSnackbar("Moved to Trash", "View Trash", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        onNavigateToTrash()
                    }
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    LaunchedEffect(viewerState) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open)
    }

    LaunchedEffect(activeFilter) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        localSearchQuery = ""
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    BackHandler(enabled = activeDialog != PictureUiDialog.None) {
        activeDialog = PictureUiDialog.None
    }

    BackHandler(enabled = viewerState is GalleryViewerState.Open) {
        viewModel.closeViewer()
    }

    fun getSelectedItems(): List<MediaItem> {
        return pagedMedia.itemSnapshotList.items
            .filterIsInstance<GalleryGridItem.Media>()
            .filter { selectedIds.contains(it.item.id) }
            .map { mediaMap[it.item.id] ?: it.item }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (isSelectionMode) {
                    var totalSelectableCount by remember { mutableIntStateOf(0) }

                    LaunchedEffect(pagedMedia.itemSnapshotList) {
                        withContext(Dispatchers.Default) {
                            totalSelectableCount = pagedMedia.itemSnapshotList.items.count { it is GalleryGridItem.Media }
                        }
                    }

                    val isAllSelected = selectedIds.size == totalSelectableCount && totalSelectableCount > 0

                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "${selectedIds.size} selected",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        isSelectionMode = false
                                        selectedIds = emptySet()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Close Selection"
                                    )
                                }
                            },
                            actions = {
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.Default) {
                                            if (isAllSelected) {
                                                withContext(Dispatchers.Main) { selectedIds = emptySet() }
                                            } else {
                                                val ids = pagedMedia.itemSnapshotList.items
                                                    .filterIsInstance<GalleryGridItem.Media>()
                                                    .map { it.item.id }
                                                    .take(5000)
                                                    .toSet()
                                                withContext(Dispatchers.Main) { selectedIds = ids }
                                            }
                                        }
                                    }
                                ) {
                                    if (isAllSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = "Select All",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )
                    }
                } else if (isSearchActive) {
                    SearchTopBar(
                        query = localSearchQuery,
                        onQueryChange = { localSearchQuery = it },
                        onClose = {
                            isSearchActive = false
                            localSearchQuery = ""
                        }
                    )
                } else {
                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        ModernTopBar(
                            title = "Photos",
                            scrollBehavior = scrollBehavior,
                            onSearchClick = { isSearchActive = true },
                            onMenuAction = { action ->
                                when (action) {
                                    "select_all" -> {
                                        scope.launch(Dispatchers.Default) {
                                            val ids = pagedMedia.itemSnapshotList.items
                                                .filterIsInstance<GalleryGridItem.Media>()
                                                .map { it.item.id }
                                                .take(5000)
                                                .toSet()
                                            withContext(Dispatchers.Main) {
                                                selectedIds = ids
                                                isSelectionMode = true
                                            }
                                        }
                                    }
                                    "camera" -> onNavigateToCamera()
                                    "grid" -> activeDialog = PictureUiDialog.GridSize
                                    "sort" -> activeDialog = PictureUiDialog.Sort
                                    "slideshow" -> onNavigateToSlideshow()
                                    "trash" -> onNavigateToTrash()
                                    "about" -> onNavigateToAbout()
                                }
                            }
                        )
                    }
                }
            },
            floatingActionButton = {
                val showScrollToTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 10 } }
                if (!isSelectionMode && showScrollToTop) {
                    AnimatedVisibility(
                        visible = true,
                        enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else PremiumEnter,
                        exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else PremiumExit
                    ) {
                        FloatingActionButton(
                            onClick = { scope.launch { gridState.scrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(imageVector = Icons.Rounded.ArrowUpward, contentDescription = "Scroll to Top")
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                if (isBusy && pagedMedia.itemCount == 0) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (pagedMedia.itemCount == 0) {
                    EmptyMediaOverlay(onCameraClick = onNavigateToCamera, onScanClick = onNavigateToScan)
                } else {
                    AnimatedContent(
                        targetState = activeFilter,
                        transitionSpec = {
                            if (deviceTier == DeviceTier.LOW) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            else PremiumEnter togetherWith PremiumExit
                        },
                        label = "filter_transition"
                    ) { targetFilter: UiMediaFilter ->
                        GalleryGridContent(
                            pagedMedia = pagedMedia,
                            gridState = gridState,
                            columnCount = columnCount,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds,
                            mediaMap = mediaMap,
                            deviceTier = deviceTier,
                            onSelectionChange = { selectedIds = it },
                            onSelectionModeChange = { isSelectionMode = it },
                            onItemClick = { item ->
                                if (isSelectionMode) {
                                    val newSet = selectedIds.toMutableSet()
                                    if (!newSet.remove(item.id)) {
                                        if (newSet.size < 5000) newSet.add(item.id)
                                    }
                                    selectedIds = newSet.toSet()
                                } else {
                                    viewModel.openViewer(item.id)
                                }
                            },
                            onItemLongClick = { item ->
                                if (!isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isSelectionMode = true
                                    selectedIds = setOf(item.id)
                                }
                            },
                            header = {
                                if (!isSelectionMode && !isSearchActive) {
                                    ModernFilterRow(
                                        filters = filters,
                                        activeFilter = targetFilter,
                                        onFilterSelected = { filter ->
                                            activeFilter = filter
                                            viewModel.updateFilter(
                                                when (filter) {
                                                    UiMediaFilter.PHOTOS -> MediaTypeFilter.PHOTOS
                                                    UiMediaFilter.VIDEOS -> MediaTypeFilter.VIDEOS
                                                    else -> MediaTypeFilter.ALL
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }

                    SamsungFastScrollbar(
                        gridState = gridState,
                        pagedMedia = pagedMedia,
                        indexOffset = 0,
                        deviceTier = deviceTier,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }

        if (activeDialog != PictureUiDialog.None) {
            DialogsHost(
                dialog = activeDialog,
                mediaMap = mediaMap,
                trashViewModel = trashViewModel,
                viewModel = viewModel,
                activeSort = activeSort,
                columnCount = columnCount,
                prefs = prefs,
                onDismiss = { activeDialog = PictureUiDialog.None },
                onNavigateToEditor = onNavigateToEditor,
                onNavigateToMoveCopy = onNavigateToMoveCopy,
                onNavigateToWallpaper = onNavigateToWallpaper,
                onUpdateColumns = { cols ->
                    columnCount = cols
                    prefs.edit().putInt("picture_grid_columns", cols).apply()
                    activeDialog = PictureUiDialog.None
                }
            )
        }

        if (viewerState is GalleryViewerState.Open && currentItem != null) {
            var stableMediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
            var stableStartIndex by remember { mutableIntStateOf(0) }

            LaunchedEffect(pagedMedia.itemSnapshotList, mediaMap, currentItem.id) {
                withContext(Dispatchers.Default) {
                    val items = pagedMedia.itemSnapshotList.items
                        .filterIsInstance<GalleryGridItem.Media>()
                        .map { mediaMap[it.item.id] ?: it.item }
                    val index = items.indexOfFirst { it.id == currentItem.id }.coerceAtLeast(0)
                    withContext(Dispatchers.Main) {
                        stableMediaList = items
                        stableStartIndex = index
                    }
                }
            }

            if (stableMediaList.isNotEmpty()) {
                FullscreenMediaPager(
                    initialIndex = stableStartIndex,
                    mediaList = stableMediaList,
                    mediaMap = mediaMap,
                    favoriteIds = favoriteIds,
                    sharedPlayer = viewModel.getPlayer(),
                    onClose = { viewModel.closeViewer() },
                    onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                    onEdit = { item ->
                        viewModel.closeViewer()
                        onNavigateToEditor(item.uri.toString(), item.id)
                    },
                    onDelete = { item -> activeDialog = PictureUiDialog.TrashConfirm(listOf(item)) },
                    onNavigateToVideoPlayer = { uri ->
                        viewModel.closeViewer()
                        onNavigateToVideoPlayer(uri, stableMediaList.filter { it.isVideo }.map { it.uri.toString() })
                    },
                    onMove = { item ->
                        viewModel.closeViewer()
                        onNavigateToMoveCopy("MOVE", item.id.toString(), null)
                    },
                    onCopy = { item ->
                        viewModel.closeViewer()
                        onNavigateToMoveCopy("COPY", item.id.toString(), null)
                    },
                    onWallpaper = { item ->
                        viewModel.closeViewer()
                        onNavigateToWallpaper(item.uri.toString(), item.id)
                    }
                )
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
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
                            onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), null)
                            isSelectionMode = false
                        }
                        BottomBarActionItem(icon = Icons.Outlined.FileCopy, label = "Copy") {
                            onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), null)
                            isSelectionMode = false
                        }
                        BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") {
                            scope.launch(Dispatchers.Default) {
                                val itemsToShare = getSelectedItems()
                                if (itemsToShare.isNotEmpty()) {
                                    val intent = Intent(if (itemsToShare.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                                        val hasImg = itemsToShare.any { !it.isVideo }
                                        val hasVid = itemsToShare.any { it.isVideo }
                                        type = if (hasVid && !hasImg) "video/*" else if (hasImg && !hasVid) "image/*" else "*/*"
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        if (itemsToShare.size > 1) {
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(itemsToShare.map { it.uri }))
                                        } else {
                                            putExtra(Intent.EXTRA_STREAM, itemsToShare.first().uri)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share via"))
                                        isSelectionMode = false
                                        selectedIds = emptySet()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        isSelectionMode = false
                                        selectedIds = emptySet()
                                    }
                                }
                            }
                        }
                        BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Trash", isDestructive = true) {
                            scope.launch(Dispatchers.Default) {
                                val itemsToTrash = getSelectedItems()
                                withContext(Dispatchers.Main) {
                                    activeDialog = PictureUiDialog.TrashConfirm(itemsToTrash)
                                }
                            }
                        }
                        Box {
                            var showMoreMenu by remember { mutableStateOf(false) }
                            BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") {
                                showMoreMenu = true
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                if (selectedIds.size == 1) {
                                    DropdownMenuItem(
                                        text = { Text("Details") },
                                        onClick = {
                                            showMoreMenu = false
                                            scope.launch(Dispatchers.Default) {
                                                val items = getSelectedItems()
                                                if (items.isNotEmpty()) {
                                                    withContext(Dispatchers.Main) {
                                                        activeDialog = PictureUiDialog.MetadataInfo(items.first())
                                                    }
                                                }
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Info, null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Deselect All") },
                                    onClick = {
                                        showMoreMenu = false
                                        isSelectionMode = false
                                        selectedIds = emptySet()
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Deselect, null) }
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
fun BottomBarActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
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
fun EmptyMediaOverlay(onCameraClick: () -> Unit = {}, onScanClick: () -> Unit = {}) {
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
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "No Photos Yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Capture moments or scan your device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCameraClick,
                    modifier = Modifier
                        .height(58.dp)
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open Camera", fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = onScanClick,
                    modifier = Modifier
                        .height(58.dp)
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Device", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSortSheet(activeSort: PhotoSort, onDismiss: () -> Unit, onSortSelected: (PhotoSort) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 48.dp,
                height = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
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
fun ModernTopBar(title: String, scrollBehavior: TopAppBarScrollBehavior, onSearchClick: () -> Unit, onMenuAction: (String) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
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
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Select All", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("select_all"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.SelectAll, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Launch Camera", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("camera"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Start Slideshow", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("slideshow"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Slideshow, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Grid Size", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("grid"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Rounded.GridView, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("sort"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Filled.Sort, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Trash", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("trash"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("About", color = MaterialTheme.colorScheme.onSurface) },
                        onClick = { onMenuAction("about"); showMenu = false },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    )
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

@Composable
fun ModernFilterRow(filters: List<UiMediaFilter>, activeFilter: UiMediaFilter, onFilterSelected: (UiMediaFilter) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items = filters, key = { _, filter -> filter.name }) { _, filter ->
            val isSelected = activeFilter == filter
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onFilterSelected(filter) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    text = filter.label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaMetadataSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateStr = remember(item) { metadataFormatter.format(Date(item.dateAdded * 1000)) }
    val formattedSize = remember(item) { Formatter.formatFileSize(context, item.size) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 48.dp,
                height = 4.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
                    MetadataRow(icon = Icons.Outlined.CalendarToday, label = "Date", value = dateStr)
                    MetadataRow(icon = Icons.Outlined.Storage, label = "Size", value = formattedSize)
                    if (item.width > 0 && item.height > 0) {
                        MetadataRow(icon = Icons.Outlined.AspectRatio, label = "Resolution", value = "${item.width} × ${item.height}")
                    }
                    if (item.isVideo && item.duration > 0L) {
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

@Composable
fun ActionItem(icon: ImageVector, label: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ElevatedCard(
        modifier = Modifier
            .width(86.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(3.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogsHost(
    dialog: PictureUiDialog,
    mediaMap: Map<Long, MediaItem>,
    trashViewModel: TrashViewModel,
    viewModel: GalleryViewModel,
    activeSort: PhotoSort,
    columnCount: Int,
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onNavigateToEditor: (String, Long) -> Unit,
    onNavigateToMoveCopy: (String, String, String?) -> Unit,
    onNavigateToWallpaper: (String, Long) -> Unit,
    onUpdateColumns: (Int) -> Unit
) {
    val context = LocalContext.current
    when (dialog) {
        is PictureUiDialog.TrashConfirm -> {
            ModernMoveToTrashSheet(
                count = dialog.mediaItems.size,
                onDismiss = onDismiss,
                onConfirm = {
                    if (dialog.mediaItems.isNotEmpty()) {
                        trashViewModel.confirmPendingGalleryTrash(dialog.mediaItems)
                    }
                    onDismiss()
                }
            )
        }
        is PictureUiDialog.QuickAction -> {
            val item = dialog.item
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var showMoreExpanded by remember { mutableStateOf(false) }

            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = {
                    BottomSheetDefaults.DragHandle(
                        width = 48.dp,
                        height = 4.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = getSmartName(item),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = getFolderName(item.path),
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        item {
                            ActionItem(icon = Icons.Outlined.Edit, label = "Edit") {
                                onDismiss()
                                onNavigateToEditor(item.uri.toString(), item.id)
                            }
                        }
                        item {
                            ActionItem(icon = Icons.Outlined.Share, label = "Share") {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = if (item.isVideo) "video/*" else "image/*"
                                    putExtra(Intent.EXTRA_STREAM, item.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share via"))
                                onDismiss()
                            }
                        }
                        item {
                            ActionItem(icon = Icons.Outlined.Delete, label = "Delete", isDestructive = true) {
                                onDismiss()
                                trashViewModel.confirmPendingGalleryTrash(listOf(item))
                            }
                        }
                        item {
                            ActionItem(icon = Icons.Default.MoreVert, label = "More") {
                                showMoreExpanded = true
                            }
                        }
                    }
                    if (showMoreExpanded) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            ListItem(
                                headlineContent = { Text("Move to Album", fontWeight = FontWeight.SemiBold) },
                                leadingContent = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onNavigateToMoveCopy("MOVE", item.id.toString(), null)
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Copy to Album", fontWeight = FontWeight.SemiBold) },
                                leadingContent = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onNavigateToMoveCopy("COPY", item.id.toString(), null)
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Set as Wallpaper", fontWeight = FontWeight.SemiBold) },
                                leadingContent = { Icon(imageVector = Icons.Outlined.Wallpaper, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onNavigateToWallpaper(item.uri.toString(), item.id)
                                }
                            )
                        }
                    }
                }
            }
        }
        is PictureUiDialog.GridSize -> {
            ModernGridSheet(
                currentColumns = columnCount,
                max = 8,
                onDismiss = onDismiss,
                onUpdate = onUpdateColumns
            )
        }
        is PictureUiDialog.Sort -> {
            ModernSortSheet(
                activeSort = activeSort,
                onDismiss = onDismiss,
                onSortSelected = {
                    viewModel.updateSort(it)
                    onDismiss()
                }
            )
        }
        is PictureUiDialog.MetadataInfo -> {
            MediaMetadataSheet(
                item = dialog.item,
                onDismiss = onDismiss
            )
        }
        PictureUiDialog.None -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryGridContent(
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    gridState: LazyGridState,
    columnCount: Int,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    mediaMap: Map<Long, MediaItem>,
    deviceTier: DeviceTier,
    onSelectionChange: (Set<Long>) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    header: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val scope = rememberCoroutineScope()

    var isScrollingFast by remember { mutableStateOf(false) }

    LaunchedEffect(gridState) {
        var lastTime = System.currentTimeMillis()
        var lastIndex = gridState.firstVisibleItemIndex

        snapshotFlow { gridState.firstVisibleItemIndex to gridState.isScrollInProgress }
            .collect { (index, isScrolling) ->
                if (!isScrolling) {
                    delay(180)
                    isScrollingFast = false
                } else {
                    val now = System.currentTimeMillis()
                    val dt = now - lastTime
                    if (dt > 50) {
                        val velocity = (kotlin.math.abs(index - lastIndex) * 1000f) / dt
                        if (velocity > 15f) {
                            isScrollingFast = true
                        } else if (velocity < 5f) {
                            isScrollingFast = false
                        }
                        lastTime = now
                        lastIndex = index
                    }
                }
            }
    }

    val dynamicThumbSize = remember(columnCount, screenWidthPx, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260 else 480
        val raw = (screenWidthPx / columnCount).coerceIn(160, maxSize)
        (raw / 40) * 40
    }

    val gridCells = remember(columnCount) { GridCells.Fixed(columnCount) }

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastPointerPosition by remember { mutableStateOf<Offset?>(null) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragLastIndex by remember { mutableIntStateOf(-1) }
    var dragBaseSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dragIsAdditive by remember { mutableStateOf(true) }

    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)

    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val placeholderPainter = remember(placeholderColor) { ColorPainter(placeholderColor) }

    fun indexAt(offset: Offset): Int {
        val layoutInfo = gridState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find {
            offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                    offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
        }
        return itemInfo?.index ?: -1
    }

    fun mediaIdAt(index: Int): Long? = (pagedMedia.peek(index) as? GalleryGridItem.Media)?.item?.id

    fun applyRangeSelection(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex < 0) return
        val lo = minOf(fromIndex, toIndex)
        val hi = maxOf(fromIndex, toIndex)
        val updated = dragBaseSelection.toMutableSet()
        for (i in lo..hi) {
            val id = mediaIdAt(i) ?: continue
            if (dragIsAdditive) {
                if (updated.size >= 5000) break
                updated.add(id)
            } else {
                updated.remove(id)
            }
        }
        currentOnSelectionChange(updated.toSet())
    }

    fun beginDrag(offset: Offset) {
        val idx = indexAt(offset)
        val id = mediaIdAt(idx) ?: return
        dragAnchorIndex = idx
        dragLastIndex = idx
        dragBaseSelection = currentSelectedIds
        dragIsAdditive = !currentSelectedIds.contains(id)
        lastPointerPosition = offset
        if (!currentIsSelectionMode) {
            currentOnSelectionModeChange(true)
        }
        applyRangeSelection(dragAnchorIndex, dragLastIndex)
    }

    fun updateDrag(position: Offset, boxHeightPx: Int, densityScale: Float) {
        lastPointerPosition = position
        val idx = indexAt(position)
        if (idx >= 0 && idx != dragLastIndex) {
            applyRangeSelection(dragAnchorIndex, idx)
            dragLastIndex = idx
        }

        val edge10 = 10 * densityScale
        val edge40 = 40 * densityScale
        val edge80 = 80 * densityScale
        val y = position.y

        autoScrollSpeed = when {
            y < edge10 -> -70f
            y < edge40 -> -25f
            y < edge80 -> -8f
            y > boxHeightPx - edge10 -> 70f
            y > boxHeightPx - edge40 -> 25f
            y > boxHeightPx - edge80 -> 8f
            else -> 0f
        }
    }

    fun endDrag() {
        autoScrollSpeed = 0f
        lastPointerPosition = null
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (true) {
                withFrameNanos { _ -> }
                gridState.scrollBy(autoScrollSpeed)
                lastPointerPosition?.let { pos ->
                    val idx = indexAt(pos)
                    if (idx >= 0 && idx != dragLastIndex) {
                        applyRangeSelection(dragAnchorIndex, idx)
                        dragLastIndex = idx
                    }
                }
            }
        }
    }

    val dragModifier = if (isSelectionMode) {
        Modifier.pointerInput(Unit) {
            var lastDragUpdateMs = 0L
            detectDragGestures(
                onDragStart = { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    beginDrag(offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastDragUpdateMs > 16) {
                        updateDrag(change.position, size.height, density)
                        lastDragUpdateMs = now
                    }
                },
                onDragEnd = { endDrag() },
                onDragCancel = { endDrag() }
            )
        }
    } else {
        Modifier.pointerInput(Unit) {
            var lastDragUpdateMs = 0L
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    beginDrag(offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val now = System.currentTimeMillis()
                    if (now - lastDragUpdateMs > 16) {
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
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (isSelectionMode) 100.dp else 16.dp
        LazyVerticalGrid(
            state = gridState,
            columns = gridCells,
            modifier = Modifier.fillMaxSize().then(dragModifier),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = bottomPadding,
                start = 4.dp,
                end = 4.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                header()
            }

            items(
                count = pagedMedia.itemCount,
                span = { index ->
                    if (pagedMedia.peek(index) is GalleryGridItem.Header) {
                        GridItemSpan(columnCount)
                    } else {
                        GridItemSpan(1)
                    }
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
                when (val gridItem = pagedMedia[index]) {
                    is GalleryGridItem.Header -> {
                        ModernDateHeader(
                            title = gridItem.title,
                            onSelectAllForDate = {
                                scope.launch(Dispatchers.Default) {
                                    val snapshot = pagedMedia.itemSnapshotList.items.filterIsInstance<GalleryGridItem.Media>().filter { it.item.dateHeader == gridItem.title }
                                    val newIds = (selectedIds + snapshot.map { it.item.id }.toSet()).takeIf { s -> s.size < 5000 } ?: selectedIds
                                    withContext(Dispatchers.Main) {
                                        onSelectionChange(newIds)
                                        onSelectionModeChange(true)
                                    }
                                }
                            }
                        )
                    }
                    is GalleryGridItem.Media -> {
                        val mediaId = gridItem.item.id
                        val mediaItem = mediaMap[mediaId] ?: gridItem.item
                        val baseModifier = Modifier

                        ModernMediaGridTile(
                            modifier = baseModifier,
                            item = mediaItem,
                            thumbSize = dynamicThumbSize,
                            isSelected = selectedIds.contains(mediaId),
                            isSelectionMode = isSelectionMode,
                            deviceTier = deviceTier,
                            placeholderPainter = placeholderPainter,
                            onClick = { onItemClick(mediaItem) },
                            onLongClick = { onItemLongClick(mediaItem) }
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
    placeholderPainter: ColorPainter,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = {
                    if (!isSelectionMode) {
                        onLongClick()
                    }
                }
            )
    ) {
        val effectiveSize = remember(thumbSize) { thumbSize.coerceIn(160, 480) }

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

        AsyncImage(
            model = request,
            placeholder = placeholderPainter,
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
                        val brush = Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.75f)
                        )
                        onDrawBehind { drawRect(brush) }
                    }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
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
fun SelectionOverlay(
    isSelected: Boolean,
    isSelectionMode: Boolean,
    cornerRadius: Dp,
    deviceTier: DeviceTier
) {
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
            val enterAnim = if (deviceTier == DeviceTier.LOW) fadeIn(tween(100)) else PremiumEnter
            val exitAnim = if (deviceTier == DeviceTier.LOW) fadeOut(tween(100)) else PremiumExit
            AnimatedVisibility(
                visible = isSelected,
                enter = enterAnim,
                exit = exitAnim,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .let { if (deviceTier == DeviceTier.LOW) it else it.shadow(4.dp, CircleShape) }
                        .background(Color.White, CircleShape)
                )
            }
            if (!isSelected) {
                Icon(
                    imageVector = Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .let { if (deviceTier == DeviceTier.LOW) it else it.shadow(2.dp, CircleShape) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int,
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    favoriteIds: List<Long>,
    sharedPlayer: Player,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onEdit: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onNavigateToVideoPlayer: (String) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current
    val view = LocalView.current
    val safeInitialPage = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { mediaList.size }
    )
    var showControls by remember { mutableStateOf(true) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Quick filter, safe for this isolated list
    val videoList = remember(mediaList) { mediaList.filter { it.isVideo } }

    LaunchedEffect(videoList) {
        if (videoList.isNotEmpty()) {
            sharedPlayer.setMediaItems(videoList.map { Media3Item.fromUri(it.uri) })
            sharedPlayer.playWhenReady = false
            sharedPlayer.prepare()

            val initialItem = mediaList.getOrNull(safeInitialPage)
            if (initialItem != null && initialItem.isVideo) {
                val idx = videoList.indexOfFirst { it.id == initialItem.id }
                if (idx >= 0) sharedPlayer.seekTo(idx, 0)
            }
        }
    }

    LaunchedEffect(initialIndex, mediaList.size) {
        if (pagerState.currentPage != initialIndex && initialIndex in mediaList.indices) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        showControls = true
        val current = mediaList.getOrNull(pagerState.currentPage)
        if (current != null && !current.isVideo) {
            sharedPlayer.pause()
            if (sharedPlayer.mediaItemCount > 0) {
                sharedPlayer.seekTo(sharedPlayer.currentMediaItemIndex, 0)
            }
            sharedPlayer.playWhenReady = false
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            sharedPlayer.pause()
            sharedPlayer.playWhenReady = false
            window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    BackHandler(enabled = !showControls) {
        showControls = true
    }

    BackHandler(enabled = showControls) {
        onClose()
    }

    val liveCurrentItem = mediaList.getOrNull(pagerState.currentPage)?.let { mediaMap[it.id] ?: it }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 18.dp,
            key = { index -> mediaList[index].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = mediaList[page]
            if (item.isVideo) {
                VideoPreviewPage(
                    item = item,
                    videoIndex = videoList.indexOfFirst { it.id == item.id },
                    isCurrentPage = pagerState.currentPage == page,
                    showControls = showControls,
                    sharedPlayer = sharedPlayer,
                    onTap = { showControls = !showControls },
                    onPlay = { onNavigateToVideoPlayer(item.uri.toString()) }
                )
            } else {
                ZoomableImagePage(
                    item = item,
                    onTap = { showControls = !showControls },
                    onDismiss = onClose
                )
            }
        }

        if (showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f))
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = liveCurrentItem?.let { shortDateFormatter.format(Date(it.dateAdded * 1000)) } ?: "",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
            liveCurrentItem?.let { currentItem ->
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
                            PremiumViewerAction(
                                icon = if (favoriteIds.contains(currentItem.id)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                label = if (favoriteIds.contains(currentItem.id)) "Unfavorite" else "Favorite",
                                tint = if (favoriteIds.contains(currentItem.id)) Color.Red else Color.White
                            ) {
                                onToggleFavorite(currentItem.id)
                            }
                            PremiumViewerAction(
                                icon = Icons.Outlined.Edit,
                                label = "Edit"
                            ) {
                                onEdit(currentItem)
                            }
                            PremiumViewerAction(
                                icon = Icons.Outlined.Share,
                                label = "Share"
                            ) {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = if (currentItem.isVideo) "video/*" else "image/*"
                                    putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share Media"))
                            }
                            PremiumViewerAction(
                                icon = Icons.Outlined.Delete,
                                label = "Delete",
                                tint = Color.Red
                            ) {
                                onDelete(currentItem)
                            }
                            Box {
                                PremiumViewerAction(
                                    icon = Icons.Default.MoreVert,
                                    label = "More"
                                ) {
                                    showMoreMenu = true
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Details", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showMetadataSheet = true
                                            showMoreMenu = false
                                        },
                                        leadingIcon = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move to Album", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showMoreMenu = false
                                            onMove(currentItem)
                                        },
                                        leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy to Album", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showMoreMenu = false
                                            onCopy(currentItem)
                                        },
                                        leadingIcon = { Icon(imageVector = Icons.Outlined.FileCopy, contentDescription = null) }
                                    )
                                    if (currentItem.isVideo) {
                                        DropdownMenuItem(
                                            text = { Text("Open In", color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = {
                                                showMoreMenu = false
                                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(currentItem.uri, "video/*")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                })
                                            },
                                            leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Set as Wallpaper", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showMoreMenu = false
                                            onWallpaper(currentItem)
                                        },
                                        leadingIcon = { Icon(imageVector = Icons.Outlined.Wallpaper, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showMetadataSheet && liveCurrentItem != null) {
        MediaMetadataSheet(item = liveCurrentItem) { showMetadataSheet = false }
    }
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewPage(
    item: MediaItem,
    videoIndex: Int,
    isCurrentPage: Boolean,
    showControls: Boolean,
    sharedPlayer: Player,
    onTap: () -> Unit,
    onPlay: () -> Unit
) {
    val ctx = LocalContext.current
    var m by rememberSaveable(item.id) { mutableStateOf(true) }

    LaunchedEffect(m) {
        sharedPlayer.volume = if (m) 0f else 1f
    }

    LaunchedEffect(isCurrentPage, videoIndex) {
        if (isCurrentPage) {
            if (videoIndex >= 0 && sharedPlayer.currentMediaItemIndex != videoIndex) {
                sharedPlayer.seekTo(videoIndex, 0)
            }
        } else {
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
                if (it.player != sharedPlayer) {
                    it.player = sharedPlayer
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
        )
        if (showControls) {
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).clickable {
                        sharedPlayer.pause()
                        onPlay()
                    },
                    shape = RoundedCornerShape(50.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Play video",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumViewerAction(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
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
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
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
fun ZoomableImagePage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit) {
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