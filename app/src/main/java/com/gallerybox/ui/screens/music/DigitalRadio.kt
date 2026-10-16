package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// =========================================================================================
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage

// Project-specific imports
import com.gallerybox.viewmodel.DigitalStation
import com.gallerybox.viewmodel.RadioViewModel
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- MAIN MANAGER: DIGITAL RADIO SCREEN ---
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DigitalRadioScreen(
    viewModel: RadioViewModel,
    onBack: () -> Unit
) {
    val adaptiveState = rememberAdaptiveState()
    val view = LocalView.current

    val stations by viewModel.digitalStations.collectAsState()
    val currentStation by viewModel.currentDigitalStation.collectAsState()
    val isPlaying by viewModel.isDigitalPlaying.collectAsState()
    val isLoading by viewModel.isDigitalLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val categories = viewModel.digitalCategories
    val selectedCategory by viewModel.selectedDigitalCategory.collectAsState()

    val filteredStations = remember(stations, searchQuery) {
        if (searchQuery.isBlank()) {
            stations
        } else {
            stations.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.tags.contains(searchQuery, ignoreCase = true) ||
                        it.country.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
        Modifier.width(680.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            // Subtle Top Ambient Radial Spotlight Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
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

            Column(modifier = contentWidthModifier.fillMaxHeight()) {

                // --- PREMIUM BROADCAST TOP BAR ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            shadowElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "Digital Radio",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    // Live Station Counter Badge
                    if (filteredStations.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isPlaying) Color(0xFF00E676)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                )
                                Text(
                                    text = "${filteredStations.size} Channels",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // --- SEARCH BAR & SCAN BUTTON ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = "Search stations, genres, or countries...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        viewModel.updateSearchQuery("")
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        ),
                        singleLine = true
                    )

                    Spacer(Modifier.width(12.dp))

                    // Spinning refresh animation when loading
                    val infiniteTransition = rememberInfiniteTransition(label = "ScanInfinite")
                    val infiniteRotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                        label = "ScanSpin"
                    )

                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.selectDigitalCategory(selectedCategory)
                        },
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = if (isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
                        shadowElevation = if (isLoading) 6.dp else 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = "Scan Stations",
                                tint = if (isLoading) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(if (isLoading) infiniteRotation else 0f)
                            )
                        }
                    }
                }

                // --- CATEGORY CHIPS ---
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { category ->
                        val isSelected = category == selectedCategory
                        val chipScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.04f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "chipScale"
                        )

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                viewModel.selectDigitalCategory(category)
                            },
                            modifier = Modifier.graphicsLayer {
                                scaleX = chipScale
                                scaleY = chipScale
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.CellTower,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            label = {
                                Text(
                                    text = category,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            },
                            shape = RoundedCornerShape(22.dp),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // --- THE MAIN CONTENT AREA ---
                if (isLoading && stations.isEmpty()) {
                    // Scanning Broadcast State
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Icon(
                                    Icons.Rounded.CellTower,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = "Scanning Global Frequencies...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tuning into live $selectedCategory streams",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (stations.isEmpty()) {
                    // Empty Category / Offline State
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(28.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.PortableWifiOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "No Broadcasts Found",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Could not load live stations for \"$selectedCategory\". Check your internet connection and try again.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(22.dp))
                                Button(
                                    onClick = { viewModel.selectDigitalCategory(selectedCategory) },
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Retry Connection", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else if (filteredStations.isEmpty()) {
                    // No Search Results State
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "No Matching Stations",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "We couldn't find any stations matching \"$searchQuery\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            FilledTonalButton(
                                onClick = { viewModel.updateSearchQuery("") },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Clear Search Filter", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Station List
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = if (currentStation != null) 120.dp else 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(filteredStations, key = { it.id }) { station ->
                            val isSelectedStation = currentStation?.id == station.id
                            DigitalStationItem(
                                station = station,
                                isSelected = isSelectedStation,
                                isPlaying = isSelectedStation && isPlaying,
                                onPlayClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    viewModel.playDigitalStation(station)
                                },
                                onFavoriteClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    viewModel.toggleDigitalFavorite(station.id)
                                }
                            )
                        }
                    }
                }
            }

            // --- FLOATING "NOW PLAYING" BAR ---
            AnimatedVisibility(
                visible = currentStation != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding() // Ensures it sits safely above system bars
            ) {
                currentStation?.let { station ->
                    NowPlayingBar(
                        station = station,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        modifier = contentWidthModifier,
                        onPlayPauseClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            viewModel.toggleDigitalPlayPause()
                        },
                        onFavoriteClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.toggleDigitalFavorite(station.id)
                        }
                    )
                }
            }
        }
    }
}

