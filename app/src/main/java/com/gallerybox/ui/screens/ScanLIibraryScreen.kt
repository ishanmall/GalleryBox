// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("unused")

package com.gallerybox.ui.screens

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for drawing buttons, formatting text, showing loading spinners, and connecting to our ViewModels.
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * --- THE DIGITAL DASHBOARD (ScanLibraryScreen) ---
 * This is the entire screen that shows the user exactly how many Photos, Videos, and Songs
 * they have, and how much space it takes up.
 *
 * `@Composable` means this is a UI (User Interface) drawing function. It describes *what* the screen should look like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLibraryScreen(
    onBack: () -> Unit, // The command to run when the user hits the "Back" arrow
    onLockApp: () -> Unit = {}, // The command to run if they click the padlock
    // We bring in our "Managers" (ViewModels) who have all the actual data
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel()
) {
    val context = LocalContext.current // Gets the environment info (needed to format file sizes)

    // --- LOOKING AT THE SCOREBOARDS ---
    // `collectAsState()` tells the screen to stare at the ViewModel's scoreboards.
    // If the ViewModel finds 10 new photos, the screen instantly redraws itself with the new numbers.
    val mediaList by galleryViewModel.media.collectAsState() // The giant list of all photos and videos
    val allSongs by musicViewModel.allAudioTracks.collectAsState() // The list of all MP3s
    val isScanning by galleryViewModel.isBusy.collectAsState() // True if the app is currently searching the phone for new files

    // --- LOCAL SCOREBOARDS (State Variables) ---
    // We create a few temporary scoreboards just for this screen to hold our math results.
    // `remember` tells Android not to accidentally erase these numbers if the screen has to redraw itself.
    var photoCount by remember { mutableIntStateOf(0) }
    var videoCount by remember { mutableIntStateOf(0) }
    var formattedTotalSize by remember { mutableStateOf("Calculating...") }

    /**
     * THE CENSUS TAKER (LaunchedEffect)
     * If the user has 50,000 photos, counting them and calculating their file size takes a lot of math.
     * We don't want to freeze the screen while we do that math, so we send the Census Taker to the
     * background warehouse (Dispatchers.Default) to do the counting.
     *
     * `LaunchedEffect(mediaList)` means: "Every time the giant list of media changes, recount everything!"
     */
    LaunchedEffect(mediaList) {
        withContext(Dispatchers.Default) { // "Go to the background warehouse..."
            var pCount = 0
            var vCount = 0
            var totalBytes = 0L // "L" means Long, a number box big enough to hold billions of bytes

            // Go through every single file in the list
            for (item in mediaList) {
                if (item.isVideo) {
                    vCount++
                } else {
                    pCount++
                }
                totalBytes += item.size // Add up the file size
            }

            // Update our local scoreboards with the final totals
            photoCount = pCount
            videoCount = vCount
            // Android has a built in tool that converts "1073741824 bytes" into a human readable "1.0 GB"
            formattedTotalSize = Formatter.formatFileSize(context, totalBytes)
        }
    }

    // Songs are easy, we just ask the music manager for the total size of its list.
    val songCount = allSongs.size

    // --- DRAWING THE SCREEN ---
    // A Scaffold is like a blank canvas with pre-marked zones for a Top Bar, Bottom Bar, and the main Content.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Library Scanner",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack, // Standard Android back arrow
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // A padlock button in the top right corner
                    IconButton(onClick = onLockApp) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Lock"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // The main content area
        Column( // A Column stacks things vertically on top of each other
            modifier = Modifier
                .padding(padding) // Don't draw over the TopAppBar
                .fillMaxSize() // Take up all available screen space
                .padding(horizontal = 24.dp), // Add a little breathing room on the left and right edges
            horizontalAlignment = Alignment.CenterHorizontally // Center everything left-to-right
        ) {
            Spacer(modifier = Modifier.height(32.dp)) // Empty vertical space to push things down

            // Draw the big circle icon at the top of the screen
            ScannerVisual(isScanning = isScanning)

            Spacer(modifier = Modifier.height(24.dp))

            // Draw the text that says "Library Up to Date" or "Scanning..."
            ScannerStatusText(
                isScanning = isScanning,
                totalSize = formattedTotalSize
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Draw the grid of cards showing the numbers (Photos: 500, Videos: 10, etc.)
            LibraryStatsGrid(
                photos = photoCount,
                videos = videoCount,
                audio = songCount
            )

            // A 'weight' Spacer acts like a spring. It pushes everything above it UP, and everything below it DOWN.
            // This forces our "Refresh" button to stick to the very bottom of the screen.
            Spacer(modifier = Modifier.weight(1f))

            // The big button at the bottom
            RefreshButton(
                isScanning = isScanning,
                onClick = {
                    // Tell both Managers to go check the phone for any brand new files
                    galleryViewModel.refreshData()
                    musicViewModel.loadAllAudioTracks()
                }
            )
        }
    }
}

