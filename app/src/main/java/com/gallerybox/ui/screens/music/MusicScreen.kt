@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

import android.app.Activity
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.TrashViewModel
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

sealed class MusicRoute(val route: String) {
    data object Dashboard : MusicRoute("dashboard")
    data object Library : MusicRoute("library")
}

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    initialUri: String? = null,
    viewModel: MusicViewModel,
    trashViewModel: TrashViewModel = hiltViewModel(),
    onViewerStateChanged: (Boolean) -> Unit = {},
    onNavigateToEqualizer: () -> Unit,
    onNavigateToRadio: () -> Unit,
    onNavigateToDuoPlayer: () -> Unit,
    onNavigateToDigitalRadio: () -> Unit,
    onNavigateToOnlineFinder: () -> Unit
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val context = LocalContext.current
    val adaptiveState = rememberAdaptiveState()

    var isSearchActive by remember { mutableStateOf(false) }
    var trackToTrash by remember { mutableStateOf<AudioTrack?>(null) }
    var hasPlayedInitial by remember { mutableStateOf(false) }
    var showSpeedPitchDialog by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val loadedSongsRaw by viewModel.allAudioTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var loadedSongs by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }
    var displaySongs by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }

    // Direct single-track playback using the working DuoTrack engine path (no full player screen)
    val playSelectedSong: (AudioTrack) -> Unit = { song ->
        viewModel.playDuoTrack(track = song, isPlayer2 = false)
        viewModel.setDuoMode(false)
    }

    LaunchedEffect(Unit) {
        onViewerStateChanged(false)
        viewModel.loadAllAudioTracks()
        trashViewModel.onRefreshMusic = {
            viewModel.loadAllAudioTracks()
        }
    }

    LaunchedEffect(loadedSongsRaw) {
        withContext(Dispatchers.Default) {
            loadedSongs = loadedSongsRaw.toImmutableList()
        }
    }

    LaunchedEffect(loadedSongs, searchQuery) {
        withContext(Dispatchers.Default) {
            displaySongs = if (searchQuery.isBlank()) {
                loadedSongs
            } else {
                loadedSongs.filter {
                    it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)
                }.toImmutableList()
            }
        }
    }

    LaunchedEffect(initialUri, loadedSongs) {
        if (!initialUri.isNullOrEmpty() && !hasPlayedInitial && loadedSongs.isNotEmpty()) {
            val targetUriStr = initialUri.trim()
            val trackToPlay = loadedSongs.find {
                it.path == targetUriStr ||
                        ContentUris.withAppendedId(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            it.id
                        ).toString() == targetUriStr
            }

            if (trackToPlay != null) {
                playSelectedSong(trackToPlay)
                hasPlayedInitial = true
            }
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultMusic(isGranted)
        if (isGranted) Toast.makeText(context, "Song moved to trash", Toast.LENGTH_SHORT).show()
        else Toast.makeText(context, "Trash permission denied", Toast.LENGTH_SHORT).show()
    }

    trackToTrash?.let { song ->
        AlertDialog(
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { trackToTrash = null },
            title = { Text("Move to Trash?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("Move '${song.title}' to the trash?") },
            confirmButton = {
                Button(
                    onClick = {
                        trashViewModel.confirmPendingMusicTrash(listOf(song), trashLauncher::launch)
                        trackToTrash = null
                    },
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)
                ) { Text("Move to Trash", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { trackToTrash = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showSpeedPitchDialog) {
        SpeedPitchDialog(
            viewModel = viewModel,
            adaptiveState = adaptiveState,
            onDismiss = { showSpeedPitchDialog = false }
        )
    }

    BackHandler(isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding(),
            topBar = {
                MusicTopAppBar(
                    currentRoute = currentRoute,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    adaptiveState = adaptiveState,
                    onSearchChange = viewModel::setSearchQuery,
                    onToggleSearch = {
                        isSearchActive = it
                        if (!it) viewModel.setSearchQuery("")
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToEqualizer = onNavigateToEqualizer,
                    onOpenSpeedPitch = { showSpeedPitchDialog = true }
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = currentTrack != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    currentTrack?.let { track ->
                        val playerWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                            Modifier.width(640.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        }

                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ModernMiniPlayer(
                                track = track,
                                isPlaying = isPlaying,
                                positionFlow = viewModel.currentPosition,
                                adaptiveState = adaptiveState,
                                modifier = playerWidth,
                                onPlayPause = viewModel::togglePlayPause,
                                onSeek = { ms -> viewModel.seekTo(ms, false) },
                                onOpenSpeedPitch = { showSpeedPitchDialog = true },
                                onNext = {
                                    val idx = displaySongs.indexOfFirst { it.id == track.id }
                                    if (idx != -1 && displaySongs.isNotEmpty()) {
                                        val nextSong = displaySongs[(idx + 1) % displaySongs.size]
                                        playSelectedSong(nextSong)
                                    } else {
                                        viewModel.skipNext()
                                    }
                                },
                                onPrev = {
                                    val idx = displaySongs.indexOfFirst { it.id == track.id }
                                    if (idx != -1 && displaySongs.isNotEmpty()) {
                                        val prevSong = displaySongs[(idx - 1 + displaySongs.size) % displaySongs.size]
                                        playSelectedSong(prevSong)
                                    } else {
                                        viewModel.skipPrevious()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (isPlaying) 0.14f else 0.06f),
                                    Color.Transparent
                                ),
                                radius = 900f
                            )
                        )
                )

                NavHost(
                    navController = navController,
                    startDestination = MusicRoute.Dashboard.route,
                    enterTransition = { slideInHorizontally { it / 2 } + fadeIn(tween(300)) },
                    exitTransition = { fadeOut(tween(300)) },
                    popEnterTransition = { fadeIn(tween(300)) },
                    popExitTransition = { slideOutHorizontally { it / 2 } + fadeOut(tween(300)) }
                ) {
                    composable(MusicRoute.Dashboard.route) {
                        DashboardScreen(
                            loadedSongs = loadedSongs,
                            adaptiveState = adaptiveState,
                            onNavigateToAllSongs = {
                                navController.navigate(MusicRoute.Library.route) { launchSingleTop = true }
                            },
                            onNavigateToRadio = onNavigateToRadio,
                            onNavigateToDuoMode = onNavigateToDuoPlayer,
                            onNavigateToEqualizer = onNavigateToEqualizer,
                            onNavigateToDigitalRadio = onNavigateToDigitalRadio,
                            onNavigateToOnlineFinder = onNavigateToOnlineFinder,
                            onPlayTrack = { song -> playSelectedSong(song) }
                        )
                    }
                    composable(MusicRoute.Library.route) {
                        LibraryContent(
                            displaySongs = displaySongs,
                            vm = viewModel,
                            adaptiveState = adaptiveState,
                            onTrashClick = { trackToTrash = it },
                            onSongClick = { song -> playSelectedSong(song) }
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.CUPCAKE)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTopAppBar(
    currentRoute: String?,
    isSearchActive: Boolean,
    searchQuery: String,
    adaptiveState: AdaptiveState,
    onSearchChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onOpenSpeedPitch: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var showTopMenu by remember { mutableStateOf(false) }

    if (isSearchActive) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            shadowElevation = 4.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onToggleSearch(false); keyboard?.hide() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = {
                        Text("Search songs, artists...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )
            }
        }
    } else {
        val isDashboard = currentRoute == MusicRoute.Dashboard.route
        val title = when (currentRoute) {
            MusicRoute.Library.route -> "All Songs"
            else -> "Music"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (!isDashboard) {
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onNavigateBack()
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                }

                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (22 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (currentRoute == MusicRoute.Dashboard.route ||
                    currentRoute == MusicRoute.Library.route
                ) {
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onToggleSearch(true)
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (isDashboard) {
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onNavigateToEqualizer()
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                contentDescription = "Equalizer",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Box {
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            showTopMenu = true
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Speed & Pitch", fontWeight = FontWeight.Bold) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            onClick = {
                                showTopMenu = false
                                onOpenSpeedPitch()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Equalizer", fontWeight = FontWeight.Bold) },
                            leadingIcon = {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            onClick = {
                                showTopMenu = false
                                onNavigateToEqualizer()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    loadedSongs: ImmutableList<AudioTrack>,
    adaptiveState: AdaptiveState,
    onNavigateToAllSongs: () -> Unit,
    onNavigateToRadio: () -> Unit,
    onNavigateToDuoMode: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToDigitalRadio: () -> Unit,
    onNavigateToOnlineFinder: () -> Unit,
    onPlayTrack: (AudioTrack) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    val dashboardWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
        Modifier.width(640.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    val triggerMasterShuffle: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (loadedSongs.isNotEmpty()) {
            val randomTrack = loadedSongs.random()
            onPlayTrack(randomTrack)
        } else {
            Toast.makeText(context, "Library is empty", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = dashboardWidth,
            contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.LibraryMusic,
                                "All Songs",
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                                adaptiveState,
                                onNavigateToAllSongs
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.Radio,
                                "FM Radio",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.onSurface,
                                adaptiveState,
                                onNavigateToRadio
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.Headset,
                                "Duo Player",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                Color(0xFF4FC3F7),
                                adaptiveState,
                                onNavigateToDuoMode
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.GraphicEq,
                                "Equalizer",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                Color(0xFF00E676),
                                adaptiveState,
                                onNavigateToEqualizer
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.Shuffle,
                                "Shuffle",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.onSurface,
                                adaptiveState,
                                onClick = triggerMasterShuffle
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.CellTower,
                                "Web Radio",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.primary,
                                adaptiveState,
                                onNavigateToDigitalRadio
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            QuickActionIcon(
                                Icons.Rounded.TravelExplore,
                                "Find Online",
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                Color(0xFFB388FF),
                                adaptiveState,
                                onNavigateToOnlineFinder
                            )
                        }
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionIcon(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    adaptiveState: AdaptiveState,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "TileScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClick()
                }
            )
    ) {
        Surface(
            modifier = Modifier.size(66.dp),
            shape = RoundedCornerShape(22.dp),
            color = containerColor,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.25f)),
            shadowElevation = 3.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = contentColor, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = (12 * adaptiveState.textScaleFactor).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LibraryContent(
    displaySongs: ImmutableList<AudioTrack>,
    vm: MusicViewModel,
    adaptiveState: AdaptiveState,
    onTrashClick: (AudioTrack) -> Unit,
    onSongClick: (AudioTrack) -> Unit
) {
    val listWidth = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) Modifier.width(640.dp) else Modifier.fillMaxWidth()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (displaySongs.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.MusicOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No songs found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = listWidth.fillMaxHeight(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displaySongs, key = { it.id }, contentType = { "song" }) { song ->
                    InteractiveSongRow(
                        song = song,
                        vm = vm,
                        adaptiveState = adaptiveState,
                        onClick = { onSongClick(song) },
                        onTrashClick = { onTrashClick(song) }
                    )
                }
            }
        }
    }
}

@Composable
fun InteractiveSongRow(
    song: AudioTrack,
    vm: MusicViewModel,
    adaptiveState: AdaptiveState,
    onClick: () -> Unit,
    onTrashClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val currentTrackRaw by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying = remember(currentTrackRaw?.id, song.id) { currentTrackRaw?.id == song.id }

    val titleColor by animateColorAsState(
        targetValue = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        label = "titleColor"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "bgColor"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(
            1.dp,
            if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(54.dp)) {
                AsyncImage(
                    getArtRequest(song.albumId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    song.title,
                    color = titleColor,
                    fontSize = (15 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${song.artist} • ${formatTime(song.duration)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (12 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    DropdownMenuItem(
                        text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onTrashClick() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

@Composable
fun ModernMiniPlayer(
    track: AudioTrack,
    isPlaying: Boolean,
    positionFlow: Flow<Long>,
    adaptiveState: AdaptiveState,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSpeedPitch: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current

    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    var isDragging by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = track.duration.coerceAtLeast(1L).toFloat()
    val currentFraction = if (isDragging) {
        sliderFraction
    } else {
        (position.toFloat() / safeDuration).coerceIn(0f, 1f)
    }

    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = colors.surfaceContainerHighest.copy(alpha = 0.98f),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(colors.primary.copy(alpha = 0.5f), colors.outlineVariant.copy(alpha = 0.25f))
            )
        ),
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    getArtRequest(track.albumId),
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .border(1.5.dp, colors.primary.copy(alpha = 0.5f), CircleShape)
                )

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                ) {
                    Text(
                        track.title,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (14 * adaptiveState.textScaleFactor).sp
                    )
                    Text(
                        track.artist,
                        color = colors.onSurfaceVariant,
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onPrev(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Surface(
                        onClick = { onPlayPause(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
                        shape = CircleShape,
                        color = colors.primary,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = colors.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { onNext(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onOpenSpeedPitch()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Speed & Pitch",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatTime(
                        if (isDragging) (safeDuration * sliderFraction).toLong() else position
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )

                Slider(
                    value = currentFraction,
                    onValueChange = {
                        isDragging = true
                        sliderFraction = it
                    },
                    onValueChangeFinished = {
                        val targetMs = (safeDuration * sliderFraction).toLong()
                        onSeek(targetMs)
                        isDragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                )

                Text(
                    text = formatTime(track.duration),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SpeedPitchDialog(
    viewModel: MusicViewModel,
    adaptiveState: AdaptiveState,
    onDismiss: () -> Unit
) {
    val speed by viewModel.speedPlayer1.collectAsStateWithLifecycle()
    val pitch by viewModel.pitchPlayer1.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Speed & Pitch",
                    fontSize = (18 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onSurface
                )
                TextButton(
                    onClick = {
                        viewModel.setPlayerSpeed(false, 1.0f)
                        viewModel.setPlayerPitch(false, 1.0f)
                    }
                ) {
                    Text("Reset (1.0x)", fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Playback Speed",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        String.format(Locale.US, "%.2fx", speed),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = colors.primary
                    )
                }
                Slider(
                    value = speed.coerceIn(0.5f, 2.0f),
                    onValueChange = { viewModel.setPlayerSpeed(false, it) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.surfaceVariant
                    )
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Audio Pitch",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        String.format(Locale.US, "%.2fx", pitch),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = colors.primary
                    )
                }
                Slider(
                    value = pitch.coerceIn(0.5f, 2.0f),
                    onValueChange = { viewModel.setPlayerPitch(false, it) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.surfaceVariant
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun getArtRequest(albumId: Long): ImageRequest? {
    val ctx = LocalContext.current
    return remember(albumId) {
        if (albumId > 0) {
            ImageRequest.Builder(ctx)
                .data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))
                .size(200)
                .allowHardware(false)
                .build()
        } else null
    }
}

fun getAlbumArtUri(albumId: Long): Uri? =
    if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null

fun formatTotalDuration(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    return if (h > 0) String.format(Locale.US, "%dh %dm", h, m) else String.format(Locale.US, "%dm", m)
}

fun formatTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}