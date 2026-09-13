// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.picture

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for drawing grids, touching the screen, playing video, formatting dates, and managing files.
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

// ---------------------------------------------------------------------------
// 🖼️ APP DATA & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.data.MediaItem
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.GalleryViewerState
import com.gallerybox.viewmodel.MediaTypeFilter
import com.gallerybox.viewmodel.PhotoSort
import com.gallerybox.viewmodel.TrashViewModel

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * =========================================================================================
 * 🪄 SMOOTH ANIMATIONS
 * =========================================================================================
 * These define how dialogs and screens bounce and fade into view.
 * A "Spring" animation makes menus pop up playfully instead of just rigidly appearing.
 */
private val PremiumSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
private val PremiumEnter = scaleIn(PremiumSpring) + fadeIn(tween(180, easing = FastOutSlowInEasing))
private val PremiumExit = scaleOut(tween(120)) + fadeOut(tween(120))

/**
 * The 3 main tabs at the top of the photo grid that let you filter what you see.
 */
enum class UiMediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos")
}

/**
 * Helps us detect if the phone is cheap/slow or fast/expensive so we don't crash it.
 */
enum class DeviceTier { LOW, MID, HIGH }

fun isValidUri(context: Context, uri: Uri?): Boolean {
    return uri != null && uri != Uri.EMPTY
}

/**
 * Looks at a file's name and guesses what it is (e.g., if it says "whatsapp", it's WhatsApp Media).
 */
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

// Grabs just the folder name out of a giant file path (e.g., turns "/storage/emulated/0/DCIM/Camera" into "Camera")
fun getFolderName(path: String): String {
    return try {
        java.io.File(path).parentFile?.name ?: "Unknown Folder"
    } catch(e: Exception) {
        "Unknown Folder"
    }
}

/**
 * Checks how much RAM the phone has. If it's a slow phone, we turn off heavy animations.
 */
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

// Converts raw milliseconds into a readable clock (e.g., "1:05")
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

// Tools to turn raw timestamps into pretty text (e.g., "Sunday, August 12, 2026")
private val metadataFormatter by lazy { SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh a", Locale.getDefault()) }
private val shortDateFormatter by lazy { SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()) }

/**
 * Because our grid has both Text Dates (Headers) and Photos (Media), we need a way
 * to tell the app which one it is looking at when drawing the screen.
 */
sealed class GalleryGridItem {
    data class Header(val id: String, val title: String, val count: Int) : GalleryGridItem()
    data class Media(val item: MediaItem) : GalleryGridItem()
}

/**
 * A master list of every pop-up menu that can open on this screen.
 */
sealed class PictureUiDialog {
    data object None : PictureUiDialog() // No menu open
    data object GridSize : PictureUiDialog()
    data object Sort : PictureUiDialog()
    data class TrashConfirm(val mediaItems: List<MediaItem>) : PictureUiDialog()
    data class MetadataInfo(val item: MediaItem) : PictureUiDialog()
    data class QuickAction(val item: MediaItem) : PictureUiDialog()
}

/**
 * =========================================================================================
 * 🏎️ THE FAST SCROLLBAR (SamsungFastScrollbar)
 * =========================================================================================
 * This draws the little bubble on the right side of the screen that lets you grab it
 * and fly through 10,000 photos in seconds. It looks exactly like the one in Samsung's Gallery app.
 */
