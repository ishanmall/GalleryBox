@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "UnsafeOptInUsageError")
package com.gallerybox.ui.screens.file

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
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.content.Intent

enum class MatchType { EXACT, SIMILAR, VIDEO }
private enum class ScanState { IDLE, SCANNING, COMPLETE, CANCELLED }
enum class OperationMode { MOVE, COPY }

data class DuplicateGroup(val items: List<MediaItem>, val type: MatchType, val wastedSize: Long)

private const val MIN_GRID_COLUMNS = 1
private const val MAX_GRID_COLUMNS = 8

@Composable
fun sharedGalleryViewModel(): GalleryViewModel {
    val owner = LocalContext.current.findActivityForSlideshow() as androidx.activity.ComponentActivity
    return hiltViewModel(owner)
}

@RequiresApi(Build.VERSION_CODES.ECLAIR)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    viewModel: GalleryViewModel = sharedGalleryViewModel(),
    trashViewModel: TrashViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToTrash: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allMedia by viewModel.media.collectAsState()

    var scanState by remember { mutableStateOf(ScanState.IDLE) }
    var allGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var activeTab by remember { mutableStateOf(MatchType.EXACT) }
    var cancelScan by remember { mutableStateOf(false) }

    val selectedIds = remember { mutableStateMapOf<Long, Long>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableIntStateOf(0) }
    val totalToScan = allMedia.size

    val sizeToDelete: Long by remember(selectedIds.size) { derivedStateOf { selectedIds.values.sumOf { it } } }
    var scanTrigger by remember { mutableIntStateOf(1) }

    val burstTimeWindowSec = 5L

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(isGranted)
        if (!isGranted) Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(trashViewModel, lifecycleOwner) {
        trashViewModel.onRefreshGallery = { scope.launch { viewModel.forceSync() } }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            trashViewModel.events.collectLatest { event ->
                when (event) {
                    is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.OperationSuccess -> {
                        val idsToDelete = selectedIds.keys.toList()
                        allGroups = allGroups.mapNotNull { group ->
                            val kept = group.items.filter { !idsToDelete.contains(it.id) }
                            if (kept.size > 1) group.copy(items = kept, wastedSize = kept.drop(1).sumOf { it.size }) else null
                        }
                        selectedIds.clear()

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
    }

    LaunchedEffect(scanTrigger, allMedia.isNotEmpty()) {
        if (allMedia.isNotEmpty() && scanState != ScanState.SCANNING) {
            scanState = ScanState.SCANNING
            scannedCount = 0
            cancelScan = false
            selectedIds.clear()

            val results = withContext(Dispatchers.IO) {
                val foundGroups = mutableListOf<DuplicateGroup>()
                val processedIds = mutableSetOf<Long>()
                var processed = 0
                val mediaSnapshot = allMedia.toList()

                val candidates = mediaSnapshot.filter { it.size > 51200L && !it.isVideo }

                val sizeGroups = candidates.groupBy { it.size }.filter { it.value.size > 1 }

                sizeGroups.values.chunked(200).forEach { batch ->
                    for (potentialDuplicates in batch) {
                        if (cancelScan) return@withContext emptyList<DuplicateGroup>()
                        processed += potentialDuplicates.size
                        withContext(Dispatchers.Main) { scannedCount = processed }
                        yield()

                        val hashGroups = potentialDuplicates.groupBy { calculatePartialHash(context, it) }
                        hashGroups.values.filter { it.size > 1 }.forEach { group ->
                            val sorted = group.sortedByDescending { it.size + it.dateAdded }
                            val wasted = group.drop(1).sumOf { it.size }
                            foundGroups.add(DuplicateGroup(sorted, MatchType.EXACT, wasted))
                            processedIds.addAll(group.map { it.id })
                        }
                    }
                }

                val videoCandidates = mediaSnapshot.filter { it.isVideo && !processedIds.contains(it.id) }
                val videoGroups = videoCandidates.groupBy {
                    val partialVideoHash = calculatePartialHash(context, it)
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

                val remaining = candidates.filter { !processedIds.contains(it.id) }.sortedBy { it.dateAdded }
                if (remaining.isNotEmpty()) {
                    val hashCache = mutableMapOf<Long, Long>()

                    fun getHash(item: MediaItem): Long? {
                        if (hashCache.containsKey(item.id)) return hashCache[item.id]
                        val h = averageHash(context, item)
                        if (h != null) hashCache[item.id] = h
                        return h
                    }

                    var currentBurst = mutableListOf(remaining.first())
                    for (i in 1 until remaining.size) {
                        if (cancelScan) return@withContext emptyList<DuplicateGroup>()

                        val curr = remaining[i]
                        val anchor = currentBurst.first()
                        val timeDiff = abs(curr.dateAdded - anchor.dateAdded)

                        val nameMatch = curr.name.contains("edit", true) || curr.name.contains("copy", true) || curr.name.contains("(1)")
                        var isSimilar = false

                        if (timeDiff < burstTimeWindowSec || nameMatch) {
                            val hAnchor = getHash(anchor)
                            val hCurr = getHash(curr)
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
                            currentBurst = mutableListOf(curr)
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
                foundGroups.sortedByDescending { it.wastedSize }
            }
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

    val displayedGroups = remember(allGroups, activeTab) { allGroups.filter { it.type == activeTab } }

    BackHandler(enabled = showDeleteConfirm) { showDeleteConfirm = false }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds.clear() }
    BackHandler(enabled = scanState == ScanState.SCANNING) { cancelScan = true }

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
                ScanState.CANCELLED -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Cancel, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Scan Cancelled", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { scanTrigger++ }) { Text("Restart Scan", color = MaterialTheme.colorScheme.primary) }
                    }
                }
                ScanState.COMPLETE -> {
                    if (displayedGroups.isEmpty()) {
                        EmptyDuplicatesState(activeTab)
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
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

                            items(displayedGroups) { group ->
                                DuplicateGroupItem(
                                    group = group,
                                    selectedIds = selectedIds,
                                    onToggleSelection = { id, size -> if (selectedIds.containsKey(id)) selectedIds.remove(id) else selectedIds[id] = size },
                                    onSelectAllInGroup = {
                                        val originalId = group.items.first().id
                                        val others = group.items.filter { it.id != originalId }
                                        if (others.all { selectedIds.containsKey(it.id) }) {
                                            others.forEach { selectedIds.remove(it.id) }
                                        } else {
                                            others.forEach { if (!selectedIds.containsKey(it.id)) selectedIds[it.id] = it.size }
                                        }
                                    }
                                )
                                HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))
                            }
                        }
                    }
                }
            }
        }
    }

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

