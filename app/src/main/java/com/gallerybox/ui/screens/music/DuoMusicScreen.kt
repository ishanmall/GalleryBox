@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

import android.content.ContentUris
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.MusicViewModel
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

// --- IMPORTING THE ADAPTIVE ENGINE ---
// This tells the app how to handle folding phones and big tablet screens.
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// --- SHARED THEME COLORS ---
// The base colors for the Left (Player 1) and Right (Player 2) earphones.
private val Player1BaseColor = Color(0xFF64B5F6) // Light Blue
private val Player2BaseColor = Color(0xFFFF8DA1) // Pink

/**
 * Loads the album cover artwork for a song.
 * We use 'remember' so the phone doesn't do the hard work of loading the picture over and over again.
 */
@Composable
fun rememberAlbumArtRequest(albumId: Long, size: Int, crossfade: Boolean): ImageRequest {
    val context = LocalContext.current
    return remember(albumId, size, crossfade) {
        val artUri = if (albumId > 0) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
        ImageRequest.Builder(context)
            .data(artUri)
            .size(size)
            .allowHardware(true)
            .crossfade(crossfade)
            .error(android.R.drawable.ic_menu_gallery) // Shows a default icon if there is no album art
            .build()
    }
}

/**
 * THE MAIN SCREEN FOR THE DJ / DUO MUSIC PLAYER.
 * This is where everything comes together: the top player, the bottom player, and the divider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuoMusicScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    // 1. Get the list of all songs from the phone
    val pagedSongs = viewModel.pagedAudio.collectAsLazyPagingItems()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // 2. Listen to Player 1 (Left Earphone) to know what song is playing, its speed, and if it's paused.
    val track1 by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying1 by viewModel.isPlaying.collectAsStateWithLifecycle()
    val pos1 by viewModel.currentPosition.collectAsStateWithLifecycle()
    val speed1 by viewModel.speedPlayer1.collectAsStateWithLifecycle()
    val pitch1 by viewModel.pitchPlayer1.collectAsStateWithLifecycle()

    // 3. Listen to Player 2 (Right Earphone) for the same details.
    val track2 by viewModel.currentTrack2.collectAsStateWithLifecycle()
    val isPlaying2 by viewModel.isPlaying2.collectAsStateWithLifecycle()
    val pos2 by viewModel.currentPosition2.collectAsStateWithLifecycle()
    val speed2 by viewModel.speedPlayer2.collectAsStateWithLifecycle()
    val pitch2 by viewModel.pitchPlayer2.collectAsStateWithLifecycle()

    // 4. Screen States (Things that change when the user taps on stuff)
    var showSheet by remember { mutableStateOf(false) } // Shows the song selection menu
    var activePlayerForSelection by remember { mutableIntStateOf(1) } // Remembers which earphone needs a new song
    var isLinked by remember { mutableStateOf(false) } // Tells us if the Link button is turned on

    val view = LocalView.current // Used to make the phone vibrate on button clicks

    // Animate the colors to Purple if the 'Link' button is pressed. Otherwise, stay Blue/Pink.
    val p1Color by animateColorAsState(targetValue = if (isLinked) Color(0xFFB39DDB) else Player1BaseColor, label = "p1Color")
    val p2Color by animateColorAsState(targetValue = if (isLinked) Color(0xFFB39DDB) else Player2BaseColor, label = "p2Color")

    // --- ADAPTIVE SCREEN CHECK ---
    // This asks the phone: "Are you a tablet? Are you a folding phone held like a book?"
    val adaptiveState = rememberAdaptiveState()

    // We split the screen Side-by-Side (Left and Right) IF it's a book mode fold, a wide screen, or held horizontally.
    val isVerticalSplit = adaptiveState.posture == DevicePosture.BOOK_MODE ||
            adaptiveState.widthSize != WindowWidthSize.COMPACT ||
            adaptiveState.isLandscape

    // When this screen opens, tell the background music engine to switch to "Duo Mode".
    DisposableEffect(Unit) {
        viewModel.setDuoMode(true)
        onDispose {
            viewModel.setDuoMode(false) // Turn it off when the user leaves the screen
            viewModel.setSearchQuery("")
        }
    }

    // Handles the Android hardware back button
    BackHandler { onBack() }

    // This Box holds everything on the screen and paints the background color.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        if (isVerticalSplit) {
            // =========================================================================
            // SIDE-BY-SIDE LAYOUT (For Tablets, Foldables, and Landscape Phones)
            // =========================================================================
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT EARPHONE
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DuoPlayerHalf(
                        label = "LEFT EARPHONE", color = p1Color, track = track1, isPlaying = isPlaying1,
                        position = pos1, speedVal = speed1, pitchVal = pitch1, isTop = true,
                        onPlayPause = {
                            if (isPlaying1) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(false)
                            else if (isLinked) viewModel.playBothSynced() else viewModel.play(false)
                        },
                        onSeek = { ms ->
                            viewModel.seekTo(ms, false)
                            if (isLinked) viewModel.seekTo(ms, true)
                        },
                        onOpenLibrary = { activePlayerForSelection = 1; showSheet = true },
                        onSpeed = {
                            viewModel.setPlayerSpeed(false, it)
                            if (isLinked) viewModel.setPlayerSpeed(true, it)
                        },
                        onPitch = {
                            viewModel.setPlayerPitch(false, it)
                            if (isLinked) viewModel.setPlayerPitch(true, it)
                        }
                    )
                }

                // THE DJ DIVIDER (Now standing vertically between the left and right players)
                PremiumDJDivider(
                    p1Color = p1Color, p2Color = p2Color, isPlaying = isPlaying1 || isPlaying2,
                    isLinked = isLinked, isVerticalSplit = true,
                    onSync = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.setPlayerSpeed(true, speed1)
                        viewModel.setPlayerPitch(true, pitch1)
                    },
                    onLink = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        isLinked = !isLinked
                        if (isLinked) {
                            viewModel.seekTo(pos1, true)
                            viewModel.setPlayerSpeed(true, speed1)
                            viewModel.setPlayerPitch(true, pitch1)
                        }
                    },
                    onCrossfade = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        viewModel.crossfadePlayers()
                    }
                )

                // RIGHT EARPHONE
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DuoPlayerHalf(
                        label = "RIGHT EARPHONE", color = p2Color, track = track2, isPlaying = isPlaying2,
                        position = pos2, speedVal = speed2, pitchVal = pitch2, isTop = false,
                        onPlayPause = {
                            if (isPlaying2) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(true)
                            else if (isLinked) viewModel.playBothSynced() else viewModel.play(true)
                        },
                        onSeek = { ms ->
                            viewModel.seekTo(ms, true)
                            if (isLinked) viewModel.seekTo(ms, false)
                        },
                        onOpenLibrary = { activePlayerForSelection = 2; showSheet = true },
                        onSpeed = {
                            viewModel.setPlayerSpeed(true, it)
                            if (isLinked) viewModel.setPlayerSpeed(false, it)
                        },
                        onPitch = {
                            viewModel.setPlayerPitch(true, it)
                            if (isLinked) viewModel.setPlayerPitch(false, it)
                        }
                    )
                }
            }
        } else {
            // =========================================================================
            // TOP-AND-BOTTOM LAYOUT (For Standard Phones held normally)
            // =========================================================================
            Column(modifier = Modifier.fillMaxSize()) {
                // TOP PLAYER (Left Earphone)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    DuoPlayerHalf(
                        label = "LEFT EARPHONE", color = p1Color, track = track1, isPlaying = isPlaying1,
                        position = pos1, speedVal = speed1, pitchVal = pitch1, isTop = true,
                        onPlayPause = {
                            if (isPlaying1) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(false)
                            else if (isLinked) viewModel.playBothSynced() else viewModel.play(false)
                        },
                        onSeek = { ms ->
                            viewModel.seekTo(ms, false)
                            if (isLinked) viewModel.seekTo(ms, true)
                        },
                        onOpenLibrary = { activePlayerForSelection = 1; showSheet = true },
                        onSpeed = {
                            viewModel.setPlayerSpeed(false, it)
                            if (isLinked) viewModel.setPlayerSpeed(true, it)
                        },
                        onPitch = {
                            viewModel.setPlayerPitch(false, it)
                            if (isLinked) viewModel.setPlayerPitch(true, it)
                        }
                    )
                }

                // THE DJ DIVIDER (Laying flat horizontally between top and bottom players)
                PremiumDJDivider(
                    p1Color = p1Color, p2Color = p2Color, isPlaying = isPlaying1 || isPlaying2,
                    isLinked = isLinked, isVerticalSplit = false,
                    onSync = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.setPlayerSpeed(true, speed1)
                        viewModel.setPlayerPitch(true, pitch1)
                    },
                    onLink = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        isLinked = !isLinked
                        if (isLinked) {
                            viewModel.seekTo(pos1, true)
                            viewModel.setPlayerSpeed(true, speed1)
                            viewModel.setPlayerPitch(true, pitch1)
                        }
                    },
                    onCrossfade = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        viewModel.crossfadePlayers()
                    }
                )

                // BOTTOM PLAYER (Right Earphone)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    DuoPlayerHalf(
                        label = "RIGHT EARPHONE", color = p2Color, track = track2, isPlaying = isPlaying2,
                        position = pos2, speedVal = speed2, pitchVal = pitch2, isTop = false,
                        onPlayPause = {
                            if (isPlaying2) if (isLinked) viewModel.pauseBothSynced() else viewModel.pause(true)
                            else if (isLinked) viewModel.playBothSynced() else viewModel.play(true)
                        },
                        onSeek = { ms ->
                            viewModel.seekTo(ms, true)
                            if (isLinked) viewModel.seekTo(ms, false)
                        },
                        onOpenLibrary = { activePlayerForSelection = 2; showSheet = true },
                        onSpeed = {
                            viewModel.setPlayerSpeed(true, it)
                            if (isLinked) viewModel.setPlayerSpeed(false, it)
                        },
                        onPitch = {
                            viewModel.setPlayerPitch(true, it)
                            if (isLinked) viewModel.setPlayerPitch(false, it)
                        }
                    )
                }
            }
        }

        // The "Back" arrow button in the top left corner of the screen
        FilledIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopStart),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
        }
    }

    // =========================================================================
    // THE SONG PICKER MENU
    // Opens up from the bottom when the user wants to change a song.
    // =========================================================================
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; viewModel.setSearchQuery("") }, // Closes when tapping outside
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)) }
        ) {
            // Find out what the *other* earphone is playing, so we can show an "In Use" badge
            val otherPlayerTrackId = if (activePlayerForSelection == 1) track2?.id else track1?.id

            SongPickerSheet(
                pagedSongs = pagedSongs,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                playerLabel = if (activePlayerForSelection == 1) "Left Earphone" else "Right Earphone",
                otherTrackId = otherPlayerTrackId,
                onSongSelected = { track ->
                    // When the user taps a song, play it and close the menu
                    viewModel.playDuoTrack(track = track, isPlayer2 = activePlayerForSelection == 2)
                    showSheet = false
                    viewModel.setSearchQuery("")
                }
            )
        }
    }
}

/**
 * THE MIDDLE CONTROL BAR (Crossfade, Link, and Sync)
 * This is the glowing bar that separates the left and right players.
 */
