@file:Suppress(
    "BlockingMethodInNonBlockingContext",
    "UNUSED_PARAMETER",
    "unused",
    "FunctionName",
    "MemberVisibilityCanBePrivate",
    "UnsafeOptInUsageError"
)

package com.gallerybox.ui.screens.file

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
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
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class MatchType { EXACT, SIMILAR, VIDEO }
private enum class ScanState { IDLE, SCANNING, COMPLETE, CANCELLED }
enum class OperationMode { MOVE, COPY }

data class DuplicateGroup(val items: List<MediaItem>, val type: MatchType, val wastedSize: Long)

private const val MIN_GRID_COLUMNS = 1
private const val MAX_GRID_COLUMNS = 8

@Composable
fun sharedGalleryViewModel(): GalleryViewModel {
    val activity = LocalContext.current.findActivityForSlideshow() as? ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}

// =========================================================================================
// 🧹 DUPLICATES & SIMILAR CLEANER SCREEN (Samsung One UI 7 Device Care Style)
// =========================================================================================
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
    val haptic = LocalHapticFeedback.current
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

    val sizeToDelete: Long by remember(selectedIds.size) {
        derivedStateOf { selectedIds.values.sumOf { it } }
    }
    var scanTrigger by remember { mutableIntStateOf(1) }

    val burstTimeWindowSec = 5L

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
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
                    is GalleryEvent.RequestPermission ->
                        intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    is GalleryEvent.OperationSuccess -> {
                        val idsToDelete = selectedIds.keys.toList()
                        allGroups = allGroups.mapNotNull { group ->
                            val kept = group.items.filter { !idsToDelete.contains(it.id) }
                            if (kept.size > 1) {
                                group.copy(items = kept, wastedSize = kept.drop(1).sumOf { it.size })
                            } else null
                        }
                        selectedIds.clear()

                        scope.launch {
                            if (snackbarHostState.showSnackbar(
                                    message = "Moved to Trash",
                                    actionLabel = "View Trash",
                                    duration = SnackbarDuration.Short
                                ) == SnackbarResult.ActionPerformed
                            ) {
                                onNavigateToTrash()
                            }
                        }
                    }
                    is GalleryEvent.ShowToast ->
                        Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
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
                            val wasted = sorted.drop(1).sumOf { it.size }
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
                    val wasted = sorted.drop(1).sumOf { it.size }
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

                        val nameMatch = curr.name.contains("edit", true) ||
                                curr.name.contains("copy", true) ||
                                curr.name.contains("(1)")
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
                                val sortedBurst = currentBurst.sortedByDescending { it.size + it.dateAdded }
                                foundGroups.add(
                                    DuplicateGroup(
                                        sortedBurst,
                                        MatchType.SIMILAR,
                                        sortedBurst.drop(1).sumOf { it.size }
                                    )
                                )
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
                        val sortedBurst = currentBurst.sortedByDescending { it.size + it.dateAdded }
                        foundGroups.add(
                            DuplicateGroup(
                                sortedBurst,
                                MatchType.SIMILAR,
                                sortedBurst.drop(1).sumOf { it.size }
                            )
                        )
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
    val exactCount = remember(allGroups) { allGroups.count { it.type == MatchType.EXACT } }
    val similarCount = remember(allGroups) { allGroups.count { it.type == MatchType.SIMILAR } }
    val videoCount = remember(allGroups) { allGroups.count { it.type == MatchType.VIDEO } }

    BackHandler(enabled = showDeleteConfirm) { showDeleteConfirm = false }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds.clear() }
    BackHandler(enabled = scanState == ScanState.SCANNING) { cancelScan = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "Duplicate Cleaner",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (scanState == ScanState.COMPLETE) {
                                    Text(
                                        text = "${allGroups.size} duplicate sets found",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                FilledTonalButton(
                                    onClick = { cancelScan = true },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    shape = CircleShape,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Stop", fontWeight = FontWeight.Bold)
                                }
                            } else if (scanState == ScanState.COMPLETE && displayedGroups.isNotEmpty()) {
                                val allDuplicatesSelected = remember(displayedGroups, selectedIds.size) {
                                    displayedGroups.all { grp -> grp.items.drop(1).all { selectedIds.containsKey(it.id) } }
                                }
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (allDuplicatesSelected) {
                                            selectedIds.clear()
                                        } else {
                                            selectedIds.clear()
                                            displayedGroups.forEach { group ->
                                                group.items.drop(1).forEach { item -> selectedIds[item.id] = item.size }
                                            }
                                        }
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (allDuplicatesSelected) Icons.Rounded.RemoveDone else Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (allDuplicatesSelected) "Deselect" else "Smart Select",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )

                    if (scanState == ScanState.COMPLETE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CleanerTabPill(
                                modifier = Modifier.weight(1f),
                                selected = activeTab == MatchType.EXACT,
                                title = "Exact",
                                count = exactCount,
                                onClick = { activeTab = MatchType.EXACT; selectedIds.clear() }
                            )
                            CleanerTabPill(
                                modifier = Modifier.weight(1f),
                                selected = activeTab == MatchType.SIMILAR,
                                title = "Similar",
                                count = similarCount,
                                onClick = { activeTab = MatchType.SIMILAR; selectedIds.clear() }
                            )
                            CleanerTabPill(
                                modifier = Modifier.weight(1f),
                                selected = activeTab == MatchType.VIDEO,
                                title = "Videos",
                                count = videoCount,
                                onClick = { activeTab = MatchType.VIDEO; selectedIds.clear() }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectedIds.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedIds.size} duplicates selected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Free up ${Formatter.formatShortFileSize(context, sizeToDelete)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Move to Trash", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (scanState) {
                ScanState.IDLE, ScanState.SCANNING -> {
                    val progressFraction = if (totalToScan > 0) (scannedCount.toFloat() / totalToScan).coerceIn(0f, 1f) else 0f
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                            CircularProgressIndicator(
                                progress = { progressFraction },
                                strokeWidth = 6.dp,
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            Text(
                                text = "${(progressFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Scanning Library for Duplicates...",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Analyzed $scannedCount of $totalToScan items",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ScanState.CANCELLED -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Scan Cancelled", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { scanTrigger++ }, shape = RoundedCornerShape(16.dp)) {
                            Text("Restart Scan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                ScanState.COMPLETE -> {
                    if (displayedGroups.isEmpty()) {
                        EmptyDuplicatesState(activeTab)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(18.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (activeTab == MatchType.EXACT) Icons.Outlined.ContentCopy else Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                text = "Recoverable Storage",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Text(
                                                text = "Save up to ${Formatter.formatShortFileSize(context, displayedGroups.sumOf { it.wastedSize })} while keeping the best copy of each set.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            items(displayedGroups) { group ->
                                DuplicateGroupItem(
                                    group = group,
                                    selectedIds = selectedIds,
                                    onToggleSelection = { id, size ->
                                        if (selectedIds.containsKey(id)) selectedIds.remove(id)
                                        else selectedIds[id] = size
                                    },
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
                            }

                            item {
                                Spacer(Modifier.height(96.dp))
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
            title = { Text("Move to Trash?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    "Move ${selectedIds.size} duplicate items to the Trash? The 'KEEP' best version of every group will remain untouched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        val idsToDelete = selectedIds.keys.toSet()
                        if (idsToDelete.isNotEmpty()) {
                            val itemsToTrash = allMedia.filter { idsToDelete.contains(it.id) }
                            trashViewModel.confirmPendingGalleryTrash(itemsToTrash)
                        }
                        showDeleteConfirm = false
                    }
                ) { Text("Move to Trash", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun CleanerTabPill(
    modifier: Modifier = Modifier,
    selected: Boolean,
    title: String,
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$count",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.ECLAIR)
@Composable
fun DuplicateGroupItem(
    group: DuplicateGroup,
    selectedIds: Map<Long, Long>,
    onToggleSelection: (Long, Long) -> Unit,
    onSelectAllInGroup: () -> Unit
) {
    val context = LocalContext.current
    val bestItem = group.items.first()
    val allDuplicatesInGroupSelected = group.items.drop(1).all { selectedIds.containsKey(it.id) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            bestItem.isVideo -> "Duplicate Videos"
                            group.type == MatchType.SIMILAR -> "Similar Burst Shots"
                            else -> "Exact Copies"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${group.items.size} items • ${Formatter.formatShortFileSize(context, group.wastedSize)} wasted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onSelectAllInGroup,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = if (allDuplicatesInGroupSelected) "Deselect" else "Select Duplicates",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(group.items, key = { it.id }) { item ->
                    val isSelected = selectedIds.containsKey(item.id)
                    val isBest = item.id == bestItem.id

                    Column(modifier = Modifier.width(142.dp)) {
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = when {
                                        isSelected -> 2.5.dp
                                        isBest -> 2.dp
                                        else -> 0.dp
                                    },
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.error
                                        isBest -> Color(0xFF4CAF50)
                                        else -> Color.Transparent
                                    },
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    if (!isBest) onToggleSelection(item.id, item.size)
                                }
                        ) {
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
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(formatDuration(item.duration), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (isBest) {
                                Surface(
                                    color = Color(0xD92E7D32),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "KEEP",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(24.dp)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.45f),
                                            CircleShape
                                        )
                                        .border(1.5.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onError,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = Formatter.formatShortFileSize(context, item.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = remember(item.dateAdded) {
                                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.dateAdded * 1000L))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyDuplicatesState(type: MatchType) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (type == MatchType.EXACT) Icons.Outlined.CheckCircle else Icons.Outlined.ImageSearch,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (type) {
                MatchType.EXACT -> "No Exact Duplicates"
                MatchType.SIMILAR -> "No Similar Shots"
                MatchType.VIDEO -> "No Duplicate Videos"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your gallery storage is clean and optimized!",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =========================================================================================
// 📂 MOVE & COPY DESTINATION SCREEN (Supports Internal & SD Card Albums)
// =========================================================================================
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
    val snackbarHostState = remember { SnackbarHostState() }
    val albums by viewModel.albumsState.collectAsState(initial = emptyList())
    val allMedia by viewModel.media.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var targetAlbumForConfirmation by remember { mutableStateOf<Album?>(null) }

    val fileOpState by viewModel.fileOperationState.collectAsState()
    var opRequested by remember { mutableStateOf(false) }
    var opStarted by remember { mutableStateOf(false) }
    var opSucceeded by remember { mutableStateOf(false) }

    val isWaitingForPermission = fileOpState is FileOperationState.WaitingForPermission
    val isProcessing = opRequested || fileOpState !is FileOperationState.Idle
    val processingState = fileOpState as? FileOperationState.Processing
    val currentProgress = processingState?.progressPercentage ?: 0f
    val processedCount = processingState?.itemsProcessed ?: 0
    val currentPhase = processingState?.phase ?: if (operationMode == OperationMode.MOVE) "Moving" else "Copying"
    val totalCount = selectedMediaIds.size

    val sdCardAlbumIds = remember(allMedia.size, albums.size) {
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
            if (viewModel.fileOperationState.value is FileOperationState.WaitingForPermission) {
                viewModel.abortFileOperation()
            }
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

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
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
            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (operationMode == OperationMode.MOVE) "Move to Album" else "Copy to Album",
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${selectedMediaIds.size} item(s) selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !isProcessing) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showCreateDialog = true },
                            enabled = !isProcessing,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Icon(Icons.Rounded.CreateNewFolder, "New Album", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    CreateNewAlbumTile(onClick = { if (!isProcessing) showCreateDialog = true })
                }
                items(
                    albums.filter { !it.id.startsWith("virtual_") && it.id != sourceAlbumId }.sortedBy { it.name.lowercase() },
                    key = { it.id }
                ) { album ->
                    val isSource = sourceAlbumId != null && album.id == sourceAlbumId
                    val isSdCard = sdCardAlbumIds.contains(album.id)
                    Box(modifier = Modifier.graphicsLayer { alpha = if (isSource) 0.4f else 1f }) {
                        AlbumTargetTile(
                            album = album,
                            isSdCard = isSdCard,
                            onClick = {
                                if (!isProcessing) {
                                    if (isSource) {
                                        Toast.makeText(context, "Source and destination are the same", Toast.LENGTH_SHORT).show()
                                    } else {
                                        targetAlbumForConfirmation = album
                                    }
                                }
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.86f),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        shadowElevation = 16.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isWaitingForPermission) {
                                    "Waiting for Permission..."
                                } else {
                                    "$currentPhase Items..."
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(18.dp))
                            if (!isWaitingForPermission) {
                                LinearProgressIndicator(
                                    progress = { currentProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))

                                val percent = (currentProgress * 100).toInt().coerceIn(0, 100)
                                Text(
                                    text = "$currentPhase $processedCount / $totalCount ($percent%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(22.dp))
                            FilledTonalButton(
                                onClick = {
                                    opSucceeded = false
                                    viewModel.cancelCurrentOperation()
                                    opRequested = false
                                    opStarted = false
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Cancel Operation", fontWeight = FontWeight.Bold)
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
            icon = {
                Icon(
                    imageVector = if (operationMode == OperationMode.MOVE) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.FileCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("$actionVerb to \"${album.name}\"?") },
            text = { Text("$actionVerb ${selectedMediaIds.size} selected item(s) into this album.") },
            confirmButton = {
                Button(
                    onClick = {
                        targetAlbumForConfirmation = null
                        opSucceeded = false
                        opRequested = true
                        if (operationMode == OperationMode.MOVE) viewModel.moveData(selectedMediaIds, album.id)
                        else viewModel.copyData(selectedMediaIds, album.id)
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(actionVerb, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { targetAlbumForConfirmation = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            icon = { Icon(Icons.Rounded.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Create New Album") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Album Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val albumName = newName.trim()
                        if (albumName.isNotEmpty()) {
                            showCreateDialog = false
                            opSucceeded = false
                            opRequested = true
                            if (operationMode == OperationMode.MOVE) viewModel.createAndMove(selectedMediaIds, albumName)
                            else viewModel.createAndCopy(selectedMediaIds, albumName)
                        }
                    },
                    enabled = newName.isNotBlank(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Create & ${if (operationMode == OperationMode.MOVE) "Move" else "Copy"}", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun AlbumTargetTile(album: Album, isSdCard: Boolean = false, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier.aspectRatio(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.coverUri)
                        .crossfade(true)
                        .size(400)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isSdCard) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SdStorage,
                            contentDescription = "SD Card",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "${album.mediaCount} items",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun CreateNewAlbumTile(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Surface(
            modifier = Modifier.aspectRatio(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Create",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "New Album",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "Create folder",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// =========================================================================================
// 🖼️ GALLERY MEDIA PICKER SCREEN (2-Finger Pinch Zoom Without Blocking Vertical Scroll)
// =========================================================================================
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerScreen(
    viewModel: GalleryViewModel = sharedGalleryViewModel(),
    onBack: () -> Unit,
    onMediaSelected: (MediaItem) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var gridCount by rememberSaveable { mutableIntStateOf(3) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val animatedGridCount by animateIntAsState(
        targetValue = gridCount,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "GridCountAnim"
    )

    val allMedia by viewModel.media.collectAsState(initial = emptyList())
    val filteredMedia = remember(allMedia, searchQuery) {
        if (searchQuery.isNotEmpty()) allMedia.filter { it.name.contains(searchQuery, true) }
        else allMedia
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GalleryPickerTopBar(
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onBack = onBack,
                onSearchQueryChange = { searchQuery = it },
                onSearchToggle = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (filteredMedia.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, null, Modifier.size(64.dp), tint = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No results found" else "No media available",
                            color = Color.Gray
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(animatedGridCount.coerceAtLeast(1)),
                    contentPadding = PaddingValues(bottom = 32.dp, start = 4.dp, end = 4.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var cumulativeZoom = 1f
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        cumulativeZoom *= zoom
                                        if (abs(cumulativeZoom - 1f) > 0.22f) {
                                            val newCount = if (cumulativeZoom > 1f) {
                                                (gridCount - 1).coerceAtLeast(MIN_GRID_COLUMNS)
                                            } else {
                                                (gridCount + 1).coerceAtMost(MAX_GRID_COLUMNS)
                                            }
                                            if (newCount != gridCount) {
                                                gridCount = newCount
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            cumulativeZoom = 1f
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    items(filteredMedia, key = { it.id }) { media ->
                        GalleryPickerThumbnail(
                            modifier = Modifier.animateItem(),
                            media = media,
                            gridCount = animatedGridCount,
                            onClick = { onMediaSelected(media) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryPickerThumbnail(
    modifier: Modifier = Modifier,
    media: MediaItem,
    gridCount: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(media.uri)
                .size(
                    when {
                        gridCount <= 2 -> 600
                        gridCount <= 4 -> 400
                        else -> 200
                    }
                )
                .precision(Precision.INEXACT)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (media.isVideo) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)))
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(formatPickerDuration(media.duration), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPickerTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit
) {
    Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        TopAppBar(
            title = {
                if (isSearchActive) {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("Search files...") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Select Media", fontWeight = FontWeight.ExtraBold)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = onSearchToggle) {
                    Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

// =========================================================================================
// 🎞️ PICTURE SCREEN CINEMA SLIDESHOW (Samsung One UI 7 + Ken Burns Effect)
// =========================================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowScreen(
    albumId: String? = null,
    viewModel: GalleryViewModel = sharedGalleryViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val activity = remember { context.findActivityForSlideshow() }
    val processedMedia by viewModel.processedMedia.collectAsState(initial = emptyList())
    val allMedia by viewModel.media.collectAsState(initial = emptyList())
    var isShuffleEnabled by rememberSaveable { mutableStateOf(false) }
    var slideDelayMs by rememberSaveable { mutableLongStateOf(4000L) }

    val slideshowItems = remember(processedMedia, allMedia, albumId, isShuffleEnabled) {
        val baseSource = if (albumId == null && processedMedia.isNotEmpty()) processedMedia else allMedia
        val photosOnly = (if (albumId != null) baseSource.filter { it.bucketId == albumId } else baseSource)
            .filter { !it.isVideo }
        if (isShuffleEnabled) photosOnly.shuffled() else photosOnly
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
        onDispose {
            activity?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
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
                        animationSpec = tween(1100, easing = FastOutSlowInEasing)
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
            delay(3500)
            isPlaying = true
            showControls = false
        }
    }

    LaunchedEffect(showControls, showSpeedMenu) {
        if (showControls && !showSpeedMenu && !isTouched) {
            delay(3500)
            if (isPlaying) showControls = false
        }
    }

    val currentItem = slideshowItems.getOrNull(pagerState.currentPage.coerceIn(0, maxOf(0, slideshowItems.lastIndex)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
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
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.Slideshow, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("No photos available for slideshow", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
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

                LaunchedEffect(pagerState.currentPage, isPlaying, isTouched, slideDelayMs) {
                    if (pagerState.currentPage == page && isPlaying && !isTouched) {
                        scale.snapTo(1f)
                        scale.animateTo(1.14f, tween(slideDelayMs.toInt(), easing = LinearEasing))
                    } else {
                        scale.snapTo(1f)
                    }
                }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .bitmapConfig(Bitmap.Config.ARGB_8888)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(800)
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

            // --- TOP CINEMA HEADER ---
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(tween(180)) + slideInVertically { -it / 3 },
                exit = fadeOut(tween(150)) + slideOutVertically { -it / 3 },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            onClick = onBack,
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = currentItem?.name ?: "Slideshow",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            currentItem?.let {
                                Text(
                                    text = remember(it.dateAdded) {
                                        SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(it.dateAdded * 1000L))
                                    },
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${slideshowItems.size}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // --- BOTTOM GLASSMORPHIC CONTROL DOCK ---
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(tween(180)) + slideInVertically { it / 3 },
                exit = fadeOut(tween(150)) + slideOutVertically { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = Color(0xCC101218),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        shadowElevation = 14.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    isPlaying = true
                                },
                                modifier = Modifier.size(46.dp),
                                shape = CircleShape,
                                color = if (isShuffleEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f)
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = "Shuffle",
                                        tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val prev = (pagerState.currentPage - 1 + slideshowItems.size) % slideshowItems.size
                                        pagerState.animateScrollToPage(prev)
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(30.dp))
                            }

                            Surface(
                                onClick = { isPlaying = !isPlaying },
                                modifier = Modifier.size(62.dp),
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.20f),
                                border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.35f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val next = (pagerState.currentPage + 1) % slideshowItems.size
                                        pagerState.animateScrollToPage(next)
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(30.dp))
                            }

                            Box {
                                Surface(
                                    onClick = {
                                        showSpeedMenu = true
                                        isPlaying = false
                                    },
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Speed, "Speed", tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "${slideDelayMs / 1000}s",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = {
                                        showSpeedMenu = false
                                        isPlaying = true
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    listOf(
                                        2000L to "Fast (2s)",
                                        4000L to "Normal (4s)",
                                        6000L to "Slow (6s)",
                                        10000L to "Cinema (10s)"
                                    ).forEach { (delay, label) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = label,
                                                    fontWeight = if (slideDelayMs == delay) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (slideDelayMs == delay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
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
        }
    }
}

// =========================================================================================
// 🛠️ HASHING & IMAGE UTILITY FUNCTIONS
// =========================================================================================
private fun skipFully(input: InputStream, bytesToSkip: Long) {
    var remaining = bytesToSkip
    val discardBuffer = ByteArray(8192)
    while (remaining > 0L) {
        val skipped = input.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else {
            val read = input.read(discardBuffer, 0, minOf(discardBuffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }
}

private fun calculatePartialHash(context: Context, item: MediaItem): String {
    return try {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            val buf = ByteArray(65536)
            var read = input.read(buf)
            if (read > 0) digest.update(buf, 0, read)
            if (item.size > 131072L) {
                skipFully(input, (item.size / 2L) - 65536L)
                read = input.read(buf)
                if (read > 0) digest.update(buf, 0, read)
            }
        } ?: return "err_${item.id}"
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "err_${item.id}"
    }
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
        if (scaled != bmp) bmp.recycle()

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
    } catch (e: Exception) {
        null
    }
}

private fun hammingDistance(h1: Long, h2: Long): Int = java.lang.Long.bitCount(h1 xor h2)

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
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