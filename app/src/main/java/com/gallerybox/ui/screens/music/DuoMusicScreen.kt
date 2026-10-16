@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

import android.content.ContentUris
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.pow
import kotlin.math.roundToInt

// --- IMPORTING THE ADAPTIVE ENGINE ---
// This tells the app how to handle folding phones and big tablet screens.
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// --- SHARED THEME COLORS ---
// The base colors for the Left (Player 1) and Right (Player 2) earphones.
private val Player1BaseColor = Color(0xFF4FC3F7) // Cyber Cyan-Blue
private val Player2BaseColor = Color(0xFFFF6E91) // Neon Coral-Pink
private val LinkedAccentColor = Color(0xFFB388FF) // Studio Electric Violet

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
    val p1Color by animateColorAsState(
        targetValue = if (isLinked) LinkedAccentColor else Player1BaseColor,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "p1Color"
    )
    val p2Color by animateColorAsState(
        targetValue = if (isLinked) LinkedAccentColor else Player2BaseColor,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "p2Color"
    )

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

        // The "Back" arrow button in the top left corner of the screen (Upgraded with glassmorphic border)
        Surface(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(14.dp)
                .size(44.dp)
                .align(Alignment.TopStart),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            shadowElevation = 6.dp
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
    }

    // =========================================================================
    // THE SONG PICKER MENU
    // Opens up from the bottom when the user wants to change a song.
    // =========================================================================
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; viewModel.setSearchQuery("") }, // Closes when tapping outside
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)) }
        ) {
            // Find out what the *other* earphone is playing, so we can show an "In Use" badge
            val otherPlayerTrackId = if (activePlayerForSelection == 1) track2?.id else track1?.id

            SongPickerSheet(
                pagedSongs = pagedSongs,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                playerLabel = if (activePlayerForSelection == 1) "Left Earphone • Deck A" else "Right Earphone • Deck B",
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
                animatedPulse.animateTo(1f, animationSpec = tween(900, easing = FastOutSlowInEasing))
                animatedPulse.animateTo(0.35f, animationSpec = tween(900, easing = FastOutSlowInEasing))
            }
        } else {
            animatedPulse.animateTo(0.35f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        }
    }

    val linkScale by animateFloatAsState(
        targetValue = if (isLinked) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "linkScale"
    )

    if (isVerticalSplit) {
        // TABLET MODE: The divider stands straight up (Vertical)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(104.dp)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            // The glowing laser line in the center
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                p1Color.copy(alpha = animatedPulse.value),
                                if (isLinked) LinkedAccentColor else Color.White.copy(alpha = animatedPulse.value * 0.6f),
                                p2Color.copy(alpha = animatedPulse.value)
                            )
                        )
                    )
            )

            // Floating Glassmorphic Console Pill
            Surface(
                modifier = Modifier
                    .fillMaxHeight(0.82f)
                    .width(86.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(p1Color.copy(alpha = 0.45f), p2Color.copy(alpha = 0.45f))
                    )
                ),
                shadowElevation = 10.dp
            ) {
                // The 3 buttons: Sync, Link, Crossfade
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 22.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DividerControlButton(icon = Icons.Rounded.Sync, label = "Sync FX", color = p1Color, onClick = onSync)

                    // The Link Button (Lock / Unlock)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier
                                .size(58.dp)
                                .graphicsLayer {
                                    scaleX = linkScale
                                    scaleY = linkScale
                                },
                            shape = CircleShape,
                            color = if (isLinked) p1Color else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(
                                width = if (isLinked) 2.dp else 1.dp,
                                color = if (isLinked) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            shadowElevation = if (isLinked) 12.dp else 2.dp
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { onLink() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLinked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Link Players",
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isLinked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isLinked) "LINKED" else "LINK",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLinked) p1Color else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                    }

                    DividerControlButton(icon = Icons.Rounded.CompareArrows, label = "Swap Mix", color = p2Color, onClick = onCrossfade)
                }
            }
        }
    } else {
        // PHONE MODE: The divider lays flat (Horizontal)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            // The glowing laser line in the center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                p1Color.copy(alpha = animatedPulse.value),
                                if (isLinked) LinkedAccentColor else Color.White.copy(alpha = animatedPulse.value * 0.6f),
                                p2Color.copy(alpha = animatedPulse.value)
                            )
                        )
                    )
            )

            // Floating Glassmorphic Console Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(80.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(p1Color.copy(alpha = 0.45f), p2Color.copy(alpha = 0.45f))
                    )
                ),
                shadowElevation = 8.dp
            ) {
                // The 3 buttons: Sync, Link, Crossfade
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DividerControlButton(icon = Icons.Rounded.Sync, label = "Sync FX", color = p1Color, onClick = onSync)

                    // The Link Button (Lock / Unlock)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier
                                .size(50.dp)
                                .graphicsLayer {
                                    scaleX = linkScale
                                    scaleY = linkScale
                                },
                            shape = CircleShape,
                            color = if (isLinked) p1Color else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(
                                width = if (isLinked) 2.dp else 1.dp,
                                color = if (isLinked) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            shadowElevation = if (isLinked) 10.dp else 2.dp
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { onLink() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLinked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Link Players",
                                    modifier = Modifier.size(22.dp),
                                    tint = if (isLinked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isLinked) "LINKED" else "LINK",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (isLinked) p1Color else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                    }

                    DividerControlButton(icon = Icons.Rounded.CompareArrows, label = "Crossfade", color = p2Color, onClick = onCrossfade)
                }
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
        Surface(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = color.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Animated Mini Spectrum Visualizer Bars shown next to the Earphone Channel Pill when playing.
 */
@Composable
private fun MiniEqualizerBars(isPlaying: Boolean, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "eqBars")
    val barScales = listOf(380, 520, 310, 460).mapIndexed { idx, dur ->
        infiniteTransition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(dur, delayMillis = idx * 70, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$idx"
        )
    }

    Row(
        modifier = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barScales.forEach { animState ->
            val fraction = if (isPlaying) animState.value else 0.22f
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(fraction)
                    .clip(CircleShape)
                    .background(color)
            )
        }
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
    var showRemainingTime by remember { mutableStateOf(false) } // Toggle total vs remaining time on tap
    LaunchedEffect(track?.id) { showFx = false } // Hide the sliders if a new song starts
    val view = LocalView.current // Used for vibrations

    val artRequest = rememberAlbumArtRequest(albumId = track?.albumId ?: -1L, size = 400, crossfade = false)

    // Using `key` helps Android know when to completely refresh this area.
    key(track?.id, isTop) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // 1. The big, blurry background image of the album cover
            if (track != null) {
                AsyncImage(
                    model = artRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(36.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.16f
                )
            }

            // 2. Ambient radial spotlight glow behind the vinyl deck
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = if (isPlaying) 0.16f else 0.06f),
                                Color.Transparent
                            ),
                            radius = 650f
                        )
                    )
            )

            // 3. A fade effect so the background isn't too distracting
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            if (isTop) listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                Color.Transparent
                            ) else listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            // 4. The giant faint "L" or "R" watermark in the background
            Text(
                text = if (isTop) "L" else "R",
                color = color.copy(alpha = 0.045f),
                fontSize = 175.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(if (isTop) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
            )

            //The time length of the song
            val duration = (track?.duration ?: 1000L).toFloat().coerceAtLeast(1f)
            val safePosition = if (track != null) position.toFloat().coerceIn(0f, duration) else 0f

            var isDragging by remember { mutableStateOf(false) }
            var sliderPos by remember { mutableFloatStateOf(0f) }
            // Prevents the slider from jumping back while the user is actively dragging it
            val displayPosition = if (isDragging) sliderPos else safePosition
            val progressFraction = (displayPosition / duration).coerceIn(0f, 1f)

            // 5. The main content area (scrollable if the phone screen is too short)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // The small pill badge at the top saying "LEFT EARPHONE" or "RIGHT EARPHONE" + Live EQ Bars + Active FX tags
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = color.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniEqualizerBars(isPlaying = isPlaying, color = color)
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    // Show a subtle live telemetry badge when Speed or Pitch is modified
                    val semitones = (12 * log2(pitchVal.coerceAtLeast(0.1f))).roundToInt()
                    if (speedVal != 1.0f || semitones != 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%.1fx", speedVal)} • ${if (semitones >= 0) "+$semitones" else "$semitones"}st",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // If a song is selected, show the spinning record + tonearm. Otherwise, show a studio deck placeholder.
                if (track != null) {
                    val rotationAnimatable = remember { Animatable(0f) }

                    // Spins the record 360 degrees endlessly while the music plays, scaling with speedVal
                    LaunchedEffect(isPlaying, speedVal) {
                        if (isPlaying) {
                            val cycleDuration = (12000f / speedVal.coerceIn(0.25f, 3f)).roundToInt().coerceAtLeast(1000)
                            while (isActive) {
                                rotationAnimatable.animateTo(
                                    targetValue = rotationAnimatable.value + 360f,
                                    animationSpec = tween(cycleDuration, easing = LinearEasing)
                                )
                            }
                        }
                    }

                    // Tonearm angle: pivots onto the record when playing
                    val tonearmAngle by animateFloatAsState(
                        targetValue = if (isPlaying) 24f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                        label = "tonearmAngle"
                    )

                    // Turntable Platter Assembly (Double-tap left/right half of record to skip -10s / +10s)
                    Box(
                        modifier = Modifier
                            .size(152.dp)
                            .pointerInput(track.id, position) {
                                detectTapGestures(
                                    onDoubleTap = { offset ->
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                                        val target = (position + delta).coerceIn(0L, track.duration)
                                        onSeek(target)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer Circular Progress Ring + Vinyl Grooves
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeW = 3.5.dp.toPx()
                            // Subtle outer track ring
                            drawArc(
                                color = color.copy(alpha = 0.16f),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = strokeW)
                            )
                            // Active progress arc around the turntable
                            drawArc(
                                color = color,
                                startAngle = -90f,
                                sweepAngle = 360f * progressFraction,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round)
                            )
                        }

                        // Dark Vinyl Platter Rim with Grooves
                        Surface(
                            modifier = Modifier.size(138.dp),
                            shape = CircleShape,
                            color = Color(0xFF141418),
                            shadowElevation = 10.dp,
                            border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // Concentric Vinyl Micro-Grooves
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val r = size.minDimension / 2f
                                    drawCircle(Color.White.copy(alpha = 0.05f), radius = r * 0.92f, style = Stroke(1.dp.toPx()))
                                    drawCircle(Color.White.copy(alpha = 0.04f), radius = r * 0.84f, style = Stroke(1.dp.toPx()))
                                }

                                // Rotating Album Artwork Core
                                AsyncImage(
                                    model = artRequest,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(102.dp)
                                        .clip(CircleShape)
                                        .graphicsLayer { rotationZ = rotationAnimatable.value },
                                    contentScale = ContentScale.Crop
                                )

                                // Metallic Center Vinyl Label & Spindle Hole
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(color, Color(0xFF1E1E24))
                                            )
                                        )
                                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black)
                                    )
                                }
                            }
                        }

                        // Stylized DJ Tonearm in the top-right corner of the platter
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    rotationZ = tonearmAngle
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.86f, 0.14f)
                                }
                        ) {
                            val pivot = Offset(size.width * 0.86f, size.height * 0.14f)
                            val armTip = Offset(size.width * 0.72f, size.height * 0.56f)
                            drawCircle(color = Color(0xFF2C2C34), radius = 8.dp.toPx(), center = pivot)
                            drawCircle(color = color, radius = 4.dp.toPx(), center = pivot)
                            drawLine(
                                color = Color.LightGray,
                                start = pivot,
                                end = armTip,
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(color = color, radius = 3.5.dp.toPx(), center = armTip)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Song Title & Artist Text
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // WHAT TO SHOW IF NO SONG IS CHOSEN YET (Tappable Empty Deck)
                    Surface(
                        onClick = onOpenLibrary,
                        modifier = Modifier.size(142.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.5.dp,
                            Brush.sweepGradient(
                                listOf(color.copy(alpha = 0.5f), Color.Transparent, color.copy(alpha = 0.5f))
                            )
                        ),
                        shadowElevation = 6.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.Album,
                                contentDescription = null,
                                tint = color.copy(alpha = 0.7f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "LOAD TRACK",
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Deck Empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tap deck or library to cue audio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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

                // The numbers showing how many minutes/seconds have played (Tap right timestamp to toggle remaining time)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (track != null) formatDuoTime(displayPosition.toLong()) else "00:00",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDragging) color else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (track != null) {
                            if (showRemainingTime) {
                                "-${formatDuoTime((track.duration - displayPosition.toLong()).coerceAtLeast(0L))}"
                            } else {
                                formatDuoTime(track.duration)
                            }
                        } else "00:00",
                        modifier = Modifier.clickable { showRemainingTime = !showRemainingTime },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // BOTTOM CONTROLS: Effects | Play/Pause | Library
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    DuoControlButton(
                        icon = Icons.Rounded.Tune,
                        label = "Studio FX",
                        color = color,
                        isSelected = showFx, // Highlights the button if the slider menu is open
                        onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showFx = !showFx }
                    )

                    // The Big Play/Pause Button with glowing outer aura
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            // Subtle outer halo ring when actively playing
                            if (isPlaying) {
                                Box(
                                    modifier = Modifier
                                        .size(66.dp)
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = 0.22f))
                                )
                            }
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
                                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f)),
                                shadowElevation = if (isPlaying) 8.dp else 2.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isPlaying) "Pause • Hold Cue" else "Play",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                AnimatedVisibility(
                    visible = showFx,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FxSlider("Tempo / Speed", speedVal, 0.5f..2f, color, isPitch = false) { onSpeed(it) }
                        FxSlider("Key / Pitch", pitchVal, 0.5f..2.0f, color, isPitch = true) { onPitch(it) }
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (isSelected) color else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (isSelected) Color.White.copy(alpha = 0.4f) else color.copy(alpha = 0.22f)
            ),
            shadowElevation = if (isSelected) 6.dp else 1.dp,
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The slider controls that allow you to speed up the song or make the voice deeper/higher (Pitch).
 * Upgraded with 1-tap Reset and Precision Stepper (+/-) buttons.
 */
