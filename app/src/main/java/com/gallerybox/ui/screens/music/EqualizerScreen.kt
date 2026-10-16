// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling a strict grammar checker to ignore specific words because we know what we are doing.
@file:Suppress("unused", "UnsafeOptInUsageError", "DEPRECATION")

package com.gallerybox.ui.screens.music

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for math, drawing sliders, feeling physical vibrations (haptics), and doing audio processing.
import android.media.audiofx.PresetReverb
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel

// --- VIEWMODEL & APP DATA IMPORTS ---
import com.gallerybox.viewmodel.MusicViewModel
import com.gallerybox.viewmodel.Preset

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// These give our screen the brain to know if it's on a phone or a giant tablet!
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

import kotlinx.coroutines.launch
import kotlin.math.*

// A specific orange/yellow color used for the "Warning" banner
private val WarningAmberColor = Color(0xFFFFB300)
private val StudioGreenColor = Color(0xFF00E676)

/**
 * =========================================================================================
 * 🎛️ THE DJ BOOTH (EqualizerScreen)
 * =========================================================================================
 * This is the main screen where the user can adjust the bass, treble, and surround sound effects.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(viewModel: MusicViewModel = hiltViewModel(), onBack: () -> Unit) {

    // 1. 🧠 Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()
    val view = LocalView.current

    // Local memory for this screen: Should we show the popup asking the user to name their custom preset?
    var showSaveDialog by remember { mutableStateOf(false) }

    // Look at the Manager's scoreboard: Is the Equalizer master switch currently ON or OFF?
    val enabled by viewModel.eqEnabled.collectAsState()

    // The engine that handles the swiping between the two tabs (if we need tabs)
    val pagerState = rememberPagerState(pageCount = { 2 })

    val scope = rememberCoroutineScope() // Tool for launching background tasks
    val colors = MaterialTheme.colorScheme // The app's color palette

    BackHandler { onBack() }

    // Scaffold is a blank canvas with pre-marked zones for the Top Bar and the main Content
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onBack()
                        },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = colors.surfaceContainerHigh,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.3f)),
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Text(
                        text = "Master Equalizer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onSurface
                    )
                }

                // The "Save Preset" pill button in the top right corner
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        showSaveDialog = true
                    },
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save Preset",
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.onPrimaryContainer
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // The background color with an ambient radial studio glow at the top
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colors.primary.copy(alpha = if (enabled) 0.14f else 0.04f),
                                Color.Transparent
                            ),
                            radius = 900f
                        )
                    )
            )

            // Main Content Area
            Column(Modifier.fillMaxSize().padding(paddingValues)) {

                // 🎨 2. THE SHAPE-SHIFTER (Adaptive Layout Logic)
                // We ask the Adaptive Engine: "Is this a small phone?"
                if (adaptiveState.widthSize == WindowWidthSize.COMPACT) {

                    // 📱 PHONE MODE: Not enough room. We must use swiping Tabs.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = colors.surfaceContainerHigh,
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier
                                    .height(48.dp)
                                    .padding(4.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TabPill("Parametric EQ", pagerState.currentPage == 0, Modifier.weight(1f)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                }
                                TabPill("Acoustics & Gain", pagerState.currentPage == 1, Modifier.weight(1f)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    scope.launch { pagerState.animateScrollToPage(1) }
                                }
                            }
                        }
                    }

                    // The swiping pages
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        if (page == 0) EqTab(viewModel, enabled) else VolTab(viewModel)
                    }

                } else {

                    // 🖥️ TABLET / FOLDABLE MODE: We have tons of space!
                    // Ditch the tabs completely. Draw BOTH the Equalizer and the Effects side-by-side in one giant row.
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // The Sliders get 60% of the screen width (weight 1.5)
                        Box(Modifier.weight(1.5f).fillMaxHeight()) {
                            EqTab(viewModel, enabled)
                        }

                        // The Knobs and Reverb get 40% of the screen width (weight 1.0)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            VolTab(viewModel)
                        }
                    }
                }
            }
        }
    }

    // --- POPUP MENU ---
    // If they clicked the Save icon, draw the popup asking for a name!
    if (showSaveDialog) {
        SavePresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { presetName ->
                viewModel.saveCustomPreset(presetName) // Tell the Manager to save it to the notebook!
                showSaveDialog = false
            }
        )
    }
}

/**
 * The Popup Menu asking the user to type a name for their custom Equalizer settings.
 */
