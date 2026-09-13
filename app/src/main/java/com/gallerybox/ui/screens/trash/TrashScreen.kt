// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.ui.screens.trash

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for drawing grids, showing popups, feeling physical vibrations (haptics), and doing background math.
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random

// ---------------------------------------------------------------------------
// 🖼️ APP DATA & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.data.GalleryDao
import com.gallerybox.viewmodel.*

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

/**
 * =========================================================================================
 * 📦 TRASH DATA MODELS
 * =========================================================================================
 * These define the different types of files we can throw away and how to sort them.
 */
enum class TrashMediaType { Image, Video, Audio, Story }

// The basic "Box" holding the details about a file that was deleted.
data class TrashUiItem(
    val id: Long,
    val originalPath: String, // Where it used to live before it was deleted
    val contentUri: Uri,
    val name: String,
    val size: Long,
    val type: TrashMediaType,
    val deletedTimestamp: Long, // The exact millisecond it was deleted
    val daysLeft: Int // Tells the user how many days before it's gone forever
)

// Used to show the "Expiring Soon" and "This Week" text headers in the scrolling grid
sealed class TrashGridItem {
    data class Header(val title: String) : TrashGridItem()
    data class Media(val item: TrashUiItem) : TrashGridItem()
}

// How the user wants to sort the trash list
enum class TrashSort { NewestDeleted, OldestDeleted }
// Which type of files the user wants to look at right now
enum class TrashFilter { All, Images, Videos, Audio, Stories }

/**
 * =========================================================================================
 * 🗑️ THE MAIN TRASH SCREEN
 * =========================================================================================
 * This screen shows all deleted files and tells the user how many days are left
 * until Android automatically destroys them forever.
 */
