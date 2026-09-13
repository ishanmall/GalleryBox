package com.gallerybox.ui.screens.file

// =========================================================================================
// --- IMPORTS ---
// Bringing in all the necessary tools and blueprints to build our screen.
// =========================================================================================
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Importing our custom data models and managers
import com.gallerybox.data.Album
import com.gallerybox.viewmodel.GalleryViewModel

// Importing the Adaptive Engine (This tells us if the device is a phone or tablet!)
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- THE HIDE ALBUMS SCREEN ---
// Analogy: Think of this screen as the "Backroom Control Panel" for a retail store.
// It shows every single box of inventory (Albums), and lets the user flip a switch
// to decide if that box should be placed on the main store floor, or hidden in the back.
// =========================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideScreen(
    // The "Store Manager" who knows everything about our photos and albums
    viewModel: GalleryViewModel = hiltViewModel(),
    // A simple instruction on what to do when the user presses the "Back" arrow
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // 🧠 1. THE ADAPTIVE ENGINE
    // We ask the engine: "What shape is this device right now?"
    val adaptiveState = rememberAdaptiveState()

    // 📝 2. THE STICKY NOTE (SharedPreferences)
    // We use a small, fast memory file to quickly jot down which albums the user wants hidden.
    // This way, the app remembers their choice even if they close and reopen it tomorrow.
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    // 📊 3. THE LIVE SCOREBOARDS (State)
    // "collectAsState" means we are constantly watching these lists. If a new photo is taken,
    // or an album is hidden, this "scoreboard" updates instantly and redraws the screen automatically.
    val rawMedia by viewModel.rawMedia.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

    // 📦 4. INVENTORY SORTING
    // We take the giant, messy pile of thousands of photos (rawMedia) and group them
    // together by their folder ID. We then build a neat list of "Albums" out of them.
    val allPossibleAlbums = remember(rawMedia) {
        rawMedia.groupBy { it.bucketId }.mapNotNull { (id, items) ->
            // Skip "virtual" albums (like the "Favorites" tab or "Recent" tab) because
            // those aren't real physical folders on the phone.
            if (id.startsWith("virtual_")) return@mapNotNull null

            val first = items.firstOrNull() ?: return@mapNotNull null

            Album(
                id = id,
                name = first.bucketName.ifBlank { "Unknown" },
                coverUri = first.uri, // Use the very first photo as the cover image
                mediaCount = items.size,
                sizeBytes = items.sumOf { it.size },
                isPinned = false
            )
        }.sortedBy { it.name.lowercase() } // Sort them alphabetically A-Z
    }

    // 🏗️ 5. THE ROOM / CANVAS (Scaffold)
    // Scaffold provides the basic structure: a Top Bar for the title, and a body for the content.
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Hide Albums",
                        fontWeight = FontWeight.Bold,
                        // 🎨 ADAPTIVE: Make the text slightly larger on big monitors
                        fontSize = (20 * adaptiveState.textScaleFactor).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->

        // 🎨 ADAPTIVE LAYOUT: Prevent stretching on massive screens!
        // If the user is on a huge tablet, we don't want the list items stretching 15 inches wide.
        // That looks terrible. So, if it's a big screen, we limit the width to 600.dp and center it.
        val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(600.dp)
        } else {
            Modifier.fillMaxWidth() // On phones, it fills the whole screen edge-to-edge
        }

        // This outer box fills the whole screen, but centers our inner content list
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {

            // Scenario A: The user has literally zero albums on their phone.
            if (allPossibleAlbums.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No Albums Found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // Scenario B: We have albums! Let's show them.
                // 🔄 THE SMART CONVEYOR BELT (LazyColumn)
                // A "Lazy" column doesn't build all 500 rows at once (which would freeze the phone).
                // Instead, like a conveyor belt, it only builds the 8 rows you can currently see.
                LazyColumn(
                    modifier = contentWidthModifier,
                    contentPadding = PaddingValues(
                        start = adaptiveState.recommendedPadding,
                        end = adaptiveState.recommendedPadding,
                        top = 16.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Loop through our organized list of albums...
                    items(allPossibleAlbums, key = { it.id }) { album ->

                        // Check our Live Scoreboard: Is this specific album currently hidden?
                        val isHidden = hiddenAlbums.contains(album.id)

                        // 🕹️ THE LIGHT SWITCH LOGIC
                        // This tiny function runs whenever the user taps a row or the toggle switch.
                        val toggleVisibility = {
                            // Tell the Store Manager to update the live app memory
                            viewModel.toggleHiddenAlbum(album.id)

                            // Write the new status down on our "Sticky Note" (SharedPreferences)
                            // so it survives when the app closes.
                            val newHidden = if (isHidden) {
                                hiddenAlbums - album.id // Unhide it
                            } else {
                                hiddenAlbums + album.id // Hide it
                            }
                            enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                        }

                        // 💳 THE PHYSICAL ROW CARD
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { toggleVisibility() }, // Tapping anywhere on the row flips the switch
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The tiny square preview photo for the album
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(album.coverUri)
                                        .size(200)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )

                                Spacer(Modifier.width(16.dp))

                                // The Album Name and Item Count
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = (16 * adaptiveState.textScaleFactor).sp
                                    )
                                    Text(
                                        text = "${album.mediaCount} items",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = (12 * adaptiveState.textScaleFactor).sp
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                // The visual On/Off Switch
                                Switch(
                                    checked = isHidden,
                                    onCheckedChange = { toggleVisibility() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}