@Composable
fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") } // Remembers what they are typing in the text box
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surfaceContainerHigh,
        onDismissRequest = onDismiss, // Close if they tap outside the box
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.primary)
                Text("Save Custom Preset", color = colors.onSurface, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column {
                Text(
                    "Store your current multi-band curve and acoustic settings for quick recall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                // The actual text input box
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it }, // Update memory as they type
                    label = { Text("Preset Name (e.g. Late Night Bass)", color = colors.onSurfaceVariant) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        focusedContainerColor = colors.surfaceContainerHighest,
                        unfocusedContainerColor = colors.surfaceContainerHighest.copy(alpha = 0.6f),
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant.copy(alpha = 0.4f)
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim() // Remove accidental extra spaces at the end
                    if (trimmed.isNotBlank()) {
                        Toast.makeText(context, "'$trimmed' Saved", Toast.LENGTH_SHORT).show()
                        onSave(trimmed)
                    }
                },
                enabled = name.isNotBlank(), // Disable the Save button if the text box is empty!
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Save Preset", color = colors.onPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * A blueprint for drawing the little "Equalizer" and "Effects" buttons at the top (Only used on Phones).
 */
@Composable
fun TabPill(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) colors.primary else Color.Transparent) // Color it primary if selected
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

/**
 * A tiny tool that takes the ugly computer code names (e.g. "HEAVY_METAL")
 * and turns them into pretty English text ("Heavy Metal").
 */
private fun Preset.displayName(): String =
    name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * =========================================================================================
 * 🎚️ SECTION 1: THE EQUALIZER SLIDERS
 * =========================================================================================
 * The complex section with the 5 (or 9) vertical sliders and the wavy math graph at the top.
 */
@Composable
fun EqTab(viewModel: MusicViewModel, enabled: Boolean) {
    // Read the current slider positions from the Manager's scoreboard
    val bands by viewModel.eqBands1.collectAsState()
    val view = LocalView.current

    // Most phones have 5 audio bands. Some expensive phones have 9. We need to know which one this is.
    val bandsSize by remember(bands) { derivedStateOf { bands.size } }

    // Label the sliders with the actual audio frequencies (Bass on the left, Treble on the right)
    val freqs = remember(bandsSize) {
        when (bandsSize) {
            5 -> listOf("60Hz", "230Hz", "910Hz", "3.6k", "14k")
            9 -> listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k")
            10 -> listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
            else -> List(bandsSize) { "" }
        }
    }

    // MATH WARNING: If the user pushes all the sliders to the absolute maximum (+15 dB),
    // the audio will distort and crackle horribly. This math checks if they are pushing it too hard.
    val isDistorting by remember(bands) {
        derivedStateOf {
            if (bands.isEmpty()) false
            // If the highest slider is near the top, OR the bass sliders are both very high, trigger the warning!
            else (bands.maxOrNull() ?: 0f) > 0.9f || bands.take(2).average() > 0.85
        }
    }

    val colors = MaterialTheme.colorScheme
    val currentPreset by viewModel.currentPreset.collectAsState(initial = Preset.NORMAL)
    val activeCustomName by viewModel.activeCustomPresetName.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()

    var expandedPresetMenu by remember { mutableStateOf(false) } // Is the drop-down menu open?
    var presetPendingDelete by remember { mutableStateOf<String?>(null) } // Are they trying to delete a preset?

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {

        // --- TOP ROW: MASTER SWITCH AND PRESET DROP-DOWN ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {

            // 1. The Master ON/OFF Switch
            Surface(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleEq(!enabled)
                },
                modifier = Modifier.weight(1f).height(76.dp),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    if (enabled) colors.primary.copy(alpha = 0.45f)
                    else colors.outlineVariant.copy(alpha = 0.25f)
                ),
                shadowElevation = 2.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Equalizer", color = colors.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text(
                            if (enabled) "Active processing" else "Bypassed",
                            color = if (enabled) colors.primary else colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            viewModel.toggleEq(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.primary,
                            uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            // 2. The Preset Drop-Down Menu
            Surface(
                modifier = Modifier.weight(0.9f).height(76.dp),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)),
                shadowElevation = 2.dp
            ) {
                Box {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                expandedPresetMenu = true
                            }
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "SOUND PROFILE",
                            color = colors.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                // If they clicked a custom preset ("My Bass"), show that. Otherwise show the standard one ("Rock").
                                text = activeCustomName ?: currentPreset.displayName(),
                                color = colors.primary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = colors.primary)
                        }
                    }

                    // The actual list that drops down when clicked
                    DropdownMenu(
                        expanded = expandedPresetMenu,
                        onDismissRequest = { expandedPresetMenu = false },
                        modifier = Modifier
                            .background(colors.surfaceContainerHighest)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        Text(
                            "STUDIO PRESETS",
                            color = colors.primary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        Preset.entries.forEach { p ->
                            val isSelected = activeCustomName == null && currentPreset == p
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        p.displayName(),
                                        color = if (isSelected) colors.primary else colors.onSurface,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    viewModel.applyPreset(p)
                                    expandedPresetMenu = false
                                }
                            )
                        }

                        // If they have created their own custom presets, show them at the bottom!
                        if (customPresets.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = colors.outlineVariant.copy(alpha = 0.3f)
                            )
                            Text(
                                "USER PRESETS",
                                color = colors.primary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            customPresets.keys.sorted().forEach { name ->
                                val isSelected = activeCustomName == name
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            name,
                                            color = if (isSelected) colors.primary else colors.onSurface,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    },
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        viewModel.applyCustomPreset(name)
                                        expandedPresetMenu = false
                                    },
                                    trailingIcon = {
                                        // The little trash can icon to delete their custom preset
                                        IconButton(
                                            onClick = { presetPendingDelete = name; expandedPresetMenu = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                "Delete $name",
                                                tint = colors.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Header Row above Graph: Frequency Response Title + Reset to Flat Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FREQUENCY RESPONSE CURVE",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color = colors.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )

            Surface(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.applyPreset(Preset.FLAT)
                },
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Flatten (0 dB)",
                        color = colors.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // The pretty curving math graph drawn above the sliders
        EqCurveGraph(bands = bands, enabled = enabled, labels = freqs)

        // The Warning Banner that pops up if they push the sliders too high!
        AnimatedVisibility(
            visible = isDistorting && enabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = WarningAmberColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WarningAmberColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = "Warning",
                            tint = WarningAmberColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "High gain boost may introduce digital clipping",
                            color = WarningAmberColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Faded text telling them to turn the master switch on if it's off.
        AnimatedVisibility(visible = !enabled) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    "Enable the Equalizer switch above to unlock fader adjustments",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                )
            }
        }

        // --- THE ACTUAL STUDIO FADER RACK ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(275.dp)
                .alpha(if (enabled) 1f else 0.42f),
            shape = RoundedCornerShape(26.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)),
            shadowElevation = 3.dp
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // The "+15", "0", "-15" dB scale labels on the far left side
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .padding(end = 6.dp, bottom = 22.dp, top = 24.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("+15", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text("+7", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("0dB", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.primary, fontWeight = FontWeight.ExtraBold)
                        Text("-7", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("-15", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }

                    // Draw all 5 (or 9/10) sliders side-by-side
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (i in 0 until bandsSize) {
                            VerticalEqSlider(
                                modifier = Modifier.weight(1f),
                                levelProvider = { bands.getOrNull(i) ?: 0.5f },
                                enabled = enabled,
                                onValueChange = { viewModel.updateEq(i, it) }, // Send the new value to the Manager when they drag it
                                label = freqs.getOrElse(i) { "" }
                            )
                        }
                    }
                }

                Text(
                    "Double-tap any fader to snap to 0 dB",
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // --- THE ROTARY KNOBS ---
        // Bass Boost and Surround Sound controls
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.42f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KnobCard(
                title = "Sub-Bass Boost",
                subtitle = "Low-end punch",
                valueFlow = viewModel.bassBoost,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { viewModel.updateBass(it) }

            KnobCard(
                title = "3D Spatializer",
                subtitle = "Stereo width",
                valueFlow = viewModel.virtualizer,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) { viewModel.updateVirtualizer(it) }
        }
    }

    // --- POPUP MENU (Confirm Delete) ---
    presetPendingDelete?.let { name ->
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surfaceContainerHigh,
            onDismissRequest = { presetPendingDelete = null },
            title = { Text("Delete Preset?", color = colors.onSurface, fontWeight = FontWeight.ExtraBold) },
            text = { Text("Remove custom profile '$name'? This action cannot be undone.", color = colors.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteCustomPreset(name); presetPendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                ) { Text("Delete", color = colors.onError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { presetPendingDelete = null }) {
                    Text("Cancel", color = colors.onSurfaceVariant)
                }
            }
        )
    }
}

/**
 * A blueprint for drawing the spinning Knobs (like Bass Boost & 3D Spatializer).
 */
@Composable
fun KnobCard(
    title: String,
    subtitle: String = "Drag to adjust",
    valueFlow: kotlinx.coroutines.flow.StateFlow<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val value by valueFlow.collectAsState()
    val pct = (value * 100f).roundToInt().coerceIn(0, 100)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (enabled && pct > 0) colors.primary.copy(alpha = 0.4f)
            else colors.outlineVariant.copy(alpha = 0.25f)
        ),
        shadowElevation = 3.dp
    ) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RotaryKnob(enabled = enabled, value = value, onValueChange = onValueChange)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = colors.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = CircleShape,
                color = if (pct > 0 && enabled) colors.primary.copy(alpha = 0.15f)
                else colors.surfaceContainerHighest
            ) {
                Text(
                    text = "$pct%",
                    color = if (pct > 0 && enabled) colors.primary else colors.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔊 SECTION 2: THE EFFECTS & REVERB TAB
 * =========================================================================================
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VolTab(viewModel: MusicViewModel) {
    val vol by viewModel.volume1.collectAsState()
    val currentReverb by viewModel.reverbPreset.collectAsState() // E.g., does it sound like a bathroom or a concert hall?

    val reverbNames = listOf("Studio Dry (Off)", "Small Room", "Medium Room", "Large Room", "Medium Hall", "Concert Hall", "Vintage Plate")
    val reverbMap = remember {
        listOf(
            PresetReverb.PRESET_NONE,
            PresetReverb.PRESET_SMALLROOM,
            PresetReverb.PRESET_MEDIUMROOM,
            PresetReverb.PRESET_LARGEROOM,
            PresetReverb.PRESET_MEDIUMHALL,
            PresetReverb.PRESET_LARGEHALL,
            PresetReverb.PRESET_PLATE
        )
    }
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {

        // Master Volume & Output Level Meter Card
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "OUTPUT GAINSTAGE",
                            color = colors.primary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            "Master Volume",
                            color = colors.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.primary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "${(vol * 100f).roundToInt()}%",
                            color = colors.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // 16-Segment Studio LED VU Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val totalSegments = 16
                    val activeSegments = (vol * totalSegments).roundToInt()
                    for (seg in 1..totalSegments) {
                        val isLit = seg <= activeSegments
                        val segColor = when {
                            !isLit -> colors.surfaceContainerHighest
                            seg > 13 -> WarningAmberColor
                            seg > 9 -> colors.primary
                            else -> StudioGreenColor
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(segColor)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                CustomSlider(vol) { viewModel.updateVolume(it) }

                Spacer(Modifier.height(12.dp))

                // Quick Gain Stepper Presets (25%, 50%, 75%, 100%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1.0f to "MAX").forEach { (presetVol, label) ->
                        val isCurrent = abs(vol - presetVol) < 0.03f
                        Surface(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                viewModel.updateVolume(presetVol)
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) colors.primary else colors.surfaceContainerHighest
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCurrent) colors.onPrimary else colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Reverb selection buttons
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.25f)),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    "SPATIAL ACOUSTICS",
                    color = colors.primary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    "Room & Hall Reverb",
                    color = colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Simulate acoustic reflections from intimate studio rooms to expansive concert halls.",
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))

                // FlowRow automatically wraps buttons to the next line if they run out of space!
                FlowRow(
                    Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    reverbNames.forEachIndexed { i, name ->
                        val presetConst = reverbMap.getOrElse(i) { PresetReverb.PRESET_NONE }
                        ReverbChip(
                            text = name,
                            active = currentReverb == presetConst,
                            modifier = Modifier.weight(1f)
                        ) {
                            viewModel.setReverb(presetConst)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

/**
 * =========================================================================================
 * 📉 THE MATH GRAPH (EqCurveGraph)
 * =========================================================================================
 * Draws the pretty curving blue line that connects all the sliders together.
 */
@Composable
fun EqCurveGraph(bands: List<Float>, enabled: Boolean, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    val grad = Brush.verticalGradient(
        listOf(
            colors.primary.copy(alpha = 0.42f),
            colors.primary.copy(alpha = 0.08f),
            Color.Transparent
        )
    )

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .alpha(if (enabled) 1f else 0.42f),
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.28f)),
            shadowElevation = 3.dp
        ) {
            // A Canvas gives us direct, pixel-perfect control over drawing shapes and lines
            Canvas(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (bands.size < 2) return@Canvas

                val path = Path() // The invisible mathematical line we are going to draw
                val spacing = size.width / (bands.size - 1).coerceAtLeast(1)

                // Draw the faint dotted reference lines in the background (+7.5dB, 0dB, -7.5dB)
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                val lineCol = colors.onSurfaceVariant.copy(alpha = 0.18f)
                val zeroDbCol = colors.primary.copy(alpha = 0.35f)

                drawLine(zeroDbCol, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height / 4f), Offset(size.width, size.height / 4f), 1.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height - size.height / 4f), Offset(size.width, size.height - size.height / 4f), 1.dp.toPx(), pathEffect = dash)

                // Draw vertical frequency grid lines
                bands.indices.forEach { i ->
                    val x = i * spacing
                    drawLine(
                        color = lineCol.copy(alpha = 0.1f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Construct smooth cubic Bezier curve through all band points
                bands.forEachIndexed { i, lvl ->
                    val clampedLvl = lvl.coerceIn(0f, 1f)
                    val x = i * spacing
                    val y = size.height - (clampedLvl * size.height)
                    if (i == 0) {
                        path.moveTo(x, y) // Start the line at the first slider
                    } else {
                        val pX = (i - 1) * spacing
                        val pY = size.height - (bands[i - 1].coerceIn(0f, 1f) * size.height)
                        val cX = pX + spacing / 2f
                        // cubicTo creates a smooth, swooping curve instead of a harsh zig-zag line.
                        path.cubicTo(cX, pY, cX, y, x, y)
                    }
                }

                // Fill the area underneath the curve with the gradient
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fillPath, grad)

                // Draw soft outer neon bloom stroke
                drawPath(
                    path = path,
                    color = colors.primary.copy(alpha = 0.25f),
                    style = Stroke(9.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw the crisp primary spline curve
                drawPath(
                    path = path,
                    color = colors.primary,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draw glowing control nodes at each frequency point
                bands.forEachIndexed { i, lvl ->
                    val x = i * spacing
                    val y = size.height - (lvl.coerceIn(0f, 1f) * size.height)
                    drawCircle(
                        color = colors.surfaceContainerHigh,
                        radius = 5.5.dp.toPx(),
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = colors.primary,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🎚️ THE VERTICAL FADER (VerticalEqSlider)
 * =========================================================================================
 * A custom-built vertical slider that looks like professional audio mixing hardware.
 * Upgraded with Bi-Directional Center-Zero (0 dB) illumination and a sculpted studio fader cap.
 */
@Composable
fun VerticalEqSlider(
    modifier: Modifier,
    levelProvider: () -> Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    label: String
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current // Used to trigger physical vibrations (haptics)

    var height by remember { mutableFloatStateOf(0f) }
    val rawLevel = levelProvider() // 0.0 (Bottom) to 1.0 (Top)

    var isDragging by remember { mutableStateOf(false) } // Is the user touching this slider?
    var dragLevel by remember { mutableFloatStateOf(rawLevel) }
    var lastSent by remember { mutableFloatStateOf(rawLevel) }

    LaunchedEffect(rawLevel) {
        if (!isDragging) {
            dragLevel = rawLevel
            lastSent = rawLevel
        }
    }

    // Makes the slider glide smoothly instead of teleporting if an external preset changes it.
    val displayLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else rawLevel,
        animationSpec = if (isDragging) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "eqSliderAnimation"
    )

    // Convert 0.0-1.0 scale to -15dB to +15dB scale for the UI
    val dbValue = ((displayLevel - 0.5f) * 30f).roundToInt()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxHeight()
    ) {
        // Live Monospace dB Readout Badge at the top of each channel strip
        Text(
            text = "${if (dbValue > 0) "+" else ""}$dbValue",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = when {
                isDragging -> colors.primary
                dbValue != 0 -> colors.onSurface
                else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )

        Spacer(Modifier.height(4.dp))

        Box(
            Modifier
                .weight(1f)
                .width(44.dp)
                .onSizeChanged { height = it.height.toFloat() }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // Double tapping the slider instantly resets it to the middle (0 dB)
                    detectTapGestures(onDoubleTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dragLevel = 0.5f
                        lastSent = 0.5f
                        onValueChange(0.5f)
                    })
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragLevel = rawLevel
                        },
                        onDragEnd = {
                            isDragging = false
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) // Buzz when they let go
                            onValueChange(dragLevel) // Save the final setting!
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    ) { c, d ->
                        c.consume() // Tell Android we handled this touch, do not scroll the whole page!
                        if (height <= 0f) return@detectVerticalDragGestures
                        var next = (dragLevel - d / height).coerceIn(0f, 1f)
                        // If they get really close to the middle (0 dB), snap it perfectly to the middle.
                        if (abs(next - 0.5f) < 0.035f) next = 0.5f
                        dragLevel = next

                        // To prevent flooding the Manager with 1000 messages a second, only send updates if it moved significantly.
                        if (abs(next - lastSent) > 0.015f) {
                            // Give a tiny "tick" vibration if they drag it exactly through the middle point
                            if (next == 0.5f && lastSent != 0.5f) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            lastSent = next
                            onValueChange(next)
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Draw the studio slot track & bipolar 0dB illumination
            Canvas(Modifier.fillMaxSize()) {
                val tW = 6.dp.toPx()
                val cX = size.width / 2f
                val centerY = size.height / 2f
                val currentY = size.height * (1f - displayLevel)

                // Subtle horizontal tick marks along the fader slot
                for (tick in 1..9) {
                    val tickY = size.height * (tick / 10f)
                    val tickHalfW = if (tick == 5) 11.dp.toPx() else 6.dp.toPx()
                    drawLine(
                        color = colors.onSurfaceVariant.copy(alpha = if (tick == 5) 0.45f else 0.16f),
                        start = Offset(cX - tickHalfW, tickY),
                        end = Offset(cX + tickHalfW, tickY),
                        strokeWidth = if (tick == 5) 2.dp.toPx() else 1.dp.toPx()
                    )
                }

                // The recessed dark fader slot
                drawRoundRect(
                    color = colors.surfaceContainerHighest,
                    topLeft = Offset(cX - tW / 2f, 0f),
                    size = Size(tW, size.height),
                    cornerRadius = CornerRadius(tW / 2f)
                )

                // Bipolar active illumination from 0 dB center line to the current fader position
                if (enabled) {
                    val topY = min(centerY, currentY)
                    val barH = abs(currentY - centerY).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(colors.primary, colors.tertiary)),
                        topLeft = Offset(cX - tW / 2f, topY),
                        size = Size(tW, barH),
                        cornerRadius = CornerRadius(tW / 2f)
                    )
                }
            }

            // The physical sculpted studio fader cap you grab
            val handleH = 26.dp
            val handleY = if (height > 0f) {
                (height - with(density) { handleH.toPx() }) * (1f - displayLevel)
            } else 0f

            Surface(
                modifier = Modifier
                    .offset { IntOffset(0, handleY.roundToInt()) }
                    .size(width = 30.dp, height = handleH),
                shape = RoundedCornerShape(8.dp),
                color = if (isDragging) colors.primary else colors.surfaceContainerHighest,
                border = BorderStroke(
                    1.5.dp,
                    if (isDragging) Color.White else colors.primary.copy(alpha = 0.7f)
                ),
                shadowElevation = if (isDragging) 8.dp else 4.dp
            ) {
                // Center horizontal grip line on the fader cap
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(if (isDragging) Color.White else colors.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Frequency label at the bottom of the fader strip (e.g., 60Hz, 3.6k)
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * =========================================================================================
 * 🎛️ THE ROTARY KNOB (Spinning dial for Bass Boost & Spatializer)
 * =========================================================================================
 * Instead of sliding up and down, the user puts their thumb on it and drags up/down to SPIN the dial.
 */
@Composable
fun RotaryKnob(enabled: Boolean = true, value: Float, onValueChange: (Float) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val scale = remember(density) { with(density) { 180.dp.toPx() } }

    var internalValue by remember { mutableFloatStateOf(value) } // 0.0 to 1.0
    var isDragging by remember { mutableStateOf(false) }
    var lastSent by remember { mutableFloatStateOf(internalValue) }

    LaunchedEffect(value) {
        if (!isDragging) {
            internalValue = value
            lastSent = value
        }
    }

    Box(
        Modifier
            .size(84.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        internalValue = 0f
                        lastSent = 0f
                        onValueChange(0f)
                    }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDragCancel = { isDragging = false }
                ) { c, d ->
                    c.consume()
                    // If they drag their thumb UP, turn the dial right. Drag DOWN, turn dial left.
                    val n = (internalValue - d / scale).coerceIn(0f, 1f)
                    internalValue = n
                    if (abs(n - lastSent) > 0.02f) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastSent = n
                        onValueChange(n) // Send update to manager
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Calculate the exact angle to spin the circle
        val animatedRotation by animateFloatAsState(
            targetValue = internalValue * 270f,
            animationSpec = if (isDragging) snap() else tween(250),
            label = "knobRotation"
        )

        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2.6f

            // Draw the 15 tiny LED indicator dots in an arc around the dial
            for (i in 0..15) {
                val dA = (135f + i * (270f / 15f)) * (PI / 180f)
                val act = (i / 15f) <= internalValue && enabled
                drawCircle(
                    color = if (act) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.25f),
                    radius = if (act) 3.dp.toPx() else 2.dp.toPx(),
                    center = Offset(
                        center.x + (r + 12.dp.toPx()) * cos(dA).toFloat(),
                        center.y + (r + 12.dp.toPx()) * sin(dA).toFloat()
                    )
                )
            }

            // Draw the metallic outer bevel and inner dial cap
            drawCircle(colors.surfaceContainerHighest, r)
            drawCircle(
                color = if (isDragging) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
                radius = r,
                style = Stroke(2.5.dp.toPx())
            )

            // Draw the glowing pointer line & tip on the dial that shows where it is pointing
            val iA = (135f + animatedRotation) * (PI / 180f)
            val innerStart = Offset(
                center.x + (r * 0.28f) * cos(iA).toFloat(),
                center.y + (r * 0.28f) * sin(iA).toFloat()
            )
            val outerTip = Offset(
                center.x + (r * 0.76f) * cos(iA).toFloat(),
                center.y + (r * 0.76f) * sin(iA).toFloat()
            )
            drawLine(
                color = if (enabled) colors.primary else colors.onSurfaceVariant,
                start = innerStart,
                end = outerTip,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

// A blueprint for drawing the little "Small Room", "Concert Hall" buttons
@Composable
fun ReverbChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme

    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) // Tiny click feeling
            onClick()
        },
        shape = RoundedCornerShape(16.dp),
        color = if (active) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        border = BorderStroke(
            1.dp,
            if (active) colors.primary else colors.outlineVariant.copy(alpha = 0.25f)
        ),
        shadowElevation = if (active) 4.dp else 0.dp,
        modifier = modifier.height(44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = if (active) Icons.Rounded.SpatialAudioOff else Icons.Rounded.SurroundSound,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) colors.onPrimary else colors.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// The master volume slider
@Composable
fun CustomSlider(value: Float, onValueChange: (Float) -> Unit) {
    var temp by remember(value) { mutableFloatStateOf(value) }
    val colors = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "Volume",
            tint = colors.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))

        // Uses the standard Android Material Slider tool
        Slider(
            value = temp,
            onValueChange = {
                temp = it
                onValueChange(it) // Live smooth volume adjustment
            },
            onValueChangeFinished = { onValueChange(temp) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.25f)
            )
        )

        Spacer(Modifier.width(12.dp))
        Text(
            text = "${(temp * 100f).roundToInt()}%",
            color = colors.onBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(44.dp)
        )
    }
}