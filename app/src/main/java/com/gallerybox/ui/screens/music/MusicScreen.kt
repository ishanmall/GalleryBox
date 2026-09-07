@file:Suppress("UnsafeOptInUsageError", "OPT_IN_USAGE", "unused", "DEPRECATION", "ObsoleteSdkInt")

package com.gallerybox.ui.screens.music

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.engine.MusicService
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.RadioViewModel
import com.gallerybox.viewmodel.TrashViewModel
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed class MusicRoute(val route: String) {
    data object Dashboard : MusicRoute("dashboard")
    data object Library : MusicRoute("library")
    data object Folders : MusicRoute("folders")
    data object Favorites : MusicRoute("favorites")
    data object DigitalRadio : MusicRoute("digital_radio")
    data object OnlineFinder : MusicRoute("online_finder")
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
    onNavigateToDuoPlayer: () -> Unit
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val context = LocalContext.current

    var showFullPlayer by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var trackToTrash by remember { mutableStateOf<AudioTrack?>(null) }
    var hasPlayedInitial by remember { mutableStateOf(false) }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showAudioInfoSheet by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val loadedSongsRaw by viewModel.allAudioTracks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var loadedSongs by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }
    var displaySongs by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }

    LaunchedEffect(Unit) {
        viewModel.loadAllAudioTracks()
        trashViewModel.onRefreshMusic = {
            viewModel.loadAllAudioTracks()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val intent = Intent(context, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // Process lists on background thread to prevent UI freezing
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
                loadedSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }.toImmutableList()
            }
        }
    }

    // Automatically play the requested track when launched externally
    LaunchedEffect(initialUri, loadedSongs) {
        if (!initialUri.isNullOrEmpty() && !hasPlayedInitial && loadedSongs.isNotEmpty()) {
            val targetUriStr = initialUri.trim()
            val trackToPlay = loadedSongs.find {
                it.path == targetUriStr ||
                        ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id).toString() == targetUriStr
            }

            if (trackToPlay != null) {
                viewModel.playQueue(loadedSongs, trackToPlay)
                showFullPlayer = true
                hasPlayedInitial = true
            }
        }
    }

    LaunchedEffect(showFullPlayer) { onViewerStateChanged(showFullPlayer) }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        trashViewModel.onPermissionResultMusic(isGranted)
        if (isGranted) Toast.makeText(context, "Song moved to trash", Toast.LENGTH_SHORT).show()
        else Toast.makeText(context, "Trash permission denied", Toast.LENGTH_SHORT).show()
    }

    trackToTrash?.let { song ->
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { trackToTrash = null },
            title = { Text("Move to Trash?", fontWeight = FontWeight.Bold) },
            text = { Text("Move '${song.title}' to the trash?") },
            confirmButton = {
                Button(
                    onClick = { trashViewModel.confirmPendingMusicTrash(listOf(song), trashLauncher::launch); trackToTrash = null },
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)
                ) { Text("Move", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { trackToTrash = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
    }

    BackHandler(showFullPlayer || isSearchActive) {
        if (showFullPlayer) showFullPlayer = false else { isSearchActive = false; viewModel.setSearchQuery("") }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.background
                )
            )
        ),
        topBar = {
            if (!showFullPlayer) {
                MusicTopAppBar(
                    currentRoute = currentRoute,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchChange = viewModel::setSearchQuery,
                    onToggleSearch = { isSearchActive = it; if (!it) viewModel.setSearchQuery("") },
                    onNavigateBack = navController::popBackStack,
                    onNavigateToEqualizer = onNavigateToEqualizer
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = currentTrack != null && !showFullPlayer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                currentTrack?.let { track ->
                    ModernMiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        positionFlow = viewModel.currentPosition,
                        onPlayPause = viewModel::togglePlayPause,
                        onClick = { showFullPlayer = true },
                        onNext = viewModel::skipNext,
                        onPrev = viewModel::skipPrevious
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
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
                        viewModel = viewModel,
                        loadedSongs = loadedSongs,
                        onNavigateToAllSongs = { navController.navigate(MusicRoute.Library.route) { launchSingleTop = true } },
                        onNavigateToRadio = onNavigateToRadio,
                        onNavigateToFolders = { navController.navigate(MusicRoute.Folders.route) },
                        onShowQueue = { showQueueSheet = true },
                        onNavigateToDuoMode = onNavigateToDuoPlayer,
                        onNavigateToFavorites = { navController.navigate(MusicRoute.Favorites.route) },
                        onNavigateToEqualizer = onNavigateToEqualizer,
                        onNavigateToDigitalRadio = { navController.navigate(MusicRoute.DigitalRadio.route) },
                        onNavigateToOnlineFinder = { navController.navigate(MusicRoute.OnlineFinder.route) }
                    )
                }
                composable(MusicRoute.Library.route) { LibraryContent(displaySongs, viewModel) { trackToTrash = it } }
                composable(MusicRoute.Favorites.route) { FavoritesScreen(viewModel, loadedSongs) { trackToTrash = it } }
                composable(MusicRoute.Folders.route) { FolderList(displaySongs, viewModel) { trackToTrash = it } }

                composable(MusicRoute.DigitalRadio.route) {
                    val radioViewModel = hiltViewModel<RadioViewModel>()
                    DigitalRadioScreen(
                        viewModel = radioViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(MusicRoute.OnlineFinder.route) {
                    OnlineSongFinderScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    if (showFullPlayer) {
        PlayerScreen(
            onBack = { showFullPlayer = false },
            viewModel = viewModel,
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            onShowQueue = { showFullPlayer = false; showQueueSheet = true },
            onShowAudioInfo = { showFullPlayer = false; showAudioInfoSheet = true }
        )
    }

    if (showQueueSheet) {
        QueueBottomSheet(
            viewModel = viewModel,
            currentTrack = currentTrack,
            onDismiss = { showQueueSheet = false },
            onTrashClick = { trackToTrash = it; showQueueSheet = false }
        )
    }

    if (showAudioInfoSheet) {
        AudioInfoBottomSheet(
            currentTrack = currentTrack,
            onDismiss = { showAudioInfoSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTopAppBar(
    currentRoute: String?,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToEqualizer: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current

    if (isSearchActive) {
        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).statusBarsPadding(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onToggleSearch(false); keyboard?.hide() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search Music...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                                Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                )
            }
        }
    } else {
        val title = when (currentRoute) {
            MusicRoute.Library.route -> "All Songs"
            MusicRoute.Folders.route -> "Folders"
            MusicRoute.Favorites.route -> "Favorites"
            MusicRoute.Dashboard.route -> "Music"
            MusicRoute.DigitalRadio.route -> "Digital Radio"
            MusicRoute.OnlineFinder.route -> "Find Songs"
            else -> "Music"
        }
        CenterAlignedTopAppBar(
            title = { Text(text = title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (currentRoute != MusicRoute.Dashboard.route) IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) } },
            actions = {
                if (currentRoute == MusicRoute.Dashboard.route || currentRoute == MusicRoute.Library.route || currentRoute == MusicRoute.Favorites.route || currentRoute == MusicRoute.Folders.route) {
                    IconButton(onClick = { onToggleSearch(true) }) { Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurface) }
                }
                if (currentRoute == MusicRoute.Dashboard.route) {
                    IconButton(onClick = onNavigateToEqualizer) { Icon(Icons.Rounded.GraphicEq, "Equalizer", tint = MaterialTheme.colorScheme.onSurface) }
                }
            }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun DashboardScreen(
    viewModel: MusicViewModel,
    loadedSongs: ImmutableList<AudioTrack>,
    onNavigateToAllSongs: () -> Unit,
    onNavigateToRadio: () -> Unit,
    onNavigateToFolders: () -> Unit,
    onShowQueue: () -> Unit,
    onNavigateToDuoMode: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToDigitalRadio: () -> Unit,
    onNavigateToOnlineFinder: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))

                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.Rounded.Favorite, "Favorites", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, onNavigateToFavorites) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.Rounded.Folder, "Folders", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, onNavigateToFolders) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onShowQueue) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.Rounded.LibraryMusic, "All Songs", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, onNavigateToAllSongs) }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.Rounded.Radio, "FM Radio", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onNavigateToRadio) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QuickActionIcon(Icons.Rounded.Headset, "Duo Player", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onNavigateToDuoMode) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        QuickActionIcon(Icons.Rounded.Shuffle, "Shuffle", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) {
                            scope.launch(Dispatchers.Default) {
                                if (loadedSongs.isNotEmpty()) {
                                    val toPlay = if (loadedSongs.size > 1000) loadedSongs.shuffled().take(1000) else loadedSongs.shuffled()
                                    toPlay.firstOrNull()?.let { track ->
                                        withContext(Dispatchers.Main) { viewModel.playQueue(toPlay, track) }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Library is empty", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        QuickActionIcon(Icons.Rounded.GraphicEq, "Equalizer", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onNavigateToEqualizer)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        QuickActionIcon(Icons.Rounded.CellTower, "Web Radio", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onNavigateToDigitalRadio)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        QuickActionIcon(Icons.Rounded.TravelExplore, "Find Online", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, onNavigateToOnlineFinder)
                    }
                    Box(Modifier.weight(1f))
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun QuickActionIcon(icon: ImageVector, label: String, containerColor: Color, contentColor: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick, interactionSource = remember { MutableInteractionSource() }, indication = null).width(76.dp)) {
        Surface(modifier = Modifier.size(64.dp), shape = RoundedCornerShape(20.dp), color = containerColor) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = contentColor, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FavoritesScreen(viewModel: MusicViewModel, allSongs: ImmutableList<AudioTrack>, onTrashClick: (AudioTrack) -> Unit) {
    val favoriteIdsRaw by viewModel.favoriteIds.collectAsStateWithLifecycle()
    var favoriteSongs by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }

    LaunchedEffect(allSongs, favoriteIdsRaw) {
        withContext(Dispatchers.Default) {
            val favSet = favoriteIdsRaw.toSet()
            favoriteSongs = allSongs.filter { it.id in favSet }.toImmutableList()
        }
    }

    if (favoriteSongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No favorites yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            items(favoriteSongs, key = { it.id }, contentType = { "song" }) { song ->
                InteractiveSongRow(song, viewModel, { viewModel.playQueue(favoriteSongs, song) }, { onTrashClick(song) })
            }
        }
    }
}

@Composable
fun LibraryContent(displaySongs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    if (displaySongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No songs found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            items(displaySongs, key = { it.id }, contentType = { "song" }) { song ->
                InteractiveSongRow(song, vm, { vm.playQueue(displaySongs, song) }, { onTrashClick(song) })
            }
        }
    }
}