@Composable
fun FxSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    activeColor: Color,
    isPitch: Boolean,
    onValueChange: (Float) -> Unit
) {
    val view = LocalView.current

    // Formats the math so a normal person can read it. e.g., "1.5x speed" or "+2 semitones"
    val semitones = (12 * log2(value.coerceAtLeast(0.1f))).roundToInt()
    val displayValue = if (isPitch) {
        if (semitones > 0) "+$semitones st" else "$semitones st"
    } else {
        String.format(Locale.US, "%.2fx", value)
    }

    val isModified = if (isPitch) semitones != 0 else kotlin.math.abs(value - 1.0f) > 0.02f

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Header showing the name (Speed/Pitch), 1-tap Reset button, and current value badge
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPitch) Icons.Rounded.GraphicEq else Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = activeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isModified) "Tap badge to reset to default" else "Default studio setting",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Tapping the value pill resets it to 1.0x (0 semitones) immediately
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onValueChange(1.0f)
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isModified) activeColor else activeColor.copy(alpha = 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = displayValue,
                            color = if (isModified) Color.White else activeColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (isModified) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Reset",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fine-tune Stepper Row + Draggable Slider Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Minus Step Button (-1 semitone or -0.05x speed)
                FilledTonalIconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val nextVal = if (isPitch) {
                            2.0.pow((semitones - 1).coerceIn(-12, 12) / 12.0).toFloat()
                        } else {
                            ((value - 0.05f) * 20f).roundToInt() / 20f
                        }
                        onValueChange(nextVal.coerceIn(range.start, range.endInclusive))
                    },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                }

                Slider(
                    value = value,
                    valueRange = range,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = activeColor,
                        activeTrackColor = activeColor,
                        inactiveTrackColor = activeColor.copy(alpha = 0.18f),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    )
                )

                // Plus Step Button (+1 semitone or +0.05x speed)
                FilledTonalIconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val nextVal = if (isPitch) {
                            2.0.pow((semitones + 1).coerceIn(-12, 12) / 12.0).toFloat()
                        } else {
                            ((value + 0.05f) * 20f).roundToInt() / 20f
                        }
                        onValueChange(nextVal.coerceIn(range.start, range.endInclusive))
                    },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
                }
            }

            // The minimum, center, and maximum limits shown under the slider
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isPitch) "-12 st" else "0.5x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isPitch) "0 st" else "1.0x",
                    style = MaterialTheme.typography.labelSmall,
                    color = activeColor.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isPitch) "+12 st" else "2.0x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
