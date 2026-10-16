package com.gallerybox.ui.screens.music

// =========================================================================================
// --- IMPORTS ---
// =========================================================================================
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
// We now rely on MaterialTheme for most colors to support future dark mode easily,
// but keep specific brand colors here.
// =========================================================================================
private val YoutubeColor = Color(0xFFFF3D3D)
private val GoogleColor = Color(0xFF4285F4)
private val SpotifyColor = Color(0xFF1ED760)

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
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    val adaptiveState = rememberAdaptiveState()

    var searchQuery by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val recentSearches = remember { mutableStateListOf<String>() }

    val searchModifiers = remember {
        listOf("Lyrics", "Official Audio", "Live Acoustic", "Instrumental", "Remix", "Bass Boosted")
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun recordAndPerformSearch(platform: SearchPlatform) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showError = true
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            focusManager.clearFocus()
            if (!recentSearches.contains(trimmed)) {
                recentSearches.add(0, trimmed)
                if (recentSearches.size > 6) recentSearches.removeLast()
            }
            performSearch(context, trimmed, platform)
        }
    }

    // Intercept physical Back button
    BackHandler {
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->

        // Center content on massive tablets
        val contentWidthModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
            Modifier.width(660.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        val isQueryReady = searchQuery.isNotBlank()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            // Top Ambient Multi-Tone Studio Spotlight Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (isQueryReady) SpotifyColor.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                                Color.Transparent
                            ),
                            radius = 950f
                        )
                    )
            )

            Column(
                modifier = contentWidthModifier
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(12.dp))

                // --- ACOUSTIC RADAR STATUS PILL ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "RadarAlpha"
                    )

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            if (isQueryReady) SpotifyColor.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )
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
                                        if (isQueryReady) SpotifyColor.copy(alpha = pulseAlpha)
                                        else MaterialTheme.colorScheme.primary
                                    )
                            )
                            Text(
                                text = if (isQueryReady) "READY" else "STANDBY",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isQueryReady) SpotifyColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Subtitle
                Text(
                    text = "Enter a lyric fragment, artist, or track title to launch a deep search across streaming networks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 18.dp)
                )

                // --- THE SEARCH CONSOLE CARD ---
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.dp,
                        color = when {
                            showError -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            isQueryReady -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        }
                    ),
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // --- THE SEARCH BOX ---
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                if (showError && it.isNotBlank()) showError = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "Song title, artist, or remembered lyrics...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (searchQuery.isEmpty()) {
                                        // 1-Tap Clipboard Paste Button
                                        IconButton(
                                            onClick = {
                                                clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }?.let { pasted ->
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                    searchQuery = pasted.trim()
                                                    showError = false
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Rounded.ContentPaste,
                                                contentDescription = "Paste from clipboard",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                searchQuery = ""
                                                showError = false
                                            }
                                        ) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = "Clear",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                                errorBorderColor = MaterialTheme.colorScheme.error
                            ),
                            isError = showError,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    recordAndPerformSearch(SearchPlatform.YOUTUBE)
                                }
                            )
                        )

                        // Inline Error Message
                        AnimatedVisibility(
                            visible = showError,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Please enter a song title, artist, or lyric snippet first",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // --- QUICK SEARCH MODIFIER TAGS ---
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Quick Filters:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            searchModifiers.forEach { modifierTag ->
                                val isTagActive = searchQuery.contains(modifierTag, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        showError = false
                                        searchQuery = if (isTagActive) {
                                            searchQuery.replace(Regex("(?i)\\b$modifierTag\\b"), "").trim().replace(Regex("\\s+"), " ")
                                        } else {
                                            "${searchQuery.trim()} $modifierTag".trim()
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isTagActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isTagActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Text(
                                        text = if (isTagActive) "✓ $modifierTag" else "+ $modifierTag",
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isTagActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (isTagActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // --- RECENT QUERIES ROW (Shown once the user searches at least once) ---
                AnimatedVisibility(
                    visible = recentSearches.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Recent Queries",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        recentSearches.clear()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            recentSearches.forEach { item ->
                                Surface(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        searchQuery = item
                                        showError = false
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Restore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = item,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))

                // --- SECTION HEADER FOR PLATFORMS ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Launch Search On",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "EXTERNAL PORTALS",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(Modifier.height(14.dp))

                // --- THE APP DOORS (Platform Cards) ---
                SearchPlatformCard(
                    title = "YouTube",
                    subtitle = "Music videos, live sessions, covers & remixes",
                    badgeText = "VIDEO & AUDIO",
                    activeQuery = searchQuery.trim(),
                    iconColor = YoutubeColor,
                    icon = Icons.Rounded.PlayArrow
                ) {
                    recordAndPerformSearch(SearchPlatform.YOUTUBE)
                }

                Spacer(Modifier.height(14.dp))

                SearchPlatformCard(
                    title = "Google Search",
                    subtitle = "Full song lyrics, release history & artist trivia",
                    badgeText = "LYRICS & WEB",
                    activeQuery = searchQuery.trim(),
                    iconColor = GoogleColor,
                    icon = Icons.Rounded.TravelExplore
                ) {
                    recordAndPerformSearch(SearchPlatform.GOOGLE)
                }

                Spacer(Modifier.height(14.dp))

                SearchPlatformCard(
                    title = "Spotify",
                    subtitle = "High-res studio tracks, albums & curated playlists",
                    badgeText = "STREAMING",
                    activeQuery = searchQuery.trim(),
                    iconColor = SpotifyColor,
                    icon = Icons.Rounded.Headset
                ) {
                    recordAndPerformSearch(SearchPlatform.SPOTIFY)
                }

                Spacer(Modifier.height(48.dp)) // Bottom padding for scroll clearance
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
    badgeText: String = "",
    activeQuery: String = "",
    iconColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val hasQuery = activeQuery.isNotEmpty()

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
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Disable default ripple to let the scale animation shine
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (hasQuery) 1.5.dp else 1.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    iconColor.copy(alpha = if (hasQuery) 0.65f else 0.28f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
                )
            )
        ),
        shadowElevation = if (hasQuery) 6.dp else 2.dp,
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            iconColor.copy(alpha = if (hasQuery) 0.14f else 0.06f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The glowing dual-ring orb behind the platform icon
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(iconColor.copy(alpha = 0.15f))
                        .border(1.dp, iconColor.copy(alpha = 0.38f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                // The Text & Live Query Telemetry
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.ExtraBold
                        )

                        if (badgeText.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = iconColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = badgeText,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = iconColor,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (hasQuery) "Search \"$activeQuery\" on $title" else subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasQuery) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (hasQuery) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(10.dp))

                // The launch portal action button
                Surface(
                    shape = CircleShape,
                    color = if (hasQuery) iconColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = if (hasQuery) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
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