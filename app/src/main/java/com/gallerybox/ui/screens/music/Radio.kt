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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
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
// of color here so the screen looks clean and modern, while adapting to Dark/Light themes.
// =========================================================================================
private val BgColor @Composable get() = MaterialTheme.colorScheme.background
private val SurfaceColor @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
private val PrimaryColor @Composable get() = MaterialTheme.colorScheme.primary
private val TextPrimary @Composable get() = MaterialTheme.colorScheme.onSurface
private val TextSecondary @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SignalGoodColor = Color(0xFF00E676)
private val FavoriteAccentColor = Color(0xFFFF4081)

// --- MATH HELPERS ---
// Small tools to compare radio frequencies (like 98.1 vs 98.10001) safely without math errors.
private inline fun Float.toStationInt() = (this * 10f).roundToInt()
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
    val view = LocalView.current
    var currentView by remember { mutableStateOf(RadioView.TUNER) }

    // 🧠Ask the Adaptive Engine what shape the device is (Phone? Tablet? Foldable?)
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
    BackHandler {
        if (currentView != RadioView.TUNER) {
            currentView = RadioView.TUNER
        } else {
            onBack()
        }
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
        Modifier.width(660.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    // The outer walls of the screen
    Scaffold(
        containerColor = BgColor,
        topBar = {
            if (currentView != RadioView.DRIVE_MODE) {
                Box {
                    RadioTopAppBar(
                        currentView = currentView,
                        adaptiveState = adaptiveState,
                        onBack = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (currentView == RadioView.TUNER) onBack() else currentView = RadioView.TUNER
                        },
                        onDriveModeClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentView = RadioView.DRIVE_MODE
                        },
                        onMenuClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            showTopMenu = true
                        }
                    )

                    // The dropdown menu in the top right corner
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 16.dp)) {
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clip(RoundedCornerShape(18.dp))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSpeakerEnabled) "Switch to Headphones" else "Switch to Speaker",
                                        color = if (isHeadsetConnected) TextPrimary else TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isSpeakerEnabled) Icons.Rounded.Headset else Icons.Rounded.Speaker,
                                        null,
                                        tint = if (isHeadsetConnected) PrimaryColor else TextSecondary
                                    )
                                },
                                enabled = isHeadsetConnected,
                                onClick = { showTopMenu = false; viewModel.toggleSpeaker() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isMuted) "Unmute Audio" else "Mute Audio",
                                        color = if (isHeadsetConnected) TextPrimary else TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isMuted) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                                        null,
                                        tint = if (isHeadsetConnected) PrimaryColor else TextSecondary
                                    )
                                },
                                enabled = isHeadsetConnected,
                                onClick = { showTopMenu = false; viewModel.toggleMute() }
                            )
                            DropdownMenuItem(
                                text = { Text("Cockpit Drive Mode", color = TextPrimary, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Rounded.DirectionsCar, null, tint = PrimaryColor) },
                                onClick = { showTopMenu = false; currentView = RadioView.DRIVE_MODE }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            DropdownMenuItem(
                                text = { Text("Auto-Scan & Save All", color = TextPrimary, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = PrimaryColor) },
                                onClick = { showTopMenu = false; viewModel.autoScanAndSaveAll() }
                            )
                            DropdownMenuItem(
                                text = { Text("Signal Diagnostics", color = TextPrimary, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Rounded.Timeline, null, tint = TextSecondary) },
                                onClick = { showTopMenu = false; currentView = RadioView.DIAGNOSTICS }
                            )
                            DropdownMenuItem(
                                text = { Text("Saved Recordings", color = TextPrimary, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Rounded.Mic, null, tint = TextSecondary) },
                                onClick = { showTopMenu = false; currentView = RadioView.RECORDINGS }
                            )
                            DropdownMenuItem(
                                text = { Text("Tuner Settings", color = TextPrimary, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = TextSecondary) },
                                onClick = { showTopMenu = false; currentView = RadioView.SETTINGS }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        // The main content area where we swap between the Tuner, Settings, and Diagnostics
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            // Top Ambient Radial Glow that responds to FM broadcast state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                PrimaryColor.copy(alpha = if (isPlaying) 0.16f else 0.06f),
                                Color.Transparent
                            ),
                            radius = 900f
                        )
                    )
            )

            Crossfade(
                targetState = currentView,
                label = "RadioNavigation",
                modifier = contentWidthModifier.fillMaxHeight()
            ) { targetView ->
                when (targetView) {
                    RadioView.TUNER -> RadioTunerContent(
                        currentFreq = currentFreq,
                        isPlaying = isPlaying,
                        isScanning = isScanning,
                        isHeadsetConnected = isHeadsetConnected,
                        isSpeakerEnabled = isSpeakerEnabled,
                        isMuted = isMuted,
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
                    RadioView.DRIVE_MODE -> DriveModeContent(
                        currentFreq = currentFreq,
                        isPlaying = isPlaying,
                        isHeadsetConnected = isHeadsetConnected,
                        stationName = stationName,
                        favorites = favorites,
                        viewModel = viewModel,
                        adaptiveState = adaptiveState
                    ) { currentView = RadioView.TUNER }
                }
            }
        }
    }
}

