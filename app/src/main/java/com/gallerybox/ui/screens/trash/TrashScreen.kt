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
            prefs.getInt("trash_grid_columns",
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

    // Local State Variables
    var isEditMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var currentSort by remember { mutableStateOf(TrashSort.NewestDeleted) }
    var currentFilter by remember { mutableStateOf(TrashFilter.All) }

    // Full-Screen Dialog Overlay States
    var showEmptyScreen by remember { mutableStateOf(false) }
    var showDeleteScreen by remember { mutableStateOf(false) }
    var showGridScreen by remember { mutableStateOf(false) }
    var itemForDetails by remember { mutableStateOf<TrashUiItem?>(null) }

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

    // Intercept physical Back button
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val elevation by animateDpAsState(
                    targetValue = if (scrollBehavior.state.overlappedFraction > 0.01f) 4.dp else 0.dp,
                    label = "topBarElevation"
                )

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = elevation
                ) {
                    Column {
                        if (isBusy && operationProgress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                        }
                        TopAppBar(
                            title = {
                                Column {
                                    if (isEditMode) {
                                        Text("${selectedIds.size} Selected", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                                    } else {
                                        Text("Trash Bin", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                                    }
                                    if (!isEditMode && finalTrashItems.isNotEmpty()) {
                                        Text(
                                            text = "${finalTrashItems.size} items • ${Formatter.formatShortFileSize(context, trashUiItems.sumOf { it.size })}",
                                            style = MaterialTheme.typography.bodySmall,
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
                                    val allSelected = finalTrashItems.isNotEmpty() && selectedIds.size == finalTrashItems.size
                                    IconButton(
                                        onClick = {
                                            if (allSelected) selectedIds = emptySet()
                                            else selectedIds = finalTrashItems.map { it.id }.toSet()
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                            contentDescription = "Select All",
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
                                                trailingIcon = { if (currentSort == TrashSort.NewestDeleted) Icon(Icons.Default.Check, null) },
                                                onClick = { currentSort = TrashSort.NewestDeleted; showSortMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Oldest Deleted") },
                                                trailingIcon = { if (currentSort == TrashSort.OldestDeleted) Icon(Icons.Default.Check, null) },
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
                                                onClick = { showGridScreen = true; showMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Empty Trash", color = MaterialTheme.colorScheme.error) },
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
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (trashUiItems.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(12.dp))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Items are permanently deleted after 30 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (!isEditMode) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(TrashFilter.entries) { filter ->
                                    val isSelected = currentFilter == filter
                                    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    val contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = bgColor,
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clickable {
                                                currentFilter = filter
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                    ) {
                                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                            Text(text = filter.name, color = contentColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                        // UNIFORM SQUARE GRID!
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 100.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = flattenedGridItems,
                                span = { if (it is TrashGridItem.Header) GridItemSpan(gridColumns) else GridItemSpan(1) },
                                key = { if (it is TrashGridItem.Header) "h_${it.title}" else (it as TrashGridItem.Media).item.id }
                            ) { item ->
                                when (item) {
                                    is TrashGridItem.Header -> {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.title == "Expiring Soon" || item.title == "Expired") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                                        )
                                    }
                                    is TrashGridItem.Media -> {
                                        TrashSquareTile(
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

                // BOTTOM ACTION BAR
                AnimatedVisibility(
                    visible = isEditMode && operationProgress == null,
                    enter = slideInVertically(tween(250)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(250)) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    val widthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(600.dp) else Modifier.fillMaxWidth()
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp,
                        modifier = Modifier.then(widthModifier)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Button(
                                onClick = {
                                    val itemsToRestore = trashEntities.filter { selectedIds.contains(it.id) }
                                    if (itemsToRestore.isNotEmpty()) trashViewModel.restoreTrashItems(itemsToRestore)
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                enabled = !isBusy && selectedIds.isNotEmpty()
                            ) {
                                Text("Restore")
                            }

                            Button(
                                onClick = { showDeleteScreen = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                enabled = !isBusy && selectedIds.isNotEmpty()
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }

                // PROGRESS OVERLAY
                AnimatedVisibility(
                    visible = operationProgress != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).navigationBarsPadding()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 8.dp,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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

        // ---------------------------------------------------------------------------
        // FIXED FULL-PAGE SCREENS (Replacing Bottom Sheets)
        // ---------------------------------------------------------------------------

        AnimatedVisibility(visible = showGridScreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.zIndex(10f)) {
            FullscreenSettingsScreen(
                title = "Grid Layout",
                onDismiss = { showGridScreen = false }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(8) { index ->
                        val col = index + 1
                        val isSelected = gridColumns == col
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.aspectRatio(1f).clickable {
                                gridColumns = col
                                prefs.edit().putInt("trash_grid_columns", col).apply()
                                showGridScreen = false
                            }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(col.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showEmptyScreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.zIndex(10f)) {
            FullscreenSettingsScreen(
                title = "Empty Trash",
                onDismiss = { showEmptyScreen = false }
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("Permanently destroy all ${trashUiItems.size} items?", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(40.dp))
                    Button(
                        onClick = {
                            trashViewModel.permanentlyDeleteTrash(trashEntities)
                            showEmptyScreen = false
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Empty Trash")
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showEmptyScreen = false }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Cancel")
                    }
                }
            }
        }

        AnimatedVisibility(visible = showDeleteScreen, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.zIndex(10f)) {
            FullscreenSettingsScreen(
                title = "Delete Selected",
                onDismiss = { showDeleteScreen = false }
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("${selectedIds.size} selected items will be destroyed instantly.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(40.dp))
                    Button(
                        onClick = {
                            val itemsToDel = trashEntities.filter { selectedIds.contains(it.id) }
                            if (itemsToDel.isNotEmpty()) trashViewModel.permanentlyDeleteTrash(itemsToDel)
                            showDeleteScreen = false
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Permanently")
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showDeleteScreen = false }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Cancel")
                    }
                }
            }
        }

        AnimatedVisibility(visible = itemForDetails != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.zIndex(10f)) {
            itemForDetails?.let { item ->
                FullscreenSettingsScreen(
                    title = "File Details",
                    onDismiss = { itemForDetails = null }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).padding(24.dp)) {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                when (item.type) {
                                    TrashMediaType.Image, TrashMediaType.Video -> {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context).data(item.contentUri).size(600).crossfade(true).build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
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
                            Spacer(Modifier.height(24.dp))
                            MetadataRow("File Name", item.name)
                            MetadataRow("Media Type", item.type.name)
                            MetadataRow("Original Path", item.originalPath.ifBlank { "Virtual Source Stack" })
                            MetadataRow("Size", Formatter.formatShortFileSize(context, item.size))
                            MetadataRow("Expiry", if (item.daysLeft <= 0) "Expired" else "${item.daysLeft} days left")
                        }

                        // Bottom Actions for Metadata Details
                        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val targetEntity = trashEntities.find { it.id == item.id }
                                OutlinedButton(
                                    onClick = {
                                        if (targetEntity != null) {
                                            itemForDetails = null
                                            trashViewModel.restoreTrashItems(listOf(targetEntity))
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Restore")
                                }
                                Button(
                                    onClick = {
                                        if (targetEntity != null) {
                                            itemForDetails = null
                                            trashViewModel.permanentlyDeleteTrash(listOf(targetEntity))
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Delete Forever")
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
 * 🔲 TRASH UNIFORM SQUARE TILE
 * =========================================================================================
 * Uniform aspect ratio grid item for a clean gallery look.
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
    Box(
        modifier = modifier
            .aspectRatio(1f) // UNIFORM SQUARE!
            .clip(RoundedCornerShape(8.dp))
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
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.2f)))
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            TrashMediaType.Audio, TrashMediaType.Story -> {
                val iconRes = if (item.type == TrashMediaType.Audio) Icons.Rounded.MusicNote else Icons.Rounded.AutoStories
                val iconTint = if (item.type == TrashMediaType.Audio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

                Column(Modifier.fillMaxSize().padding(8.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Icon(iconRes, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }

        // Bottom gradient and expiry label
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f))))
                .padding(6.dp)
        ) {
            Text(
                text = if (item.daysLeft <= 0) "Expired" else "${item.daysLeft}d left",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.daysLeft <= 3) MaterialTheme.colorScheme.error else Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }

        // The Checkmark overlay
        if (isEditMode) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(if (isSelected) 0.3f else 0f)))
            if (isSelected) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.White, CircleShape)) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            } else {
                Icon(imageVector = Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = Color.White.copy(0.8f), modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp))
            }
        }
    }
}

/**
 * =========================================================================================
 * 📄 FIXED FULL-PAGE OVERLAY HELPER
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
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            content()
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
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
        private const val TRASH_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000L
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
        enter = fadeIn(tween(800)) + scaleIn(initialScale = 0.95f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), modifier = Modifier.size(140.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary.copy(0.5f))
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    text = if (currentFilter == TrashFilter.All) "Your trash is empty" else "No deleted ${currentFilter.name.lowercase()}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Deleted items will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}