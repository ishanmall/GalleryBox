@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// Bringing in all the UI pieces, animations, and icons needed for our radio interface.
// =========================================================================================
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

// Importing App-specific logic
import com.gallerybox.engine.MusicService
import com.gallerybox.viewmodel.RadioViewModel

// 🧠 Importing the Adaptive Engine so our UI knows what shape the phone is
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- THE PAINT PALETTE ---
// Just like a painter mixes specific colors for their canvas, we define exact shades
// of color here so the screen looks clean and modern.
// =========================================================================================
private val BgColor = Color(0xFFF2F2F7) // Soft gray background
private val SurfaceColor = Color(0xFFFFFFFF) // Pure white for cards/surfaces
private val PrimaryColor = Color(0xFF007AFF) // Bright blue for active buttons
private val TextPrimary = Color(0xFF000000) // Solid black text
private val TextSecondary = Color(0xFF8E8E93) // Faded gray text for subtitles

// --- MATH HELPERS ---
// Small tools to compare radio frequencies (like 98.1 vs 98.10001) safely without math errors.
private inline fun Float.toStationInt() = (this * 10).roundToInt()
private inline fun Float.isSameFreq(other: Float) = this.toStationInt() == other.toStationInt()
private fun formatRadioTime(ms: Long) = String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

// The list of all the sub-screens available inside the Radio app
private enum class RadioView { TUNER, PRESETS, RECORDINGS, DIAGNOSTICS, SETTINGS, DRIVE_MODE }

