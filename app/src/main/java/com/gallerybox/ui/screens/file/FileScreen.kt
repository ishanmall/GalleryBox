// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict grammar checker to ignore specific words because we know what we are doing.
@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "UnsafeOptInUsageError")
package com.gallerybox.ui.screens.file

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for drawing grids, showing popups, feeling physical vibrations (haptics), doing math on file sizes, and background processing.
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.gallerybox.data.Album
import com.gallerybox.data.MediaItem
import com.gallerybox.ui.screens.album.formatDuration
import com.gallerybox.viewmodel.FileOperationState
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * =========================================================================================
 * 📦 DATA MODELS & CONSTANTS
 * =========================================================================================
 */
// The different types of duplicates we can find
enum class MatchType { EXACT, SIMILAR, VIDEO }
// What the Duplicate Scanner is currently doing
private enum class ScanState { IDLE, SCANNING, COMPLETE, CANCELLED }
// Are we moving a file, or copying a file?
enum class OperationMode { MOVE, COPY }

// A box holding a group of identical photos (e.g., 5 identical pictures of a dog), and how much space we will save by deleting 4 of them.
data class DuplicateGroup(val items: List<MediaItem>, val type: MatchType, val wastedSize: Long)

private const val MIN_GRID_COLUMNS = 1
private const val MAX_GRID_COLUMNS = 8

/**
 * =========================================================================================
 * 🕵️ THE DIGITAL DETECTIVE (DuplicatesScreen)
 * =========================================================================================
 * Scans the entire phone looking for exact copies of photos, or burst shots that look extremely similar.
 */