fun SongPickerSheet(
    pagedSongs: LazyPagingItems<AudioTrack>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    playerLabel: String,
    otherTrackId: Long?,
    onSongSelected: (AudioTrack) -> Unit
) {
    val isLeftDeck = playerLabel.contains("Left", ignoreCase = true)
    val accentColor = if (isLeftDeck) Player1BaseColor else Player2BaseColor

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp)
    ) {
        // The title area telling you which earphone you are picking a song for
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        "Select Track",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        playerLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Total track count badge
            if (pagedSongs.itemCount > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = "${pagedSongs.itemCount} tracks",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // The text box where the user can type a song name to search (With Clear X button)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = {
                Text("Search songs, artists, albums...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = accentColor)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = accentColor.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.height(18.dp))

        // THE LIST OF SONGS
        when {
            // Shows a spinning circle if the phone is still looking for songs
            pagedSongs.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = accentColor)
            }

            // Shows text if the phone doesn't have any songs
            pagedSongs.itemCount == 0 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.MusicOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No Matching Tracks Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            // The scrollable list of songs
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                items(count = pagedSongs.itemCount, key = { index -> pagedSongs.peek(index)?.id ?: index }) { index ->
                    pagedSongs[index]?.let { song ->
                        val artRequest = rememberAlbumArtRequest(albumId = song.albumId, size = 200, crossfade = false)

                        // Checks if the other earphone is currently playing this exact same song
                        val isUsedByOther = song.id == otherTrackId

                        Surface(
                            onClick = { onSongSelected(song) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isUsedByOther) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                            ),
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The tiny square album picture next to the song name
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                ) {
                                    AsyncImage(
                                        model = artRequest,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(accentColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            song.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        // The warning box if the song is playing in the other ear
                                        if (isUsedByOther) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.errorContainer,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    "In Other Ear",
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                // Track Duration Pill + Arrow
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = formatDuoTime(song.duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Icon(
                                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = accentColor
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

/**
 * A helper math function that turns raw milliseconds (like 125000)
 * into a nice looking clock time (like 02:05) for the screen.
 */
fun formatDuoTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}