// =========================================================================================
// --- MAIN MANAGER: ANALOG RADIO SCREEN ---
// Analogy: Think of this screen as an old-school car radio. You need a physical antenna
// (plugging in wired headphones), and you spin a dial to catch local FM radio stations out
// of thin air.
// =========================================================================================
@Composable
fun RadioScreen(
    viewModel: RadioViewModel, // The "Operator" controlling the physical phone hardware
    onBack: () -> Unit // The action to take when the user hits the back button
) {
    val context = LocalContext.current
    var currentView by remember { mutableStateOf(RadioView.TUNER) }

    // 🧠 1. Ask the Adaptive Engine what shape the device is (Phone? Tablet? Foldable?)
    val adaptiveState = rememberAdaptiveState()

    // 📊 2. Setup the "Scoreboards" (State).
    // Whenever these values change in the ViewModel, the UI instantly updates.
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentFreq by viewModel.currentFrequency.collectAsState()
    val isHeadsetConnected by viewModel.isHeadsetConnected.collectAsState()
    val favorites by viewModel.favoriteStations.collectAsState()
    val signalStrength by viewModel.signalStrength.collectAsState()
    val stereoBlend by viewModel.stereoBlend.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isSpeakerEnabled by viewModel.isSpeakerEnabled.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val stationName by viewModel.rdsStationName.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf(0L) }
    var showTopMenu by remember { mutableStateOf(false) }

    // If the radio starts playing, launch our Foreground Service so Android doesn't kill the app
    // when the user puts their phone in their pocket.
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

    // Shut down the radio hardware if the user closes the screen
    DisposableEffect(LocalLifecycleOwner.current) {
        onDispose { if (isPlaying) viewModel.stopRadioIfNeeded() }
    }

    // If the radio hardware crashes or hits static, show a popup message
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    // Handle Android's built-in "Back" button so it doesn't accidentally close the whole app
    BackHandler(enabled = currentView != RadioView.TUNER) {
        currentView = RadioView.TUNER
    }

    // A tiny stopwatch that counts up when the user hits "Record"
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                recordingTime = System.currentTimeMillis() - startTime
                delay(1000L)
            }
        } else {
            recordingTime = 0L
        }
    }

    // 🎨 3. ADAPTIVE LAYOUT: Center the radio on giant screens so it doesn't look stretched
    val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
        Modifier.width(600.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    // The outer walls of the screen
    Scaffold(
        containerColor = BgColor,
        topBar = {
            if (currentView != RadioView.DRIVE_MODE) {
                RadioTopAppBar(
                    currentView = currentView,
                    adaptiveState = adaptiveState,
                    onBack = { if (currentView == RadioView.TUNER) onBack() else currentView = RadioView.TUNER },
                    onMenuClick = { showTopMenu = true }
                )

                // The dropdown menu in the top right corner
                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                    modifier = Modifier.background(SurfaceColor).clip(RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text(if(isSpeakerEnabled) "Headphones" else "Speaker", color = if (isHeadsetConnected) TextPrimary else TextSecondary) },
                        leadingIcon = { Icon(if(isSpeakerEnabled) Icons.Rounded.Headset else Icons.Rounded.Speaker, null, tint = if (isHeadsetConnected) TextPrimary else TextSecondary) },
                        enabled = isHeadsetConnected,
                        onClick = { showTopMenu = false; viewModel.toggleSpeaker() }
                    )
                    DropdownMenuItem(
                        text = { Text(if(isMuted) "Unmute" else "Mute", color = if (isHeadsetConnected) TextPrimary else TextSecondary) },
                        leadingIcon = { Icon(if(isMuted) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, null, tint = if (isHeadsetConnected) TextPrimary else TextSecondary) },
                        enabled = isHeadsetConnected,
                        onClick = { showTopMenu = false; viewModel.toggleMute() }
                    )
                    DropdownMenuItem(text = { Text("Drive Mode", color = TextPrimary) }, leadingIcon = { Icon(Icons.Rounded.DirectionsCar, null, tint = TextPrimary) }, onClick = { showTopMenu = false; currentView = RadioView.DRIVE_MODE })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = BgColor)
                    DropdownMenuItem(text = { Text("Auto Scan", color = TextPrimary) }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = TextPrimary) }, onClick = { showTopMenu = false; viewModel.autoScanAndSaveAll() })
                    DropdownMenuItem(text = { Text("Signal Diagnostics", color = TextPrimary) }, leadingIcon = { Icon(Icons.Rounded.Timeline, null, tint = TextPrimary) }, onClick = { showTopMenu = false; currentView = RadioView.DIAGNOSTICS })
                    DropdownMenuItem(text = { Text("Recordings", color = TextPrimary) }, leadingIcon = { Icon(Icons.Rounded.Mic, null, tint = TextPrimary) }, onClick = { showTopMenu = false; currentView = RadioView.RECORDINGS })
                    DropdownMenuItem(text = { Text("Settings", color = TextPrimary) }, leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = TextPrimary) }, onClick = { showTopMenu = false; currentView = RadioView.SETTINGS })
                }
            }
        }
    ) { paddingValues ->
        // The main content area where we swap between the Tuner, Settings, and Diagnostics
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Crossfade(
                targetState = currentView,
                label = "RadioNavigation",
                modifier = contentWidthModifier.fillMaxHeight()
            ) { view ->
                when (view) {
                    RadioView.TUNER -> RadioTunerContent(
                        currentFreq = currentFreq,
                        isPlaying = isPlaying,
                        isScanning = isScanning,
                        isHeadsetConnected = isHeadsetConnected,
                        signalStrength = signalStrength,
                        favorites = favorites,
                        stationName = stationName,
                        isRecording = isRecording,
                        recordingTime = recordingTime,
                        viewModel = viewModel,
                        adaptiveState = adaptiveState,
                        onToggleRecording = { isRecording = !isRecording },
                        onNavigate = { currentView = it }
                    )
                    RadioView.PRESETS -> RadioPresetsContent(favorites, currentFreq, adaptiveState, viewModel)
                    RadioView.RECORDINGS -> RadioRecordingsContent(adaptiveState)
                    RadioView.DIAGNOSTICS -> RadioDiagnosticsContent(currentFreq, signalStrength, stereoBlend, stationName, adaptiveState)
                    RadioView.SETTINGS -> RadioSettingsContent(adaptiveState)
                    RadioView.DRIVE_MODE -> DriveModeContent(currentFreq, isPlaying, isHeadsetConnected, stationName, favorites, viewModel, adaptiveState) { currentView = RadioView.TUNER }
                }
            }
        }
    }
}

