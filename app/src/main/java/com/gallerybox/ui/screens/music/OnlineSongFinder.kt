package com.gallerybox.ui.screens.music

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

// Unified light theme palette
private val BgColor = Color(0xFFF2F2F7)
private val SurfaceColor = Color(0xFFFFFFFF)
private val PrimaryColor = Color(0xFF007AFF)
private val TextPrimary = Color(0xFF000000)
private val TextSecondary = Color(0xFF8E8E93)

// Platform Colors
private val YoutubeColor = Color(0xFFFF0000)
private val GoogleColor = Color(0xFF4285F4)
private val SpotifyColor = Color(0xFF1DB954)

enum class SearchPlatform { YOUTUBE, GOOGLE, SPOTIFY }

@Composable
fun OnlineSongFinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var searchQuery by remember { mutableStateOf("") }

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Find Songs Online",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter song name, artist, or lyrics...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextSecondary) },
                trailingIcon = {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
                    }
                )
            )

            Spacer(Modifier.height(32.dp))
            Text("Search using", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // Search Platform Cards
            SearchPlatformCard(
                title = "YouTube",
                subtitle = "Search for music videos and covers",
                iconColor = YoutubeColor,
                icon = Icons.Rounded.PlayArrow
            ) {
                performSearch(context, searchQuery, SearchPlatform.YOUTUBE)
            }

            SearchPlatformCard(
                title = "Google",
                subtitle = "Search the web for lyrics and audio",
                iconColor = GoogleColor,
                icon = Icons.Rounded.TravelExplore
            ) {
                performSearch(context, searchQuery, SearchPlatform.GOOGLE)
            }

            SearchPlatformCard(
                title = "Spotify",
                subtitle = "Find official tracks and albums",
                iconColor = SpotifyColor,
                icon = Icons.Rounded.Headset
            ) {
                performSearch(context, searchQuery, SearchPlatform.SPOTIFY)
            }
        }
    }
}

@Composable
private fun SearchPlatformCard(
    title: String,
    subtitle: String,
    iconColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = SurfaceColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = TextSecondary, fontSize = 13.sp)
            }

            Icon(Icons.Rounded.OpenInNew, null, tint = TextSecondary.copy(alpha = 0.5f))
        }
    }
}

private fun performSearch(context: Context, query: String, platform: SearchPlatform) {
    if (query.trim().isEmpty()) {
        Toast.makeText(context, "Please enter a song name first", Toast.LENGTH_SHORT).show()
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