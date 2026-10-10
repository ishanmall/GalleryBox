package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// =========================================================================================
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

// =========================================================================================
// --- THE PAINT PALETTE ---
// We now rely on MaterialTheme for most colors to support future dark mode easily,
// but keep specific brand colors here.
// =========================================================================================
private val YoutubeColor = Color(0xFFE52D27)
private val GoogleColor = Color(0xFF4285F4)
private val SpotifyColor = Color(0xFF1DB954)

enum class SearchPlatform { YOUTUBE, GOOGLE, SPOTIFY }

/**
 * =========================================================================================
 * 🕵️ THE MUSIC DETECTIVE (OnlineSongFinderScreen)
 * =========================================================================================
 * Analogy: Think of this screen as a private detective's office. You hand the detective
 * a sticky note with some lyrics or an artist name, and they run out the door to check
 * YouTube, Google, or Spotify to find exactly what you're looking for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSongFinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val adaptiveState = rememberAdaptiveState()

    var searchQuery by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    // Intercept physical Back button
    BackHandler {
        onBack()
    }

    // A top-level container mapping to standard Material 3 scaffold
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Songs Online", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->

        // Center content on massive tablets
        val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(600.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = contentWidthModifier
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(16.dp))

                // Subtitle
                Text(
                    text = "Discover music across your favorite platforms",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // --- THE SEARCH BOX ---
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (showError && it.isNotBlank()) showError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Song, artist or lyrics...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = ""; showError = false }) {
                                Icon(Icons.Rounded.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        errorBorderColor = MaterialTheme.colorScheme.error
                    ),
                    isError = showError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (searchQuery.isBlank()) {
                                showError = true
                            } else {
                                focusManager.clearFocus()
                                performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
                            }
                        }
                    )
                )

                // Inline Error Message
                if (showError) {
                    Text(
                        text = "Please enter a search term first",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp)
                    )
                }

                Spacer(Modifier.height(32.dp))

                Text(
                    text = "Search on",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                // --- THE APP DOORS (Platform Cards) ---
                SearchPlatformCard(
                    title = "YouTube",
                    subtitle = "Music videos, covers and live performances",
                    iconColor = YoutubeColor,
                    icon = Icons.Rounded.PlayArrow
                ) {
                    if (searchQuery.isBlank()) showError = true else performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
                }

                Spacer(Modifier.height(12.dp))

                SearchPlatformCard(
                    title = "Google",
                    subtitle = "Find songs, lyrics and music information",
                    iconColor = GoogleColor,
                    icon = Icons.Rounded.TravelExplore
                ) {
                    if (searchQuery.isBlank()) showError = true else performSearch(context, searchQuery, SearchPlatform.GOOGLE)
                }

                Spacer(Modifier.height(12.dp))

                SearchPlatformCard(
                    title = "Spotify",
                    subtitle = "Explore tracks, albums and artists",
                    iconColor = SpotifyColor,
                    icon = Icons.Rounded.Headset
                ) {
                    if (searchQuery.isBlank()) showError = true else performSearch(context, searchQuery, SearchPlatform.SPOTIFY)
                }

                Spacer(Modifier.height(40.dp)) // Bottom padding for scroll clearance
            }
        }
    }
}

/**
 * =========================================================================================
 * 🚪 THE DOORWAY BUILDER (SearchPlatformCard)
 * =========================================================================================
 * A reusable blueprint function that draws the beautiful, bouncy buttons for YouTube, Google, etc.
 */
@Composable
private fun SearchPlatformCard(
    title: String,
    subtitle: String,
    iconColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Scale animation mimicking a physical button press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "ButtonScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Disable default ripple to let the scale animation shine
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp, // Subtle elevation replaces the plain gray border
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The colored circle behind the icon
            Surface(
                shape = CircleShape,
                color = iconColor.copy(alpha = 0.12f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.width(16.dp))

            // The Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The little arrow icon
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * =========================================================================================
 * 🚕 THE TAXI DISPATCHER (performSearch)
 * =========================================================================================
 * Throws the user into the Android intent system to safely open the web browser.
 */
private fun performSearch(context: Context, query: String, platform: SearchPlatform) {
    if (query.trim().isEmpty()) {
        return
    }

    try {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = when (platform) {
            SearchPlatform.YOUTUBE -> "https://www.youtube.com/results?search_query=$encodedQuery"
            SearchPlatform.GOOGLE -> "https://www.google.com/search?q=$encodedQuery+song"
            SearchPlatform.SPOTIFY -> "https://open.spotify.com/search/$encodedQuery"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to open search. No browser found.", Toast.LENGTH_SHORT).show()
    }
}