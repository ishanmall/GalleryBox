package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// Bringing in all the tools, UI pieces, and libraries needed to build our search screen.
// =========================================================================================
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- THE PAINT PALETTE ---
// Just like a painter mixes specific colors for their canvas, we define exact shades
// of color here so the screen looks clean and modern.
// =========================================================================================
private val BgColor = Color(0xFFF2F2F7) // A very soft, modern gray background
private val SurfaceColor = Color(0xFFFFFFFF) // Pure white for cards and text boxes
private val PrimaryColor = Color(0xFF007AFF) // The main app "Action" color (blue)
private val TextPrimary = Color(0xFF000000) // Dark black text
private val TextSecondary = Color(0xFF8E8E93) // Faded gray text for subtitles

// The official brand colors for the platforms we are searching on!
private val YoutubeColor = Color(0xFFFF0000)
private val GoogleColor = Color(0xFF4285F4)
private val SpotifyColor = Color(0xFF1DB954)

/**
 * A simple list of the different websites/apps the user can search on.
 */
enum class SearchPlatform { YOUTUBE, GOOGLE, SPOTIFY }

// =========================================================================================
// --- THE MUSIC DETECTIVE (OnlineSongFinderScreen) ---
// Analogy: Think of this screen as a private detective's office. You hand the detective
// a sticky note with some lyrics or an artist name, and they run out the door to check
// YouTube, Google, or Spotify to find exactly what you're looking for.
// =========================================================================================
@Composable
fun OnlineSongFinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // This tool lets us forcefully hide the Android keyboard when the user is done typing.
    val focusManager = LocalFocusManager.current

    // 🧠 THE ADAPTIVE ENGINE
    // We ask the engine: "What shape is this device right now?"
    val adaptiveState = rememberAdaptiveState()

    // 📝 THE STICKY NOTE (State)
    // This remembers what the user has typed into the search box so far.
    var searchQuery by remember { mutableStateOf("") }

    // If the user presses the physical/swipe "Back" button on their phone, we run the onBack logic.
    BackHandler {
        onBack()
    }

    // 🎨 ADAPTIVE LAYOUT: Prevent stretching on massive screens!
    // If the user is on a huge tablet, we don't want the search box stretching 15 inches wide.
    // So, if it's a big screen, we limit the width to 600.dp. Otherwise, we fill the screen.
    val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
        Modifier.width(600.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    // The outer walls of the room
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        // The inner column that holds our text, search bar, and buttons
        Column(
            modifier = contentWidthModifier
                .fillMaxHeight()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // The Title
            Text(
                text = "Find Songs Online",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (28 * adaptiveState.textScaleFactor).sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // --- THE SEARCH BOX ---
            // Where the user types their lyrics or song name.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Enter song name, artist, or lyrics...",
                        color = TextSecondary,
                        fontSize = (14 * adaptiveState.textScaleFactor).sp
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextSecondary) },
                trailingIcon = {
                    // Only show the "X" clear button if they have actually typed something!
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, "Clear", tint = TextSecondary)
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
                singleLine = true,
                // Tells the Android keyboard to show a "Search" magnifying glass instead of an "Enter" key
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // The user hit Search! Hide the keyboard and check YouTube as the default.
                        focusManager.clearFocus()
                        performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
                    }
                )
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Search using",
                color = TextPrimary,
                fontSize = (18 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            // --- THE APP DOORS (Platform Cards) ---
            // These buttons are like doors. Clicking one opens the browser or app for that specific service.
            SearchPlatformCard(
                title = "YouTube",
                subtitle = "Search for music videos and covers",
                iconColor = YoutubeColor,
                icon = Icons.Rounded.PlayArrow,
                adaptiveState = adaptiveState
            ) {
                performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
            }

            SearchPlatformCard(
                title = "Google",
                subtitle = "Search the web for lyrics and audio",
                iconColor = GoogleColor,
                icon = Icons.Rounded.TravelExplore,
                adaptiveState = adaptiveState
            ) {
                performSearch(context, searchQuery, SearchPlatform.GOOGLE)
            }

            SearchPlatformCard(
                title = "Spotify",
                subtitle = "Find official tracks and albums",
                iconColor = SpotifyColor,
                icon = Icons.Rounded.Headset,
                adaptiveState = adaptiveState
            ) {
                performSearch(context, searchQuery, SearchPlatform.SPOTIFY)
            }
        }
    }
}

/**
 * =========================================================================================
 * 🚪 THE DOORWAY BUILDER (SearchPlatformCard)
 * =========================================================================================
 * A reusable function that draws the beautiful white buttons for YouTube, Google, etc.
 * We reuse this code 3 times to save space!
 */
@Composable
private fun SearchPlatformCard(
    title: String,
    subtitle: String,
    iconColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    adaptiveState: AdaptiveState,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick), // Make the whole white box clickable
        color = SurfaceColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The colored circle behind the icon
            Surface(
                shape = CircleShape,
                color = iconColor.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (18 * adaptiveState.textScaleFactor).sp
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = (13 * adaptiveState.textScaleFactor).sp
                )
            }

            // The little arrow icon on the far right indicating "Opens in a new app"
            Icon(Icons.Rounded.OpenInNew, null, tint = TextSecondary.copy(alpha = 0.5f))
        }
    }
}

/**
 * =========================================================================================
 * 🚕 THE TAXI DISPATCHER (performSearch)
 * =========================================================================================
 * Analogy: When the user hits search, we can't search Google from INSIDE our app.
 * We have to flag down an Android Taxi (an "Intent"), tell the taxi driver the exact web
 * address we want, and throw the user into the taxi. Android takes care of opening Chrome
 * or the YouTube app automatically!
 */
private fun performSearch(context: Context, query: String, platform: SearchPlatform) {
    // If they clicked search without typing anything, warn them and stop.
    if (query.trim().isEmpty()) {
        Toast.makeText(context, "Please enter a song name first", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        // "URLEncoder" takes normal words with spaces (like "Hello World")
        // and turns them into web-safe text (like "Hello+World") so the browser doesn't break.
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // Build the specific web address based on which door they clicked.
        val url = when (platform) {
            SearchPlatform.YOUTUBE -> "https://www.youtube.com/results?search_query=$encodedQuery"
            SearchPlatform.GOOGLE -> "https://www.google.com/search?q=$encodedQuery+song"
            SearchPlatform.SPOTIFY -> "https://open.spotify.com/search/$encodedQuery"
        }

        // Hail the Android Taxi (Intent) to view a specific web address
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Send the user away!
        context.startActivity(intent)
    } catch (e: Exception) {
        // If the user's phone has absolutely no web browser installed (very rare), catch the crash.
        Toast.makeText(context, "Unable to open search. No browser found.", Toast.LENGTH_SHORT).show()
    }
}