@RequiresApi(Build.VERSION_CODES.ECLAIR)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    // We bring in 3 different Managers (ViewModels) because the Trash Bin handles photos, music, AND stories!
    trashViewModel: TrashViewModel = hiltViewModel(),
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit // Command to go back to the previous screen
) {
    val context = LocalContext.current // Gets the environment info (needed to format file sizes and show popups)

    // 🧠 1. Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()

    val scope = rememberCoroutineScope() // Tool for launching background workers
    val snackbarHostState = remember { SnackbarHostState() } // Tool for showing the little message banners at the bottom of the screen
    val lifecycleOwner = LocalLifecycleOwner.current // Knows if the app is currently visible or pushed to the background
    val haptics = LocalHapticFeedback.current // Tool for making the phone vibrate slightly when you tap something

    // A tiny digital notebook to save user settings (like how many columns they want in the grid)
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    // 🎨 2. SMART ADAPTIVE COLUMNS
    // Giant tablets should show more trash items side-by-side than small phones.
    var gridColumns by remember {
        mutableIntStateOf(
            prefs.getInt("trash_grid_columns",
                when (adaptiveState.widthSize) {
                    WindowWidthSize.COMPACT -> 4   // Phones show 4 squares per row
                    WindowWidthSize.MEDIUM -> 6    // Small tablets show 6
                    WindowWidthSize.EXPANDED -> 8  // Giant monitors show 8
                }
            )
        )
    }

    // --- SCOREBOARDS ---
    // Look at the Manager's scoreboards so the screen updates instantly when the trash is emptied.
    val trashEntities by galleryViewModel.trashBin.collectAsState(initial = emptyList())
    val isGalleryBusy by galleryViewModel.isBusy.collectAsState(initial = false)

    // Tracks if we are currently permanently deleting a massive batch of files (0% to 100%)
    val operationProgress by trashViewModel.operationProgress.collectAsState()

    val isBusy = isGalleryBusy || operationProgress != null

    // Sync with the database as soon as the screen opens to make sure we aren't showing files that were already deleted.
    LaunchedEffect(Unit) {
        // Connect the "Refresh" buttons
        trashViewModel.onRefreshGallery = { galleryViewModel.forceSync() }
        trashViewModel.onRefreshMusic = { musicViewModel.loadAllAudioTracks() }

        // Actually trigger the refresh
        galleryViewModel.refreshData()
        musicViewModel.loadAllAudioTracks()
    }

    // We use this to force the "Days Left" text to recalculate if the user leaves the app and comes back tomorrow.
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // If the user leaves the app and comes back, refresh the timer (days left)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTime = System.currentTimeMillis() // Update the time to right now!
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // THE TRANSLATOR.
    // Takes the raw ugly database entries and converts them into nice clean `TrashUiItem` boxes for the screen.
    val trashUiItems = remember(trashEntities, currentTime) {
        trashEntities.mapNotNull { entity ->
            val uri = try {
                Uri.parse(entity.contentUri)
            } catch (_: Exception) { null } ?: return@mapNotNull null

            val type = when (entity.mediaType) {
                "video" -> TrashMediaType.Video
                "audio" -> TrashMediaType.Audio
                "story" -> TrashMediaType.Story
                else -> TrashMediaType.Image
            }

            TrashUiItem(
                id = entity.id,
                originalPath = entity.originalPath,
                contentUri = uri,
                name = entity.name,
                size = entity.size,
                type = type,
                deletedTimestamp = entity.deletedTimestamp,
                daysLeft = trashViewModel.calculateDaysLeft(entity.deletedTimestamp) // "29 Days left"
            )
        }
    }

    // Local State Variables
    var isEditMode by remember { mutableStateOf(false) } // Is the user actively selecting files with checkboxes?
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) } // A list of the files they selected
    var showEmptySheet by remember { mutableStateOf(false) } // Show the "Empty Entire Trash?" slide-up menu
    var showDeleteSheet by remember { mutableStateOf(false) } // Show the "Delete these 5 files forever?" slide-up menu
    var showGridSheet by remember { mutableStateOf(false) } // Show the "Change Grid Columns" slide-up menu
    var itemForDetails by remember { mutableStateOf<TrashUiItem?>(null) } // Show the "Metadata" slide-up menu for a specific file

    // State controllers for the slide-up menus
    val emptySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deleteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Current Sorting and Filtering rules
    var currentSort by remember { mutableStateOf(TrashSort.NewestDeleted) }
    var currentFilter by remember { mutableStateOf(TrashFilter.All) }

    // If the user changes the filter from "All" to "Videos", instantly cancel their selection so they don't accidentally delete invisible photos.
    LaunchedEffect(currentFilter) {
        if (isEditMode) selectedIds = emptySet()
    }

    // Apply the Sort and Filter rules to the list of trash items
    val finalTrashItems = remember(trashUiItems, currentSort, currentFilter) {
        val filtered = when (currentFilter) {
            TrashFilter.All -> trashUiItems
            TrashFilter.Images -> trashUiItems.filter { it.type == TrashMediaType.Image }
            TrashFilter.Videos -> trashUiItems.filter { it.type == TrashMediaType.Video }
            TrashFilter.Audio -> trashUiItems.filter { it.type == TrashMediaType.Audio }
            TrashFilter.Stories -> trashUiItems.filter { it.type == TrashMediaType.Story }
        }

        when (currentSort) {
            TrashSort.NewestDeleted -> filtered.sortedByDescending { it.deletedTimestamp } // Newest at top
            TrashSort.OldestDeleted -> filtered.sortedBy { it.deletedTimestamp } // Oldest at top
        }
    }

    // THE ORGANIZER.
    // Organizes the photos into groups: "Expired", "Expiring Soon", "This Week", and "Later".
    // This creates the nice text headers you see as you scroll down the grid.
    val flattenedGridItems = remember(finalTrashItems) {
        val grouped = finalTrashItems.groupBy {
            when {
                it.daysLeft <= 0 -> "Expired"
                it.daysLeft <= 3 -> "Expiring Soon"
                it.daysLeft <= 7 -> "This Week"
                else -> "Later"
            }
        }

        // Flatten the groups into a single list with Headers mixed in
        val list = mutableListOf<TrashGridItem>()
        listOf("Expired", "Expiring Soon", "This Week", "Later").forEach { key ->
            grouped[key]?.let { groupItems ->
                if (groupItems.isNotEmpty()) {
                    list.add(TrashGridItem.Header(key)) // Add the Text Header
                    groupItems.forEach { item ->
                        list.add(TrashGridItem.Media(item)) // Add the photos underneath it
                    }
                }
            }
        }
        list
    }

    // THE PERMISSION CATCHER.
    // Android 11+ requires apps to explicitly ask the user for permission to delete/restore files.
    // It pops up a little system window. This "Launcher" catches the "Yes" or "No" answer when the window closes.
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK // Did they click "Allow"?
        trashViewModel.onPermissionResultGlobal(granted) // Tell the Manager what they clicked!
        if (!granted) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Listen to the Manager's Walkie-Talkie
    LaunchedEffect(trashViewModel) {
        trashViewModel.events.collect { event ->
            when (event) {
                // If the manager says "Ask for permission!", open the system popup
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                // If the deletion/restore was completely successful!
                is GalleryEvent.OperationSuccess -> {
                    isEditMode = false // Turn off selection mode
                    selectedIds = emptySet() // Clear the checkboxes
                    itemForDetails?.let {
                        scope.launch { detailsSheetState.hide() }.invokeOnCompletion { itemForDetails = null } // Close the metadata menu
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress) // Buzz the phone
                    scope.launch { snackbarHostState.showSnackbar("Operation Completed Successfully") } // Show the green banner

                    // Tell the other screens to refresh their lists
                    galleryViewModel.refreshData()
                    musicViewModel.loadAllAudioTracks()
                }
                else -> {}
            }
        }
    }

    // If the trash becomes completely empty, automatically exit "Selection" mode
    LaunchedEffect(finalTrashItems.size) {
        if (finalTrashItems.isEmpty() && isEditMode) {
            isEditMode = false
            selectedIds = emptySet()
        }
    }

    // Intercept the physical Android "Back" button so it closes menus instead of closing the app
    BackHandler(enabled = itemForDetails != null) {
        scope.launch { detailsSheetState.hide() }.invokeOnCompletion { itemForDetails = null }
    }

    BackHandler(enabled = isEditMode && operationProgress == null) {
        isEditMode = false
        selectedIds = emptySet()
    }

    var showMenu by remember { mutableStateOf(false) } // The 3-dot menu in the top right
    var showSortMenu by remember { mutableStateOf(false) } // The sort icon menu
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior() // Makes the top bar shrink when scrolling down

    // --- DRAWING THE SCREEN ---
    // A Scaffold provides the blank canvas with pre-marked zones for the TopBar, BottomBar, and Main Content.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) }, // The zone where the little popup banners appear
        topBar = {
            // Calculate a tiny shadow to draw under the top bar when the user scrolls down
            val elevation by animateDpAsState(
                targetValue = if (scrollBehavior.state.overlappedFraction > 0.01f) 8.dp else 0.dp,
                label = "topBarElevation"
            )

            Surface(
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp), // Round the bottom corners of the top bar
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.shadow(elevation, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            ) {
                Column {
                    // Show a blue loading line right under the top bar if the app is busy
                    if (isBusy && operationProgress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    }
                    LargeTopAppBar(
                        title = {
                            Column {
                                if (isEditMode) {
                                    // Animate the number changing (e.g. "5 Selected" -> "6 Selected")
                                    AnimatedContent(targetState = selectedIds.size, label = "SelectionCount") { count ->
                                        Text("✓ $count Selected", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Trash Bin", fontWeight = FontWeight.Bold)
                                }
                                // Show "400 items • 2.5 GB" under the title
                                if (!isEditMode && finalTrashItems.isNotEmpty()) {
                                    Text(
                                        text = "${finalTrashItems.size} items • ${Formatter.formatShortFileSize(context, trashUiItems.sumOf { it.size })}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (isEditMode) {
                                        isEditMode = false // Cancel selection
                                        selectedIds = emptySet()
                                    } else {
                                        onBack() // Actually leave the screen
                                    }
                                }
                            ) {
                                Icon(if (isEditMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            // Top Right Buttons
                            if (isEditMode) {
                                val allSelected = remember(selectedIds, finalTrashItems) {
                                    finalTrashItems.isNotEmpty() && selectedIds.size == finalTrashItems.size
                                }
                                // The "Select All" button
                                IconButton(
                                    onClick = {
                                        if (allSelected) {
                                            selectedIds = emptySet() // Deselect all
                                        } else {
                                            selectedIds = finalTrashItems.map { it.id }.toSet() // Select all
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Tiny vibration click
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                        contentDescription = null,
                                        tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else if (finalTrashItems.isNotEmpty()) {
                                // The "Select" text button
                                TextButton(onClick = { isEditMode = !isEditMode }) {
                                    Text("Select", fontWeight = FontWeight.Bold)
                                }

                                // The Sort button (Oldest/Newest)
                                Box {
                                    IconButton(onClick = { showSortMenu = true }) {
                                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                    }
                                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Newest Deleted") },
                                            onClick = { currentSort = TrashSort.NewestDeleted; showSortMenu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Oldest Deleted") },
                                            onClick = { currentSort = TrashSort.OldestDeleted; showSortMenu = false }
                                        )
                                    }
                                }

                                // The 3-dot "More" menu
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Grid Size") },
                                            leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                                            onClick = { showGridSheet = true; showMenu = false } // Open the Grid Size slide-up menu
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Empty Trash", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { showEmptySheet = true; showMenu = false } // Open the Empty Trash slide-up menu
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        ),
                        scrollBehavior = scrollBehavior
                    )
                    // Draw a thin line under the top bar if the user scrolled down
                    if (scrollBehavior.state.overlappedFraction > 0.01f) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    ) { paddingValues ->
        // --- THE MAIN CONTENT AREA ---
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // If there is trash, show the warning banner at the top
                if (trashUiItems.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Items are permanently deleted after 30 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }

                    // The Filter Chips (All, Images, Videos, Audio, Stories)
                    if (!isEditMode) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TrashFilter.entries) { filter ->
                                val isSelected = currentFilter == filter
                                val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "ChipScale") // Make the selected chip slightly bigger
                                val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = bgColor,
                                    modifier = Modifier
                                        .scale(scale)
                                        .height(38.dp)
                                        .clickable {
                                            currentFilter = filter
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Tiny click feeling
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = when (filter) {
                                                TrashFilter.All -> Icons.Rounded.AllInclusive
                                                TrashFilter.Images -> Icons.Rounded.Image
                                                TrashFilter.Videos -> Icons.Rounded.VideoLibrary
                                                TrashFilter.Audio -> Icons.Rounded.MusicNote
                                                TrashFilter.Stories -> Icons.Rounded.AutoStories
                                            },
                                            contentDescription = null,
                                            tint = contentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = filter.name,
                                            color = contentColor,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // If the user's specific filter (e.g., "Audio") resulted in 0 items...
                if (finalTrashItems.isEmpty()) {
                    if (isBusy && operationProgress == null) {
                        // We are loading from the database, show a spinner
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        // The filter is truly empty. Show the pretty "Trash Bin is Clean" illustration!
                        EmptyTrashView(currentFilter)
                    }
                } else {
                    // THE ACTUAL STAGGERED GRID!
                    // Staggered means the squares can be different heights, looking like a Pinterest board.
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(gridColumns), // Read from user settings
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = flattenedGridItems,
                            // If it's a Text Header ("This Week"), make it span all the way across. Otherwise, take 1 square.
                            span = { if (it is TrashGridItem.Header) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
                            key = { if (it is TrashGridItem.Header) "h_${it.title}" else (it as TrashGridItem.Media).item.id }
                        ) { item ->
                            when (item) {
                                // Draw the Header Text
                                is TrashGridItem.Header -> {
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                // Color it Red if it's "Expired" or "Expiring Soon"
                                                color = if (item.title == "Expiring Soon" || item.title == "Expired") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                                // Draw the Photo/Video Square
                                is TrashGridItem.Media -> {
                                    TrashTile(
                                        item = item.item,
                                        isSelected = selectedIds.contains(item.item.id),
                                        isEditMode = isEditMode,
                                        onClick = {
                                            if (isEditMode) {
                                                // Check or uncheck the box
                                                selectedIds = if (selectedIds.contains(item.item.id)) selectedIds - item.item.id else selectedIds + item.item.id
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else {
                                                // Open the Metadata details menu
                                                itemForDetails = item.item
                                            }
                                        },
                                        onLongClick = {
                                            if (!isEditMode) {
                                                // Turn on Edit Mode
                                                isEditMode = true
                                                selectedIds = selectedIds + item.item.id
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress) // Big buzz
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- THE BOTTOM ACTION BAR ---
            // A floating menu that slides up from the bottom when you select files.
            AnimatedVisibility(
                visible = isEditMode && operationProgress == null,
                enter = slideInVertically(tween(250)) { it } + fadeIn(), // Slide up
                exit = slideOutVertically(tween(250)) { it } + fadeOut(), // Slide down
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            ) {
                // Adaptive width so it doesn't stretch weirdly on a giant iPad
                val widthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(600.dp) else Modifier.fillMaxWidth()

                Row(
                    modifier = Modifier.then(widthModifier).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The "Restore" Button
                    Button(
                        onClick = {
                            val itemsToRestore = trashEntities.filter { selectedIds.contains(it.id) }
                            if (itemsToRestore.isNotEmpty()) {
                                trashViewModel.restoreTrashItems(itemsToRestore) // Send to Manager!
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isBusy && selectedIds.isNotEmpty() // Disable if nothing is selected
                    ) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restore", fontWeight = FontWeight.Bold)
                    }

                    // The "Delete Permanently" Button
                    Button(
                        onClick = { showDeleteSheet = true }, // Opens the slide-up confirmation menu
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        enabled = !isBusy && selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Permanently", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // --- THE PROGRESS OVERLAY ---
            // If we are deleting 1,000 files, show a progress bar instead of the bottom action menu.
            AnimatedVisibility(
                visible = operationProgress != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            progress = { operationProgress ?: 0f }, // 0.0 to 1.0
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.width(16.dp))

                        val percentage = ((operationProgress ?: 0f) * 100).toInt()
                        Text(
                            text = "Processing... $percentage%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // --- SLIDE-UP MENUS (BottomSheets) ---

    // The Grid Size Menu (Lets user pick 1, 2, 3, or 4 columns)
    if (showGridSheet) {
        ModalBottomSheet(onDismissRequest = { showGridSheet = false }, sheetState = gridSheetState) {
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
                    // Draw 8 buttons (1 to 8 columns)
                    items(8) { index ->
                        val col = index + 1
                        val isSelected = gridColumns == col
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .aspectRatio(1f) // Perfect square
                                .clickable {
                                    gridColumns = col // Update column count instantly
                                    prefs.edit().putInt("trash_grid_columns", col).apply() // Save to digital notebook
                                    scope.launch { gridSheetState.hide() }.invokeOnCompletion { showGridSheet = false } // Close menu
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

    // The "Empty Entire Trash" Confirmation Menu
    if (showEmptySheet) {
        ModalBottomSheet(onDismissRequest = { showEmptySheet = false }, sheetState = emptySheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp, 0.dp, 24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(16.dp))
                Text("Empty Entire Trash?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Permanently destroy all ${trashUiItems.size} items?", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch { emptySheetState.hide() }.invokeOnCompletion {
                            if (!emptySheetState.isVisible) {
                                trashViewModel.permanentlyDeleteTrash(trashEntities) // Send kill command to Manager!
                                showEmptySheet = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Trash")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showEmptySheet = false },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    // The "Delete Selected Files" Confirmation Menu
    if (showDeleteSheet) {
        ModalBottomSheet(onDismissRequest = { showDeleteSheet = false }, sheetState = deleteSheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp, 0.dp, 24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(16.dp))
                Text("Permanently Delete?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("${selectedIds.size} items will be destroyed instantly.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch { deleteSheetState.hide() }.invokeOnCompletion {
                            if (!deleteSheetState.isVisible) {
                                val itemsToDel = trashEntities.filter { selectedIds.contains(it.id) }
                                if (itemsToDel.isNotEmpty()) trashViewModel.permanentlyDeleteTrash(itemsToDel) // Send kill command!
                                showDeleteSheet = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteSheet = false },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    // The "File Details / Metadata" Menu (Shown when you tap a single file)
    if (itemForDetails != null) {
        val item = itemForDetails!!
        ModalBottomSheet(onDismissRequest = { itemForDetails = null }, sheetState = detailsSheetState) {
            Column(modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 40.dp).fillMaxWidth()) {
                Text("Metadata Specs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                // Draw a large preview picture at the top
                Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    when (item.type) {
                        TrashMediaType.Image, TrashMediaType.Video -> {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(item.contentUri)
                                    .size(600)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (item.type == TrashMediaType.Video) {
                                Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        }
                        // If it's a song or a story, just draw a big icon
                        TrashMediaType.Audio -> Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(56.dp))
                        TrashMediaType.Story -> Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))

                // The Data Rows
                Column {
                    Text("File Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
                Column {
                    Text("Grouping", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(item.type.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
                Column {
                    Text("Origin Path", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(item.originalPath.ifBlank { "Virtual Source Stack" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
                Column {
                    Text("Size / Expiry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    // Combine size string and days left into one row
                    Text("${Formatter.formatShortFileSize(context, item.size)} • ${if (item.daysLeft <= 0) "Expired" else "${item.daysLeft} days left"}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }

                Spacer(Modifier.height(24.dp))

                // The Restore/Delete Buttons at the bottom of the details menu
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val targetEntity = trashEntities.find { it.id == item.id }

                    OutlinedButton(
                        onClick = {
                            if (targetEntity != null) {
                                scope.launch { detailsSheetState.hide() }.invokeOnCompletion {
                                    trashViewModel.restoreTrashItems(listOf(targetEntity))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("Restore")
                    }

                    Button(
                        onClick = {
                            if (targetEntity != null) {
                                scope.launch { detailsSheetState.hide() }.invokeOnCompletion {
                                    trashViewModel.permanentlyDeleteTrash(listOf(targetEntity))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Permanent Delete", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔲 TRASH GRID TILE (Individual item display)
 * =========================================================================================
 * The individual photo or video square shown inside the trash grid.
 * We use a "Staggered" layout, meaning the squares randomly have different heights.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashTile(
    modifier: Modifier = Modifier,
    item: TrashUiItem,
    isSelected: Boolean, // Did the user check the box?
    isEditMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // Math to slightly shrink and dim the photo when selected
    val scale by animateFloatAsState(if (isSelected) 0.97f else 1f, label = "Scale")
    val alpha by animateFloatAsState(if (isSelected) 0.20f else 0f, label = "Alpha")

    // Pick a random height for the photo to create the staggered Pinterest-style look
    // We use the item's ID as the "seed" so the height is always the same for that specific photo, preventing jumping around.
    val height = remember(item.id) {
        if (item.type == TrashMediaType.Image) {
            listOf(110.dp, 130.dp, 150.dp).random(Random(item.id xor item.originalPath.hashCode().toLong()))
        } else {
            110.dp // Videos and Audio are always the same height so they don't look weird
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(height)
            .padding(4.dp)
            .shadow(1.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick) // Detects both short taps and long hold-downs
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when (item.type) {
            TrashMediaType.Image, TrashMediaType.Video -> {
                // Fetch the tiny low-res thumbnail preview
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.contentUri)
                        .size(350)
                        .memoryCacheKey("t_${item.id}")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop, // Fill the square completely, chopping off the edges if needed
                    modifier = Modifier.fillMaxSize()
                )
                if (item.type == TrashMediaType.Video) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.4f)))))
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            TrashMediaType.Audio, TrashMediaType.Story -> {
                val iconRes = when (item.type) {
                    TrashMediaType.Audio -> Icons.Rounded.MusicNote
                    else -> Icons.Rounded.AutoStories
                }
                val iconTint = when (item.type) {
                    TrashMediaType.Audio -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.primary
                }

                // If it's audio or a story, we don't have a picture, so just draw a big colored icon and the file name
                Column(Modifier.fillMaxSize().padding(8.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Icon(iconRes, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }

        // The tiny black tag in the bottom left corner showing how many days are left!
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                // Turn the tag bright red if it expires in 3 days or less!
                .background(if (item.daysLeft <= 3) MaterialTheme.colorScheme.error else Color.Black.copy(0.55f), RoundedCornerShape(6.dp))
                .padding(6.dp, 2.dp)
        ) {
            Text(
                text = if (item.daysLeft <= 0) "Expired" else "${item.daysLeft} d",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.daysLeft <= 3) MaterialTheme.colorScheme.onError else Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        // The Checkmark overlay
        if (isEditMode || alpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha)))
            if (isSelected) {
                // Draw the blue checked circle
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.White, CircleShape)
                        .padding(1.dp) // Creates a tiny border effect visually
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else if (isEditMode) {
                // Draw the empty grey circle waiting to be clicked
                Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                )
            }
        }
    }
}

/**
 * =========================================================================================
 * ⚙️ THE JANITOR (Background Cleanup Worker - Android WorkManager)
 * =========================================================================================
 * This runs invisibly in the background every few hours. It checks if any items in the
 * trash have been sitting there for more than 30 days. If they have, it deletes them forever!
 */
@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: GalleryDao // Local database
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TRASH_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000L // 30 Days represented in milliseconds
    }

    // This is the function that Android calls in the background while the user is sleeping
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Find out exactly what time it was 30 days ago
            val expiryThreshold = System.currentTimeMillis() - TRASH_EXPIRY_MS

            // Ask the database: "Give me the oldest 5000 items in the trash bin"
            val allCandidates = dao.getOldestTrashItems(5000)

            if (allCandidates.isEmpty()) {
                return@withContext Result.success() // Nothing to delete! Job is done.
            }

            val deletedIds = mutableListOf<Long>()
            var deleteFailures = 0

            // If we try to delete 5000 files instantly, the phone's CPU will max out and the phone will get hot.
            // So we chunk them into small batches of 500.
            allCandidates.chunked(500).forEach { batch ->
                batch.forEachIndexed { index, trash ->
                    // Every 50 items, `yield()` tells the CPU: "Take a microsecond break to breathe and handle other phone tasks"
                    if (index % 50 == 0) yield()

                    val uri = runCatching { Uri.parse(trash.contentUri) }.getOrNull()
                    val exists = uri != null && mediaExists(uri) // Double check if the file still exists on the phone
                    val isExpired = trash.deletedTimestamp < expiryThreshold // Is it actually 30 days old?

                    when {
                        // If the file was already deleted by something else outside the app (like a file manager app),
                        // we just remove the text record of it from our database.
                        !exists -> {
                            deletedIds.add(trash.id)
                        }
                        // If it IS expired, we ask the Android system to delete the actual physical file
                        isExpired -> {
                            try {
                                if (applicationContext.contentResolver.delete(uri!!, null, null) > 0) {
                                    // Success!
                                    deletedIds.add(trash.id)
                                } else {
                                    // It failed. Did it fail because it was already deleted?
                                    if (!mediaExists(uri)) {
                                        deletedIds.add(trash.id)
                                    } else {
                                        deleteFailures++ // Nope, it failed because Android blocked us or it's corrupted.
                                    }
                                }
                            } catch (e: Exception) {
                                deleteFailures++
                            }
                        }
                    }
                }

                // Remove the successfully deleted files from our local database
                if (deletedIds.isNotEmpty()) {
                    dao.deleteTrashItems(deletedIds)
                    deletedIds.clear()
                }
            }

            // Android WorkManager is smart. If we tell it "Retry", it will try running the Janitor again later.
            // If more than 100 files failed to delete (e.g. Android locked the folder), we ask it to retry tomorrow.
            if (deleteFailures > 100) {
                Result.retry()
            } else {
                Result.success() // Good job Janitor!
            }
        } catch (e: Exception) {
            Result.success() // If the entire Janitor system crashes, just report success so Android doesn't get mad and ban our background service.
        }
    }

    // Helper to check if a file physically exists on the hard drive
    private fun mediaExists(uri: Uri): Boolean {
        return try {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                // MATCH_INCLUDE tells Android: "Yes, I know this file is marked as 'in the trash bin', I still want to check if it's there."
                Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) },
                null
            )?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * =========================================================================================
 * 🪹 EMPTY TRASH VIEW
 * =========================================================================================
 * Shows a pretty illustration when the trash is completely empty.
 */
@Composable
fun EmptyTrashView(currentFilter: TrashFilter) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Make the illustration smoothly fade in and pop onto the screen
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(800)) + scaleIn(initialScale = 0.95f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), modifier = Modifier.size(120.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(0.6f))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    // Changes text dynamically based on what filter the user is looking at
                    text = if (currentFilter == TrashFilter.All) "Trash Bin is Clean" else "No ${currentFilter.name.lowercase()} inside the bin",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Removed files materialize here",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}