// =========================================================================================
// --- THE TOP APP BAR ---
// Shows the title of the current page with studio hardware button styling.
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioTopAppBar(
    currentView: RadioView,
    adaptiveState: AdaptiveState,
    onBack: () -> Unit,
    onDriveModeClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Back Button + Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = SurfaceColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    text = when (currentView) {
                        RadioView.TUNER -> "FM Radio"
                        RadioView.PRESETS -> "Saved Presets"
                        RadioView.RECORDINGS -> "Recordings"
                        RadioView.DIAGNOSTICS -> "Signal Telemetry"
                        RadioView.SETTINGS -> "Tuner Settings"
                        RadioView.DRIVE_MODE -> ""
                    },
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (20 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right: Quick Drive Mode + Menu Button
        if (currentView == RadioView.TUNER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onDriveModeClick,
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.DirectionsCar,
                            contentDescription = "Drive Mode",
                            tint = PrimaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    onClick = onMenuClick,
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
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
    isSpeakerEnabled: Boolean,
    isMuted: Boolean,
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.dp))

        // Red warning banner if headphones are unplugged
        AnimatedVisibility(
            visible = !isHeadsetConnected,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.HeadsetOff,
                            contentDescription = "Headset Required",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Antenna Disconnected",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = (14 * adaptiveState.textScaleFactor).sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Plug in wired headphones or USB-C audio to receive FM signals.",
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                            fontSize = (12 * adaptiveState.textScaleFactor).sp
                        )
                    }
                }
            }
        }

        // The big digital VFD screen showing the current station numbers
        AdvancedRadioDisplay(
            frequency = currentFreq,
            stationName = stationName,
            isPlaying = isPlaying,
            isFavorite = isFavorite,
            isHeadsetConnected = isHeadsetConnected,
            isSpeakerEnabled = isSpeakerEnabled,
            isMuted = isMuted,
            signalStrength = signalStrength,
            adaptiveState = adaptiveState,
            onToggleFavorite = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.toggleFavorite(currentFreq)
            },
            onToggleSpeaker = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                viewModel.toggleSpeaker()
            },
            onToggleMute = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                viewModel.toggleMute()
            },
            onFineTuneDown = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                viewModel.tuneDown()
            },
            onFineTuneUp = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                viewModel.tuneUp()
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // The interactive horizontal dial you can drag left and right
        RadioTunerDial(
            frequencyList = frequencyList,
            favorites = favorites,
            currentFreq = currentFreq,
            isHeadsetConnected = isHeadsetConnected,
            isScanning = isScanning,
            onTune = { viewModel.tuneToFrequency(it) }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // The "Record" pill button with pulsing crimson dot
        AnimatedVisibility(
            visible = isPlaying && isHeadsetConnected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "RecPulse")
            val recAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "RecDotAlpha"
            )

            Surface(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onToggleRecording()
                },
                color = if (isRecording) MaterialTheme.colorScheme.errorContainer else SurfaceColor,
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                ),
                shadowElevation = if (isRecording) 6.dp else 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = recAlpha))
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.FiberManualRecord,
                            contentDescription = "Record",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) "REC • ${formatRadioTime(recordingTime)}" else "Record Broadcast",
                        color = if (isRecording) MaterialTheme.colorScheme.onErrorContainer else TextPrimary,
                        fontFamily = if (isRecording) FontFamily.Monospace else FontFamily.Default,
                        fontSize = (13 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Play/Pause and Scan Buttons
        if (!isHeadsetConnected) {
            // Subtle offline deck state when antenna is unplugged
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp),
                shape = RoundedCornerShape(26.dp),
                color = SurfaceColor.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.SettingsInputAntenna,
                        contentDescription = "Disconnected",
                        modifier = Modifier.size(36.dp),
                        tint = TextSecondary.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "Hardware Tuner Standby\nAwaiting wired antenna connection",
                        color = TextSecondary,
                        fontSize = (13 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            RadioControls(
                isPlaying = isPlaying,
                isScanning = isScanning,
                onPowerClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleRadio()
                },
                onScanPrev = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.scanPrevious()
                },
                onScanNext = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.autoScan()
                }
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        // Navigation row to Settings, Diagnostic, Presets
        RadioBottomNavRow(adaptiveState, onNavigate)

        Spacer(modifier = Modifier.height(24.dp))
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
    isHeadsetConnected: Boolean,
    isSpeakerEnabled: Boolean,
    isMuted: Boolean,
    signalStrength: Int,
    adaptiveState: AdaptiveState,
    onToggleFavorite: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleMute: () -> Unit,
    onFineTuneDown: () -> Unit,
    onFineTuneUp: () -> Unit
) {
    // Make the entire screen gently "breathe" (scale up and down) when music is playing
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                pulseAnim.animateTo(1.008f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
                pulseAnim.animateTo(1f, animationSpec = tween(1500, easing = FastOutSlowInEasing))
            }
        } else {
            pulseAnim.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
        }
    }

    val favScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "FavHeartBounce"
    )

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = SurfaceColor,
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.verticalGradient(
                listOf(
                    PrimaryColor.copy(alpha = if (isPlaying) 0.55f else 0.2f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                )
            )
        ),
        shadowElevation = if (isPlaying) 10.dp else 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .scale(pulseAnim.value)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            PrimaryColor.copy(alpha = if (isPlaying) 0.12f else 0.03f),
                            Color.Transparent
                        ),
                        radius = 550f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Row: "FM STEREO" Badge, Quick Speaker/Mute Pills, and Signal Bars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PrimaryColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = if (isPlaying) "FM STEREO" else "FM BAND",
                            color = PrimaryColor,
                            fontSize = (11 * adaptiveState.textScaleFactor).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // Signal Strength Percentage + 5 Illuminated Bars
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "$signalStrength%" else "--%",
                            fontSize = (11 * adaptiveState.textScaleFactor).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isPlaying && signalStrength > 60) SignalGoodColor else TextSecondary
                        )

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            repeat(5) { i ->
                                val isBarActive = isPlaying && signalStrength >= (i + 1) * 18
                                Box(
                                    modifier = Modifier
                                        .width(4.5.dp)
                                        .height((7f + i * 3.5f).dp)
                                        .background(
                                            color = when {
                                                !isBarActive -> MaterialTheme.colorScheme.surfaceContainerHighest
                                                signalStrength > 65 -> SignalGoodColor
                                                else -> PrimaryColor
                                            },
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Station RDS Text (e.g. "KISS FM" or "TUNING...")
                AnimatedContent(targetState = stationName, label = "StationName") { name ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = null,
                                tint = if (isPlaying) PrimaryColor else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = name ?: (if (isPlaying) "LIVE BROADCAST" else "RECEIVER STANDBY"),
                                color = if (name != null || isPlaying) TextPrimary else TextSecondary,
                                fontSize = (13 * adaptiveState.textScaleFactor).sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // The massive numbers and fine-tuning arrows
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = onFineTuneDown,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, "-0.1 MHz", tint = TextPrimary)
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", frequency),
                            color = if (isPlaying) TextPrimary else TextPrimary.copy(alpha = 0.65f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = (62 * adaptiveState.textScaleFactor).sp,
                            letterSpacing = (-1.5).sp
                        )
                        Text(
                            text = "MHz",
                            color = PrimaryColor,
                            fontSize = (15 * adaptiveState.textScaleFactor).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(bottom = 12.dp, start = 6.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onFineTuneUp,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Icon(Icons.Rounded.ChevronRight, "+0.1 MHz", tint = TextPrimary)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Bottom Utility Bar inside Display: Output Speaker Toggle | Favorite Heart | Mute Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speaker / Headset Output Pill
                    Surface(
                        onClick = onToggleSpeaker,
                        enabled = isHeadsetConnected,
                        shape = CircleShape,
                        color = if (isSpeakerEnabled) PrimaryColor.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(
                            1.dp,
                            if (isSpeakerEnabled) PrimaryColor.copy(alpha = 0.5f) else Color.Transparent
                        ),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeakerEnabled) Icons.Rounded.Speaker else Icons.Rounded.Headset,
                                contentDescription = "Output Mode",
                                tint = if (isSpeakerEnabled) PrimaryColor else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isSpeakerEnabled) "Speaker" else "Headset",
                                fontSize = (12 * adaptiveState.textScaleFactor).sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSpeakerEnabled) PrimaryColor else TextSecondary
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // Favorite (Heart) Button
                    Surface(
                        onClick = onToggleFavorite,
                        shape = CircleShape,
                        color = if (isFavorite) FavoriteAccentColor.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(
                            1.dp,
                            if (isFavorite) FavoriteAccentColor.copy(alpha = 0.5f) else Color.Transparent
                        ),
                        modifier = Modifier
                            .size(46.dp)
                            .graphicsLayer {
                                scaleX = favScale
                                scaleY = favScale
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Save Station",
                                tint = if (isFavorite) FavoriteAccentColor else TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // Mute Pill
                    Surface(
                        onClick = onToggleMute,
                        enabled = isHeadsetConnected,
                        shape = CircleShape,
                        color = if (isMuted) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.height(42.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                contentDescription = "Mute",
                                tint = if (isMuted) MaterialTheme.colorScheme.error else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isMuted) "Muted" else "Audio",
                                fontSize = (12 * adaptiveState.textScaleFactor).sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMuted) MaterialTheme.colorScheme.error else TextSecondary
                            )
                        }
                    }
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
    favorites: List<Float>,
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
        shape = RoundedCornerShape(24.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        shadowElevation = 4.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(isHeadsetConnected && !isScanning) {
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
                contentPadding = PaddingValues(horizontal = 150.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Draw all the tiny tick marks (like a ruler)
                items(frequencyList) { freq ->
                    val dec = (freq * 10f).roundToInt() % 10
                    val isMajor = dec == 0 || dec == 5 // Every 0.5 step gets a bigger line and text
                    val isCurr = freq.isSameFreq(currentFreq)
                    val isFav = favorites.any { it.isSameFreq(freq) }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(24.dp)
                            .clickable(enabled = isHeadsetConnected && !isScanning) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onTune(freq)
                            }
                    ) {
                        // Tiny star dot if this frequency is saved in favorites
                        if (isFav) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(FavoriteAccentColor)
                            )
                            Spacer(Modifier.height(3.dp))
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }

                        Box(
                            modifier = Modifier
                                .width(if (isCurr) 3.5.dp else if (isMajor) 2.5.dp else 1.5.dp)
                                .height(if (isCurr) 30.dp else if (isMajor) 24.dp else 13.dp)
                                .background(
                                    when {
                                        isCurr -> PrimaryColor
                                        isMajor -> TextSecondary.copy(alpha = 0.65f)
                                        else -> TextSecondary.copy(alpha = 0.28f)
                                    },
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.height(5.dp))
                        if (isMajor) {
                            Text(
                                text = String.format(Locale.US, "%.1f", freq),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isCurr) PrimaryColor else TextSecondary,
                                fontWeight = if (isCurr) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        } else {
                            Spacer(Modifier.height(13.dp))
                        }
                    }
                }
            }

            // Left & Right Edge Vignette Fades for 3D cylindrical dial depth
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            listOf(SurfaceColor, Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, SurfaceColor)
                        )
                    )
            )

            // The glowing center tuning needle
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(PrimaryColor)
                )
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(46.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(PrimaryColor, PrimaryColor.copy(alpha = 0.1f))
                            ),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Scan Backwards
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onScanPrev,
                enabled = !isScanning,
                shape = CircleShape,
                color = SurfaceColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 3.dp,
                modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.FastRewind,
                        contentDescription = "Scan Previous",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Seek -", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }

        // Play/Pause Power Button with Illuminated Halo
        Box(contentAlignment = Alignment.Center) {
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .size(94.dp)
                        .clip(CircleShape)
                        .background(PrimaryColor.copy(alpha = 0.2f))
                )
            }
            Surface(
                onClick = onPowerClick,
                shape = CircleShape,
                color = if (isPlaying) PrimaryColor else SurfaceColor,
                border = BorderStroke(
                    width = 2.dp,
                    color = if (isPlaying) Color.White.copy(alpha = 0.35f)
                    else PrimaryColor.copy(alpha = 0.45f)
                ),
                shadowElevation = if (isPlaying) 10.dp else 4.dp,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            color = if (isPlaying) MaterialTheme.colorScheme.onPrimary else PrimaryColor,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.PowerSettingsNew else Icons.Rounded.PlayArrow,
                            contentDescription = "Power",
                            tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else PrimaryColor,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }
        }

        // Scan Forwards
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onScanNext,
                enabled = !isScanning,
                shape = CircleShape,
                color = SurfaceColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 3.dp,
                modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.FastForward,
                        contentDescription = "Scan Next",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Seek +", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

