@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE")
@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)

package com.gallerybox.ui.screens.stories

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import coil.size.Size

// ---------------------------------------------------------------------------
// 🖼️ APP DATA & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.data.UiStory
import com.gallerybox.engine.NotificationHelper
import com.gallerybox.ui.screens.picture.GalleryGridItem
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.StoryViewModel

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayList
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun Context.findStoryActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * =========================================================================================
 * 📖 THE STORIES / MEMORIES SCREEN (Samsung One UI 7 Style)
 * =========================================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    storyViewModel: StoryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val adaptiveState = rememberAdaptiveState()

    LaunchedEffect(Unit) {
        storyViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    val displayStories by storyViewModel.stories.collectAsState()
    val isGenerating by storyViewModel.isGenerating.collectAsState()
    val generationProgress by storyViewModel.generationProgress.collectAsState()
    val generationTotal by storyViewModel.generationTotal.collectAsState()

    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }

    var activeStoryIndex by remember { mutableStateOf<Int?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var newStoryTitle by remember { mutableStateOf("") }

    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val columnCount = when (adaptiveState.widthSize) {
        WindowWidthSize.COMPACT -> 2
        WindowWidthSize.MEDIUM -> 3
        WindowWidthSize.EXPANDED -> 5
    }

    val sharedExoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(sharedExoPlayer) {
        onDispose { sharedExoPlayer.release() }
    }

    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var isNotificationEnabled by remember { mutableStateOf(prefs.getBoolean("daily_notification", false)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isNotificationEnabled = true
            prefs.edit().putBoolean("daily_notification", true).apply()
            NotificationHelper.enableDailyNotifications(context)
            Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    val toggleNotification = {
        if (isNotificationEnabled) {
            isNotificationEnabled = false
            prefs.edit().putBoolean("daily_notification", false).apply()
            NotificationHelper.disableDailyNotifications(context)
            Toast.makeText(context, "Daily Reminders Disabled", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    isNotificationEnabled = true
                    prefs.edit().putBoolean("daily_notification", true).apply()
                    NotificationHelper.enableDailyNotifications(context)
                    Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                isNotificationEnabled = true
                prefs.edit().putBoolean("daily_notification", true).apply()
                NotificationHelper.enableDailyNotifications(context)
                Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = activeStoryIndex != null) {
        activeStoryIndex = null
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = activeStoryIndex,
            label = "StoryMorph",
            transitionSpec = { fadeIn(tween(280)).togetherWith(fadeOut(tween(260))) }
        ) { currentIndex ->

            if (currentIndex == null) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (isSelectionMode) {
                            Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                                TopAppBar(
                                    title = {
                                        Text(
                                            text = if (selectedIds.isEmpty()) "Select Moments" else "${selectedIds.size} selected",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = { isSelectionMode = false; selectedIds = emptySet() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close")
                                        }
                                    },
                                    actions = {
                                        Button(
                                            onClick = { if (selectedIds.isNotEmpty()) showNameDialog = true },
                                            enabled = selectedIds.isNotEmpty(),
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                                            modifier = Modifier.padding(end = 10.dp)
                                        ) {
                                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Create", fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                )
                            }
                        } else {
                            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                                TopAppBar(
                                    title = {
                                        Column {
                                            Text(
                                                text = "Stories",
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-0.3).sp
                                            )
                                            if (displayStories.isNotEmpty()) {
                                                Text(
                                                    text = "${displayStories.size} curated memories",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    navigationIcon = {
                                        val onBackPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                                        IconButton(onClick = { onBackPressedDispatcher?.onBackPressed() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = toggleNotification,
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isNotificationEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                                                contentDescription = "Toggle Daily Reminder",
                                                tint = if (isNotificationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { isSelectionMode = true },
                                            modifier = Modifier
                                                .padding(end = 10.dp)
                                                .size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Add,
                                                contentDescription = "Create Manual Memory",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    },
                                    scrollBehavior = scrollBehavior,
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = Color.Transparent,
                                        scrolledContainerColor = Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                storyViewModel.refreshMemories()
                                delay(1.seconds)
                                isRefreshing = false
                            }
                        },
                        state = pullRefreshState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding())
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isSelectionMode) {
                                MediaSelectorGrid(
                                    pagedMedia = pagedMedia,
                                    selectedIds = selectedIds,
                                    onToggle = { id: Long ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    columnCount = (columnCount + 1).coerceAtLeast(3)
                                )
                            } else if (displayStories.isEmpty() && !isGenerating) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(32.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(112.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                                        )
                                                    )
                                                )
                                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoStories,
                                                contentDescription = null,
                                                modifier = Modifier.size(52.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(Modifier.height(24.dp))
                                        Text(
                                            text = "No Stories Yet",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Your best moments will be grouped into stories automatically, or you can create one manually.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(28.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Button(
                                                onClick = { isSelectionMode = true },
                                                shape = RoundedCornerShape(18.dp),
                                                modifier = Modifier.height(50.dp)
                                            ) {
                                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Create Story", fontWeight = FontWeight.Bold)
                                            }
                                            FilledTonalButton(
                                                onClick = { storyViewModel.refreshMemories() },
                                                shape = RoundedCornerShape(18.dp),
                                                modifier = Modifier.height(50.dp)
                                            ) {
                                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Scan Moments", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            } else if (isGenerating && displayStories.isEmpty()) {
                                val progressFraction = if (generationTotal > 0) {
                                    (generationProgress.toFloat() / generationTotal).coerceIn(0f, 1f)
                                } else 0f

                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                                            CircularProgressIndicator(
                                                progress = { progressFraction },
                                                strokeWidth = 6.dp,
                                                modifier = Modifier.fillMaxSize(),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                            )
                                            Text(
                                                text = "${(progressFraction * 100).toInt()}%",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "Curating Your Stories...",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$generationProgress / $generationTotal moments organized",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    AnimatedVisibility(visible = isGenerating) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Fixed(columnCount),
                                        contentPadding = PaddingValues(
                                            top = 14.dp,
                                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 92.dp
                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp)
                                    ) {
                                        items(
                                            count = displayStories.size,
                                            key = { index -> displayStories[index].id },
                                            contentType = { "story" }
                                        ) { index ->
                                            val story = displayStories[index]
                                            val displayTitle = story.title.ifBlank { "Memory ${index + 1}" }

                                            StoryCard(
                                                story = story,
                                                displayTitle = displayTitle,
                                                sharedTransitionScope = this@SharedTransitionLayout,
                                                animatedVisibilityScope = this@AnimatedContent,
                                                adaptiveState = adaptiveState,
                                                onClick = { activeStoryIndex = index },
                                                onDelete = { storyViewModel.deleteStory(story.id) },
                                                onSave = {
                                                    shareStoryMedia(context, story)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                displayStories.getOrNull(currentIndex)?.let { activeStory ->
                    val displayTitle = activeStory.title.ifBlank { "Memory ${currentIndex + 1}" }

                    StoryPlayer(
                        story = activeStory,
                        displayTitle = displayTitle,
                        sharedExoPlayer = sharedExoPlayer,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        adaptiveState = adaptiveState,
                        onClose = { activeStoryIndex = null },
                        onNextStoryGroup = {
                            activeStoryIndex = if (currentIndex < displayStories.lastIndex) currentIndex + 1 else null
                        },
                        onPrevStoryGroup = {
                            activeStoryIndex = if (currentIndex > 0) currentIndex - 1 else null
                        }
                    )
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false; newStoryTitle = "" },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.AutoStories,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Name Your Story",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "${selectedIds.size} moments selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newStoryTitle,
                        onValueChange = { newStoryTitle = it },
                        placeholder = { Text("e.g. Summer Trip, Birthday...") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedIds.isEmpty()) {
                            Toast.makeText(context, "Select at least one item", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (newStoryTitle.isBlank()) {
                            Toast.makeText(context, "Enter a story title", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        storyViewModel.createManualStory(selectedIds.toList(), newStoryTitle.trim())
                        isSelectionMode = false
                        selectedIds = emptySet()
                        newStoryTitle = ""
                        showNameDialog = false
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Create Story", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false; newStoryTitle = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun shareStoryMedia(context: Context, story: UiStory) {
    if (story.items.isEmpty()) return
    val uris = ArrayList(story.items.map { it.uri })
    val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        } else {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share '${story.title}'"))
    }
}

/**
 * =========================================================================================
 * 🎴 THE INDIVIDUAL STORY CARD (Samsung One UI 7 Cinema Poster Card)
 * =========================================================================================
 */
@Composable
fun StoryCard(
    story: UiStory,
    displayTitle: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    adaptiveState: AdaptiveState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "StoryCardScale"
    )

    val videoCount = remember(story.items) { story.items.count { it.isVideo } }
    val favCount = remember(story.items) { story.items.count { it.isFavorite } }

    val badgeLabel = remember(story.id, story.items) {
        when {
            story.id.startsWith("manual") -> "Custom"
            story.id.startsWith("auto_throwback") -> "Throwback"
            story.id.startsWith("auto_favorites") -> "Favorites"
            else -> {
                val latestSec = story.items.maxOfOrNull { it.dateAdded } ?: 0L
                val daysAgo = ((System.currentTimeMillis() - latestSec * 1000L) / 86_400_000L).coerceAtLeast(0L)
                when {
                    daysAgo == 0L -> "Today"
                    daysAgo == 1L -> "Yesterday"
                    daysAgo < 30L -> "${daysAgo}d ago"
                    daysAgo < 365L -> "${daysAgo / 30L}mo ago"
                    else -> "${daysAgo / 365L}y ago"
                }
            }
        }
    }

    with(sharedTransitionScope) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .scale(scale)
                .sharedBounds(
                    sharedContentState = rememberSharedContentState("story_bounds_${story.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring<Rect>(dampingRatio = 0.8f, stiffness = 300f) }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showMenu = true },
                        onTap = { onClick() }
                    )
                }
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(story.coverUri)
                        .size(420)
                        .precision(Precision.INEXACT)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .allowHardware(true)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Multi-stop cinema gradient scrim for crisp text legibility
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.28f),
                                0.35f to Color.Transparent,
                                0.68f to Color.Black.copy(alpha = 0.35f),
                                1.0f to Color.Black.copy(alpha = 0.86f)
                            )
                        )
                )

                // Top-Start Category / Recency Pill
                Surface(
                    color = Color.Black.copy(alpha = 0.48f),
                    shape = CircleShape,
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        if (favCount > 0 || story.id.startsWith("auto_favorites")) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFFF4D6D),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = badgeLabel,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Top-End Play / Video Indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.48f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (videoCount > 0) Icons.Rounded.Movie else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Bottom Title & Subtitle Metadata
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = displayTitle,
                        fontSize = (15 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        lineHeight = 19.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = story.subtitle.ifBlank {
                            "${story.items.size} Moments" + if (videoCount > 0) " • $videoCount Videos" else ""
                        },
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = (11 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    DropdownMenuItem(
                        text = { Text("Share Story", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = { showMenu = false; onSave() }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove Story", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * ▶️ THE FULL SCREEN STORY PLAYER (Cinema Slideshow Projector)
 * =========================================================================================
 */
@Composable
fun StoryPlayer(
    story: UiStory,
    displayTitle: String,
    sharedExoPlayer: ExoPlayer,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    adaptiveState: AdaptiveState,
    onClose: () -> Unit,
    onNextStoryGroup: () -> Unit,
    onPrevStoryGroup: () -> Unit
) {
    if (story.items.isEmpty()) {
        onClose()
        return
    }

    val context = LocalContext.current
    var currentIndex by remember(story.id) { mutableIntStateOf(0) }
    var isPaused by remember(story.id) { mutableStateOf(false) }
    var isMuted by rememberSaveable { mutableStateOf(false) }
    val currentItemProgress = remember(story.id, currentIndex) { mutableFloatStateOf(0f) }
    val currentItem = remember(currentIndex, story.items) { story.items.getOrNull(currentIndex) }
    val coroutineScope = rememberCoroutineScope()

    val dragOffsetY = remember { Animatable(0f) }

    val animatedScale = 1f - (dragOffsetY.value / 2500f).coerceIn(0f, 0.4f)
    val dynamicCornerRadius = (dragOffsetY.value / 10f).coerceIn(0f, 48f).dp

    var pressTime by remember { mutableLongStateOf(0L) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }

    var lastAdvanceTime by remember { mutableLongStateOf(0L) }
    val ADVANCE_LOCK_MS = 280L

    fun canAdvance(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAdvanceTime < ADVANCE_LOCK_MS) return false
        lastAdvanceTime = now
        return true
    }

    LaunchedEffect(isMuted) {
        sharedExoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Preload the next 2 slides into memory whenever currentIndex changes
    LaunchedEffect(story.id, currentIndex) {
        val nextItems = story.items.drop(currentIndex + 1).take(2)
        nextItems.forEach { nextMedia ->
            if (!nextMedia.isVideo) {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(nextMedia.uri)
                        .size(Size.ORIGINAL)
                        .allowHardware(true)
                        .build()
                )
            }
        }
    }

    DisposableEffect(Unit) {
        val window = context.findStoryActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            sharedExoPlayer.stop()
            sharedExoPlayer.clearMediaItems()
        }
    }

    val onNextState by rememberUpdatedState {
        if (canAdvance()) {
            sharedExoPlayer.seekTo(0)
            currentItemProgress.floatValue = 0f
            if (currentIndex < story.items.lastIndex) currentIndex++ else onNextStoryGroup()
        }
    }

    val onPrevState by rememberUpdatedState {
        if (canAdvance()) {
            sharedExoPlayer.seekTo(0)
            currentItemProgress.floatValue = 0f
            if (currentIndex > 0) currentIndex-- else onPrevStoryGroup()
        }
    }

    val handleTap: (Boolean) -> Unit = { isNext ->
        if (isNext) onNextState() else onPrevState()
    }

    with(sharedTransitionScope) {
        val widthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(600.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = animatedScale.coerceIn(0f, 1f))),
            contentAlignment = Alignment.Center
        ) {
            if (currentItem != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(widthModifier)
                        .scale(animatedScale)
                        .clip(RoundedCornerShape(dynamicCornerRadius))
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "story_bounds_${story.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> spring<Rect>(dampingRatio = 0.8f, stiffness = 300f) }
                        )
                ) {
                    // Ambient Dimmed Backdrop so non-9:16 photos look cinematic
                    AsyncImage(
                        model = remember(currentItem.uri) {
                            ImageRequest.Builder(context)
                                .data(currentItem.uri)
                                .size(240)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 0.28f }
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                    )

                    if (currentItem.isVideo) {
                        LifecycleAwareVideoPlayer(
                            exoPlayer = sharedExoPlayer,
                            uri = currentItem.uri,
                            isStoryPaused = isPaused || dragOffsetY.value > 0f,
                            progressState = currentItemProgress,
                            onComplete = { onNextState() }
                        )
                    } else {
                        SimpleImageItem(
                            uri = currentItem.uri,
                            isPaused = isPaused || dragOffsetY.value > 0f,
                            durationMs = 5000L,
                            progressState = currentItemProgress,
                            onComplete = { onNextState() }
                        )
                    }

                    // Top & Bottom Scrim Gradients
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    )

                    // --- TOUCH CONTROLS ---
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(story.id) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        pressTime = System.currentTimeMillis()
                                        pressPosition = offset
                                        isPaused = true
                                        tryAwaitRelease()
                                        isPaused = false
                                    },
                                    onTap = { offset ->
                                        val holdDuration = System.currentTimeMillis() - pressTime
                                        val dx = abs(offset.x - pressPosition.x)
                                        val dy = abs(offset.y - pressPosition.y)

                                        if (dx > 24f || dy > 24f || holdDuration > 220L) {
                                            return@detectTapGestures
                                        }

                                        if (offset.x < size.width * 0.30f) {
                                            handleTap(false)
                                        } else {
                                            handleTap(true)
                                        }
                                    }
                                )
                            }
                            .pointerInput(story.id) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragOffsetY.value > 260f) onClose()
                                        else coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                    },
                                    onDragCancel = {
                                        coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    if (dragAmount > 0 || dragOffsetY.value > 0) {
                                        coroutineScope.launch {
                                            dragOffsetY.snapTo(
                                                dragOffsetY.value + (dragAmount * (1f - (dragOffsetY.value / 2000f).coerceIn(0f, 0.8f)))
                                            )
                                        }
                                    }
                                }
                            }
                    )

                    // Top Progress Bars & Header (Hidden while user long-presses to inspect photo)
                    AnimatedVisibility(
                        visible = !isPaused,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Column {
                            StoryHeaderOverlay(
                                story = story,
                                displayTitle = displayTitle,
                                currentIndex = currentIndex,
                                currentItemProgress = currentItemProgress,
                                adaptiveState = adaptiveState,
                                onClose = onClose
                            )

                            if (currentItem.isVideo) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        onClick = { isMuted = !isMuted },
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                                contentDescription = if (isMuted) "Unmute" else "Mute",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (isMuted) "Muted" else "Sound On",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
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
}

/**
 * =========================================================================================
 * 🔋 PROGRESS BARS AND HEADERS
 * =========================================================================================
 */
@Composable
fun StoryHeaderOverlay(
    story: UiStory,
    displayTitle: String,
    currentIndex: Int,
    currentItemProgress: State<Float>,
    adaptiveState: AdaptiveState,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 14.dp, start = 12.dp, end = 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in story.items.indices) {
                StoryProgressBar(
                    index = index,
                    currentIndex = currentIndex,
                    currentItemProgress = currentItemProgress,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = story.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    color = Color.White,
                    fontSize = (15 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (story.subtitle.isNotEmpty()) {
                    Text(
                        text = "${story.subtitle} • ${currentIndex + 1}/${story.items.size}",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StoryProgressBar(
    index: Int,
    currentIndex: Int,
    currentItemProgress: State<Float>,
    modifier: Modifier
) {
    val targetProgress = when {
        index < currentIndex -> 1f
        index > currentIndex -> 0f
        else -> currentItemProgress.value.coerceIn(0f, 1f)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 80, easing = LinearOutSlowInEasing),
        label = "StoryProgress"
    )

    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier
            .height(2.5.dp)
            .clip(CircleShape),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.28f)
    )
}

/**
 * =========================================================================================
 * ⚙️ STORY CREATOR SCREEN (Choosing photos manually)
 * =========================================================================================
 */
@Composable
fun MediaSelectorGrid(
    pagedMedia: LazyPagingItems<GalleryGridItem>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    contentPadding: PaddingValues,
    columnCount: Int
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount.coerceAtLeast(1)),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = 96.dp,
            start = 4.dp,
            end = 4.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            count = pagedMedia.itemCount,
            span = { index ->
                if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount.coerceAtLeast(1))
                else GridItemSpan(1)
            },
            key = { index ->
                when (val item = pagedMedia.peek(index)) {
                    is GalleryGridItem.Header -> "header_${item.id}"
                    is GalleryGridItem.Media -> item.item.id
                    else -> "placeholder_$index"
                }
            },
            contentType = { index ->
                when (pagedMedia.peek(index)) {
                    is GalleryGridItem.Header -> "header"
                    is GalleryGridItem.Media -> "media"
                    else -> "placeholder"
                }
            }
        ) { index ->
            when (val item = pagedMedia[index]) {
                is GalleryGridItem.Header -> {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                }
                is GalleryGridItem.Media -> {
                    val mediaItem = item.item
                    val isSelected = selectedIds.contains(mediaItem.id)
                    val corner = 5.dp

                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(corner))
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(corner))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onToggle(mediaItem.id) }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(mediaItem.uri)
                                .size(256)
                                .apply { if (mediaItem.isVideo) videoFrameMillis(1000) }
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (mediaItem.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = "VIDEO",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(22.dp)
                                    .shadow(2.dp, CircleShape)
                                    .background(Color.White, CircleShape)
                            )
                        }
                    }
                }
                null -> {
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ CORE RENDERING TOOLS (Images with Ken Burns Zoom & Lifecycle-Aware Videos)
 * =========================================================================================
 */
@Composable
fun SimpleImageItem(
    uri: Uri,
    isPaused: Boolean,
    durationMs: Long,
    progressState: MutableState<Float>,
    onComplete: () -> Unit
) {
    // Keyed to `uri` so every new photo starts cleanly at 0f
    val progressAnim = remember(uri) { Animatable(0f) }

    LaunchedEffect(uri) {
        progressState.value = 0f
        progressAnim.snapTo(0f)
    }

    LaunchedEffect(isPaused, uri) {
        if (!isPaused) {
            val remainingTime = ((1f - progressAnim.value) * durationMs).toInt().coerceAtLeast(50)
            val result = progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = remainingTime, easing = LinearEasing)
            )
            if (result.endReason == AnimationEndReason.Finished) {
                onComplete()
            }
        }
    }

    LaunchedEffect(progressAnim) {
        snapshotFlow { progressAnim.value }.collect { progressState.value = it }
    }

    // Subtle cinema Ken Burns zoom from 1.0x to 1.07x while the photo is displayed
    val kenBurnsScale = 1f + (progressAnim.value * 0.07f)

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(Size.ORIGINAL)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = kenBurnsScale
                scaleY = kenBurnsScale
            }
    )
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LifecycleAwareVideoPlayer(
    exoPlayer: ExoPlayer,
    uri: Uri,
    isStoryPaused: Boolean,
    progressState: MutableState<Float>,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasError by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        progressState.value = 0f
        val mediaId = uri.toString()
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) {
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(ExoMediaItem.Builder().setUri(uri).setMediaId(mediaId).build())
            exoPlayer.prepare()
        } else {
            exoPlayer.seekTo(0L)
        }
        hasError = false
    }

    LaunchedEffect(isStoryPaused, uri) {
        if (!isStoryPaused) {
            while (isActive) {
                if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.duration > 0) {
                    progressState.value = (exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()).coerceIn(0f, 1f)
                }
                delay(80.milliseconds)
            }
        }
    }

    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onComplete()
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(isStoryPaused, uri) {
        if (isStoryPaused) exoPlayer.pause() else exoPlayer.play()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (!isStoryPaused) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.pause()
        }
    }

    if (hasError) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(8.dp))
                Text("Video unavailable", color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    } else {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
    }
}