// =========================================================================================
// --- LIVE ANIMATED SPECTRUM BARS ---
// =========================================================================================
@Composable
private fun LiveSpectrumBars(
    isPlaying: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadioSpectrum")
    val durations = listOf(340, 490, 290, 430)
    val heights = durations.mapIndexed { index, duration ->
        infiniteTransition.animateFloat(
            initialValue = 0.22f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, delayMillis = index * 65, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "BarHeight_$index"
        )
    }

    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { anim ->
            val fraction = if (isPlaying) anim.value else 0.25f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

// =========================================================================================
// --- STATION ROW CARD ---
// =========================================================================================
@Composable
private fun DigitalStationItem(
    station: DigitalStation,
    isSelected: Boolean,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(300),
        label = "CardContainer"
    )
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    val favScale by animateFloatAsState(
        targetValue = if (station.isFavorite) 1.18f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "FavBounce"
    )

    val subtitleText = remember(station.country, station.tags) {
        listOf(station.country.trim(), station.tags.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
            .ifEmpty { "Live Broadcast Stream" }
    }

    Surface(
        onClick = onPlayClick,
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
        ),
        shadowElevation = if (isSelected) 6.dp else 2.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station Artwork with Live Equalizer Overlay
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else Color.Transparent,
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (station.imageUrl.isNotEmpty()) {
                    AsyncImage(
                        model = station.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        LiveSpectrumBars(
                            isPlaying = isPlaying,
                            barColor = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Text Info & Broadcast Tags
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = station.name,
                        color = contentColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isPlaying) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "LIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimary,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = subtitleText,
                    color = if (isSelected) contentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Favorite Button (Independent action with bouncy spring scale)
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.graphicsLayer {
                    scaleX = favScale
                    scaleY = favScale
                }
            ) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (station.isFavorite) Color(0xFFFF4081)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// =========================================================================================
// --- NOW PLAYING BAR (Floating Glassmorphic Broadcast Dock) ---
// =========================================================================================
@Composable
private fun NowPlayingBar(
    station: DigitalStation,
    isPlaying: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onPlayPauseClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    // Pulsing Crimson "ON AIR" Beacon Dot
    val infiniteTransition = rememberInfiniteTransition(label = "LiveBeacon")
    val beaconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BeaconAlpha"
    )

    Surface(
        modifier = modifier.height(82.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                )
            )
        ),
        shadowElevation = 14.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Blurred station artwork ambient wash inside the dock
            if (station.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = station.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(40.dp),
                    alpha = 0.16f
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Station Artwork Thumbnail
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (station.imageUrl.isNotEmpty()) {
                        AsyncImage(
                            model = station.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Radio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                // Station Name & Live Status Telemetry
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = station.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(3.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPlaying) Color(0xFFFF1744).copy(alpha = beaconAlpha)
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                        Text(
                            text = when {
                                isLoading -> "CONNECTING STREAM..."
                                isPlaying -> "LIVE ON AIR"
                                else -> "STREAM PAUSED"
                            },
                            color = if (isPlaying) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.7.sp
                        )

                        if (station.country.isNotBlank()) {
                            Text(
                                text = "• ${station.country}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Quick Favorite Toggle on the Dock
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (station.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorite Station",
                        tint = if (station.isFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Glowing Play/Pause Button
                Surface(
                    onClick = onPlayPauseClick,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f)),
                    shadowElevation = if (isPlaying) 8.dp else 2.dp,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}