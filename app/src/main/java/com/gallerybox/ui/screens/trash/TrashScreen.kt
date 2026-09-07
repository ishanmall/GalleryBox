@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.ui.screens.trash

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
import com.gallerybox.data.GalleryDao
import com.gallerybox.viewmodel.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random

enum class TrashMediaType { Image, Video, Audio, Story }

data class TrashUiItem(
    val id: Long,
    val originalPath: String,
    val contentUri: Uri,
    val name: String,
    val size: Long,
    val type: TrashMediaType,
    val deletedTimestamp: Long,
    val daysLeft: Int
)

sealed class TrashGridItem {
    data class Header(val title: String) : TrashGridItem()
    data class Media(val item: TrashUiItem) : TrashGridItem()
}

enum class TrashSort { NewestDeleted, OldestDeleted }
enum class TrashFilter { All, Images, Videos, Audio, Stories }

@RequiresApi(Build.VERSION_CODES.ECLAIR)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    trashViewModel: TrashViewModel = hiltViewModel(),
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var gridColumns by remember { mutableIntStateOf(prefs.getInt("trash_grid_columns", 4)) }

    val trashEntities by galleryViewModel.trashBin.collectAsState(initial = emptyList())
    val isGalleryBusy by galleryViewModel.isBusy.collectAsState(initial = false)
    val operationProgress by trashViewModel.operationProgress.collectAsState()

    val isBusy = isGalleryBusy || operationProgress != null

    LaunchedEffect(Unit) {
        trashViewModel.onRefreshGallery = { galleryViewModel.forceSync() }
        trashViewModel.onRefreshMusic = { musicViewModel.loadAllAudioTracks() }

        galleryViewModel.refreshData()
        musicViewModel.loadAllAudioTracks()
    }

    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTime = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                daysLeft = trashViewModel.calculateDaysLeft(entity.deletedTimestamp)
            )
        }
    }

    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var showEmptySheet by remember { mutableStateOf(false) }
    var showDeleteSheet by remember { mutableStateOf(false) }
    var showGridSheet by remember { mutableStateOf(false) }
    var itemForDetails by remember { mutableStateOf<TrashUiItem?>(null) }

    val emptySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deleteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var currentSort by remember { mutableStateOf(TrashSort.NewestDeleted) }
    var currentFilter by remember { mutableStateOf(TrashFilter.All) }

    LaunchedEffect(currentFilter) {
        if (isEditMode) selectedIds = emptySet()
    }

    val finalTrashItems = remember(trashUiItems, currentSort, currentFilter) {
        val filtered = when (currentFilter) {
            TrashFilter.All -> trashUiItems
            TrashFilter.Images -> trashUiItems.filter { it.type == TrashMediaType.Image }
            TrashFilter.Videos -> trashUiItems.filter { it.type == TrashMediaType.Video }
            TrashFilter.Audio -> trashUiItems.filter { it.type == TrashMediaType.Audio }
            TrashFilter.Stories -> trashUiItems.filter { it.type == TrashMediaType.Story }
        }

        when (currentSort) {
            TrashSort.NewestDeleted -> filtered.sortedByDescending { it.deletedTimestamp }
            TrashSort.OldestDeleted -> filtered.sortedBy { it.deletedTimestamp }
        }
    }

    val flattenedGridItems = remember(finalTrashItems) {
        val grouped = finalTrashItems.groupBy {
            when {
                it.daysLeft <= 0 -> "Expired"
                it.daysLeft <= 3 -> "Expiring Soon"
                it.daysLeft <= 7 -> "This Week"
                else -> "Later"
            }
        }

        val list = mutableListOf<TrashGridItem>()
        listOf("Expired", "Expiring Soon", "This Week", "Later").forEach { key ->
            grouped[key]?.let { groupItems ->
                if (groupItems.isNotEmpty()) {
                    list.add(TrashGridItem.Header(key))
                    groupItems.forEach { item ->
                        list.add(TrashGridItem.Media(item))
                    }
                }
            }
        }
        list
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultGlobal(granted)
        if (!granted) {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(trashViewModel) {
        trashViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestPermission -> intentSenderLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is GalleryEvent.OperationSuccess -> {
                    isEditMode = false
                    selectedIds = emptySet()
                    itemForDetails?.let {
                        scope.launch { detailsSheetState.hide() }.invokeOnCompletion { itemForDetails = null }
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { snackbarHostState.showSnackbar("Operation Completed Successfully") }

                    galleryViewModel.refreshData()
                    musicViewModel.loadAllAudioTracks()
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(finalTrashItems.size) {
        if (finalTrashItems.isEmpty() && isEditMode) {
            isEditMode = false
            selectedIds = emptySet()
        }
    }

    BackHandler(enabled = itemForDetails != null) {
        scope.launch { detailsSheetState.hide() }.invokeOnCompletion { itemForDetails = null }
    }

    BackHandler(enabled = isEditMode && operationProgress == null) {
        isEditMode = false
        selectedIds = emptySet()
    }

    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val elevation by animateDpAsState(
                targetValue = if (scrollBehavior.state.overlappedFraction > 0.01f) 8.dp else 0.dp,
                label = "topBarElevation"
            )

            Surface(
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.shadow(elevation, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            ) {
                Column {
                    if (isBusy && operationProgress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    }
                    LargeTopAppBar(
                        title = {
                            Column {
                                if (isEditMode) {
                                    AnimatedContent(targetState = selectedIds.size, label = "SelectionCount") { count ->
                                        Text("✓ $count Selected", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("Trash Bin", fontWeight = FontWeight.Bold)
                                }
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
                                        isEditMode = false
                                        selectedIds = emptySet()
                                    } else {
                                        onBack()
                                    }
                                }
                            ) {
                                Icon(if (isEditMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (isEditMode) {
                                val allSelected = remember(selectedIds, finalTrashItems) {
                                    finalTrashItems.isNotEmpty() && selectedIds.size == finalTrashItems.size
                                }
                                IconButton(
                                    onClick = {
                                        if (allSelected) {
                                            selectedIds = emptySet()
                                        } else {
                                            selectedIds = finalTrashItems.map { it.id }.toSet()
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                        contentDescription = null,
                                        tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else if (finalTrashItems.isNotEmpty()) {
                                TextButton(onClick = { isEditMode = !isEditMode }) {
                                    Text("Select", fontWeight = FontWeight.Bold)
                                }

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

                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Grid Size") },
                                            leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                                            onClick = { showGridSheet = true; showMenu = false }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Empty Trash", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { showEmptySheet = true; showMenu = false }
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
                    if (scrollBehavior.state.overlappedFraction > 0.01f) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxSize()) {
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

                    if (!isEditMode) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TrashFilter.entries) { filter ->
                                val isSelected = currentFilter == filter
                                val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "ChipScale")
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
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

                if (finalTrashItems.isEmpty()) {
                    if (isBusy && operationProgress == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        EmptyTrashView(currentFilter)
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = flattenedGridItems,
                            span = { if (it is TrashGridItem.Header) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
                            key = { if (it is TrashGridItem.Header) "h_${it.title}" else (it as TrashGridItem.Media).item.id }
                        ) { item ->
                            when (item) {
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
                                                color = if (item.title == "Expiring Soon" || item.title == "Expired") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                                is TrashGridItem.Media -> {
                                    TrashTile(
                                        item = item.item,
                                        isSelected = selectedIds.contains(item.item.id),
                                        isEditMode = isEditMode,
                                        onClick = {
                                            if (isEditMode) {
                                                selectedIds = if (selectedIds.contains(item.item.id)) selectedIds - item.item.id else selectedIds + item.item.id
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else {
                                                itemForDetails = item.item
                                            }
                                        },
                                        onLongClick = {
                                            if (!isEditMode) {
                                                isEditMode = true
                                                selectedIds = selectedIds + item.item.id
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isEditMode && operationProgress == null,
                enter = slideInVertically(tween(250)) { it } + fadeIn(),
                exit = slideOutVertically(tween(250)) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val itemsToRestore = trashEntities.filter { selectedIds.contains(it.id) }
                            if (itemsToRestore.isNotEmpty()) {
                                trashViewModel.restoreTrashItems(itemsToRestore)
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isBusy && selectedIds.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restore", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showDeleteSheet = true },
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

            // Enhanced Progress Overlay for Bulk Operations
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
                            progress = { operationProgress ?: 0f },
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
                    items(8) { index ->
                        val col = index + 1
                        val isSelected = gridColumns == col
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable {
                                    gridColumns = col
                                    prefs.edit().putInt("trash_grid_columns", col).apply()
                                    scope.launch { gridSheetState.hide() }.invokeOnCompletion { showGridSheet = false }
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
                                trashViewModel.permanentlyDeleteTrash(trashEntities)
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
                                if (itemsToDel.isNotEmpty()) trashViewModel.permanentlyDeleteTrash(itemsToDel)
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

    if (itemForDetails != null) {
        val item = itemForDetails!!
        ModalBottomSheet(onDismissRequest = { itemForDetails = null }, sheetState = detailsSheetState) {
            Column(modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 40.dp).fillMaxWidth()) {
                Text("Metadata Specs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
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
                        TrashMediaType.Audio -> Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(56.dp))
                        TrashMediaType.Story -> Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))

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
                    Text("${Formatter.formatShortFileSize(context, item.size)} • ${if (item.daysLeft <= 0) "Expired" else "${item.daysLeft} days left"}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }

                Spacer(Modifier.height(24.dp))

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashTile(
    modifier: Modifier = Modifier,
    item: TrashUiItem,
    isSelected: Boolean,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scale by animateFloatAsState(if (isSelected) 0.97f else 1f, label = "Scale")
    val alpha by animateFloatAsState(if (isSelected) 0.20f else 0f, label = "Alpha")
    val height = remember(item.id) {
        if (item.type == TrashMediaType.Image) {
            listOf(110.dp, 130.dp, 150.dp).random(Random(item.id xor item.originalPath.hashCode().toLong()))
        } else {
            110.dp
        }
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(height)
            .padding(4.dp)
            .shadow(1.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when (item.type) {
            TrashMediaType.Image, TrashMediaType.Video -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.contentUri)
                        .size(350)
                        .memoryCacheKey("t_${item.id}")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
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

                Column(Modifier.fillMaxSize().padding(8.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Icon(iconRes, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
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

        if (isEditMode || alpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha)))
            if (isSelected) {
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

@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: GalleryDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TRASH_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val expiryThreshold = System.currentTimeMillis() - TRASH_EXPIRY_MS
            val allCandidates = dao.getOldestTrashItems(5000)

            if (allCandidates.isEmpty()) {
                return@withContext Result.success()
            }

            val deletedIds = mutableListOf<Long>()
            var deleteFailures = 0

            allCandidates.chunked(500).forEach { batch ->
                batch.forEachIndexed { index, trash ->
                    if (index % 50 == 0) yield()

                    val uri = runCatching { Uri.parse(trash.contentUri) }.getOrNull()
                    val exists = uri != null && mediaExists(uri)
                    val isExpired = trash.deletedTimestamp < expiryThreshold

                    when {
                        !exists -> {
                            deletedIds.add(trash.id)
                        }
                        isExpired -> {
                            try {
                                if (applicationContext.contentResolver.delete(uri!!, null, null) > 0) {
                                    deletedIds.add(trash.id)
                                } else {
                                    if (!mediaExists(uri)) {
                                        deletedIds.add(trash.id)
                                    } else {
                                        deleteFailures++
                                    }
                                }
                            } catch (e: Exception) {
                                deleteFailures++
                            }
                        }
                    }
                }

                if (deletedIds.isNotEmpty()) {
                    dao.deleteTrashItems(deletedIds)
                    deletedIds.clear()
                }
            }

            // Allow WorkManager to retry the job later if too many deletes failed (e.g. system constraint)
            if (deleteFailures > 100) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Result.success()
        }
    }

    private fun mediaExists(uri: Uri): Boolean {
        return try {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) },
                null
            )?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
fun EmptyTrashView(currentFilter: TrashFilter) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

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