@RequiresApi(Build.VERSION_CODES.ECLAIR)
@Composable
fun DuplicateGroupItem(group: DuplicateGroup, selectedIds: Map<Long, Long>, onToggleSelection: (Long, Long) -> Unit, onSelectAllInGroup: () -> Unit) {
    val context = LocalContext.current
    val bestItem = group.items.first()

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (bestItem.isVideo) "Video Group" else if (group.type == MatchType.SIMILAR) "Similar Burst" else "Exact Matches", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text("${group.items.size} copies • ${Formatter.formatShortFileSize(context, group.wastedSize)} wasted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSelectAllInGroup) {
                Text(if (group.items.drop(1).all { selectedIds.containsKey(it.id) }) "Deselect All" else "Select All", color = MaterialTheme.colorScheme.primary)
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            items(group.items) { item ->
                val isSelected = selectedIds.containsKey(item.id)
                val isBest = item.id == bestItem.id

                Column(modifier = Modifier.width(140.dp)) {
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

                        if (item.isVideo) {
                            Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text(formatDuration(item.duration), color = Color.White, fontSize = 10.sp)
                            }
                        }

                        if (!isBest) {
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.scrim.copy(0.4f), CircleShape).border(1.dp, Color.White, CircleShape)) {
                                Icon(if (isSelected) Icons.Default.Check else Icons.Outlined.Circle, null, tint = if (isSelected) MaterialTheme.colorScheme.onError else Color.Transparent, modifier = Modifier.padding(2.dp).size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(Formatter.formatShortFileSize(context, item.size), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(remember(item.dateAdded) { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.dateAdded * 1000)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun EmptyDuplicatesState(type: MatchType) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if(type == MatchType.EXACT) Icons.Outlined.CheckCircle else Icons.Outlined.ImageSearch, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text(if(type == MatchType.EXACT) "No Exact Duplicates" else "No Similar Photos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Your gallery is optimized!", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveCopyScreen(
    operationMode: OperationMode,
    selectedMediaIds: List<Long>,
    sourceAlbumId: String? = null,
    viewModel: GalleryViewModel = sharedGalleryViewModel(),
    onBack: () -> Unit,
    onOperationComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val albums by viewModel.albumsState.collectAsState(initial = emptyList())

    var showCreateDialog by remember { mutableStateOf(false) }
    var targetAlbumForConfirmation by remember { mutableStateOf<Album?>(null) }

    val fileOpState by viewModel.fileOperationState.collectAsState()
    var opRequested by remember { mutableStateOf(false) }
    var opStarted by remember { mutableStateOf(false) }
    var opSucceeded by remember { mutableStateOf(false) }

    val isWaitingForPermission = fileOpState is FileOperationState.WaitingForPermission
    val isProcessing = opRequested || fileOpState !is FileOperationState.Idle
    val currentProgress = (fileOpState as? FileOperationState.Processing)?.progressPercentage ?: 0f
    val processedCount  = (fileOpState as? FileOperationState.Processing)?.itemsProcessed ?: 0
    val totalCount = selectedMediaIds.size

    LaunchedEffect(Unit) {
        viewModel.forceSync()
    }

    LaunchedEffect(fileOpState) {
        if (fileOpState is FileOperationState.Idle) {
            if (opStarted) {
                opStarted = false
                opRequested = false
                if (opSucceeded) {
                    onOperationComplete()
                }
                opSucceeded = false
            }
        } else {
            opStarted = true
        }
    }

    LaunchedEffect(opRequested) {
        if (!opRequested) return@LaunchedEffect
        delay(6000)
        if (!opStarted) {
            opRequested = false
            Toast.makeText(context, "Couldn't start — try again", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.fileOperationState.value is FileOperationState.WaitingForPermission)
                viewModel.abortFileOperation()
        }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data

        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            viewModel.saveSafTreeUri(uri)
        } else {
            viewModel.onSafTreeGranted(null)
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onPermissionResult(result.resultCode == Activity.RESULT_OK)
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, "Permission denied. Action aborted.", Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                if (!opRequested && !opStarted) return@collect
                when (event) {
                    is GalleryEvent.RequestPermission ->
                        intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.LaunchIntent -> safTreeLauncher.launch(event.intent)
                    is GalleryEvent.OperationSuccess -> {
                        opSucceeded = true
                    }
                    is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler(enabled = true) { if (!isProcessing) onBack() }

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
                    IconButton(onClick = { showCreateDialog = true }, enabled = !isProcessing) {
                        Icon(Icons.Default.CreateNewFolder, "New Album")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    CreateNewAlbumTile(onClick = { if (!isProcessing) showCreateDialog = true })
                }
                items(
                    albums.filter { !it.id.startsWith("virtual_") && it.id != sourceAlbumId }.sortedBy { it.name.lowercase() },
                    key = { it.id }
                ) { album ->
                    val isSource = sourceAlbumId != null && album.id == sourceAlbumId
                    Box(modifier = Modifier.graphicsLayer { alpha = if (isSource) 0.4f else 1f }) {
                        AlbumTargetTile(
                            album = album,
                            onClick = {
                                if (!isProcessing) {
                                    if (isSource) Toast.makeText(context, "Source and destination are the same", Toast.LENGTH_SHORT).show()
                                    else targetAlbumForConfirmation = album
                                }
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isProcessing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
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
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(24.dp))
                            TextButton(
                                onClick = {
                                    opSucceeded = false
                                    viewModel.cancelCurrentOperation()
                                    opRequested = false
                                    opStarted = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

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
                    opSucceeded = false
                    opRequested = true
                    if (operationMode == OperationMode.MOVE) viewModel.moveData(selectedMediaIds, album.id)
                    else viewModel.copyData(selectedMediaIds, album.id)
                }) { Text(actionVerb) }
            },
            dismissButton = { TextButton(onClick = { targetAlbumForConfirmation = null }) { Text("Cancel") } }
        )
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create album") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Album name") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    val albumName = newName.trim()
                    if (albumName.isNotEmpty()) {
                        showCreateDialog = false
                        opSucceeded = false
                        opRequested = true
                        if (operationMode == OperationMode.MOVE) viewModel.createAndMove(selectedMediaIds, albumName)
                        else viewModel.createAndCopy(selectedMediaIds, albumName)
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AlbumTargetTile(album: Album, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).crossfade(true).size(400).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.mediaCount}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerScreen(viewModel: GalleryViewModel = sharedGalleryViewModel(), onBack: () -> Unit, onMediaSelected: (MediaItem) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var gridCount by rememberSaveable { mutableIntStateOf(3) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val animatedGridCount by animateIntAsState(targetValue = gridCount, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "GridCountAnim")

    val allMedia by viewModel.media.collectAsState(initial = emptyList())
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

@Composable
fun GalleryPickerThumbnail(modifier: Modifier = Modifier, media: MediaItem, gridCount: Int, onClick: () -> Unit) {
    Box(modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() }) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowScreen(albumId: String?, viewModel: GalleryViewModel = sharedGalleryViewModel(), onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember { context.findActivityForSlideshow() }
    val allMedia by viewModel.media.collectAsState(initial = emptyList())
    var isShuffleEnabled by remember { mutableStateOf(false) }
    var slideDelayMs by remember { mutableLongStateOf(4000L) }

    val slideshowItems = remember(allMedia, albumId, isShuffleEnabled) {
        val filtered = (if (albumId != null) allMedia.filter { it.bucketId == albumId } else allMedia).filter { !it.isVideo }
        if (isShuffleEnabled) filtered.shuffled() else filtered
    }

    val pagerState = rememberPagerState(pageCount = { slideshowItems.size })
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isTouched by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    DisposableEffect(showControls) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (showControls) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose { activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(isPlaying, slideshowItems.size, slideDelayMs, isTouched) {
        if (!isPlaying || slideshowItems.isEmpty() || isTouched) return@LaunchedEffect
        while (isActive) {
            delay(slideDelayMs)
            if (!isActive || isTouched) break
            if (!pagerState.isScrollInProgress) {
                isAutoScrolling = true
                try {
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

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress && !isAutoScrolling) {
            isPlaying = false
            showControls = true
        }
    }

    LaunchedEffect(isTouched) {
        if (!isTouched && !isPlaying && !showSpeedMenu) {
            delay(3000)
            isPlaying = true
            showControls = false
        }
    }

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
                awaitEachGesture {
                    awaitFirstDown()
                    isTouched = true
                    showControls = !showControls
                    if (showControls) isPlaying = false
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    isTouched = false
                }
            }
    ) {
        if (slideshowItems.isEmpty()) {
            Text("No photos to show", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val safePage = page.coerceIn(0, slideshowItems.lastIndex)
                val item = slideshowItems[safePage]
                val scale = remember { Animatable(1f) }

                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val alphaValue = 1f - abs(pageOffset).coerceIn(0f, 1f)

                LaunchedEffect(pagerState.currentPage, isPlaying, isTouched) {
                    if (pagerState.currentPage == page && isPlaying && !isTouched) {
                        scale.snapTo(1f)
                        scale.animateTo(1.15f, tween(slideDelayMs.toInt(), easing = LinearEasing))
                    } else {
                        scale.snapTo(1f)
                    }
                }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(1000)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alphaValue }
                        .scale(scale.value)
                )
            }

            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))).navigationBarsPadding().padding(vertical = 24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isShuffleEnabled = !isShuffleEnabled; isPlaying = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        IconButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.size(72.dp).background(Color.White.copy(0.2f), CircleShape)) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
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
                                            slideDelayMs = delay
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

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(16.dp).size(48.dp).background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
    }
}

private fun calculatePartialHash(context: Context, item: MediaItem): String {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            val buf = ByteArray(65536)
            var read = input.read(buf); if (read > 0) digest.update(buf, 0, read)
            if (item.size > 131072) { input.skip(item.size / 2 - 65536L)
                read = input.read(buf); if (read > 0) digest.update(buf, 0, read) }
        } ?: return "err_${item.id}"
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "err_${item.id}" }
}

private fun averageHash(context: Context, item: MediaItem): Long? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val resolver = context.contentResolver
        resolver.openInputStream(item.uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
        options.inSampleSize = calculateInSampleSize(options, 8, 8)
        options.inJustDecodeBounds = false
        val bmp = resolver.openInputStream(item.uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) } ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        bmp.recycle()

        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        scaled.recycle()

        var sum = 0L
        val grays = IntArray(64)
        for (i in 0 until 64) {
            val p = pixels[i]
            val gray = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
            grays[i] = gray
            sum += gray
        }
        val avg = sum / 64
        var hash = 0L
        for (i in 0 until 64) {
            if (grays[i] >= avg) hash = hash or (1L shl i)
        }
        hash
    } catch (e: Exception) { null }
}

private fun hammingDistance(h1: Long, h2: Long): Int = java.lang.Long.bitCount(h1 xor h2)

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

private fun formatPickerDuration(durationMs: Long): String {
    val s = (durationMs / 1000) % 60
    val m = (durationMs / (1000 * 60)) % 60
    val h = (durationMs / (1000 * 60 * 60))
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun Context.findActivityForSlideshow(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}