@RequiresApi(Build.VERSION_CODES.ECLAIR)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToTrash: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // Tool for launching background workers
    val snackbarHostState = remember { SnackbarHostState() }

    // Grab the massive list of every photo/video on the phone
    val allMedia by viewModel.media.collectAsState()

    // Local scoreboards for tracking the scan
    var scanState by remember { mutableStateOf(ScanState.IDLE) }
    var allGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var activeTab by remember { mutableStateOf(MatchType.EXACT) } // Which tab are they looking at? (Exact Matches, Similar, etc.)
    var cancelScan by remember { mutableStateOf(false) }

    // `mutableStateMapOf` is a dictionary tracking which files the user has checked to delete.
    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableIntStateOf(0) }
    val totalToScan = allMedia.size

    // Math: Adds up the file size of every single photo the user has checked.
    val sizeToDelete: Long by remember(selectedIds.size) { derivedStateOf { selectedIds.values.sumOf { it } } }
    var scanTrigger by remember { mutableIntStateOf(1) } // Used to restart the scan if they hit cancel

    // If two photos were taken within 5 seconds of each other, they are probably "Burst" or "Similar" photos
    val burstTimeWindowSec = 5L

    // The Permission Bouncer: Asks the Android OS for permission to delete files
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(isGranted)
        if (!isGranted) Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    // Connects the ViewModels' Walkie-Talkies to the screen so we can hear when a deletion finishes
    LaunchedEffect(Unit) {
        trashViewModel.onRefreshGallery = { scope.launch { viewModel.forceSync() } }
        trashViewModel.events.collectLatest { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.OperationSuccess -> {
                    // We successfully deleted the duplicates! Now we need to remove them from the screen.
                    val idsToDelete = selectedIds.keys.toList()
                    allGroups = allGroups.mapNotNull { group ->
                        val kept = group.items.filter { !idsToDelete.contains(it.id) }
                        // If the group still has more than 1 photo, keep it on screen. Otherwise, the group is resolved! Remove it.
                        if (kept.size > 1) group.copy(items = kept, wastedSize = kept.drop(1).sumOf { it.size }) else null
                    }
                    selectedIds.clear()

                    // Show a success banner. If they click it, take them to the Trash screen.
                    scope.launch {
                        if (snackbarHostState.showSnackbar("Moved to Trash", "View Trash", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            onNavigateToTrash()
                        }
                    }
                }
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    // --- THE SCAN ENGINE ---
    // This heavy worker runs in the background to analyze tens of thousands of photos.
    LaunchedEffect(scanTrigger, allMedia.isNotEmpty()) {
        if (allMedia.isNotEmpty() && scanState != ScanState.SCANNING) {
            scanState = ScanState.SCANNING
            scannedCount = 0
            cancelScan = false
            selectedIds.clear()

            // Send the worker to the warehouse
            val results = withContext(Dispatchers.IO) {
                val foundGroups = mutableListOf<DuplicateGroup>()
                val processedIds = mutableSetOf<Long>()
                var processed = 0
                val mediaSnapshot = allMedia.toList()

                // Step 1: Filter out tiny system icons and videos. We only want normal photos.
                val candidates = mediaSnapshot.filter { it.size > 51200L && !it.isVideo }

                // Step 2: The Quick Filter. If two photos have different file sizes, they CANNOT be exact duplicates!
                // This instantly filters out 99% of the gallery without doing any heavy math.
                val sizeGroups = candidates.groupBy { it.size }.filter { it.value.size > 1 }

                // Step 3: Exact Matches (Photos)
                // For the ones with identical sizes, we read pieces of the file (Partial Hash) to see if the pixels match.
                sizeGroups.values.chunked(200).forEach { batch ->
                    for (potentialDuplicates in batch) {
                        if (cancelScan) return@withContext emptyList<DuplicateGroup>()
                        processed += potentialDuplicates.size
                        withContext(Dispatchers.Main) { scannedCount = processed } // Update progress bar
                        yield() // Let the phone breathe so it doesn't freeze

                        val hashGroups = potentialDuplicates.groupBy { calculatePartialHash(it.path) }
                        hashGroups.values.filter { it.size > 1 }.forEach { group ->
                            val sorted = group.sortedByDescending { it.size + it.dateAdded }
                            val wasted = group.drop(1).sumOf { it.size }
                            foundGroups.add(DuplicateGroup(sorted, MatchType.EXACT, wasted))
                            processedIds.addAll(group.map { it.id }) // Mark them as processed so we don't scan them again
                        }
                    }
                }

                // Step 4: Video Duplicates
                // Videos are huge, so we just compare their Length + Resolution + File Size + Quick Hash.
                val videoCandidates = mediaSnapshot.filter { it.isVideo && !processedIds.contains(it.id) }
                val videoGroups = videoCandidates.groupBy {
                    val partialVideoHash = calculatePartialHash(it.path)
                    "${it.duration}_${it.width}_${it.height}_${it.size}_$partialVideoHash"
                }

                videoGroups.values.filter { it.size > 1 }.forEach { group ->
                    if (cancelScan) return@withContext emptyList<DuplicateGroup>()
                    val sorted = group.sortedByDescending { it.size + it.dateAdded }
                    val wasted = group.drop(1).sumOf { it.size }
                    foundGroups.add(DuplicateGroup(sorted, MatchType.VIDEO, wasted))
                    processedIds.addAll(group.map { it.id })
                    processed += group.size
                    withContext(Dispatchers.Main) { scannedCount = processed }
                }

                // Step 5: Similar Burst Shots
                // This is the hardest part. It looks for photos taken within seconds of each other.
                val remaining = candidates.filter { !processedIds.contains(it.id) }.sortedBy { it.dateAdded }
                if (remaining.isNotEmpty()) {
                    val hashCache = mutableMapOf<Long, Long>() // Memory box to store math so we don't do it twice

                    // `averageHash` literally shrinks a photo to 8x8 pixels, turns it black and white, and creates a simple math signature for it.
                    fun getHash(item: MediaItem): Long? {
                        if (hashCache.containsKey(item.id)) return hashCache[item.id]
                        val h = averageHash(item.path)
                        if (h != null) hashCache[item.id] = h
                        return h
                    }

                    var currentBurst = mutableListOf(remaining.first())
                    for (i in 1 until remaining.size) {
                        if (cancelScan) return@withContext emptyList<DuplicateGroup>()

                        val curr = remaining[i]
                        val anchor = currentBurst.first()
                        val timeDiff = abs(curr.dateAdded - anchor.dateAdded)

                        // If it has "(1)" or "copy" in the file name, it's definitely a duplicate!
                        val nameMatch = curr.name.contains("edit", true) || curr.name.contains("copy", true) || curr.name.contains("(1)")
                        var isSimilar = false

                        // If it was taken within 5 seconds...
                        if (timeDiff < burstTimeWindowSec || nameMatch) {
                            val hAnchor = getHash(anchor)
                            val hCurr = getHash(curr)
                            // We compare the two 8x8 black and white signatures. If they are more than 90% identical, it's a Similar Burst!
                            if (hAnchor != null && hCurr != null && hammingDistance(hAnchor, hCurr) <= 10) {
                                isSimilar = true
                            }
                        }

                        if (isSimilar) {
                            currentBurst.add(curr)
                        } else {
                            if (currentBurst.size > 1) {
                                foundGroups.add(DuplicateGroup(currentBurst.sortedBy { it.dateAdded }, MatchType.SIMILAR, currentBurst.drop(1).sumOf { it.size }))
                            }
                            currentBurst = mutableListOf(curr) // Start a new group
                        }

                        processed++
                        if (processed % 10 == 0) {
                            withContext(Dispatchers.Main) { scannedCount = processed }
                            yield()
                        }
                    }
                    if (currentBurst.size > 1) {
                        foundGroups.add(DuplicateGroup(currentBurst.sortedBy { it.dateAdded }, MatchType.SIMILAR, currentBurst.drop(1).sumOf { it.size }))
                    }
                }
                // Sort the groups so the ones wasting the most Gigabytes of space are at the top of the screen!
                foundGroups.sortedByDescending { it.wastedSize }
            }
            // Update the screen when finished
            if (!cancelScan) {
                allGroups = results
                scanState = ScanState.COMPLETE
            } else {
                scanState = ScanState.CANCELLED
            }
        } else if (allMedia.isEmpty()) {
            scanState = ScanState.COMPLETE
        }
    }

    // Get only the groups that belong to the tab the user currently clicked
    val displayedGroups = remember(allGroups, activeTab) { allGroups.filter { it.type == activeTab } }

    // Android back-button handlers
    BackHandler(enabled = showDeleteConfirm) { showDeleteConfirm = false }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds.clear() }
    BackHandler(enabled = scanState == ScanState.SCANNING) { cancelScan = true }

    // --- DRAWING THE SCREEN ---
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Cleaner", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (scanState == ScanState.COMPLETE) {
                                Text("${allGroups.size} groups found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        if (scanState == ScanState.SCANNING) {
                            TextButton(onClick = { cancelScan = true }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        } else if (scanState == ScanState.COMPLETE && displayedGroups.isNotEmpty()) {
                            // "Auto Select" Button
                            // This smartly selects every duplicate photo, leaving exactly 1 "Best" original unselected in every group.
                            TextButton(onClick = {
                                selectedIds.clear()
                                displayedGroups.forEach { group ->
                                    group.items.drop(1).forEach { item -> selectedIds[item.id] = item.size }
                                }
                            }) {
                                Text("Auto Select", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // The 3 tabs at the top (Exact Copies, Similar Shots, Videos)
                if (scanState == ScanState.COMPLETE) {
                    ScrollableTabRow(
                        selectedTabIndex = when(activeTab) {
                            MatchType.EXACT -> 0
                            MatchType.SIMILAR -> 1
                            MatchType.VIDEO -> 2
                        },
                        edgePadding = 16.dp,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Tab(
                            selected = activeTab == MatchType.EXACT,
                            onClick = { activeTab = MatchType.EXACT; selectedIds.clear() },
                            text = { Text("Exact Copies") },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Tab(
                            selected = activeTab == MatchType.SIMILAR,
                            onClick = { activeTab = MatchType.SIMILAR; selectedIds.clear() },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Similar Shots")
                                }
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Tab(
                            selected = activeTab == MatchType.VIDEO,
                            onClick = { activeTab = MatchType.VIDEO; selectedIds.clear() },
                            text = { Text("Videos") },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            // The massive red "Delete" button that pops up from the bottom when you check a box
            AnimatedVisibility(visible = selectedIds.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(tonalElevation = 8.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Move ${selectedIds.size} items to Trash (${Formatter.formatShortFileSize(context, sizeToDelete)})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (scanState) {
                // If the background worker is currently scanning...
                ScanState.IDLE, ScanState.SCANNING -> {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Text("Analyzing library...", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text("Comparing $scannedCount of $totalToScan files", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { if (totalToScan > 0) scannedCount.toFloat() / totalToScan else 0f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                // If they hit cancel
                ScanState.CANCELLED -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Cancel, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Scan Cancelled", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { scanTrigger++ }) { Text("Restart Scan", color = MaterialTheme.colorScheme.primary) }
                    }
                }
                // When finished!
                ScanState.COMPLETE -> {
                    if (displayedGroups.isEmpty()) {
                        EmptyDuplicatesState(activeTab) // Pretty empty illustration
                    } else {
                        // The scrolling list of duplicate groups
                        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
                            // Info banner at the top
                            item {
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (activeTab == MatchType.EXACT) Icons.Outlined.ContentCopy else Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(if (activeTab == MatchType.EXACT) "Duplicate Storage Found" else "Similar Shots Storage", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                            Text("You can save up to ${Formatter.formatShortFileSize(context, displayedGroups.sumOf { it.wastedSize })}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }

                            // Draw each group of matching photos
                            items(displayedGroups) { group ->
                                DuplicateGroupItem(
                                    group = group,
                                    selectedIds = selectedIds,
                                    onToggleSelection = { id, size -> if (selectedIds.containsKey(id)) selectedIds.remove(id) else selectedIds[id] = size },
                                    onSelectAllInGroup = {
                                        // Helper button that checks every box EXCEPT the first (best) photo in this specific group.
                                        val originalId = group.items.first().id
                                        val others = group.items.filter { it.id != originalId }
                                        if (others.all { selectedIds.containsKey(it.id) }) {
                                            others.forEach { selectedIds.remove(it.id) }
                                        } else {
                                            others.forEach { if (!selectedIds.containsKey(it.id)) selectedIds[it.id] = it.size }
                                        }
                                    }
                                )
                                // Thick divider line between groups
                                HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Popup confirming deletion
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Move to Trash?", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("Move ${selectedIds.size} items to the Trash? You can restore or permanently delete them from there within 30 days. The smart-selected 'Best' versions will remain safe.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        val idsToDelete = selectedIds.keys.toSet()
                        if (idsToDelete.isNotEmpty()) {
                            val itemsToTrash = allMedia.filter { idsToDelete.contains(it.id) }
                            trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                        }
                        showDeleteConfirm = false
                    }
                ) { Text("Move to Trash") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) } },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

/**
 * The horizontal scrolling row that holds all the identical photos for one specific group.
 */
@RequiresApi(Build.VERSION_CODES.ECLAIR)
@Composable
fun DuplicateGroupItem(group: DuplicateGroup, selectedIds: Map<Long, Long>, onToggleSelection: (Long, Long) -> Unit, onSelectAllInGroup: () -> Unit) {
    val context = LocalContext.current
    val bestItem = group.items.first() // The first item is always the largest/highest quality one

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        // Group Header text
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (bestItem.isVideo) "Video Group" else if (group.type == MatchType.SIMILAR) "Similar Burst" else "Exact Matches", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text("${group.items.size} copies • ${Formatter.formatShortFileSize(context, group.wastedSize)} wasted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSelectAllInGroup) {
                Text(if (group.items.drop(1).all { selectedIds.containsKey(it.id) }) "Deselect All" else "Select All", color = MaterialTheme.colorScheme.primary)
            }
        }

        // Horizontal list of the identical photos
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            items(group.items) { item ->
                val isSelected = selectedIds.containsKey(item.id)
                val isBest = item.id == bestItem.id // We prevent checking the box on the "Best" item.

                Column(modifier = Modifier.width(140.dp)) {
                    // The photo box
                    Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp)).clickable { if (!isBest) onToggleSelection(item.id, item.size) }) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(item.uri)
                                .size(400)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .memoryCacheKey("dup_${item.id}")
                                .diskCacheKey("dup_${item.id}")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .allowHardware(true)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Play icon for videos
                        if (item.isVideo) {
                            Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text(formatDuration(item.duration), color = Color.White, fontSize = 10.sp)
                            }
                        }

                        // The checkmark. (Hidden entirely if this is the "Best" photo)
                        if (!isBest) {
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.scrim.copy(0.4f), CircleShape).border(1.dp, Color.White, CircleShape)) {
                                Icon(if (isSelected) Icons.Default.Check else Icons.Outlined.Circle, null, tint = if (isSelected) MaterialTheme.colorScheme.onError else Color.Transparent, modifier = Modifier.padding(2.dp).size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Exact file size and date below the picture
                    Text(Formatter.formatShortFileSize(context, item.size), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(remember(item.dateAdded) { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.dateAdded * 1000)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// Pretty illustration when there are no duplicates found
@Composable
fun EmptyDuplicatesState(type: MatchType) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if(type == MatchType.EXACT) Icons.Outlined.CheckCircle else Icons.Outlined.ImageSearch, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text(if(type == MatchType.EXACT) "No Exact Duplicates" else "No Similar Photos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Your gallery is optimized!", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * =========================================================================================
 * 🚚 THE MAILROOM (MoveCopyScreen)
 * =========================================================================================
 * This screen lets the user select an Album folder to Move or Copy photos into.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveCopyScreen(
    operationMode: OperationMode,
    selectedMediaIds: List<Long>,
    sourceAlbumId: String? = null,
    viewModel: GalleryViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOperationComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val albums by viewModel.albumsState.collectAsState(initial = emptyList()) // The list of destination folders
    val fileOpState by viewModel.fileOperationState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) } // Popup to create a brand new folder
    var targetAlbumForConfirmation by remember { mutableStateOf<Album?>(null) } // Popup asking "Are you sure?"

    // The progress bar status
    var isProcessing by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var processedCount by remember { mutableIntStateOf(0) }
    var isWaitingForPermission by remember { mutableStateOf(false) }
    val totalCount = selectedMediaIds.size

    LaunchedEffect(Unit) {
        viewModel.forceSync()
    }

    // Listens to the Manager to update the progress bar as the 5,000 files are copied byte-by-byte
    LaunchedEffect(fileOpState) {
        when (val state = fileOpState) {
            is FileOperationState.Processing -> {
                isProcessing = true
                isWaitingForPermission = false
                currentProgress = state.progressPercentage
                processedCount = state.itemsProcessed
            }
            is FileOperationState.WaitingForPermission -> {
                isProcessing = true
                isWaitingForPermission = true // The process paused to ask the user a system question ("Allow Move?")
            }
            is FileOperationState.Idle -> {
                // If it was processing and is now Idle, we are done!
                if (isProcessing && !isWaitingForPermission) {
                    currentProgress = 0f
                    processedCount = 0
                    isProcessing = false
                    onOperationComplete()
                }
            }
            else -> {}
        }
    }

    // Permission Bouncer
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        viewModel.onPermissionResult(isGranted)
        if (!isGranted) {
            isProcessing = false
            isWaitingForPermission = false
            Toast.makeText(context, "Permission denied. Action aborted.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())

                is GalleryEvent.OperationSuccess -> {
                    isProcessing = false
                    isWaitingForPermission = false
                    onOperationComplete() // Leave the screen
                }

                is GalleryEvent.ShowToast -> {
                    val msg = event.message.lowercase()
                    if (msg.contains("failed") || msg.contains("error") || msg.contains("cancelled")) {
                        isProcessing = false
                        isWaitingForPermission = false
                    }
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    // Don't let them hit the back button and break the app while files are currently copying!
    BackHandler(enabled = !isProcessing) { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (operationMode == OperationMode.MOVE) "Move to album" else "Copy to album", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isProcessing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // "Create Folder" button in the top right
                    IconButton(onClick = { showCreateDialog = true }, enabled = !isProcessing) {
                        Icon(Icons.Default.CreateNewFolder, "New Album")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // A 3-column grid of all the folders on the phone
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Big '+' button to make a new folder
                    CreateNewAlbumTile(onClick = { if (!isProcessing) showCreateDialog = true })
                }
                items(
                    // Filter out "Virtual" folders (like 'Recent' or 'Favorites') because they aren't physical places you can move files to.
                    albums.filter { !it.id.startsWith("virtual_") && it.id != sourceAlbumId }.sortedBy { it.name.lowercase() },
                    key = { it.id }
                ) { album ->
                    // Dim the folder the photos are currently inside
                    val isSource = sourceAlbumId != null && album.id == sourceAlbumId
                    Box(modifier = Modifier.graphicsLayer { alpha = if (isSource) 0.4f else 1f }) {
                        AlbumTargetTile(
                            album = album,
                            onClick = {
                                if (!isProcessing) {
                                    if (isSource) Toast.makeText(context, "Source and destination are the same", Toast.LENGTH_SHORT).show()
                                    else targetAlbumForConfirmation = album // Show the confirmation popup
                                }
                            }
                        )
                    }
                }
            }

            // The massive Progress Bar overlay that locks the screen while copying
            AnimatedVisibility(visible = isProcessing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                // Clickable(enabled=false) prevents touches from passing through the black background
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.fillMaxWidth(0.85f), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isWaitingForPermission) "Waiting for permission..." else if (operationMode == OperationMode.MOVE) "Moving items..." else "Copying items...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            if (!isWaitingForPermission) {
                                LinearProgressIndicator(progress = { currentProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                                Spacer(Modifier.height(8.dp))

                                val percent = (currentProgress * 100).toInt()
                                val actionVerb = if (operationMode == OperationMode.MOVE) "Moving" else "Copying"
                                Text(
                                    text = "$actionVerb $processedCount / $totalCount ($percent%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                            } else {
                                // Waiting on the user to hit "Allow" on the system popup
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(24.dp))
                            TextButton(onClick = { viewModel.cancelCurrentOperation(); isProcessing = false; isWaitingForPermission = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("Cancel", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Popup
    if (targetAlbumForConfirmation != null) {
        val album = targetAlbumForConfirmation!!
        val actionVerb = if (operationMode == OperationMode.MOVE) "Move" else "Copy"
        AlertDialog(
            onDismissRequest = { targetAlbumForConfirmation = null },
            title = { Text("$actionVerb to \"${album.name}\"?") },
            text = { Text("$actionVerb ${selectedMediaIds.size} items.") },
            confirmButton = {
                Button(onClick = {
                    targetAlbumForConfirmation = null
                    isProcessing = true // Lock the screen
                    if (operationMode == OperationMode.MOVE) viewModel.moveData(selectedMediaIds, album.id)
                    else viewModel.copyData(selectedMediaIds, album.id)
                }) { Text(actionVerb) }
            },
            dismissButton = { TextButton(onClick = { targetAlbumForConfirmation = null }) { Text("Cancel") } }
        )
    }

    // Create New Folder Popup
    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create album") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Album name") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) {
                        showCreateDialog = false
                        isProcessing = true
                        if (operationMode == OperationMode.MOVE) viewModel.createAndMove(selectedMediaIds, newName) // Make folder, then Move
                        else viewModel.createAndCopy(selectedMediaIds, newName) // Make folder, then Copy
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}

// Visual drawing for a destination folder
@Composable
fun AlbumTargetTile(album: Album, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            // Put a tiny preview picture of the folder
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).crossfade(true).size(400).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.mediaCount}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

// Visual drawing for the "+" New Folder button
@Composable
fun CreateNewAlbumTile(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, "Create", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Create album", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * =========================================================================================
 * 📌 THE SIMPLE PICKER
 * =========================================================================================
 * This is used when the Video Editor or Live Wallpaper screen just needs the user to quickly select ONE photo/video.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerScreen(viewModel: GalleryViewModel = hiltViewModel(), onBack: () -> Unit, onMediaSelected: (MediaItem) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var gridCount by rememberSaveable { mutableIntStateOf(3) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val animatedGridCount by animateIntAsState(targetValue = gridCount, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "GridCountAnim")

    val allMedia by viewModel.media.collectAsState(initial = emptyList())
    // Local filter instead of using the heavy database search
    val filteredMedia = remember(allMedia, searchQuery) { if (searchQuery.isNotEmpty()) allMedia.filter { it.name.contains(searchQuery, true) } else allMedia }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GalleryPickerTopBar(isSearchActive = isSearchActive, searchQuery = searchQuery, onBack = onBack, onSearchQueryChange = { searchQuery = it }, onSearchToggle = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" })
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (filteredMedia.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, null, Modifier.size(64.dp), tint = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text(if (searchQuery.isNotEmpty()) "No results found" else "No media available", color = Color.Gray)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(animatedGridCount),
                    contentPadding = PaddingValues(bottom = 32.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    // Magic: Let the user pinch the screen with two fingers to change how many columns are in the grid!
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (abs(zoom - 1f) > 0.15f) {
                                val newCount = if (zoom > 1f) (gridCount - 1).coerceAtLeast(MIN_GRID_COLUMNS) else (gridCount + 1).coerceAtMost(MAX_GRID_COLUMNS)
                                if (newCount != gridCount) {
                                    gridCount = newCount
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    }
                ) {
                    items(filteredMedia, key = { it.id }) { media ->
                        GalleryPickerThumbnail(modifier = Modifier.animateItem(), media = media, gridCount = animatedGridCount, onClick = { onMediaSelected(media) })
                    }
                }
            }
        }
    }
}

// The tiny photo drawn inside the simple picker grid
@Composable
fun GalleryPickerThumbnail(modifier: Modifier = Modifier, media: MediaItem, gridCount: Int, onClick: () -> Unit) {
    Box(modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() }) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                // Ask for higher quality if there are fewer columns, lower quality if there are lots of columns.
                .size(when { gridCount <= 2 -> 600; gridCount <= 4 -> 400; else -> 200 })
                .precision(Precision.INEXACT)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (media.isVideo) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatPickerDuration(media.duration), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
        }
    }
}

// Top Bar for the Simple Picker
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerTopBar(isSearchActive: Boolean, searchQuery: String, onBack: () -> Unit, onSearchQueryChange: (String) -> Unit, onSearchToggle: () -> Unit) {
    TopAppBar(
        title = {
            if (isSearchActive) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search files...") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Select Media", fontWeight = FontWeight.Bold)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = onSearchToggle) { Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, null) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

/**
 * =========================================================================================
 * 🎞️ THE SLIDE PROJECTOR (SlideshowScreen)
 * =========================================================================================
 * An automatic presentation that flips through photos on a timer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowScreen(albumId: String?, viewModel: GalleryViewModel = hiltViewModel(), onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember { context.findActivityForSlideshow() }
    val allMedia by viewModel.media.collectAsState(initial = emptyList())
    var isShuffleEnabled by remember { mutableStateOf(false) }
    var slideDelayMs by remember { mutableLongStateOf(4000L) } // Default 4 seconds per photo

    // Get the right list of photos (either everything, or just the specific album) and remove videos.
    val slideshowItems = remember(allMedia, albumId, isShuffleEnabled) {
        val filtered = (if (albumId != null) allMedia.filter { it.bucketId == albumId } else allMedia).filter { !it.isVideo }
        if (isShuffleEnabled) filtered.shuffled() else filtered // Shuffle them up if requested
    }

    val pagerState = rememberPagerState(pageCount = { slideshowItems.size })
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isTouched by remember { mutableStateOf(false) } // Is the user touching the screen right now?
    var isAutoScrolling by remember { mutableStateOf(false) } // Is the app currently doing the sliding animation?

    // Force true full screen (Hide clock and battery)
    DisposableEffect(showControls) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (showControls) {
                controller.show(WindowInsetsCompat.Type.systemBars()) // Show clock if buttons are visible
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars()) // Hide it if watching photos
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose { activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    // Tell the Android OS: "Do not let the screen turn black and lock the phone while this is running!"
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // THE TICKING CLOCK
    // This loops continuously. It waits 4 seconds, then tells the Pager to slide to the next photo.
    LaunchedEffect(isPlaying, slideshowItems.size, slideDelayMs, isTouched) {
        if (!isPlaying || slideshowItems.isEmpty() || isTouched) return@LaunchedEffect
        while (isActive) {
            delay(slideDelayMs)
            if (!isActive || isTouched) break
            if (!pagerState.isScrollInProgress) {
                isAutoScrolling = true
                try {
                    // Start the 1.2 second long sliding animation to the next picture
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage + 1) % slideshowItems.size,
                        animationSpec = tween(1200, easing = FastOutSlowInEasing)
                    )
                } finally {
                    isAutoScrolling = false
                }
            }
        }
    }

    // If the user manually swipes to the next photo, pause the automatic slideshow.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress && !isAutoScrolling) {
            isPlaying = false
            showControls = true
        }
    }

    // If the user hasn't touched the screen in 3 seconds, hide the buttons and start playing again!
    LaunchedEffect(isTouched) {
        if (!isTouched && !isPlaying && !showSpeedMenu) {
            delay(3000)
            isPlaying = true
            showControls = false
        }
    }

    // If the buttons are visible, but the user does nothing for 3 seconds, hide the buttons.
    LaunchedEffect(showControls, showSpeedMenu) {
        if (showControls && !showSpeedMenu && !isTouched) {
            delay(3000)
            if (isPlaying) showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // If they touch the screen, show the pause button and menu
                awaitEachGesture {
                    awaitFirstDown()
                    isTouched = true
                    showControls = !showControls
                    if (showControls) isPlaying = false
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed }) // Wait for them to lift their finger
                    isTouched = false
                }
            }
    ) {
        if (slideshowItems.isEmpty()) {
            Text("No photos to show", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            // The Horizontal Pager handles the left/right swiping
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val safePage = page.coerceIn(0, slideshowItems.lastIndex)
                val item = slideshowItems[safePage]
                val scale = remember { Animatable(1f) } // "Ken Burns" effect scale

                // Math to calculate how to fade the photo out slightly as it slides off the screen
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val alphaValue = 1f - abs(pageOffset).coerceIn(0f, 1f)

                // The "Ken Burns Effect". This makes the photo very slowly zoom in while it is on screen, giving it a cinematic look.
                LaunchedEffect(pagerState.currentPage, isPlaying, isTouched) {
                    if (pagerState.currentPage == page && isPlaying && !isTouched) {
                        scale.snapTo(1f) // Reset zoom
                        scale.animateTo(1.15f, tween(slideDelayMs.toInt(), easing = LinearEasing)) // Slowly zoom to 115% over 4 seconds
                    } else {
                        scale.snapTo(1f)
                    }
                }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .bitmapConfig(Bitmap.Config.RGB_565) // Use lower color quality to save massive amounts of RAM
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(1000) // 1 second fade between images
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop, // Fill the whole screen
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alphaValue }
                        .scale(scale.value)
                )
            }

            // The Bottom Controls (Play/Pause, Speed, Shuffle)
            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))).navigationBarsPadding().padding(vertical = 24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        // Shuffle Button
                        IconButton(onClick = { isShuffleEnabled = !isShuffleEnabled; isPlaying = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        // Huge Play/Pause Button
                        IconButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.size(72.dp).background(Color.White.copy(0.2f), CircleShape)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        // Speed Menu Button
                        Box {
                            IconButton(onClick = { showSpeedMenu = true; isPlaying = false }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Speed, "Speed", tint = Color.White)
                            }
                            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false; isPlaying = true }, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                listOf(2000L to "Fast (2s)", 4000L to "Normal (4s)", 6000L to "Slow (6s)", 10000L to "Very Slow (10s)").forEach { (delay, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(label, fontWeight = if (slideDelayMs == delay) FontWeight.Bold else FontWeight.Normal, color = if (slideDelayMs == delay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        },
                                        onClick = {
                                            slideDelayMs = delay // Update the speed timer!
                                            showSpeedMenu = false
                                            isPlaying = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // The Top Back Arrow
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(16.dp).size(48.dp).background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
    }
}

/**
 * =========================================================================================
 * ⚙️ DUPLICATE DETECTION MATH HELPERS
 * =========================================================================================
 */

// Reads the very beginning, middle, and end of a massive video file to generate a quick, unique signature.
private fun calculatePartialHash(filePath: String): String {
    return try {
        val file = File(filePath)
        val size = file.length()
        val chunkSize = 65536L // 64 Kilobytes
        val digest = MessageDigest.getInstance("MD5") // Math algorithm to generate the signature
        FileInputStream(file).use { fis ->
            val buf = ByteArray(chunkSize.toInt())
            var read = fis.read(buf) // Read the beginning
            if (read > 0) digest.update(buf, 0, read)
            if (size > chunkSize * 2) {
                fis.channel.position(size / 2) // Jump to the middle
                read = fis.read(buf)
                if (read > 0) digest.update(buf, 0, read)
            }
            if (size > chunkSize) {
                fis.channel.position(size - chunkSize) // Jump to the end
                read = fis.read(buf)
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } // Spits out the signature like "a1b2c3d4e5f6"
    } catch (e: Exception) {
        "err_${System.nanoTime()}"
    }
}

// Shrinks a picture to exactly 8x8 pixels, turns it black and white, and creates a 64-bit mathematical number.
private fun averageHash(path: String): Long? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, 8, 8)
        options.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(path, options) ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        bmp.recycle() // Throw away the big picture to save RAM

        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        scaled.recycle()

        var sum = 0L
        val grays = IntArray(64)
        // Convert colors to grayscale based on how human eyes perceive brightness
        for (i in 0 until 64) {
            val p = pixels[i]
            val gray = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
            grays[i] = gray
            sum += gray
        }
        val avg = sum / 64
        var hash = 0L
        // If a pixel is brighter than average, mark it as a 1. If darker, a 0.
        for (i in 0 until 64) {
            if (grays[i] >= avg) hash = hash or (1L shl i)
        }
        hash
    } catch (e: Exception) { null }
}

// Math that compares two signatures. If the distance is 0, the photos are identical. If it's 3, they are 95% identical (a burst shot).
private fun hammingDistance(h1: Long, h2: Long): Int = java.lang.Long.bitCount(h1 xor h2)

// A math helper function. Calculates how much we need to "shrink" an image so it fits in memory.
private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
    }
    return inSampleSize
}

// Converts raw milliseconds into "1:05" for the simple picker screen.
private fun formatPickerDuration(durationMs: Long): String {
    val s = (durationMs / 1000) % 60
    val m = (durationMs / (1000 * 60)) % 60
    val h = (durationMs / (1000 * 60 * 60))
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

// Unwraps the Russian Nesting Dolls to find the main Activity screen for the Slideshow.
private fun Context.findActivityForSlideshow(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}