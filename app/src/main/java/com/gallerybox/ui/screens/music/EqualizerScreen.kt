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
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.text.font.FontWeight
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
private val WarningAmberColor = Color(0xFFFFA000)

/**
 * =========================================================================================
 * 🎛️ THE DJ BOOTH (EqualizerScreen)
 * =========================================================================================
 * This is the main screen where the user can adjust the bass, treble, and surround sound effects.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(viewModel: MusicViewModel = hiltViewModel(), onBack: () -> Unit) {

    // 🧠 1. Bring in the Adaptive Engine to know if this is a phone, tablet, or foldable!
    val adaptiveState = rememberAdaptiveState()

    // Local memory for this screen: Should we show the popup asking the user to name their custom preset?
    var showSaveDialog by remember { mutableStateOf(false) }

    // Look at the Manager's scoreboard: Is the Equalizer master switch currently ON or OFF?
    val enabled by viewModel.eqEnabled.collectAsState()

    // The engine that handles the swiping between the two tabs (if we need tabs)
    val pagerState = rememberPagerState(pageCount = { 2 })

    val scope = rememberCoroutineScope() // Tool for launching background tasks
    val colors = MaterialTheme.colorScheme // The app's color palette

    // Scaffold is a blank canvas with pre-marked zones for the Top Bar and the main Content
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Equalizer", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = colors.onSurface) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onSurface) } },
                actions = {
                    // The "Save" icon button in the top right corner
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.Save, "Save Preset", tint = colors.onSurface)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        // The background color with a faint gradient glow at the top
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.05f), colors.background))))

            // Main Content Area
            Column(Modifier.fillMaxSize().padding(paddingValues)) {

                // 🎨 2. THE SHAPE-SHIFTER (Adaptive Layout Logic)
                // We ask the Adaptive Engine: "Is this a small phone?"
                if (adaptiveState.widthSize == WindowWidthSize.COMPACT) {

                    // 📱 PHONE MODE: Not enough room. We must use swiping Tabs.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.height(44.dp).padding(4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TabPill("Equalizer", pagerState.currentPage == 0, Modifier.weight(1f)) { scope.launch { pagerState.animateScrollToPage(0) } }
                                TabPill("Effects", pagerState.currentPage == 1, Modifier.weight(1f)) { scope.launch { pagerState.animateScrollToPage(1) } }
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
                        modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 16.dp, end = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
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
        title = { Text("Save Custom Preset", color = colors.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            // The actual text input box
            OutlinedTextField(
                value = name,
                onValueChange = { name = it }, // Update memory as they type
                label = { Text("Preset Name", color = colors.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = colors.primary,
                    unfocusedIndicatorColor = colors.onSurfaceVariant
                )
            )
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
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Save", color = colors.onPrimary, fontWeight = FontWeight.Bold)
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
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) colors.primary else Color.Transparent) // Color it blue if selected
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
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

    // Most phones have 5 audio bands. Some expensive phones have 9. We need to know which one this is.
    val bandsSize by remember(bands) { derivedStateOf { bands.size } }

    // Label the sliders with the actual audio frequencies (Bass on the left, Treble on the right)
    val freqs = remember(bandsSize) {
        when (bandsSize) {
            5 -> listOf("60", "230", "910", "3.6k", "14k")
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

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 48.dp)) {

        // --- TOP ROW: MASTER SWITCH AND PRESET DROP-DOWN ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            // 1. The Master ON/OFF Switch
            Surface(
                modifier = Modifier.weight(1f).height(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh,
                shadowElevation = 2.dp
            ) {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Equalizer", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Improve sound quality", color = colors.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { viewModel.toggleEq(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, uncheckedTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
                    )
                }
            }

            // 2. The Preset Drop-Down Menu
            Surface(
                modifier = Modifier.weight(0.75f).height(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh,
                shadowElevation = 2.dp
            ) {
                Box {
                    Column(
                        Modifier.fillMaxSize().clickable { expandedPresetMenu = true }.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Preset", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                // If they clicked a custom preset ("My Bass"), show that. Otherwise show the standard one ("Rock").
                                text = activeCustomName ?: currentPreset.displayName(),
                                color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = colors.primary)
                        }
                    }

                    // The actual list that drops down when clicked
                    DropdownMenu(expanded = expandedPresetMenu, onDismissRequest = { expandedPresetMenu = false }, modifier = Modifier.background(colors.surfaceContainerHigh).clip(RoundedCornerShape(12.dp))) {
                        Text("BUILT-IN", color = colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        Preset.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName(), color = colors.onSurface) },
                                onClick = { viewModel.applyPreset(p); expandedPresetMenu = false } // Tell Manager to change settings!
                            )
                        }

                        // If they have created their own custom presets, show them at the bottom!
                        if (customPresets.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = colors.background)
                            Text("YOUR PRESETS", color = colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            customPresets.keys.sorted().forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name, color = colors.onSurface) },
                                    onClick = { viewModel.applyCustomPreset(name); expandedPresetMenu = false },
                                    trailingIcon = {
                                        // The little trash can icon to delete their custom preset
                                        IconButton(onClick = { presetPendingDelete = name; expandedPresetMenu = false }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Delete, "Delete $name", tint = colors.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Reset to Flat button
        TextButton(onClick = { viewModel.applyPreset(Preset.FLAT) }, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.Refresh, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset to Flat", color = colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))

        // The pretty curving math graph drawn above the sliders
        EqCurveGraph(bands = bands, enabled = enabled, labels = freqs)

        // The Warning Banner that pops up if they push the sliders too high!
        AnimatedVisibility(visible = isDistorting && enabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                Surface(shape = CircleShape, color = WarningAmberColor.copy(alpha = 0.15f)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, "Warning", tint = WarningAmberColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("High gain may cause audio clipping", color = WarningAmberColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Faded text telling them to turn the master switch on if it's off.
        AnimatedVisibility(visible = !enabled) {
            Text("Turn on Equalizer above to adjust bands", color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        }

        // --- THE ACTUAL SLIDERS ---
        // If disabled, make them 40% see-through (alpha).
        Row(Modifier.weight(1f).fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
            // The "+15", "0", "-15" labels on the far left side
            Column(Modifier.fillMaxHeight().padding(end = 12.dp, bottom = 24.dp, top = 14.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text("+15", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text("0", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text("-15", fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            // Draw all 5 (or 9) sliders side-by-side
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
            "Double-tap a band to reset it",
            color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
        )

        Spacer(Modifier.height(24.dp))

        // --- THE ROTARY KNOBS ---
        // Bass Boost and Surround Sound controls
        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            KnobCard("Bass Boost", viewModel.bassBoost, enabled) { viewModel.updateBass(it) }
            Spacer(Modifier.width(16.dp))
            KnobCard("Surround", viewModel.virtualizer, enabled) { viewModel.updateVirtualizer(it) }
        }
    }

    // --- POPUP MENU (Confirm Delete) ---
    presetPendingDelete?.let { name ->
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surfaceContainerHigh,
            onDismissRequest = { presetPendingDelete = null },
            title = { Text("Delete Preset?", color = colors.onSurface, fontWeight = FontWeight.Bold) },
            text = { Text("Delete '$name'? This can't be undone.", color = colors.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteCustomPreset(name); presetPendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                ) { Text("Delete", color = colors.onError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { presetPendingDelete = null }) { Text("Cancel", color = colors.onSurfaceVariant) } }
        )
    }
}

/**
 * A blueprint for drawing the spinning Knobs (like Bass Boost).
 */