@Composable
fun PremiumDJDivider(
    p1Color: Color, p2Color: Color, isPlaying: Boolean, isLinked: Boolean,
    isVerticalSplit: Boolean,
    onSync: () -> Unit, onLink: () -> Unit, onCrossfade: () -> Unit
) {
    // This makes the divider glow and throb to the beat of the music
    val animatedPulse = remember { Animatable(0.35f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                animatedPulse.animateTo(1f, animationSpec = tween(1400, easing = FastOutSlowInEasing))
                animatedPulse.animateTo(0.35f, animationSpec = tween(1400, easing = FastOutSlowInEasing))
            }
        } else {
            animatedPulse.animateTo(0.35f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        }
    }

    if (isVerticalSplit) {
        // TABLET MODE: The divider stands straight up (Vertical)
        Box(modifier = Modifier.fillMaxHeight().width(100.dp).background(MaterialTheme.colorScheme.background)) {
            // The glowing line in the center
            Box(modifier = Modifier.align(Alignment.Center).fillMaxHeight().width(4.dp).background(Brush.verticalGradient(listOf(p1Color.copy(alpha = animatedPulse.value), p2Color.copy(alpha = animatedPulse.value)))))

            // The 3 buttons: Sync, Link, Crossfade
            Column(modifier = Modifier.fillMaxSize().padding(vertical = 28.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                DividerControlButton(icon = Icons.Rounded.Sync, label = "Sync FX", color = p1Color, onClick = onSync)

                // The Link Button (Lock / Unlock)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = if (isLinked) p1Color else MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = if (isLinked) 4.dp else 2.dp) {
                        Box(modifier = Modifier.fillMaxSize().clickable { onLink() }, contentAlignment = Alignment.Center) { Icon(if (isLinked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Link Players", modifier = Modifier.size(24.dp), tint = if (isLinked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (isLinked) "Linked" else "Link", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }

                DividerControlButton(icon = Icons.Rounded.CompareArrows, label = "Crossfade", color = p2Color, onClick = onCrossfade)
            }
        }
    } else {
        // PHONE MODE: The divider lays flat (Horizontal)
        Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.background)) {
            // The glowing line in the center
            Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(Brush.horizontalGradient(listOf(p1Color.copy(alpha = animatedPulse.value), p2Color.copy(alpha = animatedPulse.value)))))

            // The 3 buttons: Sync, Link, Crossfade
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                DividerControlButton(icon = Icons.Rounded.Sync, label = "Sync FX", color = p1Color, onClick = onSync)

                // The Link Button (Lock / Unlock)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-4).dp)) {
                    Surface(modifier = Modifier.size(56.dp), shape = CircleShape, color = if (isLinked) p1Color else MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = if (isLinked) 4.dp else 2.dp) {
                        Box(modifier = Modifier.fillMaxSize().clickable { onLink() }, contentAlignment = Alignment.Center) { Icon(if (isLinked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Link Players", modifier = Modifier.size(24.dp), tint = if (isLinked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (isLinked) "Linked" else "Link", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }

                DividerControlButton(icon = Icons.Rounded.CompareArrows, label = "Crossfade", color = p2Color, onClick = onCrossfade)
            }
        }
    }
}

/**
 * A tiny reusable button for the Sync and Crossfade buttons inside the Divider.
 */
@Composable
fun DividerControlButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Icon(icon, contentDescription = label, tint = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * ONE HALF OF THE MUSIC PLAYER (Either the Top/Left or the Bottom/Right).
 * This draws the rotating vinyl record, the play button, and the song progress bar.
 */
@Composable
fun DuoPlayerHalf(
    label: String, color: Color, track: AudioTrack?, isPlaying: Boolean, position: Long,
    speedVal: Float, pitchVal: Float, isTop: Boolean,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onOpenLibrary: () -> Unit,
    onSpeed: (Float) -> Unit, onPitch: (Float) -> Unit
) {
    var showFx by remember { mutableStateOf(false) } // Tells us if the Speed/Pitch sliders are visible
    LaunchedEffect(track?.id) { showFx = false } // Hide the sliders if a new song starts
    val view = LocalView.current // Used for vibrations

    val artRequest = rememberAlbumArtRequest(albumId = track?.albumId ?: -1L, size = 400, crossfade = false)

    // Using `key` helps Android know when to completely refresh this area.
    key(track?.id, isTop) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // 1. The big, blurry background image of the album cover
            if (track != null) AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.08f)

            // 2. A fade effect so the background isn't too distracting
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(if (isTop) listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0.82f), Color.Transparent) else listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.82f), MaterialTheme.colorScheme.background))))

            // 3. The giant faint "L" or "R" watermark in the background
            Text(text = if (isTop) "L" else "R", color = color.copy(alpha = 0.03f), fontSize = 170.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(if (isTop) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 18.dp))

            // 4. The main content area (scrollable if the phone screen is too short)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // The small pill badge at the top saying "LEFT EARPHONE" or "RIGHT EARPHONE"
                Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.25f))) {
                    Text(text = label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // If a song is selected, show the spinning record. Otherwise, show a blank icon.
                if (track != null) {
                    val rotationAnimatable = remember { Animatable(0f) }

                    // Spins the record 360 degrees endlessly while the music plays
                    LaunchedEffect(isPlaying) {
                        if (isPlaying) {
                            while (isActive) {
                                rotationAnimatable.animateTo(
                                    targetValue = rotationAnimatable.value + 360f,
                                    animationSpec = tween(12000, easing = LinearEasing)
                                )
                            }
                        }
                    }

                    // The circular album cover (like a vinyl record)
                    Surface(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape),
                        shape = CircleShape, color = Color.White, shadowElevation = 4.dp
                    ) {
                        Box {
                            AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotationAnimatable.value }, contentScale = ContentScale.Crop)
                            // The black hole in the center of the vinyl record
                            Box(modifier = Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.Black))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Song Title & Artist Text
                    Text(text = track.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    // WHAT TO SHOW IF NO SONG IS CHOSEN YET
                    Box(modifier = Modifier.size(130.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = color.copy(alpha = 0.5f), modifier = Modifier.size(60.dp)) }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = "No Song Selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "Tap library to choose a track", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // The time length of the song
                val duration = (track?.duration ?: 1000L).toFloat().coerceAtLeast(1f)
                val safePosition = if (track != null) position.toFloat().coerceIn(0f, duration) else 0f

                var isDragging by remember { mutableStateOf(false) }
                var sliderPos by remember { mutableFloatStateOf(0f) }
                // Prevents the slider from jumping back while the user is actively dragging it
                val displayPosition = if (isDragging) sliderPos else safePosition

                // The Music Progress Bar (Seekbar)
                Slider(
                    value = displayPosition,
                    valueRange = 0f..duration,
                    enabled = track != null,
                    onValueChange = {
                        if (track != null) {
                            isDragging = true
                            sliderPos = it
                        }
                    },
                    onValueChangeFinished = {
                        if (track != null) {
                            onSeek(sliderPos.toLong())
                        }
                        isDragging = false
                    },
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = color,
                        activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.18f)
                    )
                )

                // The numbers showing how many minutes/seconds have played (e.g. 01:23)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (track != null) formatDuoTime(displayPosition.toLong()) else "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (track != null) formatDuoTime(track.duration) else "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // BOTTOM CONTROLS: Effects | Play/Pause | Library
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {

                    DuoControlButton(
                        icon = Icons.Rounded.Tune,
                        label = "Effects",
                        color = color,
                        isSelected = showFx, // Highlights the button if the slider menu is open
                        onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showFx = !showFx }
                    )

                    // The Big Play/Pause Button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .pointerInput(track?.id) {
                                    detectTapGestures(
                                        // A long press restarts the song from the beginning
                                        onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onSeek(0L) },
                                        onTap = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onPlayPause() }
                                    )
                                },
                            shape = CircleShape,
                            color = color,
                            shadowElevation = if (isPlaying) 4.dp else 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(if (isPlaying) "Pause" else "Play", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    DuoControlButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Library",
                        color = color,
                        isSelected = false,
                        onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onOpenLibrary() }
                    )
                }

                // THE EFFECTS MENU (Speed & Pitch Sliders)
                // This block smoothly slides down when the user clicks the "Effects" button.
                AnimatedVisibility(visible = showFx, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }, exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 }) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            FxSlider("Speed", speedVal, 0.5f..2f, color, isPitch = false) { onSpeed(it) }
                            Spacer(modifier = Modifier.height(6.dp))
                            FxSlider("Pitch", pitchVal, 0.5f..2.0f, color, isPitch = true) { onPitch(it) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * A tiny reusable button for the Library and Effects options.
 */
@Composable
fun DuoControlButton(icon: ImageVector, label: String, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Surface(
            shape = CircleShape,
            color = if (isSelected) color else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The slider controls that allow you to speed up the song or make the voice deeper/higher (Pitch).
 */
@Composable
fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, activeColor: Color, isPitch: Boolean, onValueChange: (Float) -> Unit) {
    // Formats the math so a normal person can read it. e.g., "1.5x speed" or "+2 semitones"
    val displayValue = if (isPitch) {
        val semitones = (12 * log2(value)).roundToInt()
        if (semitones > 0) "+$semitones st" else "$semitones st"
    } else {
        String.format(Locale.US, "%.1fx", value)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
            // Header showing the name (Speed/Pitch) and the current value
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(activeColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(activeColor)) }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) { Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Spacer(modifier = Modifier.height(2.dp)); Text(text = "Audio Effect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Surface(shape = RoundedCornerShape(16.dp), color = activeColor.copy(alpha = 0.14f)) { Text(text = displayValue, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = activeColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // The draggable slider bar
            Slider(value = value, valueRange = range, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = activeColor.copy(alpha = 0.18f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            Spacer(modifier = Modifier.height(8.dp))

            // The minimum and maximum limits shown under the slider
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = if (isPitch) "-12 st" else "${range.start.toInt()}x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = if (isPitch) "+12 st" else "${range.endInclusive.toInt()}x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * THE MENU THAT SLIDES UP FROM THE BOTTOM.
 * Shows all the songs on the device so the user can choose one to play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongPickerSheet(pagedSongs: LazyPagingItems<AudioTrack>, searchQuery: String, onSearchQueryChange: (String) -> Unit, playerLabel: String, otherTrackId: Long?, onSongSelected: (AudioTrack) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp).padding(top = 12.dp)) {

        // The little gray pill shape at the very top of the bottom sheet
        Box(modifier = Modifier.align(Alignment.CenterHorizontally).width(54.dp).height(5.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)))
        Spacer(modifier = Modifier.height(22.dp))

        // The title area telling you which earphone you are picking a song for
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column { Text("Select Audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.height(3.dp)); Text(playerLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // The text box where the user can type a song name to search
        OutlinedTextField(value = searchQuery, onValueChange = onSearchQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp), placeholder = { Text("Search songs, artists...", color = MaterialTheme.colorScheme.onSurfaceVariant) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) }, colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), unfocusedBorderColor = Color.Transparent))
        Spacer(modifier = Modifier.height(22.dp))

        // THE LIST OF SONGS
        when {
            // Shows a spinning circle if the phone is still looking for songs
            pagedSongs.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

            // Shows text if the phone doesn't have any songs
            pagedSongs.itemCount == 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No Songs Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

            // The scrollable list of songs
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
                items(count = pagedSongs.itemCount, key = { index -> pagedSongs.peek(index)?.id ?: index }) { index ->
                    pagedSongs[index]?.let { song ->
                        val artRequest = rememberAlbumArtRequest(albumId = song.albumId, size = 200, crossfade = false)

                        // Checks if the other earphone is currently playing this exact same song
                        val isUsedByOther = song.id == otherTrackId

                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onSongSelected(song) }, shape = RoundedCornerShape(28.dp), elevation = CardDefaults.elevatedCardElevation(2.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                // The tiny square album picture next to the song name
                                Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                    AsyncImage(model = artRequest, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                                        // The red warning box if the song is playing in the other ear
                                        if (isUsedByOther) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                                                Text("In Use (Other Ear)", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        }
                                    }
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A helper math function that turns raw milliseconds (like 125000)
 * into a nice looking clock time (like 02:05) for the screen.
 */
fun formatDuoTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}