/**
 * --- THE BIG CIRCLE ICON ---
 * Draws a colored circle with an icon inside it.
 * If the app is currently scanning, it shows a "Refresh" arrow. If not, it shows a "Database" icon.
 */
@Composable
fun ScannerVisual(isScanning: Boolean) {
    Box( // A Box stacks things perfectly on top of each other (like a bullseye)
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape) // Cut the square box into a perfect circle
                .background(MaterialTheme.colorScheme.surfaceVariant), // Give it a subtle background color
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isScanning) Icons.Default.Refresh else Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, // Color the icon with the app's main theme color
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

/**
 * --- THE STATUS TEXT ---
 * Shows what the app is doing right now.
 */
@Composable
fun ScannerStatusText(isScanning: Boolean, totalSize: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isScanning) {
            Text(
                text = "Scanning Photos, Videos and Audio...",
                style = MaterialTheme.typography.headlineSmall, // Use Android's standard big headline font
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Searching for new files.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, // Use a faded gray color
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = "Library Up to Date",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All files are indexed.\nTotal Vault Size: $totalSize", // '\n' means "Hit Enter to start a new line"
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * --- THE SCOREBOARD CARDS ---
 * Organizes the little boxes showing the total counts into a neat 2x2 grid.
 */
@Composable
fun LibraryStatsGrid(photos: Int, videos: Int, audio: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { // Space out the rows vertically

        // ROW 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp) // Space out the cards horizontally
        ) {
            // Photos Card
            StatCard(
                modifier = Modifier.weight(1f), // "weight 1" means "Take up exactly 50% of the row width"
                count = photos.toString(),
                label = "Photos",
                icon = Icons.Rounded.Photo
            )
            // Videos Card
            StatCard(
                modifier = Modifier.weight(1f),
                count = videos.toString(),
                label = "Videos",
                icon = Icons.Rounded.Videocam
            )
        }

        // ROW 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Card
            StatCard(
                modifier = Modifier.weight(1f),
                count = audio.toString(),
                label = "Audio",
                icon = Icons.Rounded.MusicNote
            )
            // An invisible empty spacer card that takes up the other 50% of the row so the Audio card doesn't stretch weirdly
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * --- THE BOTTOM BUTTON ---
 * The big button at the bottom of the screen.
 */
@Composable
fun RefreshButton(isScanning: Boolean, onClick: () -> Unit) {
    Button(
        onClick = {
            // Double check that we aren't already scanning before we allow the click
            if (!isScanning) {
                onClick()
            }
        },
        enabled = !isScanning, // Grey out the button if we are currently scanning
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(bottom = 24.dp),
        shape = RoundedCornerShape(16.dp) // Slightly round the corners
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isScanning) {
                // Show a spinning circle
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Scanning...",
                    fontWeight = FontWeight.Bold
                )
            } else {
                // Show a standard refresh icon
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Refresh Library",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * --- INDIVIDUAL CARD BUILDER ---
 * This is a reusable blueprint. We use it to draw the "Photos" box, the "Videos" box, and the "Audio" box.
 */
@Composable
fun StatCard(modifier: Modifier = Modifier, count: String, label: String, icon: ImageVector) {
    // Convert "Photos" to "PHOTOS" so it looks cleaner
    val upperLabel = remember(label) {
        label.uppercase()
    }

    Surface(
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp), // Extremely rounded corners
        color = MaterialTheme.colorScheme.surfaceVariant, // The background color of the box
        tonalElevation = 0.dp // Makes the box completely flat (no shadow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp), // Padding inside the box so text doesn't touch the edges
            verticalArrangement = Arrangement.SpaceBetween // Pushes the Icon to the top, and the Label to the bottom
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, // Pushes the Icon left and the Number right
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, // Color it with the app theme
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = count, // The number
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = upperLabel, // "PHOTOS"
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp // Add a tiny bit of extra space between each letter to make it look professional
            )
        }
    }
}