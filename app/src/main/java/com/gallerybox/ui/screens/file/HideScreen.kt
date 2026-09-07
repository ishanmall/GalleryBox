package com.gallerybox.ui.screens.file

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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gallerybox.data.Album
import com.gallerybox.viewmodel.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideScreen(
    viewModel: GalleryViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val enginePrefs = remember { context.getSharedPreferences("gallery_engine_prefs", Context.MODE_PRIVATE) }

    val rawMedia by viewModel.rawMedia.collectAsState()
    val hiddenAlbums by viewModel.hiddenAlbums.collectAsState()

    val allPossibleAlbums = remember(rawMedia) {
        rawMedia.groupBy { it.bucketId }.mapNotNull { (id, items) ->
            if (id.startsWith("virtual_")) return@mapNotNull null // Skip virtual albums
            val first = items.firstOrNull() ?: return@mapNotNull null
            Album(
                id = id,
                name = first.bucketName.ifBlank { "Unknown" },
                coverUri = first.uri,
                mediaCount = items.size,
                sizeBytes = items.sumOf { it.size },
                isPinned = false
            )
        }.sortedBy { it.name.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hide Albums", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
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
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allPossibleAlbums, key = { it.id }) { album ->
                        val isHidden = hiddenAlbums.contains(album.id)

                        val toggleVisibility = {
                            // Replicate the logic from AlbumScreen
                            viewModel.toggleHiddenAlbum(album.id)
                            val newHidden = if (isHidden) {
                                hiddenAlbums - album.id
                            } else {
                                hiddenAlbums + album.id
                            }
                            enginePrefs.edit().putStringSet("hidden_albums", newHidden).apply()
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { toggleVisibility() },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${album.mediaCount} items",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

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