@Composable
fun FolderList(songs: ImmutableList<AudioTrack>, vm: MusicViewModel, onTrashClick: (AudioTrack) -> Unit) {
    var folders by remember { mutableStateOf<Map<String, List<AudioTrack>>>(emptyMap()) }
    var folderKeys by remember { mutableStateOf<ImmutableList<String>>(persistentListOf()) }
    var activeTracks by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }

    // Run folder grouping in background
    LaunchedEffect(songs) {
        withContext(Dispatchers.Default) {
            val grouped = songs.filter { it.path.isNotBlank() }
                .groupBy { it.path.substringBeforeLast("/", "Unknown").trim() }
                .toSortedMap()
            folders = grouped
            folderKeys = grouped.keys.toList().toImmutableList()

            // Sync tracks if a folder is already open
            if (selectedFolder != null) {
                activeTracks = grouped[selectedFolder]?.toImmutableList() ?: persistentListOf()
            }
        }
    }

    // Refresh active tracks when folder changes
    LaunchedEffect(selectedFolder, folders) {
        withContext(Dispatchers.Default) {
            activeTracks = if (selectedFolder != null) {
                folders[selectedFolder]?.toImmutableList() ?: persistentListOf()
            } else {
                persistentListOf()
            }
        }
    }

    BackHandler(enabled = selectedFolder != null) { selectedFolder = null }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = selectedFolder, transitionSpec = { if (targetState == null) slideInHorizontally { -it } togetherWith slideOutHorizontally { it } else slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }, label = "FolderTransition") { activeFolder ->
            if (activeFolder == null) {
                if (folders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No folders found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                        items(folderKeys, key = { it }, contentType = { "folder" }) { path ->
                            val firstTrack = folders[path]?.firstOrNull { it.albumId > 0 }
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { selectedFolder = path }) {
                                Box {
                                    if (firstTrack != null) {
                                        AsyncImage(getArtRequest(firstTrack.albumId), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))))

                                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                                        Icon(Icons.Rounded.Folder, null, tint = Color.White)
                                        Spacer(Modifier.height(8.dp))
                                        Text(path.substringAfterLast("/"), fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${folders[path]?.size ?: 0} Files", color = Color.White.copy(0.8f), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedFolder = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
                        Text(activeFolder.substringAfterLast("/"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (activeTracks.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { Text("Folder is empty", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                            items(activeTracks, key = { it.id }, contentType = { "song" }) { song ->
                                InteractiveSongRow(song, vm, { vm.playQueue(activeTracks, song) }, { onTrashClick(song) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(viewModel: MusicViewModel, currentTrack: AudioTrack?, onDismiss: () -> Unit, onTrashClick: (AudioTrack) -> Unit) {
    val activeQueueRaw by viewModel.currentQueue.collectAsStateWithLifecycle()
    var activeQueue by remember { mutableStateOf<ImmutableList<AudioTrack>>(persistentListOf()) }

    LaunchedEffect(activeQueueRaw) {
        withContext(Dispatchers.Default) {
            activeQueue = activeQueueRaw.toImmutableList()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            if (currentTrack != null) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                InteractiveSongRow(currentTrack, viewModel, { }, { onTrashClick(currentTrack) })
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            Text("UP NEXT", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (activeQueue.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { Text("No more tracks in queue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(activeQueue, key = { _, it -> it.id }, contentType = { _, _ -> "song" }) { index, song ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.playQueue(activeQueue, song) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { viewModel.removeFromQueue(song) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove from Queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioInfoBottomSheet(currentTrack: AudioTrack?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 48.dp, top = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Audio Info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }

            AudioInfoItem("Title", currentTrack?.title ?: "N/A")
            AudioInfoItem("Artist", currentTrack?.artist ?: "N/A")
            AudioInfoItem("Album", currentTrack?.album ?: "N/A")
            AudioInfoItem("Duration", currentTrack?.let { formatTime(it.duration) } ?: "N/A")
            AudioInfoItem("Format", currentTrack?.path?.substringAfterLast('.')?.uppercase(Locale.US) ?: "N/A")

            val cleanPath = currentTrack?.path?.let { path ->
                if (path.contains("0/")) ".../${path.substringAfter("0/")}" else path
            } ?: "N/A"
            AudioInfoItem("Location", cleanPath)
        }
    }
}

@Composable
fun AudioInfoItem(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: MusicViewModel, currentTrack: AudioTrack?, isPlaying: Boolean, onShowQueue: () -> Unit, onShowAudioInfo: () -> Unit) {
    val ctx = LocalContext.current

    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val isFavorite = remember(currentTrack?.id, favoriteIds) { favoriteIds.contains(currentTrack?.id) }

    var showSleepTimer by remember { mutableStateOf(false) }
    var showEffectsSheet by remember { mutableStateOf(false) }

    val bgArtworkReq = remember(currentTrack?.albumId) {
        ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(400).allowHardware(true).build()
    }
    val artworkReq = remember(currentTrack?.albumId) {
        ImageRequest.Builder(ctx).data(getAlbumArtUri(currentTrack?.albumId ?: -1)).size(800).error(android.R.drawable.ic_media_play).build()
    }

    val rotation = remember(currentTrack?.id) { Animatable(0f) }
    LaunchedEffect(isPlaying, currentTrack?.id) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 14000, easing = LinearEasing)
                )
            }
        }
    }

    val artScale by animateFloatAsState(if (isPlaying) 1f else 0.92f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "artScale")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AsyncImage(bgArtworkReq, null, modifier = Modifier.fillMaxSize().blur(48.dp), contentScale = ContentScale.Crop, alpha = 0.4f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.9f)))))

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerHeader(currentTrack?.album, viewModel.sleepTimeRemaining, onBack, { showSleepTimer = true }, { showEffectsSheet = true })

            Box(
                modifier = Modifier
                    .weight(0.65f)
                    .aspectRatio(1f)
                    .padding(vertical = 24.dp)
                    .scale(artScale),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 24.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotation.value }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = artworkReq,
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                            )
                        }
                    }
                }
            }

            TrackMetadata(currentTrack)
            Spacer(Modifier.height(16.dp))
            IsolatedProgressBar(viewModel.currentPosition, currentTrack?.duration ?: 1L) { viewModel.seekTo(it, false) }
            Spacer(Modifier.height(32.dp))

            PlaybackControls(isPlaying, viewModel.isShuffleEnabled, viewModel.repeatMode,
                onToggleShuffle = { viewModel.toggleShuffle() },
                onSkipPrev = { viewModel.skipPrevious() },
                onRewind = { viewModel.seekTo((viewModel.currentPosition.value - 10000).coerceAtLeast(0L), false) },
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onForward = { viewModel.seekTo((viewModel.currentPosition.value + 10000).coerceAtMost(currentTrack?.duration ?: 1L), false) },
                onSkipNext = { viewModel.skipNext() },
                onToggleRepeat = { viewModel.toggleRepeat() }
            )

            Spacer(Modifier.height(32.dp))

            Surface(color = Color.White.copy(alpha = 0.1f), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Row(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PlayerBottomButton(
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        label = "Favorite",
                        color = if(isFavorite) Color.Red else Color.White,
                        onClick = { currentTrack?.let { viewModel.toggleFavorite(listOf(it.id)) } }
                    )
                    PlayerBottomButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Queue",
                        color = Color.White,
                        onClick = onShowQueue
                    )
                    PlayerBottomButton(
                        icon = Icons.Rounded.Info,
                        label = "Info",
                        color = Color.White,
                        onClick = onShowAudioInfo
                    )
                }
            }
        }
    }
    if (showSleepTimer) SleepTimerDialog({ showSleepTimer = false }, { viewModel.startSleepTimer(it) }, { viewModel.cancelSleepTimer() })
    if (showEffectsSheet) AdvancedEffectsBottomSheet(viewModel) { showEffectsSheet = false }
}