// =========================================================================================
// --- THE TOP APP BAR ---
// Shows the title of the current page.
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioTopAppBar(
    currentView: RadioView,
    adaptiveState: AdaptiveState,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = when (currentView) {
                    RadioView.TUNER -> "Radio"
                    RadioView.PRESETS -> "Presets"
                    RadioView.RECORDINGS -> "Recordings"
                    RadioView.DIAGNOSTICS -> "Signal Info"
                    RadioView.SETTINGS -> "Settings"
                    RadioView.DRIVE_MODE -> ""
                },
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (20 * adaptiveState.textScaleFactor).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
        },
        actions = {
            if (currentView == RadioView.TUNER) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.MoreVert, "More", tint = TextPrimary)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

// =========================================================================================
// --- THE MAIN TUNER (The Digital Dial) ---
// Analogy: This is the actual radio dashboard. It shows the giant frequency numbers,
// the dial you can spin left and right, and warns you if the antenna is missing.
// =========================================================================================
@Composable
private fun RadioTunerContent(
    currentFreq: Float,
    isPlaying: Boolean,
    isScanning: Boolean,
    isHeadsetConnected: Boolean,
    signalStrength: Int,
    favorites: List<Float>,
    stationName: String?,
    isRecording: Boolean,
    recordingTime: Long,
    viewModel: RadioViewModel,
    adaptiveState: AdaptiveState,
    onToggleRecording: () -> Unit,
    onNavigate: (RadioView) -> Unit
) {
    // Generate the full FM dial: 87.5 to 108.0
    val frequencyList = remember { (875..1080 step 1).map { it / 10f } }
    val isFavorite = favorites.any { it.isSameFreq(currentFreq) }
    val view = LocalView.current

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Red warning box if headphones are unplugged
        AnimatedVisibility(visible = !isHeadsetConnected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Headset, "Headset Required", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Wired headphones required as antenna.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = (13 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // The big digital screen showing the current station numbers
        AdvancedRadioDisplay(
            frequency = currentFreq,
            stationName = stationName,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            signalStrength = signalStrength,
            adaptiveState = adaptiveState,
            onToggleFavorite = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.toggleFavorite(currentFreq) },
            onFineTuneDown = { viewModel.tuneDown() },
            onFineTuneUp = { viewModel.tuneUp() }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // The interactive horizontal dial you can drag left and right
        RadioTunerDial(
            frequencyList = frequencyList,
            currentFreq = currentFreq,
            isHeadsetConnected = isHeadsetConnected,
            isScanning = isScanning,
            onTune = { viewModel.tuneToFrequency(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // The "Record" button
        AnimatedVisibility(visible = isPlaying && isHeadsetConnected, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                color = if (isRecording) MaterialTheme.colorScheme.errorContainer else SurfaceColor,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.clickable { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onToggleRecording() }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.StopCircle else Icons.Rounded.Mic,
                        contentDescription = "Record",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) formatRadioTime(recordingTime) else "Tap to Record",
                        color = if (isRecording) MaterialTheme.colorScheme.error else TextSecondary,
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Play/Pause and Scan Buttons
        if (!isHeadsetConnected) {
            // Hide the buttons and show a massive "Disconnected" icon if the antenna is missing
            Box(modifier = Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.HeadsetOff, "Disconnected", modifier = Modifier.size(64.dp), tint = TextSecondary.copy(alpha = 0.3f))
            }
        } else {
            RadioControls(
                isPlaying = isPlaying,
                isScanning = isScanning,
                onPowerClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.toggleRadio() },
                onScanPrev = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); viewModel.scanPrevious() },
                onScanNext = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); viewModel.autoScan() }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Navigation row to Settings, Diagnostic, Presets
        RadioBottomNavRow(adaptiveState, onNavigate)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// =========================================================================================
// --- THE BIG SCREEN (AdvancedRadioDisplay) ---
// Analogy: This is the actual glass screen on the radio. It gently pulses when music is playing
// and shows you the exact frequency numbers, the station name, and the cell-signal bars.
// =========================================================================================
@Composable
private fun AdvancedRadioDisplay(
    frequency: Float,
    stationName: String?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    signalStrength: Int,
    adaptiveState: AdaptiveState,
    onToggleFavorite: () -> Unit,
    onFineTuneDown: () -> Unit,
    onFineTuneUp: () -> Unit
) {
    // Make the entire screen gently "breathe" (scale up and down) when music is playing
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                pulseAnim.animateTo(1.01f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
                pulseAnim.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
            }
        } else {
            pulseAnim.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceColor,
        modifier = Modifier.fillMaxWidth().scale(pulseAnim.value)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: "FM" Badge and Signal Bars
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = BgColor) {
                    Text(
                        text = "FM",
                        color = TextSecondary,
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Drawing the 5 cell-phone-style signal bars
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(5) { i ->
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height((6 + i * 4).dp)
                                .background(
                                    color = if (isPlaying && signalStrength >= (i + 1) * 20) PrimaryColor else BgColor,
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Station Text (e.g. "KISS FM" or "TUNING...")
            AnimatedContent(targetState = stationName, label = "StationName") { name ->
                Text(
                    text = name ?: (if (isPlaying) "TUNING..." else "STOPPED"),
                    color = if (name != null) TextPrimary else TextSecondary,
                    fontSize = (16 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // The massive numbers and fine-tuning arrows
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                IconButton(onClick = onFineTuneDown) {
                    Icon(Icons.Rounded.ChevronLeft, "-0.1", tint = TextSecondary)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.1f".format(frequency),
                        color = if (isPlaying) TextPrimary else TextSecondary.copy(alpha = 0.5f),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (64 * adaptiveState.textScaleFactor).sp
                    )
                    Text(
                        text = "MHz",
                        color = TextSecondary,
                        fontSize = (16 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                    )
                }
                IconButton(onClick = onFineTuneUp) {
                    Icon(Icons.Rounded.ChevronRight, "+0.1", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Favorite (Heart) Button
            Surface(onClick = onToggleFavorite, shape = CircleShape, color = if(isFavorite) PrimaryColor.copy(alpha = 0.1f) else BgColor, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Save Station",
                        tint = if (isFavorite) PrimaryColor else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================================
// --- THE INTERACTIVE SLIDER (RadioTunerDial) ---
// Analogy: This is the physical spinning dial on an old radio. You can drag it left or right
// with your finger to jump quickly between stations.
// =========================================================================================
@Composable
private fun RadioTunerDial(
    frequencyList: List<Float>,
    currentFreq: Float,
    isHeadsetConnected: Boolean,
    isScanning: Boolean,
    onTune: (Float) -> Unit
) {
    // The "conveyor belt" state that tracks where the user is scrolling
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Automatically snap the scroll wheel to the correct frequency if the app changes stations
    LaunchedEffect(currentFreq) {
        val idx = frequencyList.indexOfFirst { it.isSameFreq(currentFreq) }
        if (idx != -1 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(maxOf(0, idx - 3)) // -3 to center it nicely
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceColor)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().pointerInput(isHeadsetConnected && !isScanning) {
                // If headphones are unplugged, disable scrolling
                if (!isHeadsetConnected || isScanning) return@pointerInput

                // Allow the user to drag the dial with their finger
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val centerIndex = listState.firstVisibleItemIndex + 3
                        if (centerIndex in frequencyList.indices) {
                            onTune(frequencyList[centerIndex])
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) // Make the phone vibrate slightly
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { listState.scrollBy(-dragAmount) }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 150.dp)
        ) {
            // Draw all the tiny tick marks (like a ruler)
            items(frequencyList) { freq ->
                val dec = (freq * 10).roundToInt() % 10
                val isMajor = dec == 0 || dec == 5 // Every 0.5 step gets a bigger line and text
                val isCurr = freq.isSameFreq(currentFreq)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp).clickable(enabled = isHeadsetConnected && !isScanning) { onTune(freq) }
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (isMajor) 3.dp else 2.dp)
                            .height(if (isMajor) 24.dp else 12.dp)
                            .background(if (isCurr) PrimaryColor else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isMajor) {
                        Text(
                            text = "%.1f".format(freq),
                            fontSize = 11.sp,
                            color = if (isCurr) PrimaryColor else TextSecondary,
                            fontWeight = if (isCurr) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // The static red needle in the absolute center
        Box(modifier = Modifier.width(4.dp).height(50.dp).background(PrimaryColor, RoundedCornerShape(2.dp)).align(Alignment.TopCenter))
    }
}

@Composable
private fun RadioControls(
    isPlaying: Boolean,
    isScanning: Boolean,
    onPowerClick: () -> Unit,
    onScanPrev: () -> Unit,
    onScanNext: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // Scan Backwards
        Surface(onClick = onScanPrev, enabled = !isScanning, shape = CircleShape, color = SurfaceColor, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SkipPrevious, "Scan Previous", tint = TextPrimary, modifier = Modifier.size(32.dp)) }
        }

        // Play/Pause Power Button
        Surface(onClick = onPowerClick, shape = CircleShape, color = if (isPlaying) PrimaryColor else SurfaceColor, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (isScanning) {
                    CircularProgressIndicator(color = if (isPlaying) Color.White else PrimaryColor, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                } else {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Power", tint = if (isPlaying) Color.White else TextPrimary, modifier = Modifier.size(40.dp))
                }
            }
        }

        // Scan Forwards
        Surface(onClick = onScanNext, enabled = !isScanning, shape = CircleShape, color = SurfaceColor, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SkipNext, "Scan Next", tint = TextPrimary, modifier = Modifier.size(32.dp)) }
        }
    }
}

// A row of 4 buttons at the very bottom
@Composable
private fun RadioBottomNavRow(adaptiveState: AdaptiveState, onNavigate: (RadioView) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RadioNavIcon(Icons.Rounded.FormatListBulleted, "Presets", adaptiveState) { onNavigate(RadioView.PRESETS) }
        RadioNavIcon(Icons.Rounded.Mic, "Recordings", adaptiveState) { onNavigate(RadioView.RECORDINGS) }
        RadioNavIcon(Icons.Rounded.Timeline, "Signal", adaptiveState) { onNavigate(RadioView.DIAGNOSTICS) }
        RadioNavIcon(Icons.Rounded.Settings, "Settings", adaptiveState) { onNavigate(RadioView.SETTINGS) }
    }
}

@Composable
private fun RadioNavIcon(icon: ImageVector, label: String, adaptiveState: AdaptiveState, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Surface(modifier = Modifier.size(48.dp), color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(24.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = (11 * adaptiveState.textScaleFactor).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// =========================================================================================
// --- SUB-SCREEN: PRESETS ---
// Shows a clean list of all the stations the user hit "Heart" on.
// =========================================================================================
@Composable
private fun RadioPresetsContent(favorites: List<Float>, currentFreq: Float, adaptiveState: AdaptiveState, viewModel: RadioViewModel) {
    val view = LocalView.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your Stations", color = TextPrimary, fontSize = (18 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = { viewModel.autoScanAndSaveAll() }, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Auto-Save", fontWeight = FontWeight.Bold, fontSize = (14 * adaptiveState.textScaleFactor).sp)
            }
        }

        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.FavoriteBorder, null, tint = TextSecondary.copy(alpha=0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No presets saved.", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Tap the heart to save a station.", color = TextSecondary, fontSize = (14 * adaptiveState.textScaleFactor).sp)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp), modifier = Modifier.fillMaxSize()) {
                items(favorites.sorted()) { freq ->
                    val isPlaying = freq.isSameFreq(currentFreq)
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).clickable { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.tuneToFrequency(freq) },
                        color = SurfaceColor,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(56.dp).background(if (isPlaying) PrimaryColor.copy(alpha=0.15f) else BgColor, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "%.1f".format(freq),
                                    color = if (isPlaying) PrimaryColor else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (16 * adaptiveState.textScaleFactor).sp
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Station %.1f".format(freq), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = (16 * adaptiveState.textScaleFactor).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Local FM", color = TextSecondary, fontSize = (13 * adaptiveState.textScaleFactor).sp)
                            }
                            IconButton(onClick = { viewModel.toggleFavorite(freq) }) { Icon(Icons.Rounded.Favorite, "Remove", tint = PrimaryColor) }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================================
// --- SUB-SCREEN: RECORDINGS ---
// Placeholder for future development where users can listen back to radio recordings.
// =========================================================================================
@Composable
private fun RadioRecordingsContent(adaptiveState: AdaptiveState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mic, null, tint = TextSecondary.copy(alpha=0.3f), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("No Recordings Yet", color = TextPrimary, fontSize = (20 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
            Text("Record live radio directly from the tuner screen.", color = TextSecondary, fontSize = (14 * adaptiveState.textScaleFactor).sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }
}

// =========================================================================================
// --- SUB-SCREEN: DIAGNOSTICS ---
// Shows hardcore stats about the actual radio antenna hardware.
// =========================================================================================
@Composable
private fun RadioDiagnosticsContent(currentFreq: Float, signalStrength: Int, stereoBlend: Float, stationName: String?, adaptiveState: AdaptiveState) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = SurfaceColor, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Reception", color = PrimaryColor, fontSize = (13 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                DiagnosticRow("Signal Strength", "$signalStrength%", adaptiveState)
                DiagnosticRow("Est. SNR", "${(signalStrength * 0.4).toInt()} dB", adaptiveState)
                DiagnosticRow("Stereo Separation", "${(stereoBlend * 100).toInt()}%", adaptiveState)
                DiagnosticRow("RDS Data Stream", if (stationName != null) "Active" else "Inactive", adaptiveState, isLast = true)
            }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = {},
            enabled = false, // Functionality not connected to real ViewModel state
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Auto-Optimize Reception (Coming Soon)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, adaptiveState: AdaptiveState, isLast: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = (14 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Medium)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = (14 * adaptiveState.textScaleFactor).sp)
    }
    if (!isLast) HorizontalDivider(color = BgColor)
}

// =========================================================================================
// --- SUB-SCREEN: SETTINGS ---
// =========================================================================================
@Composable
private fun RadioSettingsContent(adaptiveState: AdaptiveState) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column {
                    RadioSettingsHeader("Reception & Scanning", adaptiveState)
                    RadioSettingsNavRow("FM Frequency Region", "Current: 87.5 - 108.0 MHz (Global)", adaptiveState, enabled = false)
                    HorizontalDivider(color = BgColor, modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Scan Sensitivity", "Current: Medium (Default)", adaptiveState, enabled = false)
                    HorizontalDivider(color = BgColor, modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsSwitchRow("Force Mono Playback", "Improves clarity on weak signals", false, adaptiveState, enabled = false)
                }
            }
        }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column {
                    RadioSettingsHeader("Audio Engine", adaptiveState)
                    RadioSettingsSwitchRow("Noise Reduction", "Apply software filter to static hiss", true, adaptiveState, enabled = false)
                    HorizontalDivider(color = BgColor, modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Radio Equalizer", "Dedicated EQ for FM broadcasts", adaptiveState, enabled = false)
                }
            }
        }
        item {
            Surface(color = SurfaceColor, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column {
                    RadioSettingsHeader("Automation", adaptiveState)
                    RadioSettingsSwitchRow("Resume Last Station", "Play automatically on app start", true, adaptiveState, enabled = false)
                    HorizontalDivider(color = BgColor, modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Sleep Timer", "Turn off radio after set duration", adaptiveState, enabled = false)
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun RadioSettingsHeader(title: String, adaptiveState: AdaptiveState) {
    Text(title, color = PrimaryColor, fontSize = (13 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun RadioSettingsSwitchRow(title: String, sub: String, def: Boolean, adaptiveState: AdaptiveState, enabled: Boolean = true) {
    var checked by remember { mutableStateOf(def) }
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).let { if (enabled) it.clickable { checked = !checked } else it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = (16 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Medium)
            Text(if (enabled) sub else "$sub (Coming Soon)", color = TextSecondary, fontSize = (13 * adaptiveState.textScaleFactor).sp)
        }
        Switch(checked, { if (enabled) checked = it }, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = SurfaceColor, checkedTrackColor = PrimaryColor))
    }
}

@Composable
private fun RadioSettingsNavRow(title: String, sub: String, adaptiveState: AdaptiveState, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).let { if (enabled) it.clickable {} else it }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = (16 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Medium)
            Text(if (enabled) sub else "$sub (Coming Soon)", color = TextSecondary, fontSize = (13 * adaptiveState.textScaleFactor).sp)
        }
    }
}

// =========================================================================================
// --- SUB-SCREEN: DRIVE MODE ---
// Analogy: Turns the screen into a giant set of distraction-free buttons for when the user
// is driving and needs to change stations quickly.
// =========================================================================================
@Composable
private fun DriveModeContent(
    currentFreq: Float,
    isPlaying: Boolean,
    isHeadsetConnected: Boolean,
    stationName: String?,
    favorites: List<Float>,
    viewModel: RadioViewModel,
    adaptiveState: AdaptiveState,
    onExitDriveMode: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(BgColor).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExitDriveMode, Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Close, "Exit", tint = TextSecondary, modifier = Modifier.size(40.dp))
            }
            Surface(shape = RoundedCornerShape(12.dp), color = PrimaryColor.copy(alpha=0.15f)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DirectionsCar, "Drive Mode", tint = PrimaryColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("DRIVE MODE", color = PrimaryColor, fontWeight = FontWeight.Bold, fontSize = (12 * adaptiveState.textScaleFactor).sp)
                }
            }
        }

        if (!isHeadsetConnected) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.HeadsetOff, null, modifier = Modifier.size(96.dp), tint = TextSecondary.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Text("Connect Wired Headphones", color = TextPrimary, fontSize = (20 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
                Text("Required as the FM antenna", color = TextSecondary, fontSize = (14 * adaptiveState.textScaleFactor).sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stationName ?: "FM RADIO", color = PrimaryColor, fontSize = (24 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "%.1f".format(currentFreq),
                    color = TextPrimary,
                    // Making this number massive!
                    fontSize = (120 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.Black
                )
            }

            // Giant, easy-to-hit Play and Skip buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SurfaceColor,
                    modifier = Modifier.size(90.dp).clickable { viewModel.scanPrevious() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(48.dp))
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = PrimaryColor,
                    modifier = Modifier.size(110.dp).clickable { viewModel.toggleRadio() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Power", tint = Color.White, modifier = Modifier.size(64.dp))
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SurfaceColor,
                    modifier = Modifier.size(90.dp).clickable { viewModel.autoScan() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(48.dp))
                    }
                }
            }

            // Quick access to their 5 favorite stations
            if (favorites.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(favorites.take(5)) { freq ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = SurfaceColor,
                            modifier = Modifier.height(72.dp).width(110.dp).clickable { viewModel.tuneToFrequency(freq) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("%.1f".format(freq), color = TextPrimary, fontSize = (20 * adaptiveState.textScaleFactor).sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}