@Composable
fun SamsungFastScrollbar(
    gridState: LazyGridState, // Knows how far down the user has scrolled
    pagedMedia: LazyPagingItems<GalleryGridItem>, // The list of all photos
    indexOffset: Int = 0,
    deviceTier: DeviceTier = DeviceTier.HIGH,
    modifier: Modifier = Modifier
) {
    // Only show the scrollbar if we have enough items to actually need scrolling
    val canScroll by remember {
        derivedStateOf { gridState.layoutInfo.totalItemsCount > gridState.layoutInfo.visibleItemsInfo.size }
    }

    if (!canScroll || pagedMedia.itemCount < 20) return

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) } // Is the user's thumb currently on the bubble?
    var visible by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var thumbOffsetPx by remember { mutableFloatStateOf(0f) }
    var bubbleLabel by remember { mutableStateOf("") } // The text inside the bubble (e.g. "August 2026")

    val thumbHeightDp = 48.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    val scrollChannel = remember { Channel<Int>(Channel.CONFLATED) }

    // When the user drags the bubble, this instantly scrolls the giant grid of photos to match.
    LaunchedEffect(Unit) {
        for (targetIndex in scrollChannel) {
            runCatching {
                val maxLoaded = (gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                val safeTarget = targetIndex.coerceIn(0, maxLoaded)
                gridState.scrollToItem(index = safeTarget, scrollOffset = 0)
            }
        }
    }

    // Hide the scrollbar a second after the user stops scrolling
    LaunchedEffect(gridState.isScrollInProgress, isDragging) {
        if (gridState.isScrollInProgress || isDragging) {
            visible = true
        } else {
            delay(1000)
            visible = false
        }
    }

    // Move the little bubble up and down automatically if the user is scrolling normally with their finger
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

    // This looks at the photo next to the thumb bubble, scans up to find the nearest Date Header,
    // and copies its text (like "August 2026") to show inside the bubble.
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

    // Math to translate "The user dragged the bubble 50 pixels down" into "Scroll the grid down 300 photos"
    fun jumpTo(offsetY: Float) {
        if (trackHeightPx <= 0f) return

        val now = System.currentTimeMillis()
        if (now - lastDragUpdateMs < 33L) return // Don't stutter by calculating this 1000 times a second
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
        modifier = modifier.zIndex(20f) // Keep the scrollbar floating ON TOP of all the photos
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(52.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
                .pointerInput(Unit) {
                    // Detect the user grabbing the bubble
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            lastDragUpdateMs = 0L
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress) // Bzzz! Let them know they grabbed it.
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
            // The tiny visual track line on the edge of the screen
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

            // The floating bubble that shows the date ("August 2026")
            if (isDragging && bubbleLabel.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            // Push the text bubble to the left so the user's thumb doesn't block it!
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

            // The little pill you grab with your thumb
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
                    .width(if (isDragging) 12.dp else 8.dp) // Make it slightly fatter when they touch it
                    .height(thumbHeightDp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(7.dp)
                    )
            )
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ THE MAIN PICTURE SCREEN
 * =========================================================================================
 * This is the primary screen of the app. It holds the giant grid of all your photos
 * and videos, and handles searching, selecting, and opening the full screen viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureScreen(
    initialUri: String? = null, // If the user clicked a link in another app, this is the photo we should open instantly
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    // These are instructions passed down by the App's Navigator on where to go when buttons are clicked
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
    val deviceTier = remember { getDeviceTier(context) } // Is this phone slow or fast?

    // 🧠 1. Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState()) // Top bar hides when scrolling down

    val filters = remember { listOf(UiMediaFilter.ALL, UiMediaFilter.PHOTOS, UiMediaFilter.VIDEOS) }
    var activeFilter by rememberSaveable { mutableStateOf(UiMediaFilter.ALL) }

    // --- SCOREBOARDS ---
    val isBusy by viewModel.isBusy.collectAsState()
    val activeSort by viewModel.activeSort.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState() // Is the full screen photo viewer currently open?
    val mediaMap by viewModel.mediaMap.collectAsState() // Master dictionary of all photos
    val favoriteIds by viewModel.favoriteIds.collectAsState() // List of hearted photos

    val openViewerState = viewerState as? GalleryViewerState.Open
    val currentItem = openViewerState?.mediaId?.let { mediaMap[it] } // The exact photo currently being viewed full screen

    // The Waiter (brings photos from the database 50 at a time)
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) ?: context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    // 🎨 2. SMART ADAPTIVE GRID COLUMNS
    // If the user hasn't chosen a custom grid size, we use the Adaptive State to automatically
    // pick the best size. Standard phones get 4 squares per row. Tablets get 6 or 8!
    var columnCount by rememberSaveable {
        mutableIntStateOf(
            prefs.getInt("picture_grid_columns",
                when (adaptiveState.widthSize) {
                    WindowWidthSize.COMPACT -> 4
                    WindowWidthSize.MEDIUM -> 6
                    WindowWidthSize.EXPANDED -> 8
                }
            )
        )
    }

    var isSelectionMode by rememberSaveable { mutableStateOf(false) } // Is the user actively selecting photos?
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) } // A list of the photos they clicked
    var isSearchActive by rememberSaveable { mutableStateOf(false) } // Is the search bar open?
    var activeDialog by remember { mutableStateOf<PictureUiDialog>(PictureUiDialog.None) } // Which pop-up menu is currently open?

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var hasOpenedInitial by rememberSaveable { mutableStateOf(false) }

    // THE DEBOUNCER
    // When the user types "Dog" in the search bar, we wait a tiny fraction of a second (250ms) before
    // asking the database to search. If we didn't do this, it would search "D", then "Do", then "Dog", freezing the app.
    LaunchedEffect(localSearchQuery) {
        delay(250)
        debouncedSearchQuery = localSearchQuery
        viewModel.setSearchQuery(debouncedSearchQuery)
    }

    // Handles the special case where WhatsApp or Gmail told GalleryBox to open a specific photo.
    LaunchedEffect(initialUri, mediaMap) {
        if (!initialUri.isNullOrEmpty() && !hasOpenedInitial && mediaMap.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val targetUriStr = initialUri.trim()
                val targetItem = mediaMap.values.find {
                    it.uri.toString() == targetUriStr || it.path == targetUriStr
                }
                if (targetItem != null) {
                    withContext(Dispatchers.Main) {
                        viewModel.openViewer(targetItem.id) // Instantly open it full screen
                        hasOpenedInitial = true
                    }
                }
            }
        }
    }

    // THE PERMISSION CATCHER
    // This handles asking the user for permission to move files to the Android Trash
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
                    // Success! Clean up all the menus and checkboxes
                    activeDialog = PictureUiDialog.None
                    isSelectionMode = false
                    selectedIds = emptySet()
                    viewModel.closeViewer()
                    // Show a green banner at the bottom. If they click it, take them to the Trash screen.
                    if (snackbarHostState.showSnackbar("Moved to Trash", "View Trash", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                        onNavigateToTrash()
                    }
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    // Informs the parent App Navigator that selection mode is active so it hides the main bottom bar.
    LaunchedEffect(viewerState, isSelectionMode) {
        onViewerStateChanged(viewerState is GalleryViewerState.Open || isSelectionMode)
    }

    LaunchedEffect(activeFilter) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    // Fixes the back button so it closes search bars instead of closing the app
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

    // Helper to get the actual full Photo Data boxes for every photo the user currently has a checkmark on.
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
        // A Scaffold provides the blank canvas with pre-marked zones for the TopBar, BottomBar, and Main Content.
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // If we are currently selecting multiple photos to delete/share
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
                                // The "Select All" Button
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.Default) {
                                            if (isAllSelected) {
                                                withContext(Dispatchers.Main) { selectedIds = emptySet() }
                                            } else {
                                                // We cap "Select All" at 5000 so the app doesn't crash from memory overload
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
                    // --- THE SEARCH BAR ---
                    SearchTopBar(
                        query = localSearchQuery,
                        onQueryChange = { localSearchQuery = it },
                        onClose = {
                            isSearchActive = false
                            localSearchQuery = ""
                        }
                    )
                } else {
                    // --- STANDARD TOP BAR ---
                    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                        ModernTopBar(
                            title = "Photos",
                            scrollBehavior = scrollBehavior,
                            onSearchClick = { isSearchActive = true },
                            onMenuAction = { action ->
                                // These are the buttons hidden inside the 3-dot "More" menu
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
                // An arrow button that pops up when you scroll down, letting you shoot back to the top instantly.
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
            // --- THE MAIN CONTENT (THE GRID) ---
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                if (isBusy && pagedMedia.itemCount == 0) {
                    // Loading circle
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (pagedMedia.itemCount == 0) {
                    // No photos found message
                    EmptyMediaOverlay(onCameraClick = onNavigateToCamera, onScanClick = onNavigateToScan)
                } else {
                    // The actual grid of photos!
                    AnimatedContent(
                        targetState = activeFilter, // This handles the smooth transition when you switch from "All" to "Videos"
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
                                    // If we are selecting, clicking a photo adds or removes the checkmark
                                    val newSet = selectedIds.toMutableSet()
                                    if (!newSet.remove(item.id)) {
                                        if (newSet.size < 5000) newSet.add(item.id)
                                    }
                                    selectedIds = newSet.toSet()
                                } else {
                                    // If we are NOT selecting, clicking a photo opens it full screen
                                    viewModel.openViewer(item.id)
                                }
                            },
                            onItemLongClick = { item ->
                                // Long-pressing a photo instantly turns on selection mode
                                if (!isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress) // Big buzz
                                    isSelectionMode = true
                                    selectedIds = setOf(item.id)
                                }
                            },
                            header = {
                                // The 3 pills ("All", "Photos", "Videos") at the top of the grid
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

        // --- POP-UP MENUS ---
        // If the user clicked a button that opens a menu, draw it here.
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

        // =====================================================================================
        // 🖼️ FULL SCREEN VIEWER OVERLAY
        // =====================================================================================
        // If the user tapped a photo, the `viewerState` changes to Open.
        // This covers the entire screen and draws the photo big.
        if (viewerState is GalleryViewerState.Open && currentItem != null) {
            var stableMediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
            var stableStartIndex by remember { mutableIntStateOf(0) }

            // Figure out exactly where this photo is in the giant list so they can swipe left/right to see the next ones.
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
                // Pass our AdaptiveState into the Fullscreen pager so it knows if it needs to
                // do a split-screen layout for Tabletop Foldables!
                FullscreenMediaPager(
                    initialIndex = stableStartIndex,
                    mediaList = stableMediaList,
                    mediaMap = mediaMap,
                    favoriteIds = favoriteIds,
                    sharedPlayer = viewModel.getPlayer(), // Pass in the DVD Player engine so it can play videos
                    adaptiveState = adaptiveState, // <-- ADAPTIVE MAGIC HAPPENS HERE
                    onPageChanged = {}, // No action needed for PictureScreen
                    onClose = { viewModel.closeViewer() }, // Tell the manager they swiped down to close it
                    onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                    onEdit = { item ->
                        viewModel.closeViewer()
                        onNavigateToEditor(item.uri.toString(), item.id) // Send them to the photo editing screen!
                    },
                    onPlayVideo = { uri, playlist ->
                        viewModel.closeViewer()
                        onNavigateToVideoPlayer(uri, playlist) // Send them to the dedicated video player!
                    },
                    onDelete = { item -> activeDialog = PictureUiDialog.TrashConfirm(listOf(item)) },
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
                        onNavigateToWallpaper(item.uri.toString(), item.id) // Send them to the "Set as Wallpaper" screen
                    }
                )
            }
        }

        // --- THE BOTTOM ACTION BAR (When selecting photos) ---
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = if (deviceTier == DeviceTier.LOW) fadeIn(tween(80)) else slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = if (deviceTier == DeviceTier.LOW) fadeOut(tween(80)) else slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                // 🎨 ADAPTIVE SELECTION BAR
                // Don't stretch the selection tools 20 inches wide on massive tablets. Keep it centered.
                val controlWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(600.dp) else Modifier.fillMaxWidth()

                Surface(
                    modifier = Modifier
                        .then(controlWidth)
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
                        // Move Button
                        BottomBarActionItem(icon = Icons.AutoMirrored.Outlined.DriveFileMove, label = "Move") {
                            onNavigateToMoveCopy("MOVE", selectedIds.joinToString(","), null)
                            isSelectionMode = false
                        }
                        // Copy Button
                        BottomBarActionItem(icon = Icons.Outlined.FileCopy, label = "Copy") {
                            onNavigateToMoveCopy("COPY", selectedIds.joinToString(","), null)
                            isSelectionMode = false
                        }
                        // Share Button (Opens the Android "Share to WhatsApp/Insta/etc" popup)
                        BottomBarActionItem(icon = Icons.Outlined.Share, label = "Share") {
                            scope.launch(Dispatchers.Default) {
                                val itemsToShare = getSelectedItems()
                                if (itemsToShare.isNotEmpty()) {
                                    val intent = Intent(if (itemsToShare.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                                        val hasImg = itemsToShare.any { !it.isVideo }
                                        val hasVid = itemsToShare.any { it.isVideo }
                                        // Tell Android what kind of files these are so it only shows apps that can handle them.
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
                        // Trash Button
                        BottomBarActionItem(icon = Icons.Outlined.Delete, label = "Trash", isDestructive = true) {
                            scope.launch(Dispatchers.Default) {
                                val itemsToTrash = getSelectedItems()
                                withContext(Dispatchers.Main) {
                                    activeDialog = PictureUiDialog.TrashConfirm(itemsToTrash)
                                }
                            }
                        }
                        // More Menu Button
                        Box {
                            var showMoreMenu by remember { mutableStateOf(false) }
                            BottomBarActionItem(icon = Icons.Default.MoreVert, label = "More") {
                                showMoreMenu = true
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                // You can only see the "Details" metadata menu if you selected exactly ONE photo
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

// A reusable blueprint for the buttons in the Bottom Action Bar
@Composable
fun BottomBarActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false, // If true, make the button Red to warn the user!
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

// The UI for the Search Bar at the top of the screen
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
                .statusBarsPadding() // Ensures it doesn't draw behind the phone's clock/battery
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The Back button to exit search
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
            // The actual oval text input field
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
                        onValueChange = onQueryChange, // Call the function whenever they type a letter
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Search albums, photos...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        // Make the default Android text field transparent so it looks nice inside our oval
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
                    // If they typed something, show an "X" button to clear it instantly
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

// A pretty illustration shown if the user has literally 0 photos on their phone.
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
            // Action buttons to help them get started
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

// The slide-up menu that lets you change how the photos are sorted (e.g., Oldest First)
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

// The slide-up menu that lets you change how many photos are in a row (e.g., 4 columns vs 6 columns)
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

            // Draw a grid of 8 buttons!
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
                                onUpdate(col) // Send the new number to the Manager
                                onDismiss() // Close the menu
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

// The popup asking "Are you sure you want to move these to the trash?"
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

// The main top bar that holds the Title, Search icon, and the 3-dot "More" menu
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

// Draws the Text dates inside the grid (e.g. "Today", "Yesterday", "August 12")
@Composable
fun ModernDateHeader(modifier: Modifier = Modifier, title: String, onSelectAllForDate: () -> Unit = {}) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelectAllForDate() } // If you click the date, it instantly selects every photo from that day!
            .padding(horizontal = 14.dp, vertical = 14.dp)
    )
}

// The 3 pills at the top ("All", "Photos", "Videos")
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

// The slide-up menu that shows you all the hidden nerd data about a photo (File size, Resolution, exact file path)
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

// Reusable blueprint for the rows inside the Metadata Menu
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

// Blueprint for the large square buttons (Edit, Share, Delete) shown when you long-press a single photo.
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

/**
 * THE MENU MANAGER.
 * Takes the `activeDialog` state variable and decides which specific popup menu to draw on the screen.
 */
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

            // The menu that pops up when you single-tap a photo in the grid.
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

/**
 * =========================================================================================
 * 🔲 THE ACTUAL PHOTO GRID MANAGER
 * =========================================================================================
 * This handles the extremely complex math of letting the user drag their finger
 * across the grid to select 50 photos at once.
 */
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

    // Calculates how fast the user is scrolling. If they are flying down the page at mach 5,
    // we stop trying to load high-quality pictures and just show blurry grey boxes to save battery.
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

    // Calculates exactly how many pixels wide the photo thumbnail needs to be to look sharp.
    val dynamicThumbSize = remember(columnCount, screenWidthPx, deviceTier) {
        val maxSize = if (deviceTier == DeviceTier.LOW) 260 else 480
        val raw = (screenWidthPx / columnCount).coerceIn(160, maxSize)
        (raw / 40) * 40
    }

    val gridCells = remember(columnCount) { GridCells.Fixed(columnCount) }

    // --- DRAG TO SELECT LOGIC ---
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) } // If they drag to the bottom of the screen, scroll down automatically!
    var lastPointerPosition by remember { mutableStateOf<Offset?>(null) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragLastIndex by remember { mutableIntStateOf(-1) }
    var dragBaseSelection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dragIsAdditive by remember { mutableStateOf(true) } // Are we dragging to Check boxes, or dragging to Un-Check boxes?

    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)

    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val placeholderPainter = remember(placeholderColor) { ColorPainter(placeholderColor) }

    // Math: "The user's finger is at pixel (200, 450). Which photo is located there?"
    fun indexAt(offset: Offset): Int {
        val layoutInfo = gridState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find {
            offset.x >= it.offset.x && offset.x <= it.offset.x + it.size.width &&
                    offset.y >= it.offset.y && offset.y <= it.offset.y + it.size.height
        }
        return itemInfo?.index ?: -1
    }

    fun mediaIdAt(index: Int): Long? = (pagedMedia.peek(index) as? GalleryGridItem.Media)?.item?.id

    // Mathematically selects every photo between the one they started touching, and the one they are currently touching.
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

        // If the user's finger gets really close to the top or bottom edge of the screen, start scrolling!
        autoScrollSpeed = when {
            y < edge10 -> -70f // Very fast up
            y < edge40 -> -25f
            y < edge80 -> -8f  // Slow up
            y > boxHeightPx - edge10 -> 70f // Very fast down
            y > boxHeightPx - edge40 -> 25f
            y > boxHeightPx - edge80 -> 8f
            else -> 0f // Stop scrolling
        }
    }

    fun endDrag() {
        autoScrollSpeed = 0f
        lastPointerPosition = null
    }

    // A ticking clock that actually moves the screen if autoScrollSpeed is active.
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

    // Intercepts the user's finger
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

    // Draw the actual Grid!
    Box(modifier = Modifier.fillMaxSize()) {
        val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // If they are selecting photos, make the grid stop higher up so it doesn't get covered by the Bottom Action Menu.
        val bottomPadding = if (isSelectionMode) navBarHeight + 100.dp else navBarHeight + 90.dp

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
                        GridItemSpan(columnCount) // Text Headers span all the way across
                    } else {
                        GridItemSpan(1) // Photos take 1 square
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
                                // "Select All For Date" magic!
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
                        // The blank gray square shown while a photo is still loading
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

/**
 * The individual photo square drawn inside the giant grid.
 */
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
    // If selected, it becomes a smaller circle. If not, it's a slightly rounded square.
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

        // Give instructions to the Coil Image Loader to fetch the picture from the hard drive
        val request = remember(item.id, effectiveSize, deviceTier) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(effectiveSize) // Don't load the 4K image, just load it at 200x200 pixels so the phone doesn't freeze.
                .memoryCacheKey("${item.id}_thumb_$effectiveSize")
                .diskCacheKey("${item.id}_thumb_$effectiveSize")
                .bitmapConfig(if (deviceTier == DeviceTier.LOW) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.DISABLED) // We are a local gallery, no need to check the internet.
                .precision(Precision.INEXACT)
                .allowHardware(deviceTier != DeviceTier.LOW)
                .crossfade(0)
                .build()
        }

        AsyncImage(
            model = request,
            placeholder = placeholderPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop, // Crop the edges so it perfectly fills the square
            filterQuality = FilterQuality.Low, // Draw it fast, not perfectly.
            modifier = Modifier.fillMaxSize()
        )

        // If it's a video, draw a dark shadow at the bottom and put the "Play" icon on it.
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

        // The checkmark that appears when you select the photo
        SelectionOverlay(
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            cornerRadius = animatedRadius,
            deviceTier = deviceTier
        )
    }
}

// Draws the actual checkmark
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
            // Give the photo a milky-white tint if selected
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent)
            )

            // Turn off the bouncy animations if the phone is cheap/slow
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
                        .let { if (deviceTier == DeviceTier.LOW) it else it.shadow(4.dp, CircleShape) } // No shadows on slow phones
                        .background(Color.White, CircleShape)
                )
            }
            if (!isSelected) {
                // The empty grey circle waiting to be clicked
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

/**
 * =========================================================================================
 * 🖼️ FULL SCREEN VIEWER OVERLAY
 * =========================================================================================
 * The pop-up overlay that shows a photo/video in full screen when you tap it in the grid.
 * It lets you swipe left and right to view all the photos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMediaPager(
    initialIndex: Int, // The exact photo they tapped
    mediaList: List<MediaItem>,
    mediaMap: Map<Long, MediaItem>,
    favoriteIds: List<Long>,
    sharedPlayer: Player, // The video engine
    adaptiveState: AdaptiveState, // 🧠 The Adaptive Engine telling us how the phone is held
    onPageChanged: (MediaItem) -> Unit,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onEdit: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onPlayVideo: (String, List<String>) -> Unit,
    onMove: (MediaItem) -> Unit,
    onCopy: (MediaItem) -> Unit,
    onWallpaper: (MediaItem) -> Unit
) {
    if (mediaList.isEmpty()) return
    val context = LocalContext.current
    val view = LocalView.current

    // The engine that handles the swiping left/right animation
    val safeInitialPage = initialIndex.coerceIn(0, maxOf(mediaList.lastIndex, 0))
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { mediaList.size }
    )

    var showControls by remember { mutableStateOf(true) } // The Back button, the Share button, etc.
    var showMetadataSheet by remember { mutableStateOf(false) } // The "Details" menu
    var showMoreMenu by remember { mutableStateOf(false) }

    val videoList = remember(mediaList) { mediaList.filter { it.isVideo } }

    // Preload all the videos into the Video Engine so it doesn't stutter when you swipe to one.
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

    // Every time the user swipes left/right to a new photo...
    LaunchedEffect(pagerState.currentPage, videoList) {
        showControls = true // Bring the buttons back
        val current = mediaList.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val resolvedCurrent = mediaMap[current.id] ?: current
        onPageChanged(resolvedCurrent)

        // If they swiped to a video, prepare the Video Engine.
        // If they swiped to a photo, pause the Video Engine so it stops making noise!
        if (current.isVideo) {
            val videoIndex = videoList.indexOfFirst { it.id == current.id }
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

    // Hide the phone's clock/battery bar at the top, and the swipe-up bar at the bottom. True full screen!
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

    // 🎨 ADAPTIVE LAYOUT: TABLETOP MODE
    // If the device is folded halfway (like a tiny laptop), we split the screen!
    if (adaptiveState.posture == DevicePosture.HALF_OPENED) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // TOP HALF: The actual Photo/Video
            Box(modifier = Modifier.weight(1f)) {
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = 18.dp, // Add a tiny gap between photos as you swipe so they don't touch
                    key = { index -> mediaList[index].id },
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = mediaList[page]
                    if (item.isVideo) {
                        VideoPreviewPage(
                            item = item,
                            videoItems = videoList,
                            isCurrentPage = pagerState.currentPage == page,
                            showControls = showControls,
                            sharedPlayer = sharedPlayer,
                            onTap = { showControls = !showControls },
                            onPlay = { onPlayVideo(item.uri.toString(), videoList.map { it.uri.toString() }) }
                        )
                    } else {
                        ZoomableImagePage(
                            item = item,
                            onTap = { showControls = !showControls },
                            onDismiss = onClose
                        )
                    }
                }
            }

            // BOTTOM HALF: The Controls
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                // Top Bar (Back button, Date) inside the bottom screen
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
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

                // Bottom Actions Menu inside the bottom screen
                liveCurrentItem?.let { currentItem ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.1f),
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
                                PremiumViewerAction(icon = Icons.Outlined.Edit, label = "Edit") { onEdit(currentItem) }
                                PremiumViewerAction(icon = Icons.Outlined.Share, label = "Share") {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = if (currentItem.isVideo) "video/*" else "image/*"
                                        putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Share Media"))
                                }
                                PremiumViewerAction(icon = Icons.Outlined.Delete, label = "Delete", tint = Color.Red) { onDelete(currentItem) }
                                Box {
                                    PremiumViewerAction(icon = Icons.Default.MoreVert, label = "More") { showMoreMenu = true }
                                    DropdownMenu(
                                        expanded = showMoreMenu,
                                        onDismissRequest = { showMoreMenu = false },
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Details", color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { showMetadataSheet = true; showMoreMenu = false },
                                            leadingIcon = { Icon(imageVector = Icons.Outlined.Info, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Move to Album", color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { showMoreMenu = false; onMove(currentItem) },
                                            leadingIcon = { Icon(imageVector = Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy to Album", color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { showMoreMenu = false; onCopy(currentItem) },
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
                                            onClick = { showMoreMenu = false; onWallpaper(currentItem) },
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
    } else {
        // 📱 STANDARD FULL SCREEN OVERLAY (For normal phones and flat tablets)
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
                        videoItems = videoList,
                        isCurrentPage = pagerState.currentPage == page,
                        showControls = showControls,
                        sharedPlayer = sharedPlayer,
                        onTap = { showControls = !showControls }, // Tapping the video hides/shows the buttons
                        onPlay = { onPlayVideo(item.uri.toString(), videoList.map { it.uri.toString() }) } // Starts full playback
                    )
                } else {
                    ZoomableImagePage(
                        item = item,
                        onTap = { showControls = !showControls }, // Tapping the photo hides/shows the buttons
                        onDismiss = onClose // Swiping the photo down closes it completely
                    )
                }
            }

            if (showControls) {
                // The Top Bar (Back button and Date)
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
                // The Bottom Bar (Favorite, Edit, Share, Delete)
                liveCurrentItem?.let { currentItem ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.4f), // Slightly see-through
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
                                                    // This asks Android to find a different app on the phone to play the video (like VLC Player)
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
    }

    // The slide-up Details menu
    if (showMetadataSheet && liveCurrentItem != null) {
        MediaMetadataSheet(item = liveCurrentItem) { showMetadataSheet = false }
    }
}

// Shows a muted, looping preview of a video when you swipe to it in the full screen viewer
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
    var m by rememberSaveable(item.id) { mutableStateOf(true) } // Mute the video initially

    LaunchedEffect(m) {
        sharedPlayer.volume = if (m) 0f else 1f
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            sharedPlayer.pause() // Pause if the user swiped away to the next photo
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                // The actual physical screen that draws the video
                PlayerView(ctx).apply {
                    useController = false // Hide the play/pause bar
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
        // A giant Play button floating in the middle of the screen
        if (showControls) {
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).clickable {
                        sharedPlayer.pause() // Pause the preview
                        onPlay() // Launch the dedicated video player!
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

// A reusable blueprint for the buttons in the Full Screen bottom menu
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

/**
 * --- THE MICROSCOPE (ZoomableImagePage) ---
 * Handles the incredibly complex math of letting a user "Pinch to Zoom"
 * into a photo without it accidentally swiping to the next page instead.
 */
@Composable
fun ZoomableImagePage(item: MediaItem, onTap: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val hap = LocalHapticFeedback.current
    val conf = LocalConfiguration.current

    // Find exactly how many pixels wide and tall the user's phone is
    val wPx = with(d) { conf.screenWidthDp.dp.roundToPx() }
    val hPx = with(d) { conf.screenHeightDp.dp.roundToPx() }

    // The "Dismiss Threshold". If they drag the photo down past 25% of the screen, we close it.
    val thr = remember(conf.screenHeightDp, d) { with(d) { conf.screenHeightDp.dp.toPx() * 0.25f } }

    var sc by remember { mutableFloatStateOf(1f) } // Scale (Zoom level). 1f = 100%. 2f = 200%.
    var oX by remember { mutableFloatStateOf(0f) } // Offset X (How far they dragged it left/right)
    var oY by remember { mutableFloatStateOf(0f) } // Offset Y (How far they dragged it up/down)
    var bA by remember { mutableFloatStateOf(1f) } // Background Alpha (How dark the black background should be)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bA))
            .graphicsLayer {
                // As the user drags the photo down (oY gets bigger), we slowly shrink the photo and fade the black background away!
                val ds = 1f - (abs(oY) / 2200f)
                scaleX = sc * ds
                scaleY = scaleX
                alpha = (1f - (abs(oY) / 850f)).coerceIn(0f, 1f)
                translationX = oX
                translationY = oY
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() }, // Show/hide menus
                    onDoubleTap = {
                        // Double tapping zooms in to 250%. Double tapping again resets to 100%.
                        sc = if (sc > 1f) 1f else 2.5f
                        oX = 0f
                        oY = 0f
                    },
                    onLongPress = { hap.performHapticFeedback(HapticFeedbackType.LongPress) }
                )
            }
            // THIS is the Pinch-To-Zoom math!
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false) // Wait for the user to touch the screen
                    do {
                        val event = awaitPointerEvent() // Wait for their finger to move
                        val zoom = event.calculateZoom() // Are two fingers moving apart?
                        val pan = event.calculatePan() // Is a finger dragging?

                        // Apply the zoom
                        if (abs(zoom - 1f) > 0.005f) {
                            sc = (sc * zoom).coerceIn(1f, 4f) // Don't let them zoom in past 400%
                        }

                        // If they are currently zoomed in...
                        if (sc > 1.05f) {
                            // "Consume" the touch. This tells Android: "Do NOT swipe to the next photo! The user is just looking around inside this zoomed photo."
                            event.changes.forEach {
                                if (it.positionChange() != Offset.Zero) {
                                    it.consume()
                                }
                            }

                            // Prevent them from dragging the photo entirely off the screen
                            val mx = (size.width * (sc - 1)) / 2f
                            val my = (size.height * (sc - 1)) / 2f
                            oX = (oX + pan.x).coerceIn(-mx, mx)
                            oY = (oY + pan.y).coerceIn(-my, my)
                        } else {
                            // If they are NOT zoomed in...
                            val isV = abs(pan.y) > abs(pan.x) // Are they dragging down, or swiping sideways?
                            if (isV && event.changes.size == 1) { // If dragging down with one finger...
                                oY += pan.y // Move the photo down
                                bA = (1f - abs(oY) / 900f).coerceIn(0.35f, 1f) // Fade the black background away
                                event.changes.forEach {
                                    if (it.positionChange() != Offset.Zero) {
                                        it.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed }) // Keep doing this until they lift their finger

                    // They lifted their finger!
                    if (sc <= 1.05f) {
                        // Did they drag it far enough down to close it?
                        if (abs(oY) > thr) {
                            hap.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss() // Close the full screen viewer!
                        } else {
                            // They didn't drag it far enough. Snap the photo back to the center like a rubber band.
                            oY = 0f
                            bA = 1f
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Fetch the ABSOLUTE HIGHEST QUALITY version of the photo so it looks crystal clear when zoomed in.
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
            contentScale = ContentScale.Fit, // Never crop the full screen photo!
            modifier = Modifier.fillMaxSize()
        )
    }
}