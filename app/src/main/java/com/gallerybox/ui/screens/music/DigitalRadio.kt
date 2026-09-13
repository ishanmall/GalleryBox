@file:Suppress("UnusedImport", "unused")
package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// Bringing in all the Compose UI components, icons, layout tools, and animation libraries.
// =========================================================================================
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
// --- THE PAINT PALETTE ---
// Specific, branded colors that keep the UI looking clean and consistent.
// =========================================================================================
private val BgColor = Color(0xFFF2F2F7)       // Soft gray background
private val SurfaceColor = Color(0xFFFFFFFF)  // Pure white for cards/surfaces
private val PrimaryColor = Color(0xFF007AFF)  // Bright blue for main actions (play, scan)
private val TextPrimary = Color(0xFF000000)   // Solid black text
private val TextSecondary = Color(0xFF8E8E93) // Faded gray text for subtitles/placeholders

// =========================================================================================
// --- MAIN MANAGER: DIGITAL RADIO SCREEN ---
// Analogy: Think of this screen as a massive shortwave radio receiver.
// It searches the entire internet for live radio broadcasts, lets the user tune into one,
// and plays it instantly.
// =========================================================================================
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DigitalRadioScreen(
    viewModel: RadioViewModel, // The "Operator" who actually connects to the internet streams
    onBack: () -> Unit         // The action to take when the user hits the back button
) {
    // 1. Ask the Adaptive Engine what shape the device is (Phone? Tablet? Foldable?)
    val adaptiveState = rememberAdaptiveState()

    // 2. Setup the "Scoreboards" (State). Whenever these values change in the ViewModel,
    // the UI instantly updates to show the new info.
    val stations by viewModel.digitalStations.collectAsState()
    val currentStation by viewModel.currentDigitalStation.collectAsState()
    val isPlaying by viewModel.isDigitalPlaying.collectAsState()
    val isLoading by viewModel.isDigitalLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val categories = viewModel.digitalCategories
    val selectedCategory by viewModel.selectedDigitalCategory.collectAsState()

    // 3. The Filter System (The "Sorting Desk")
    // If the user types "Jazz", this instantly hides all stations that don't match.
    val filteredStations = remember(stations, searchQuery) {
        if (searchQuery.isBlank()) {
            stations // Show everything
        } else {
            stations.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.tags.contains(searchQuery, ignoreCase = true) ||
                        it.country.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 4. Adaptive Layout Adjustments
    // If the user is on a huge tablet, we don't want the search bar and lists stretching
    // 2 feet wide. We restrict them to a clean 600dp width.
    val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
        Modifier.width(600.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    // The outer walls of the screen
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor),
        contentAlignment = Alignment.TopCenter
    ) {
        // The main column holding the search bar, categories, and the list
        Column(modifier = contentWidthModifier.fillMaxHeight()) {

            // --- SEARCH BAR & SCAN BUTTON ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The Text Input Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Search stations, genres, countries...",
                            color = TextSecondary,
                            fontSize = (14 * adaptiveState.textScaleFactor).sp
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary) },
                    trailingIcon = {
                        // Only show the "Clear" button if they actually typed something
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceColor,
                        unfocusedContainerColor = SurfaceColor,
                        focusedBorderColor = PrimaryColor,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    singleLine = true
                )

                Spacer(Modifier.width(8.dp))

                // The "Scan" Button (Refreshes the internet connection to find new stations)
                Surface(
                    shape = CircleShape,
                    color = SurfaceColor,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { viewModel.selectDigitalCategory(selectedCategory) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Scan Stations",
                            tint = PrimaryColor
                        )
                    }
                }
            }

            // --- CATEGORY CHIPS (e.g., "Top 100", "Jazz", "News") ---
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) PrimaryColor else SurfaceColor,
                        modifier = Modifier.clickable { viewModel.selectDigitalCategory(category) }
                    ) {
                        Text(
                            text = category,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = (13 * adaptiveState.textScaleFactor).sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // --- THE MAIN CONTENT AREA ---
            if (isLoading) {
                // Scenario A: The operator is currently downloading the list from the internet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PrimaryColor, strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Scanning live web stations...",
                            color = TextSecondary,
                            fontSize = (14 * adaptiveState.textScaleFactor).sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (filteredStations.isEmpty()) {
                // Scenario B: The user searched for "Alien Music" and we found nothing
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Radio,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No stations found",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = (16 * adaptiveState.textScaleFactor).sp
                        )
                        Text(
                            text = "Try switching category or searching another keyword",
                            color = TextSecondary,
                            fontSize = (13 * adaptiveState.textScaleFactor).sp
                        )
                    }
                }
            } else {
                // Scenario C: We have stations! Draw the list (The "Conveyor Belt")
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filteredStations, key = { it.id }) { station ->
                        val isThisPlaying = currentStation?.id == station.id
                        DigitalStationItem(
                            station = station,
                            isPlaying = isThisPlaying && isPlaying,
                            adaptiveState = adaptiveState,
                            onPlayClick = { viewModel.playDigitalStation(station) },
                            onFavoriteClick = { viewModel.toggleDigitalFavorite(station.id) }
                        )
                    }
                }
            }
        }

        // --- FLOATING "NOW PLAYING" BAR ---
        // Just like Spotify, if a station is playing, it floats at the bottom of the screen.
        AnimatedVisibility(
            visible = currentStation != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            currentStation?.let { station ->
                NowPlayingBar(
                    station = station,
                    isPlaying = isPlaying,
                    adaptiveState = adaptiveState,
                    modifier = contentWidthModifier, // Keep it aligned with the list width!
                    onPlayPauseClick = viewModel::toggleDigitalPlayPause
                )
            }
        }
    }
}

// =========================================================================================
// --- STATION ROW CARD ---
// Analogy: This is the individual physical button for a specific radio station.
// It shows the station logo, name, and location.
// =========================================================================================
@Composable
private fun DigitalStationItem(
    station: DigitalStation,
    isPlaying: Boolean,
    adaptiveState: AdaptiveState,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPlayClick),
        color = SurfaceColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station Logo / Stream Art
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgColor),
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
                    // Fallback icon if the station didn't provide a logo
                    Icon(Icons.Rounded.CellTower, contentDescription = null, tint = PrimaryColor)
                }

                // Show a dark overlay with a volume icon if THIS station is the active one
                if (isPlaying) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Playing", tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // Station Text Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    color = if (isPlaying) PrimaryColor else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (15 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${station.country} • ${station.tags}",
                    color = TextSecondary,
                    fontSize = (12 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // The Heart Icon (Favorite Button)
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (station.isFavorite) PrimaryColor else TextSecondary
                )
            }
        }
    }
}

// =========================================================================================
// --- NOW PLAYING BAR (The Floating Bottom Player) ---
// Analogy: This is the dashboard of the car radio. It sits persistently at the bottom
// so you can always hit Pause or Play without scrolling back up the list.
// =========================================================================================
@Composable
private fun NowPlayingBar(
    station: DigitalStation,
    isPlaying: Boolean,
    adaptiveState: AdaptiveState,
    modifier: Modifier = Modifier,
    onPlayPauseClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(68.dp),
        color = TextPrimary, // Deep black background for contrast
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tiny album art for the floating bar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.DarkGray),
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
                    Icon(Icons.Rounded.CellTower, contentDescription = null, tint = Color.White)
                }
            }

            Spacer(Modifier.width(12.dp))

            // The Text Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (14 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Live Stream Broadcast",
                    color = Color.LightGray,
                    fontSize = (11 * adaptiveState.textScaleFactor).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // The main Play/Pause button
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onPlayPauseClick)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}