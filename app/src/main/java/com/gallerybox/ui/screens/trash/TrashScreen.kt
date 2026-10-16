@file:Suppress("unused", "OPT_IN_USAGE", "UNCHECKED_CAST", "ObsoleteSdkInt", "DEPRECATION")

package com.gallerybox.ui.screens.trash

// --- IMPORTS ---
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// 🖼️ APP DATA & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.data.GalleryDao
import com.gallerybox.viewmodel.*

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.WindowWidthSize
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState

/**
 * =========================================================================================
 * 📦 TRASH DATA MODELS
 * =========================================================================================
 */
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

/**
 * =========================================================================================
 * 🗑️ THE MAIN TRASH SCREEN (Samsung One UI 7 Style)
 * =========================================================================================
 */
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
    val adaptiveState = rememberAdaptiveState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }

    var gridColumns by remember {
        mutableIntStateOf(
            prefs.getInt(
                "trash_grid_columns",
                when (adaptiveState.widthSize) {
                    WindowWidthSize.COMPACT -> 4
                    WindowWidthSize.MEDIUM -> 6
                    WindowWidthSize.EXPANDED -> 8
                }
            )
        )
    }

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
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null

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
    var currentSort by remember { mutableStateOf(TrashSort.NewestDeleted) }
    var currentFilter by remember { mutableStateOf(TrashFilter.All) }

    var showEmptyScreen by remember { mutableStateOf(false) }
    var showDeleteScreen by remember { mutableStateOf(false) }
    var showGridScreen by remember { mutableStateOf(false) }
    var itemForDetails by remember { mutableStateOf<TrashUiItem?>(null) }

    LaunchedEffect(currentFilter) {
        if (isEditMode) selectedIds = emptySet()
    }

    val filterCounts = remember(trashUiItems) {
        mapOf(
            TrashFilter.All to trashUiItems.size,
            TrashFilter.Images to trashUiItems.count { it.type == TrashMediaType.Image },
            TrashFilter.Videos to trashUiItems.count { it.type == TrashMediaType.Video },
            TrashFilter.Audio to trashUiItems.count { it.type == TrashMediaType.Audio },
            TrashFilter.Stories to trashUiItems.count { it.type == TrashMediaType.Story }
        )
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

    val groupedByExpiry = remember(finalTrashItems) {
        finalTrashItems.groupBy {
            when {
                it.daysLeft <= 0 -> "Expired"
                it.daysLeft <= 3 -> "Expiring Soon"
                it.daysLeft <= 7 -> "This Week"
                else -> "Later"
            }
        }
    }

    val flattenedGridItems = remember(groupedByExpiry) {
        val list = mutableListOf<TrashGridItem>()
        listOf("Expired", "Expiring Soon", "This Week", "Later").forEach { key ->
            groupedByExpiry[key]?.let { groupItems ->
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
                    itemForDetails = null
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

    BackHandler {
        when {
            itemForDetails != null -> itemForDetails = null
            showEmptyScreen -> showEmptyScreen = false
            showDeleteScreen -> showDeleteScreen = false
            showGridScreen -> showGridScreen = false
            isEditMode && operationProgress == null -> {
                isEditMode = false
                selectedIds = emptySet()
            }
            else -> onBack()
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    val selectedSizeBytes = remember(selectedIds, finalTrashItems) {
        finalTrashItems.filter { selectedIds.contains(it.id) }.sumOf { it.size }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val elevation by animateDpAsState(
                    targetValue = if (scrollBehavior.state.overlappedFraction > 0.01f) 3.dp else 0.dp,
                    label = "topBarElevation"
                )

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = elevation
                ) {
                    Column {
                        if (isBusy && operationProgress == null) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TopAppBar(
                            title = {
                                Column {
                                    if (isEditMode) {
                                        Text(
                                            text = if (selectedIds.isEmpty()) "Select items" else "${selectedIds.size} selected",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        if (selectedIds.isNotEmpty()) {
                                            Text(
                                                text = Formatter.formatFileSize(context, selectedSizeBytes),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Trash",
                                            fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.headlineSmall,
                                            letterSpacing = (-0.3).sp
                                        )
                                        if (finalTrashItems.isNotEmpty()) {
                                            Text(
                                                text = "${finalTrashItems.size} items • ${Formatter.formatShortFileSize(context, trashUiItems.sumOf { it.size })}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
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
                                    Icon(
                                        imageVector = if (isEditMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            },
                            actions = {
                                if (isEditMode) {
                                    val allSelected = finalTrashItems.isNotEmpty() && selectedIds.size == finalTrashItems.size
                                    Surface(
                                        onClick = {
                                            selectedIds = if (allSelected) emptySet() else finalTrashItems.map { it.id }.toSet()
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        shape = CircleShape,
                                        color = if (allSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.padding(end = 10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (allSelected) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                                contentDescription = "Select All",
                                                tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "All",
                                                color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                } else if (finalTrashItems.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = { showEmptyScreen = true },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .height(36.dp)
                                            .padding(end = 4.dp)
                                    ) {
                                        Text("Empty", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }

                                    Box {
                                        IconButton(onClick = { showSortMenu = true }) {
                                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                        }
                                        DropdownMenu(
                                            expanded = showSortMenu,
                                            onDismissRequest = { showSortMenu = false },
                                            shape = RoundedCornerShape(18.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Newest Deleted", fontWeight = FontWeight.Medium) },
                                                trailingIcon = {
                                                    if (currentSort == TrashSort.NewestDeleted) {
                                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                },
                                                onClick = { currentSort = TrashSort.NewestDeleted; showSortMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Oldest Deleted", fontWeight = FontWeight.Medium) },
                                                trailingIcon = {
                                                    if (currentSort == TrashSort.OldestDeleted) {
                                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                },
                                                onClick = { currentSort = TrashSort.OldestDeleted; showSortMenu = false }
                                            )
                                        }
                                    }

                                    Box(modifier = Modifier.padding(end = 4.dp)) {
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            shape = RoundedCornerShape(18.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Select Items", fontWeight = FontWeight.Medium) },
                                                leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                                                onClick = { isEditMode = true; showMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Grid Size", fontWeight = FontWeight.Medium) },
                                                leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                                                onClick = { showGridScreen = true; showMenu = false }
                                            )
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                            DropdownMenuItem(
                                                text = { Text("Empty Trash", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) },
                                                leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = { showEmptyScreen = true; showMenu = false }
                                            )
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )
                        if (scrollBehavior.state.overlappedFraction > 0.01f) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (trashUiItems.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoDelete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Items in Trash are permanently deleted after 30 days.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (!isEditMode) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(TrashFilter.entries) { filter ->
                                    val isSelected = currentFilter == filter
                                    val count = filterCounts[filter] ?: 0
                                    Surface(
                                        onClick = {
                                            currentFilter = filter
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = filter.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (count > 0) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "$count",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                            }
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
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns.coerceAtLeast(1)),
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 110.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = flattenedGridItems,
                                span = {
                                    if (it is TrashGridItem.Header) GridItemSpan(gridColumns.coerceAtLeast(1))
                                    else GridItemSpan(1)
                                },
                                key = {
                                    if (it is TrashGridItem.Header) "h_${it.title}"
                                    else (it as TrashGridItem.Media).item.id
                                }
                            ) { item ->
                                when (item) {
                                    is TrashGridItem.Header -> {
                                        val isUrgent = item.title == "Expiring Soon" || item.title == "Expired"
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    val groupIds = groupedByExpiry[item.title]?.map { it.id }?.toSet() ?: emptySet()
                                                    if (groupIds.isNotEmpty()) {
                                                        isEditMode = true
                                                        selectedIds = if (selectedIds.containsAll(groupIds)) {
                                                            selectedIds - groupIds
                                                        } else {
                                                            selectedIds + groupIds
                                                        }
                                                        if (selectedIds.isEmpty()) isEditMode = false
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = item.title,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DoneAll,
                                                    contentDescription = "Select Group",
                                                    tint = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    is TrashGridItem.Media -> {
                                        TrashSquareTile(
                                            item = item.item,
                                            isSelected = selectedIds.contains(item.item.id),
                                            isEditMode = isEditMode,
                                            onClick = {
                                                if (isEditMode) {
                                                    selectedIds = if (selectedIds.contains(item.item.id)) {
                                                        selectedIds - item.item.id
                                                    } else {
                                                        selectedIds + item.item.id
                                                    }
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

                // --- SAMSUNG ONE UI 7 FLOATING SELECTION DOCK ---
                AnimatedVisibility(
                    visible = isEditMode && operationProgress == null,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val widthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                        Modifier.width(600.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .then(widthModifier)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val itemsToRestore = trashEntities.filter { selectedIds.contains(it.id) }
                                    if (itemsToRestore.isNotEmpty()) trashViewModel.restoreTrashItems(itemsToRestore)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(18.dp),
                                enabled = !isBusy && selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Restore", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showDeleteScreen = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                enabled = !isBusy && selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Delete", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // --- PROGRESS OVERLAY ---
                AnimatedVisibility(
                    visible = operationProgress != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .navigationBarsPadding()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        shadowElevation = 10.dp,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                progress = { operationProgress ?: 0f },
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                strokeWidth = 3.dp
                            )
                            Spacer(Modifier.width(14.dp))
                            val percentage = ((operationProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
                            Text(
                                text = "Processing... $percentage%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------
        // FULL-PAGE OVERLAYS (Grid Size, Empty Trash, Delete Selected, File Details)
        // ---------------------------------------------------------------------------

        AnimatedVisibility(
            visible = showGridScreen,
            enter = fadeIn(tween(180)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
            modifier = Modifier.zIndex(10f)
        ) {
            FullscreenSettingsScreen(
                title = "Grid Density",
                onDismiss = { showGridScreen = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Choose how many columns to display per row in Trash",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
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
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        gridColumns = col
                                        prefs.edit().putInt("trash_grid_columns", col).apply()
                                        showGridScreen = false
                                    }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(col.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
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

        AnimatedVisibility(
            visible = showEmptyScreen,
            enter = fadeIn(tween(180)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
            modifier = Modifier.zIndex(10f)
        ) {
            FullscreenSettingsScreen(
                title = "Empty Trash",
                onDismiss = { showEmptyScreen = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(32.dp))
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Empty Trash Permanently?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "All ${trashUiItems.size} items (${Formatter.formatShortFileSize(context, trashUiItems.sumOf { it.size })}) will be permanently erased. This action cannot be undone.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(36.dp))
                    Button(
                        onClick = {
                            trashViewModel.permanentlyDeleteTrash(trashEntities)
                            showEmptyScreen = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Empty Trash", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { showEmptyScreen = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showDeleteScreen,
            enter = fadeIn(tween(180)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
            modifier = Modifier.zIndex(10f)
        ) {
            FullscreenSettingsScreen(
                title = "Delete Selected",
                onDismiss = { showDeleteScreen = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(32.dp))
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Delete ${selectedIds.size} Item(s) Forever?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Selected items (${Formatter.formatShortFileSize(context, selectedSizeBytes)}) will be permanently destroyed and cannot be recovered.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(36.dp))
                    Button(
                        onClick = {
                            val itemsToDel = trashEntities.filter { selectedIds.contains(it.id) }
                            if (itemsToDel.isNotEmpty()) trashViewModel.permanentlyDeleteTrash(itemsToDel)
                            showDeleteScreen = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Permanently", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { showDeleteScreen = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = itemForDetails != null,
            enter = fadeIn(tween(180)) + slideInVertically { it / 8 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 8 },
            modifier = Modifier.zIndex(10f)
        ) {
            itemForDetails?.let { item ->
                val deletedDateStr = remember(item.deletedTimestamp) {
                    SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(item.deletedTimestamp))
                }
                FullscreenSettingsScreen(
                    title = "Trash Item Details",
                    onDismiss = { itemForDetails = null }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Black,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    when (item.type) {
                                        TrashMediaType.Image, TrashMediaType.Video -> {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context)
                                                    .data(item.contentUri)
                                                    .size(600)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (item.type == TrashMediaType.Video) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayCircleOutline,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                            }
                                        }
                                        TrashMediaType.Audio -> Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        TrashMediaType.Story -> Icon(
                                            imageVector = Icons.Rounded.AutoStories,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(56.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    MetadataRow("File Name", item.name)
                                    MetadataRow("Media Type", item.type.name)
                                    MetadataRow("Original Path", item.originalPath.ifBlank { "Gallery Storage" })
                                    MetadataRow("Size", Formatter.formatFileSize(context, item.size))
                                    MetadataRow("Deleted On", deletedDateStr)
                                    MetadataRow("Auto-Delete", if (item.daysLeft <= 0) "Expired" else "${item.daysLeft} days remaining")
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 10.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .navigationBarsPadding(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val targetEntity = trashEntities.find { it.id == item.id }
                                FilledTonalButton(
                                    onClick = {
                                        if (targetEntity != null) {
                                            itemForDetails = null
                                            trashViewModel.restoreTrashItems(listOf(targetEntity))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Restore", fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        if (targetEntity != null) {
                                            itemForDetails = null
                                            trashViewModel.permanentlyDeleteTrash(listOf(targetEntity))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Delete Forever", fontWeight = FontWeight.Bold)
                                }
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
 * 🔲 TRASH UNIFORM SQUARE TILE (Spring-Animated One UI 7 Tile)
 * =========================================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashSquareTile(
    modifier: Modifier = Modifier,
    item: TrashUiItem,
    isSelected: Boolean,
    isEditMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "trash_tile_scale"
    )
    val corner by animateDpAsState(
        targetValue = if (isSelected) 16.dp else 10.dp,
        animationSpec = tween(140),
        label = "trash_tile_corner"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(corner))
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(corner))
                } else {
                    Modifier
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            TrashMediaType.Audio, TrashMediaType.Story -> {
                val iconRes = if (item.type == TrashMediaType.Audio) Icons.Rounded.MusicNote else Icons.Rounded.AutoStories
                val iconTint = if (item.type == TrashMediaType.Audio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(iconRes, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Bottom gradient and expiry capsule
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(6.dp)
        ) {
            val isUrgent = item.daysLeft <= 3
            Surface(
                shape = CircleShape,
                color = if (isUrgent) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = if (item.daysLeft <= 0) "Expired" else "${item.daysLeft}d left",
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        // Selection Checkmark Overlay
        if (isEditMode) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isSelected) 0.25f else 0.06f)))
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .shadow(4.dp, CircleShape)
                        .background(Color.White, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .size(22.dp)
                )
            }
        }
    }
}

/**
 * =========================================================================================
 * 📄 FULL-PAGE OVERLAY HELPER
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenSettingsScreen(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content()
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider(
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    }
}

/**
 * =========================================================================================
 * ⚙️ THE JANITOR (Background Cleanup Worker - Android WorkManager)
 * =========================================================================================
 */
@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: GalleryDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TRASH_EXPIRY_MS = 30L * 24L * 60L * 60L * 1000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val expiryThreshold = System.currentTimeMillis() - TRASH_EXPIRY_MS
            val allCandidates = dao.getOldestTrashItems(5000)

            if (allCandidates.isEmpty()) return@withContext Result.success()

            val deletedIds = mutableListOf<Long>()
            var deleteFailures = 0

            allCandidates.chunked(500).forEach { batch ->
                batch.forEachIndexed { index, trash ->
                    if (index % 50 == 0) yield()
                    val uri = runCatching { Uri.parse(trash.contentUri) }.getOrNull()
                    val exists = uri != null && mediaExists(uri)
                    val isExpired = trash.deletedTimestamp < expiryThreshold

                    when {
                        !exists -> deletedIds.add(trash.id)
                        isExpired -> {
                            try {
                                if (applicationContext.contentResolver.delete(uri!!, null, null) > 0) {
                                    deletedIds.add(trash.id)
                                } else {
                                    if (!mediaExists(uri)) deletedIds.add(trash.id) else deleteFailures++
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

            if (deleteFailures > 100) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }

    private fun mediaExists(uri: Uri): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bundle = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                applicationContext.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    bundle,
                    null
                )?.use { it.moveToFirst() } == true
            } else {
                applicationContext.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    null,
                    null,
                    null
                )?.use { it.moveToFirst() } == true
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * =========================================================================================
 * 🪹 EMPTY TRASH VIEW
 * =========================================================================================
 */
@Composable
fun EmptyTrashView(currentFilter: TrashFilter) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(500)) + scaleIn(initialScale = 0.94f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                                )
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(26.dp))
                Text(
                    text = if (currentFilter == TrashFilter.All) "Trash is Empty" else "No Deleted ${currentFilter.name}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Items moved to Trash stay here for 30 days before permanent deletion.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}