// A row of 4 buttons at the very bottom
@Composable
private fun RadioBottomNavRow(adaptiveState: AdaptiveState, onNavigate: (RadioView) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = SurfaceColor.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RadioNavIcon(Icons.Rounded.FormatListBulleted, "Presets", adaptiveState) { onNavigate(RadioView.PRESETS) }
            RadioNavIcon(Icons.Rounded.Mic, "Recordings", adaptiveState) { onNavigate(RadioView.RECORDINGS) }
            RadioNavIcon(Icons.Rounded.Timeline, "Signal", adaptiveState) { onNavigate(RadioView.DIAGNOSTICS) }
            RadioNavIcon(Icons.Rounded.Settings, "Settings", adaptiveState) { onNavigate(RadioView.SETTINGS) }
        }
    }
}

@Composable
private fun RadioNavIcon(icon: ImageVector, label: String, adaptiveState: AdaptiveState, onClick: () -> Unit) {
    val view = LocalView.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(15.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = PrimaryColor, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = (11 * adaptiveState.textScaleFactor).sp,
            fontWeight = FontWeight.Bold
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Your Saved Stations",
                    color = TextPrimary,
                    fontSize = (18 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "${favorites.size} FM frequencies bookmarked",
                    color = TextSecondary,
                    fontSize = (12 * adaptiveState.textScaleFactor).sp
                )
            }
            FilledTonalButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.autoScanAndSaveAll()
                },
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Auto-Save",
                    fontWeight = FontWeight.Bold,
                    fontSize = (13 * adaptiveState.textScaleFactor).sp
                )
            }
        }

        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(SurfaceColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.FavoriteBorder,
                            null,
                            tint = TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No Presets Saved Yet", color = TextPrimary, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap the heart icon on the tuner to bookmark a station.",
                        color = TextSecondary,
                        fontSize = (14 * adaptiveState.textScaleFactor).sp
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 90.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(favorites.sorted()) { freq ->
                    val isCurrent = freq.isSameFreq(currentFreq)
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            viewModel.tuneToFrequency(freq)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else SurfaceColor,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) PrimaryColor.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        ),
                        shadowElevation = if (isCurrent) 6.dp else 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .background(
                                        if (isCurrent) PrimaryColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.1f", freq),
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = (15 * adaptiveState.textScaleFactor).sp
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    String.format(Locale.US, "FM %.1f MHz", freq),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = (16 * adaptiveState.textScaleFactor).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (isCurrent) "ACTIVE TUNED FREQUENCY" else "Local Analog Broadcast",
                                    color = if (isCurrent) PrimaryColor else TextSecondary,
                                    fontSize = (12 * adaptiveState.textScaleFactor).sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            IconButton(onClick = { viewModel.toggleFavorite(freq) }) {
                                Icon(Icons.Rounded.Favorite, "Remove", tint = FavoriteAccentColor)
                            }
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
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = SurfaceColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(PrimaryColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = PrimaryColor, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "No Broadcast Recordings Yet",
                    color = TextPrimary,
                    fontSize = (20 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Start the FM tuner and tap 'Record Broadcast' to capture live radio clips directly to your device.",
                    color = TextSecondary,
                    fontSize = (14 * adaptiveState.textScaleFactor).sp,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// =========================================================================================
// --- SUB-SCREEN: DIAGNOSTICS ---
// Shows hardcore stats about the actual radio antenna hardware with live progress gauges.
// =========================================================================================
@Composable
private fun RadioDiagnosticsContent(
    currentFreq: Float,
    signalStrength: Int,
    stereoBlend: Float,
    stationName: String?,
    adaptiveState: AdaptiveState
) {
    val snrDb = (signalStrength * 0.4f).toInt()
    val stereoPct = (stereoBlend * 100f).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = SurfaceColor,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RF HARDWARE TELEMETRY",
                        color = PrimaryColor,
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        String.format(Locale.US, "%.1f MHz", currentFreq),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = (13 * adaptiveState.textScaleFactor).sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                DiagnosticMeterRow("Signal Strength (RSSI)", "$signalStrength%", signalStrength / 100f, adaptiveState)
                DiagnosticMeterRow("Estimated SNR", "$snrDb dB", (snrDb / 40f).coerceIn(0f, 1f), adaptiveState)
                DiagnosticMeterRow("Stereo Separation", "$stereoPct%", stereoPct / 100f, adaptiveState)
                DiagnosticRow(
                    label = "RDS Subcarrier Stream",
                    value = if (stationName != null) "LOCKED ($stationName)" else "SEARCHING...",
                    adaptiveState = adaptiveState,
                    isLast = true
                )
            }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = {},
            enabled = false, // Functionality not connected to real ViewModel state
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Auto-Optimize Reception (Coming Soon)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnosticMeterRow(
    label: String,
    value: String,
    progress: Float,
    adaptiveState: AdaptiveState
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                color = TextSecondary,
                fontSize = (14 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                value,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (14 * adaptiveState.textScaleFactor).sp
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = if (progress > 0.6f) SignalGoodColor else PrimaryColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, adaptiveState: AdaptiveState, isLast: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = TextSecondary,
            fontSize = (14 * adaptiveState.textScaleFactor).sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            color = PrimaryColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (14 * adaptiveState.textScaleFactor).sp
        )
    }
    if (!isLast) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
}

// =========================================================================================
// --- SUB-SCREEN: SETTINGS ---
// =========================================================================================
@Composable
private fun RadioSettingsContent(adaptiveState: AdaptiveState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 20.dp)
    ) {
        item {
            Surface(
                color = SurfaceColor,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    RadioSettingsHeader("Reception & Scanning", adaptiveState)
                    RadioSettingsNavRow("FM Frequency Region", "Current: 87.5 - 108.0 MHz (Global)", adaptiveState, enabled = false)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Scan Sensitivity", "Current: Medium (Default)", adaptiveState, enabled = false)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsSwitchRow("Force Mono Playback", "Improves clarity on weak signals", false, adaptiveState, enabled = false)
                }
            }
        }
        item {
            Surface(
                color = SurfaceColor,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    RadioSettingsHeader("Audio Engine", adaptiveState)
                    RadioSettingsSwitchRow("Noise Reduction", "Apply software filter to static hiss", true, adaptiveState, enabled = false)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Radio Equalizer", "Dedicated EQ for FM broadcasts", adaptiveState, enabled = false)
                }
            }
        }
        item {
            Surface(
                color = SurfaceColor,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    RadioSettingsHeader("Automation", adaptiveState)
                    RadioSettingsSwitchRow("Resume Last Station", "Play automatically on app start", true, adaptiveState, enabled = false)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), modifier = Modifier.padding(horizontal = 16.dp))
                    RadioSettingsNavRow("Sleep Timer", "Turn off radio after set duration", adaptiveState, enabled = false)
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun RadioSettingsHeader(title: String, adaptiveState: AdaptiveState) {
    Text(
        title.uppercase(Locale.US),
        color = PrimaryColor,
        fontSize = (12 * adaptiveState.textScaleFactor).sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp)
    )
}

