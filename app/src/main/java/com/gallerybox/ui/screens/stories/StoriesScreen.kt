// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("unused", "UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE")

// Opt-in tells Android we are using cutting-edge, experimental animation tools (like SharedTransitions).
@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)

package com.gallerybox.ui.screens.stories

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this screen.
// We are bringing in tools for detecting taps and swipes, playing videos, and animating boxes.
import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * =========================================================================================
 * 📖 THE STORIES / MEMORIES SCREEN
 * =========================================================================================
 * This screen displays the short movies (Scrapbooks) the app automatically generated from the user's photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    // Bring in the "Managers" (ViewModels) who have all the data.
    viewModel: GalleryViewModel = hiltViewModel(),
    storyViewModel: StoryViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // 🧠 1. Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()

    // Listen to the walkie-talkie. If the Manager says "Show a popup!", show it.
    LaunchedEffect(Unit) {
        storyViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    // --- SCOREBOARDS ---
    // Stare at the Managers' scoreboards. If the data changes, redraw the screen instantly.
    val displayStories by storyViewModel.stories.collectAsState() // The list of stories
    val isGenerating by storyViewModel.isGenerating.collectAsState() // Is the factory currently building new stories?
    val generationProgress by storyViewModel.generationProgress.collectAsState()
    val generationTotal by storyViewModel.generationTotal.collectAsState()

    // The Waiter (PagingSource) bringing photos from the database 50 at a time.
    val pagedMedia = viewModel.pagedMedia.collectAsLazyPagingItems()

    // Local state variables for this screen only
    var isSelectionMode by remember { mutableStateOf(false) } // Is the user actively clicking photos to build a manual story?
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) } // A list of the photos they clicked

    // When a user taps a story, this remembers WHICH story they tapped (e.g., Story #3) so we can play it.
    var activeStoryIndex by remember { mutableStateOf<Int?>(null) }
    var showNameDialog by remember { mutableStateOf(false) } // Show the popup asking "Name your story"
    var newStoryTitle by remember { mutableStateOf("") }

    val gridState = rememberLazyGridState() // Remembers how far down the user scrolled
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior() // Hides the top bar when scrolling down
    val pullRefreshState = rememberPullToRefreshState() // Handles the "pull down from top to refresh" animation
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope() // Tool for launching background workers

    // 🎨 2. SMART ADAPTIVE COLUMNS
    // Giant tablets should show more story cards side-by-side than small phones.
    val columnCount = when (adaptiveState.widthSize) {
        WindowWidthSize.COMPACT -> 2   // Phones show 2 cards per row
        WindowWidthSize.MEDIUM -> 3    // Small tablets show 3
        WindowWidthSize.EXPANDED -> 5  // Giant monitors show 5
    }

    // The Master Video Engine that plays videos smoothly without stuttering
    val sharedExoPlayer = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }

    // When the user leaves this screen completely, destroy the video engine to save RAM memory.
    DisposableEffect(sharedExoPlayer) {
        onDispose { sharedExoPlayer.release() }
    }

    // A small digital notebook remembering if the user agreed to receive daily notifications
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var isNotificationEnabled by remember { mutableStateOf(prefs.getBoolean("daily_notification", false)) }

    // THE BOUNCER. Asks the user if the app is allowed to send them a notification saying "You have a new memory!"
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isNotificationEnabled = true
            prefs.edit().putBoolean("daily_notification", true).apply()
            NotificationHelper.enableDailyNotifications(context) // Tell the Messenger Pigeon to start sending quotes
            Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // What happens when the user clicks the "Bell" icon in the top right
    val toggleNotification = {
        if (isNotificationEnabled) {
            // Turn it off
            isNotificationEnabled = false
            prefs.edit().putBoolean("daily_notification", false).apply()
            NotificationHelper.disableDailyNotifications(context)
            Toast.makeText(context, "Daily Reminders Disabled", Toast.LENGTH_SHORT).show()
        } else {
            // Turn it on (but we have to ask permission first on modern Android phones!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    isNotificationEnabled = true
                    prefs.edit().putBoolean("daily_notification", true).apply()
                    NotificationHelper.enableDailyNotifications(context)
                    Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) // Call the bouncer
                }
            } else {
                // Older phones don't ask for permission, they just let you do it
                isNotificationEnabled = true
                prefs.edit().putBoolean("daily_notification", true).apply()
                NotificationHelper.enableDailyNotifications(context)
                Toast.makeText(context, "Daily Reminders Enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handles the physical Android "Back" button or swipe gesture at the edge of the screen.
    // Normally, hitting "Back" closes the app. We intercept it and say "No, just close the Story Player instead."
    BackHandler(enabled = activeStoryIndex != null) {
        activeStoryIndex = null
    }

    // Intercept "Back" again. If they were selecting photos, just cancel the selection instead of closing the app.
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    // 🪄 THE ANIMATOR.
    // This makes the tiny rectangular story card smoothly grow and morph into the giant full-screen story player when tapped.
    SharedTransitionLayout {
        AnimatedContent(
            targetState = activeStoryIndex, // Watch the active story number
            label = "StoryMorph",
            transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(300))) }
        ) { currentIndex ->

            // If NO story is actively playing (currentIndex is null), show the main grid!
            if (currentIndex == null) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (isSelectionMode) {
                            // --- TOP BAR (Selection Mode) ---
                            // Changes the top bar to show "5 selected" and a "Create" button.
                            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                                TopAppBar(
                                    title = { Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                                    navigationIcon = {
                                        IconButton(onClick = { isSelectionMode = false; selectedIds = emptySet() }) { // Cancel button
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close")
                                        }
                                    },
                                    actions = {
                                        TextButton(onClick = { if (selectedIds.isNotEmpty()) showNameDialog = true }) { // Create button
                                            Text("Create", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                )
                            }
                        } else {
                            // --- TOP BAR (Normal Mode) ---
                            Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                                CenterAlignedTopAppBar(
                                    title = { Text("Memories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                                    navigationIcon = {
                                        val onBackPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                                        IconButton(onClick = { onBackPressedDispatcher?.onBackPressed() }) { // Standard back arrow
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    },
                                    actions = {
                                        // The Bell icon
                                        IconButton(onClick = toggleNotification) {
                                            Icon(
                                                imageVector = if (isNotificationEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                                                contentDescription = "Toggle Daily Reminder",
                                                tint = if (isNotificationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        // The Plus icon (to manually create a story)
                                        IconButton(onClick = { isSelectionMode = true }) {
                                            Icon(Icons.Rounded.Add, contentDescription = "Create Manual Memory")
                                        }
                                    },
                                    scrollBehavior = scrollBehavior,
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                ) { paddingValues ->

                    // Allows the user to put their finger near the top and pull down to refresh the list of stories.
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch { // Go to the background warehouse
                                storyViewModel.refreshMemories() // Tell the manager to rebuild the stories
                                delay(1.seconds) // Wait 1 second so the animation looks nice
                                isRefreshing = false // Stop the spinning circle
                            }
                        },
                        state = pullRefreshState,
                        modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {

                            // What goes in the middle of the screen?
                            if (isSelectionMode) {
                                // 1. The Photo Picker Grid (if they hit the "+" button)
                                MediaSelectorGrid(
                                    pagedMedia = pagedMedia,
                                    selectedIds = selectedIds,
                                    onToggle = { id: Long -> selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id }, // Add or remove photo from selection
                                    contentPadding = PaddingValues(0.dp),
                                    columnCount = columnCount
                                )
                            } else if (displayStories.isEmpty() && !isGenerating) {
                                // 2. The "Empty State" message (If they have 0 stories and the factory isn't building any)
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                        Box(modifier = Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Movie, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.height(24.dp))
                                        Text("No Memories Yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Your best moments will appear here automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    }
                                }
                            } else if (isGenerating && displayStories.isEmpty()) {
                                // 3. The "Building" spinner (If they have 0 stories, but the factory is actively building them right now)
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(16.dp))
                                        Text("Generating Memories...", fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(8.dp))
                                        Text("$generationProgress / $generationTotal", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) // e.g., "50 / 2000"
                                    }
                                }
                            } else {
                                // 4. THE MAIN GRID (Shows all the rectangular Story cards!)
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(columnCount),
                                    contentPadding = PaddingValues(top = 16.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                                ) {
                                    // Loop through our list of stories and draw a card for each one
                                    items(displayStories.size, key = { index -> displayStories[index].id }, contentType = { "story" }) { index ->
                                        val story = displayStories[index]

                                        // If the user made it, use their custom name. If the AI made it, call it "Memory 1", "Memory 2", etc.
                                        val displayTitle = if (story.id.startsWith("manual")) {
                                            story.title
                                        } else {
                                            "Memory ${index + 1}"
                                        }

                                        StoryCard(
                                            story = story,
                                            displayTitle = displayTitle,
                                            sharedTransitionScope = this@SharedTransitionLayout, // Give it access to the morphing animation
                                            animatedVisibilityScope = this@AnimatedContent,
                                            adaptiveState = adaptiveState,
                                            onClick = { activeStoryIndex = index }, // When tapped, set this story as the active one!
                                            onDelete = { storyViewModel.deleteStory(story.id) },
                                            onSave = { Toast.makeText(context, "Exporting '$displayTitle' to Gallery...", Toast.LENGTH_SHORT).show() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // --- THE FULL SCREEN PLAYER ---
                // If `currentIndex` is NOT null, it means the user tapped a story.
                // Don't show the grid anymore, show the giant full screen player!
                displayStories.getOrNull(currentIndex)?.let { activeStory ->
                    val displayTitle = if (activeStory.id.startsWith("manual")) {
                        activeStory.title
                    } else {
                        "Memory ${currentIndex + 1}"
                    }

                    StoryPlayer(
                        story = activeStory,
                        displayTitle = displayTitle,
                        sharedExoPlayer = sharedExoPlayer, // Hand it the Video Engine
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        adaptiveState = adaptiveState,
                        onClose = { activeStoryIndex = null }, // When they swipe down to close it, reset the index to null
                        onNextStoryGroup = {
                            // If they tap past the very last photo in the story, jump to the NEXT story in the grid automatically
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

    // A tiny popup window asking the user what to name their new custom story
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false; newStoryTitle = "" },
            title = { Text("Name your memory", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = newStoryTitle,
                    onValueChange = { newStoryTitle = it }, // Update the text as they type
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (selectedIds.isEmpty()) {
                        Toast.makeText(context, "Select at least one item", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (newStoryTitle.isBlank()) {
                        Toast.makeText(context, "Enter memory name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // Tell the Manager to save the new story to the database!
                    storyViewModel.createManualStory(selectedIds.toList(), newStoryTitle.trim())
                    isSelectionMode = false // Close the photo picker
                    selectedIds = emptySet()
                    newStoryTitle = ""
                    showNameDialog = false // Close the popup
                }) {
                    Text("Create")
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

/**
 * =========================================================================================
 * 🎴 THE INDIVIDUAL STORY CARD
 * =========================================================================================
 * The rectangular card showing the cover photo, title, and date of a story on the main grid.
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
    var showMenu by remember { mutableStateOf(false) } // Shows the "Delete" menu if they long-press the card

    // Animate a slight "shrink" effect when the user physically presses their finger on the card
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f), // Bouncy animation
        label = "StoryCardScale"
    )

    val videoCount = story.items.count { it.isVideo } // Count how many videos are inside

    // Calculate if the photos were taken "Today", "Yesterday", or "X days ago"
    val daysAgo = ((System.currentTimeMillis() - story.items.first().dateAdded * 1000L) / 86400000L).coerceAtLeast(0)
    val timeStr = if (daysAgo == 0L) "Today" else if (daysAgo == 1L) "Yesterday" else "$daysAgo days ago"

    with(sharedTransitionScope) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(0.56f).scale(scale) // Tall rectangle (9:16 aspect ratio)
                // 🪄 Magic tag! This tells Android exactly which card should morph into the full screen player.
                .sharedBounds(
                    sharedContentState = rememberSharedContentState("story_bounds_${story.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> spring<Rect>(dampingRatio = 0.8f, stiffness = 300f) }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showMenu = true }, // Pop up the menu
                        onTap = { onClick() } // Open the story!
                    )
                }
        ) {
            Box(Modifier.fillMaxSize()) {
                // The Background Cover Photo
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(story.coverUri)
                        .size(320) // Keep the resolution low so the grid scrolls fast
                        .precision(Precision.INEXACT)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .allowHardware(true)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // A dark gradient shadow at the bottom so the white text is readable against bright photos
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))

                // Little icon showing if it contains videos in the top right corner
                if (videoCount > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(8.dp)) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                // The Text (e.g., "Memory 1", "5 Items • 2 Videos • Today")
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Text(
                        text = displayTitle,
                        fontSize = (16 * adaptiveState.textScaleFactor).sp, // Scale the font size up if playing on a giant tablet
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${story.items.size} Items • ${if (videoCount > 0) "$videoCount Videos • " else ""}$timeStr",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = (12 * adaptiveState.textScaleFactor).sp
                    )
                }

                // The Delete/Save Menu (normally hidden)
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).clip(RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(text = { Text("Save to Device") }, leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) }, onClick = { showMenu = false; onSave() })
                    DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * ▶️ THE FULL SCREEN STORY PLAYER (The Slideshow Projector)
 * =========================================================================================
 * The actual player that flips through photos automatically, plays videos, and handles taps.
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
    var currentIndex by remember { mutableIntStateOf(0) } // Which specific photo in this story are we looking at right now?
    var isPaused by remember { mutableStateOf(false) } // Did the user hold their finger down to pause the slideshow?
    val currentItemProgress = remember { mutableFloatStateOf(0f) } // E.g., 0.5f means we are 50% through displaying this photo
    val currentItem = remember(currentIndex, story.items) { story.items.getOrNull(currentIndex) }
    val coroutineScope = rememberCoroutineScope()

    // Used for the "Swipe Down to Close" animation
    val dragOffsetY = remember { Animatable(0f) }

    // Math to make the video shrink and the corners round out as you drag your finger down the screen
    val animatedScale = 1f - (dragOffsetY.value / 2500f).coerceIn(0f, 0.4f)
    val dynamicCornerRadius = (dragOffsetY.value / 10f).coerceIn(0f, 48f).dp

    // Used to figure out if the user did a quick tap (to skip) or a long press (to pause)
    var pressTime by remember { mutableLongStateOf(0L) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }

    // Prevents the user from tapping "Next" 100 times a second and breaking the app
    var lastAdvanceTime by remember { mutableLongStateOf(0L) }
    val ADVANCE_LOCK_MS = 350L // Wait 0.35 seconds between taps

    fun canAdvance(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAdvanceTime < ADVANCE_LOCK_MS) return false
        lastAdvanceTime = now
        return true
    }

    // Preloads the next few photos into memory instantly so there is no black screen or loading circle
    LaunchedEffect(story) {
        story.items.take(3).forEach {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(it.uri)
                    .size(Size.ORIGINAL) // Get the full high-quality image
                    .allowHardware(true)
                    .build()
            )
        }
    }

    // Force Android to hide the phone's top clock/battery bar, and the bottom swipe bar, so it's truly full screen!
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // Allow them to swipe the edge to bring the clock back temporarily

        onDispose {
            // Put the clock back when they close the story player
            controller?.show(WindowInsetsCompat.Type.systemBars())
            sharedExoPlayer.stop()
            sharedExoPlayer.clearMediaItems()
        }
    }

    // Commands to run when skipping forward or backward
    val onNextState by rememberUpdatedState {
        if (canAdvance()) {
            sharedExoPlayer.seekTo(0) // Reset the video engine
            currentItemProgress.floatValue = 0f // Reset the progress bar
            if (currentIndex < story.items.lastIndex) currentIndex++ else onNextStoryGroup() // Go to the next photo, or the next entire story if we are at the end
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
        // If they are holding a giant iPad/Tablet horizontally, a full screen photo looks weird.
        // We restrict the width to 600 pixels so it looks like a normal vertical phone screen floating in the middle of their tablet.
        val widthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(600.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        // The pitch-black background
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = animatedScale.coerceIn(0f, 1f))), contentAlignment = Alignment.Center) {
            if (currentItem != null) {
                // The actual floating screen playing the movie
                Box(
                    modifier = Modifier.fillMaxHeight().then(widthModifier).scale(animatedScale).clip(RoundedCornerShape(dynamicCornerRadius))
                        // 🪄 Magic tag connecting this box to the tiny card on the grid!
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "story_bounds_${story.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> spring<Rect>(dampingRatio = 0.8f, stiffness = 300f) }
                        )
                ) {

                    // Display the content!
                    if (currentItem.isVideo) {
                        LifecycleAwareVideoPlayer(
                            exoPlayer = sharedExoPlayer,
                            uri = currentItem.uri,
                            isStoryPaused = isPaused || dragOffsetY.value > 0, // Pause the video if they are dragging the screen down
                            progressState = currentItemProgress,
                            onComplete = { onNextState() } // When the video finishes naturally, skip to the next photo automatically
                        )
                    } else {
                        SimpleImageItem(
                            uri = currentItem.uri,
                            isPaused = isPaused || dragOffsetY.value > 0,
                            durationMs = 5000L, // Show static photos for exactly 5 seconds
                            progressState = currentItemProgress,
                            onComplete = { onNextState() }
                        )
                    }

                    // A subtle shadow at the bottom to make the UI look premium and ensure text is readable
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)))))

                    // --- TOUCH CONTROLS ---
                    // An invisible layer on top of everything that detects where the user puts their fingers
                    Box(Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                // If they press down and hold...
                                onPress = { offset ->
                                    pressTime = System.currentTimeMillis()
                                    pressPosition = offset
                                    isPaused = true // Pause the slideshow immediately
                                    val released = tryAwaitRelease() // Wait for them to lift their finger
                                    isPaused = false // Resume the slideshow
                                },
                                // If they quickly tap and release...
                                onTap = { offset ->
                                    val holdDuration = System.currentTimeMillis() - pressTime
                                    val dx = abs(offset.x - pressPosition.x)
                                    val dy = abs(offset.y - pressPosition.y)

                                    // If they dragged their finger across the screen instead of tapping, or held it too long, ignore the tap.
                                    if (dx > 20f || dy > 20f || holdDuration > 150L) {
                                        return@detectTapGestures
                                    }

                                    // If they tapped on the left 25% of the screen, go backward.
                                    if (offset.x < size.width * 0.25f) {
                                        handleTap(false)
                                    }
                                    // If they tapped on the right 25% of the screen, go forward.
                                    else if (offset.x > size.width * 0.75f) {
                                        handleTap(true)
                                    }
                                }
                            )
                        }
                        // If they swipe their finger straight down...
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    // If they pulled the screen down more than 300 pixels, assume they want to close it.
                                    if (dragOffsetY.value > 300f) onClose()
                                    // Otherwise, snap the screen back up to the top like a rubber band.
                                    else coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                },
                                onDragCancel = {
                                    coroutineScope.launch { dragOffsetY.animateTo(0f, spring(0.7f, 400f)) }
                                }
                            ) { change, dragAmount ->
                                change.consume() // Consume the swipe so Android doesn't use it for something else
                                // As they drag down, physically move the box down the screen
                                if (dragAmount > 0 || dragOffsetY.value > 0) {
                                    coroutineScope.launch {
                                        // Complex math that adds "Resistance". The further down they pull, the harder it gets to pull (like a rubber band).
                                        dragOffsetY.snapTo(dragOffsetY.value + (dragAmount * (1f - (dragOffsetY.value / 2000f).coerceIn(0f, 0.8f))))
                                    }
                                }
                            }
                        }
                    )

                    // The tiny progress bars and Title text at the very top of the screen
                    StoryHeaderOverlay(
                        story = story,
                        displayTitle = displayTitle,
                        currentIndex = currentIndex,
                        currentItemProgress = currentItemProgress,
                        adaptiveState = adaptiveState,
                        onClose = onClose
                    )
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
fun StoryHeaderOverlay(story: UiStory, displayTitle: String, currentIndex: Int, currentItemProgress: State<Float>, adaptiveState: AdaptiveState, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, start = 8.dp, end = 8.dp)) {
        // Render one little white line for every single photo in the story
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in story.items.indices) {
                StoryProgressBar(index = index, currentIndex = currentIndex, currentItemProgress = currentItemProgress, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        // The little round picture, Title, and Date
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = story.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp).clip(CircleShape).border(1.dp, Color.White.copy(0.2f), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayTitle, color = Color.White, fontSize = (14 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Medium)
                if (story.subtitle.isNotEmpty()) {
                    Text(story.subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = (12 * adaptiveState.textScaleFactor).sp)
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

// Controls a single white line bar
@Composable
fun StoryProgressBar(index: Int, currentIndex: Int, currentItemProgress: State<Float>, modifier: Modifier) {
    val targetProgress = when {
        index < currentIndex -> 1f // If it's a photo we already watched, fill the bar to 100% instantly
        index > currentIndex -> 0f // If it's a future photo we haven't seen, keep it at 0%
        else -> currentItemProgress.value // If it's the CURRENT photo, slowly fill the bar
    }

    val animatedProgress by animateFloatAsState(targetValue = targetProgress, animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing), label = "StoryProgress")

    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = modifier.height(1.5.dp).clip(RoundedCornerShape(50)),
        color = Color.White, // The filled part
        trackColor = Color.White.copy(alpha = 0.3f) // The empty background part
    )
}

/**
 * =========================================================================================
 * ⚙️ STORY CREATOR SCREEN (Choosing photos manually)
 * =========================================================================================
 * The grid of photos with checkboxes on them.
 */
@Composable
fun MediaSelectorGrid(pagedMedia: LazyPagingItems<GalleryGridItem>, selectedIds: Set<Long>, onToggle: (Long) -> Unit, contentPadding: PaddingValues, columnCount: Int) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = 80.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            count = pagedMedia.itemCount,
            // If the item is a "Date Header" (e.g. "Today"), make it span all the columns. Otherwise, make it take 1 square.
            span = { index -> if (pagedMedia.peek(index) is GalleryGridItem.Header) GridItemSpan(columnCount) else GridItemSpan(1) },
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
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
                }
                is GalleryGridItem.Media -> {
                    val mediaItem = item.item
                    val isSelected = selectedIds.contains(mediaItem.id) // Did the user click this one?
                    // Shrink the photo slightly if it is selected to make it visually obvious
                    val scale by animateFloatAsState(if (isSelected) 0.85f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "GridScale")
                    val corner by animateDpAsState(if (isSelected) 16.dp else 4.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "GridCorner")

                    Box(Modifier.aspectRatio(1f).scale(scale).clip(RoundedCornerShape(corner)).clickable { onToggle(mediaItem.id) }.background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(mediaItem.uri)
                                .size(256) // Load a fast, low-res preview
                                .apply { if (mediaItem.isVideo) videoFrameMillis(1000) } // If it's a video, grab a screenshot from 1 second in
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // If it's a video, put a tiny "VIDEO" tag in the bottom right corner
                        if (mediaItem.isVideo) {
                            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(text = "VIDEO", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // The Checkmark
                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f))) // Make the photo slightly white
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape).size(24.dp))
                        } else {
                            // Show an empty circle if not selected
                            Icon(
                                imageVector = Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .shadow(2.dp, CircleShape)
                                    .size(24.dp)
                            )
                        }
                    }
                }
                null -> {
                    // Blank grey box shown while the real photo is still loading
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🖼️ CORE RENDERING TOOLS (Images and Videos)
 * =========================================================================================
 * These handle actually drawing the giant full-screen photo or video.
 */
@Composable
fun SimpleImageItem(uri: Uri, isPaused: Boolean, durationMs: Long, progressState: MutableState<Float>, onComplete: () -> Unit) {
    val progressAnim = remember { Animatable(0f) }

    // Starts a 5-second timer. If the timer reaches the end naturally, it runs `onComplete` (skip to next photo).
    LaunchedEffect(isPaused, uri) {
        if (!isPaused) {
            val remainingTime = ((1f - progressAnim.value) * durationMs).toInt()
            val result = progressAnim.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = remainingTime, easing = LinearEasing))
            if (result.endReason == AnimationEndReason.Finished) onComplete()
        }
    }

    // Sends the 0% to 100% progress number up to the main UI so the white progress bar lines at the top of the screen can fill up.
    LaunchedEffect(progressAnim) {
        snapshotFlow { progressAnim.value }.collect { progressState.value = it }
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(uri)
            .size(Size.ORIGINAL) // Full high-quality size
            .bitmapConfig(Bitmap.Config.RGB_565)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit, // Ensure the whole photo is visible, don't crop off heads
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LifecycleAwareVideoPlayer(exoPlayer: ExoPlayer, uri: Uri, isStoryPaused: Boolean, progressState: MutableState<Float>, onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current // Knows if the app is open, in the background, or closed
    var hasError by remember { mutableStateOf(false) }

    // Load the video into the engine
    LaunchedEffect(uri) {
        val mediaId = uri.toString()
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) {
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(ExoMediaItem.Builder().setUri(uri).setMediaId(mediaId).build())
            exoPlayer.prepare()
        }
        hasError = false
    }

    // Update the progress bar every 0.1 seconds so the white line at the top of the screen moves smoothly.
    LaunchedEffect(isStoryPaused, uri) {
        if (!isStoryPaused) {
            while (isActive) {
                if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.duration > 0) {
                    progressState.value = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()
                }
                delay(100.milliseconds)
            }
        }
    }

    // Connect wires to listen to the Video Engine
    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onComplete() // If the video hits the very end, skip to next photo
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Play/Pause commands from the user tapping the screen
    LaunchedEffect(isStoryPaused) {
        if (isStoryPaused) exoPlayer.pause() else exoPlayer.play()
    }

    // Automatically pause the video if someone calls the phone or the user presses the home button
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
            Text("Video failed to load", color = Color.White)
        }
    } else {
        // This takes the raw video data from the Engine and actually draws the pixels on the phone screen
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // Hide the play/pause/skip buttons, we handle touches manually!
                    setKeepContentOnPlayerReset(true) // Don't flash a black screen when switching videos
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )
    }
}