@Composable
fun KnobCard(title: String, valueFlow: kotlinx.coroutines.flow.StateFlow<Float>, enabled: Boolean, onValueChange: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val value by valueFlow.collectAsState()

    Surface(
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RotaryKnob(enabled = enabled, value = value, onValueChange = onValueChange)
            Spacer(Modifier.height(16.dp))
            Text(title, color = if (enabled) colors.onSurface else colors.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("${(value * 100).toInt()}%", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Drag to adjust", color = colors.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 10.sp)
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

    val reverbNames = listOf("Off", "Small Room", "Med Room", "Large Room", "Med Hall", "Large Hall", "Plate")
    val reverbMap = remember { listOf(PresetReverb.PRESET_NONE, PresetReverb.PRESET_SMALLROOM, PresetReverb.PRESET_MEDIUMROOM, PresetReverb.PRESET_LARGEROOM, PresetReverb.PRESET_MEDIUMHALL, PresetReverb.PRESET_LARGEHALL, PresetReverb.PRESET_PLATE) }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {

        // Master Volume Slider
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Master Volume", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                CustomSlider(vol) { viewModel.updateVolume(it) }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Reverb selection buttons
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Environment (Reverb)", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                // FlowRow automatically wraps buttons to the next line if they run out of space!
                FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    reverbNames.forEachIndexed { i, name ->
                        val presetConst = reverbMap.getOrElse(i) { PresetReverb.PRESET_NONE }
                        ReverbChip(name, currentReverb == presetConst) { viewModel.setReverb(presetConst) }
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
    val grad = Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.6f), Color.Transparent)) // Blue fading to invisible

    Column {
        Box(Modifier.fillMaxWidth().height(140.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(20.dp))) {
            // A Canvas gives us direct, pixel-perfect control over drawing shapes and lines
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = colors.surfaceContainerHigh, cornerRadius = CornerRadius(20.dp.toPx()))
                if (bands.size < 2) return@Canvas

                val path = Path() // The invisible mathematical line we are going to draw
                val spacing = size.width / (bands.size - 1).coerceAtLeast(1)

                bands.forEachIndexed { i, lvl ->
                    val x = i * spacing
                    val y = size.height - (lvl * size.height)
                    if (i == 0) path.moveTo(x, y) // Start the line at the first slider
                    else {
                        val pX = (i - 1) * spacing
                        val pY = size.height - (bands[i - 1] * size.height)
                        val cX = pX + spacing / 2
                        // cubicTo creates a smooth, swooping curve instead of a harsh zig-zag line.
                        path.cubicTo(cX, pY, cX, y, x, y)
                    }
                }

                // Draw the faint dotted lines in the background
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                val lineCol = colors.onSurfaceVariant.copy(alpha = 0.2f)

                drawLine(lineCol, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height / 4), Offset(size.width, size.height / 4), 1.dp.toPx(), pathEffect = dash)
                drawLine(lineCol, Offset(0f, size.height - size.height / 4), Offset(size.width, size.height - size.height / 4), 1.dp.toPx(), pathEffect = dash)

                // Fill the area underneath the curve with the blue gradient
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fillPath, grad)

                // Finally, draw the thick blue line itself!
                drawPath(path, color = colors.primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        // Draw the text labels under the graph
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { label ->
                Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * =========================================================================================
 * 🎚️ THE VERTICAL FADER (VerticalEqSlider)
 * =========================================================================================
 * A custom-built vertical slider that looks like professional audio mixing hardware.
 */
@Composable
fun VerticalEqSlider(modifier: Modifier, levelProvider: () -> Float, enabled: Boolean, onValueChange: (Float) -> Unit, label: String) {
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

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
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
                        var next = (dragLevel - d / height).coerceIn(0f, 1f)
                        // If they get really close to the middle (0 dB), snap it perfectly to the middle.
                        if (abs(next - 0.5f) < 0.05f) next = 0.5f
                        dragLevel = next

                        // To prevent flooding the Manager with 1000 messages a second, only send updates if it moved significantly.
                        if (abs(next - lastSent) > 0.02f) {
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
            // Draw the track
            Canvas(Modifier.fillMaxSize()) {
                val tW = 6.dp.toPx()
                val cX = size.width / 2
                val grad = Brush.verticalGradient(listOf(colors.primary, colors.secondary))

                // The empty grey background track
                drawRoundRect(colors.onSurfaceVariant.copy(alpha = 0.2f), Offset(cX - tW / 2, 0f), Size(tW, size.height), CornerRadius(10f))
                // A tiny horizontal line marking the exact center (0 dB)
                drawLine(colors.onSurfaceVariant.copy(alpha = 0.4f), Offset(cX - 12f, size.height / 2), Offset(cX + 12f, size.height / 2), 3f)

                // The filled blue color for the track below the knob
                if (enabled) {
                    drawRoundRect(grad, Offset(cX - tW / 2, size.height * (1f - displayLevel)), Size(tW, size.height * displayLevel), CornerRadius(10f))
                }
            }

            // The physical knob you grab
            val handleH = 28.dp
            val handleY = if (height > 0) (height - with(density) { handleH.toPx() }) * (1f - displayLevel) else 0f

            Box(
                Modifier
                    .offset { IntOffset(0, handleY.toInt()) }
                    .size(28.dp, handleH)
                    .background(colors.primary, CircleShape)
                    .border(2.dp, colors.surface, CircleShape) // Add a white border so it pops out
            )

            // A tiny floating tooltip that appears ABOVE the user's thumb while dragging, showing the exact number!
            this@Column.AnimatedVisibility(
                visible = isDragging,
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, handleY.toInt() - 36) },
                enter = fadeIn(), exit = fadeOut()
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = colors.primary) {
                    Text(
                        text = "${if (dbValue > 0) "+" else ""}$dbValue dB", // e.g. "+5 dB"
                        color = colors.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🎛️ THE ROTARY KNOB (Spinning dial for Bass Boost)
 * =========================================================================================
 * Instead of sliding up and down, the user puts their thumb on it and drags up/down to SPIN the dial.
 */
@Composable
fun RotaryKnob(enabled: Boolean = true, value: Float, onValueChange: (Float) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val scale = remember(density) { with(density) { 200.dp.toPx() } }

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
            .size(72.dp)
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
                        lastSent = n
                        onValueChange(n) // Send update to manager
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Calculate the exact angle to spin the circle
        val animatedRotation by animateFloatAsState(targetValue = internalValue * 270f, label = "knobRotation")

        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2.5f

            // Draw the 15 tiny indicator dots in an arc around the dial
            for (i in 0..15) {
                val dA = (135f + i * (270f / 15)) * (PI / 180f)
                val act = (i / 15f) <= internalValue && enabled
                drawCircle(if (act) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f), 4f, Offset(center.x + (r + 14f) * cos(dA).toFloat(), center.y + (r + 14f) * sin(dA).toFloat()))
            }

            // Draw the actual physical dial
            drawCircle(colors.surfaceContainerHighest, r)
            drawCircle(colors.primaryContainer, r, style = Stroke(3f))

            // Draw the tiny blue dot ON the dial that shows where it is pointing
            val iA = (135f + animatedRotation) * (PI / 180f)
            drawCircle(if (enabled) colors.primary else colors.onSurfaceVariant, 6f, Offset(center.x + (r * 0.7f) * cos(iA).toFloat(), center.y + (r * 0.7f) * sin(iA).toFloat()))
        }
    }
}

// A blueprint for drawing the little "Small Room", "Concert Hall" buttons
@Composable
fun ReverbChip(text: String, active: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme

    Surface(
        onClick = {
            onClick()
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) // Tiny click feeling
        },
        shape = CircleShape,
        color = if (active) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        modifier = Modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// The master volume slider
@Composable
fun CustomSlider(value: Float, onValueChange: (Float) -> Unit) {
    var temp by remember(value) { mutableFloatStateOf(value) }
    val colors = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))

        // Uses the standard Android Material Slider tool
        Slider(
            value = temp,
            onValueChange = { temp = it },
            onValueChangeFinished = { onValueChange(temp) }, // Only send to manager when they LET GO of the slider
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.onSurfaceVariant.copy(alpha = 0.3f))
        )

        Spacer(Modifier.width(16.dp))
        Text("${(temp * 100).toInt()}%", color = colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
    }
}