@Composable
private fun RadioSettingsSwitchRow(title: String, sub: String, def: Boolean, adaptiveState: AdaptiveState, enabled: Boolean = true) {
    var checked by remember { mutableStateOf(def) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .let { if (enabled) it.clickable { checked = !checked } else it }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontSize = (15 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (enabled) sub else "$sub (Coming Soon)",
                color = TextSecondary,
                fontSize = (12 * adaptiveState.textScaleFactor).sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) checked = it },
            enabled = enabled
        )
    }
}

@Composable
private fun RadioSettingsNavRow(title: String, sub: String, adaptiveState: AdaptiveState, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .let { if (enabled) it.clickable {} else it }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontSize = (15 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (enabled) sub else "$sub (Coming Soon)",
                color = TextSecondary,
                fontSize = (12 * adaptiveState.textScaleFactor).sp
            )
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
    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onExitDriveMode()
                },
                shape = CircleShape,
                color = SurfaceColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Exit", tint = TextPrimary, modifier = Modifier.size(28.dp))
                }
            }

            Surface(
                shape = CircleShape,
                color = PrimaryColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DirectionsCar, "Drive Mode", tint = PrimaryColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "COCKPIT DRIVE MODE",
                        color = PrimaryColor,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        fontSize = (12 * adaptiveState.textScaleFactor).sp
                    )
                }
            }
        }

        if (!isHeadsetConnected) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.HeadsetOff,
                    null,
                    modifier = Modifier.size(96.dp),
                    tint = TextSecondary.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Connect Wired Headphones",
                    color = TextPrimary,
                    fontSize = (22 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Required as the physical FM antenna",
                    color = TextSecondary,
                    fontSize = (15 * adaptiveState.textScaleFactor).sp
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stationName ?: "FM BROADCAST",
                    color = PrimaryColor,
                    fontSize = (22 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = String.format(Locale.US, "%.1f", currentFreq),
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (108 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-3).sp
                )
                Text(
                    text = "MEGAHERTZ",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (13 * adaptiveState.textScaleFactor).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            // Giant, easy-to-hit Play and Skip buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.scanPrevious()
                    },
                    shape = RoundedCornerShape(28.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(92.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(48.dp))
                    }
                }

                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        viewModel.toggleRadio()
                    },
                    shape = CircleShape,
                    color = PrimaryColor,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.35f)),
                    shadowElevation = 12.dp,
                    modifier = Modifier.size(114.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Power",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.autoScan()
                    },
                    shape = RoundedCornerShape(28.dp),
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(92.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(48.dp))
                    }
                }
            }

            // Quick access to their 5 favorite stations
            if (favorites.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(favorites.take(5)) { freq ->
                        val isActivePreset = freq.isSameFreq(currentFreq)
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                viewModel.tuneToFrequency(freq)
                            },
                            shape = RoundedCornerShape(22.dp),
                            color = if (isActivePreset) PrimaryColor else SurfaceColor,
                            border = BorderStroke(
                                1.5.dp,
                                if (isActivePreset) Color.White.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .height(72.dp)
                                .width(112.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    String.format(Locale.US, "%.1f", freq),
                                    color = if (isActivePreset) MaterialTheme.colorScheme.onPrimary else TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = (20 * adaptiveState.textScaleFactor).sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
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