@Composable
fun PlayerBottomButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun IsolatedProgressBar(positionFlow: Flow<Long>, duration: Long, onSeek: (Long) -> Unit) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    var isDragging by remember { mutableStateOf(false) }
    var sliderVal by remember { mutableFloatStateOf(0f) }
    val safeDur = duration.coerceAtLeast(1L).toFloat()

    val animatedProgress by animateFloatAsState(
        targetValue = if (isDragging) sliderVal else (position.toFloat() / safeDur).coerceIn(0f, 1f),
        label = "progressBarAnimation"
    )

    Column {
        Slider(
            value = animatedProgress,
            onValueChange = { isDragging = true; sliderVal = it },
            onValueChangeFinished = { onSeek((safeDur * sliderVal).toLong()); isDragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.height(24.dp)
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(if (isDragging) (safeDur * sliderVal).toLong() else position), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun PlaybackControls(isPlaying: Boolean, isShuffleEnabled: Boolean, repeatMode: Int, onToggleShuffle: () -> Unit, onSkipPrev: () -> Unit, onRewind: () -> Unit, onTogglePlayPause: () -> Unit, onForward: () -> Unit, onSkipNext: () -> Unit, onToggleRepeat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if(isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(0.7f), modifier = Modifier.size(24.dp)) }
        IconButton(onClick = onSkipPrev, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Prev", modifier = Modifier.size(36.dp), tint = Color.White) }
        Surface(onClick = onTogglePlayPause, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
        }
        IconButton(onClick = onSkipNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(36.dp), tint = Color.White) }
        IconButton(onClick = onToggleRepeat, modifier = Modifier.size(40.dp)) { Icon(if (repeatMode != Player.REPEAT_MODE_OFF) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if(repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White.copy(0.7f), modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun InteractiveSongRow(song: AudioTrack, vm: MusicViewModel, onClick: () -> Unit, onTrashClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val currentTrackRaw by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying = remember(currentTrackRaw?.id, song.id) { currentTrackRaw?.id == song.id }

    val titleColor by animateColorAsState(targetValue = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, label = "titleColor")
    val bgColor by animateColorAsState(targetValue = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else Color.Transparent, label = "bgColor")

    Row(modifier = Modifier.fillMaxWidth().background(bgColor).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp).drawBehind { if(isPlaying) drawLine(color = titleColor, start = Offset(0f, 0f), end = Offset(0f, size.height), strokeWidth = 4.dp.toPx()) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp)) {
            AsyncImage(getArtRequest(song.albumId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).let { if(isPlaying) it.border(2.dp, titleColor, RoundedCornerShape(12.dp)) else it })
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(song.title, color = titleColor, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} • ${formatTotalDuration(song.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            DropdownMenuItem(text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; vm.playNext(song) }, leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; vm.addToQueue(song) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = MaterialTheme.colorScheme.onSurface) })
            DropdownMenuItem(text = { Text("Move to Trash", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onTrashClick() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedEffectsBottomSheet(viewModel: MusicViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playback Effects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { viewModel.resetAudioEffects() }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    CompactSlider("Pitch (Semitones)", viewModel.pitchPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, MaterialTheme.colorScheme.primary) { viewModel.setPlayerPitch(false, it) }
                    Spacer(Modifier.height(16.dp))
                    CompactSlider("Speed", viewModel.speedPlayer1.collectAsStateWithLifecycle().value, 0.5f..2.0f, MaterialTheme.colorScheme.primary) { viewModel.setPlayerSpeed(false, it) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    CompactSlider("Volume", viewModel.volume1.collectAsStateWithLifecycle().value, 0.0f..1.0f, MaterialTheme.colorScheme.primary) { viewModel.updateVolume(it, false) }
                    Spacer(Modifier.height(16.dp))
                    CompactSlider("Stereo Balance (L/R)", viewModel.balance1.collectAsStateWithLifecycle().value, -1.0f..1.0f, MaterialTheme.colorScheme.primary) { viewModel.updateBalance(it, false) }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
fun CompactSlider(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, activeColor: Color, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.US, "%.2f", value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun ModernMiniPlayer(
    track: AudioTrack,
    isPlaying: Boolean,
    positionFlow: Flow<Long>,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    var swipeDirection by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val swipeThreshold = remember(density) { with(density) { 60.dp.toPx() } }

    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(Unit) {
                var accumulatedX = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (accumulatedX > swipeThreshold) {
                            swipeDirection = -1
                            onPrev()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        } else if (accumulatedX < -swipeThreshold) {
                            swipeDirection = 1
                            onNext()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        accumulatedX = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    accumulatedX += dragAmount
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    (slideInHorizontally { width -> swipeDirection * width } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -swipeDirection * width } + fadeOut())
                },
                label = "MiniPlayerTrackTransition"
            ) { animatedTrack ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        getArtRequest(animatedTrack.albumId),
                        "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceVariant)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).padding(end = 120.dp)) {
                        Text(
                            animatedTrack.title,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            animatedTrack.artist,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
            ) {
                IconButton(onClick = { onPrev(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = colors.onSurface, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { onPlayPause(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, modifier = Modifier.size(48.dp)) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play/Pause", tint = colors.onSurface, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { onNext(); view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = colors.onSurface, modifier = Modifier.size(24.dp))
                }
            }

            MiniPlayerProgressBar(positionFlow, track.duration)
        }
    }
}

@Composable
private fun BoxScope.MiniPlayerProgressBar(positionFlow: Flow<Long>, duration: Long) {
    val position by positionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val progress = remember(position, duration) { if (duration > 0) (position.coerceIn(0, duration)).toFloat() / duration else 0f }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "miniProgressBar")

    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
fun PlayerHeader(albumName: String?, sleepTimeRemaining: Long, onBack: () -> Unit, onSleepTimerClick: () -> Unit, onEffectsClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onBack, shape = CircleShape, color = Color.White.copy(0.15f), modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = Color.White) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = Color.White.copy(0.7f), fontWeight = FontWeight.Bold)
            Text(albumName ?: "Unknown Album", style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepTimeRemaining > 0) Text("${sleepTimeRemaining / 60000L}m", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
            IconButton(onClick = onSleepTimerClick) { Icon(Icons.Filled.Bedtime, "Sleep Timer", tint = if (sleepTimeRemaining > 0) MaterialTheme.colorScheme.primary else Color.White) }
            IconButton(onClick = onEffectsClick) { Icon(Icons.Rounded.GraphicEq, "Effects", tint = Color.White) }
        }
    }
}

@Composable
fun TrackMetadata(track: AudioTrack?) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: "Not Playing", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist ?: "Unknown", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit, onSet: (Int) -> Unit, onCancel: () -> Unit) {
    var customTime by remember { mutableStateOf("") }

    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        Surface(
                            onClick = { onSet(mins); onDismiss() },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${mins}m", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = customTime,
                    onValueChange = { customTime = it.filter { char -> char.isDigit() }.take(3) },
                    label = { Text("Custom minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val mins = customTime.toIntOrNull()
                if (mins != null && mins > 0) onSet(mins)
                onDismiss()
            }) { Text("Set Timer") }
        },
        dismissButton = {
            TextButton(onClick = { onCancel(); onDismiss() }) { Text("Stop Active Timer", color = MaterialTheme.colorScheme.error) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
fun getArtRequest(albumId: Long): ImageRequest? {
    val ctx = LocalContext.current
    return remember(albumId) {
        if (albumId > 0) ImageRequest.Builder(ctx).data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)).size(200).build() else null
    }
}

fun getAlbumArtUri(albumId: Long): Uri? {
    return if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
}

fun formatTotalDuration(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    return if (h > 0) String.format(Locale.US, "%dh %dm", h, m) else String.format(Locale.US, "%dm", m)
}

fun formatTime(ms: Long): String {
    val t = ms / 1000
    return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}