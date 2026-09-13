// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict grammar checker to ignore specific words because we know what we are doing.
@file:Suppress("unused")

package com.gallerybox.ui.util

// --- IMPORTS ---
// This is the "toolbox" area. We are bringing in tools for drawing buttons, checking the phone's hard drive space,
// showing popups (Toast), and running background tasks (Coroutines).
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.gallerybox.viewmodel.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * --- THE MAIN MENU (UtilitiesScreen) ---
 * This screen acts like a dashboard or control panel. It shows how much hard drive space is left,
 * and provides buttons to go to the Trash Bin, the Secure Vault, or the Duplicate Finder.
 *
 * `@Composable` means this is a UI drawing function. It describes *what* the screen should look like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilitiesScreen(
    // We bring in our "Manager" (ViewModel) and a list of "Commands" (like onNavigateToTrash)
    // that tell the app what to do when the user clicks a specific button.
    viewModel: GalleryViewModel = hiltViewModel(),
    onNavigateToTrash: () -> Unit,
    onNavigateToHidden: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current // Gets the environment info (needed to format file sizes and show popups)
    val scope = rememberCoroutineScope() // A tool that lets us launch background workers when a button is clicked
    val colors = MaterialTheme.colorScheme // The current color palette (Light Mode or Dark Mode)

    // A scoreboard watching the main gallery manager
    val allMedia by viewModel.media.collectAsState()

    // A local scoreboard just for this screen to hold the hard drive space numbers
    var storageInfo by remember { mutableStateOf(Pair(0L, 0L)) }

    // THE STORAGE INSPECTOR.
    // The moment this screen opens, we send a worker to the background to ask Android how much space is left on the phone.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { // Go to the background warehouse (IO)
            val path = Environment.getExternalStorageDirectory() // Find the main hard drive
            val total = path.totalSpace // Total size (e.g., 64 GB)
            val free = path.freeSpace // How much is empty (e.g., 10 GB)
            storageInfo = Pair(total, free) // Update the scoreboard
        }
    }

    // Do a little bit of math to figure out the percentages for the progress bar
    val totalStorage = storageInfo.first
    val freeStorage = storageInfo.second
    val usedStorage = totalStorage - freeStorage
    val usedPercent = if (totalStorage > 0) (usedStorage.toFloat() / totalStorage.toFloat()) else 0f

    // A Scaffold is like a blank canvas with pre-marked zones for a Top Bar, Bottom Bar, and the main Content.
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Utilities", fontWeight = FontWeight.Bold, color = colors.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) { // The back arrow in the top left corner
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { padding ->
        // LazyColumn is a scrolling list. It only draws the buttons that are currently visible on the screen to save memory.
        LazyColumn(
            modifier = Modifier
                .padding(padding) // Don't draw over the TopAppBar
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp), // Add breathing room around the edges
            verticalArrangement = Arrangement.spacedBy(16.dp) // Put 16 pixels of empty space between every button
        ) {
            // 1. Draw the big Storage Card at the very top
            item {
                StorageCard(
                    usedBytes = usedStorage,
                    totalBytes = totalStorage,
                    percentage = usedPercent
                )
            }

            // 2. Draw a small "ORGANIZE" category title
            item { SectionTitle("Organize") }

            // 3. Draw the Trash Bin button
            item {
                UtilityTile(
                    title = "Trash Bin",
                    subtitle = "Recover or delete files permanently",
                    icon = Icons.Rounded.DeleteSweep,
                    color = colors.error, // Make the trash icon red
                    onClick = onNavigateToTrash
                )
            }

            // 4. Draw the Hidden Cabinet (Vault) button
            item {
                UtilityTile(
                    title = "Hidden Cabinet",
                    subtitle = "Secure photos & videos",
                    icon = Icons.Rounded.Shield,
                    color = colors.primary, // Make the shield icon the app's main theme color
                    onClick = onNavigateToHidden
                )
            }

            // 5. Draw the Favorites button
            item {
                UtilityTile(
                    title = "Favorites",
                    subtitle = "Quick access to loved items",
                    icon = Icons.Rounded.FolderSpecial,
                    color = Color(0xFFFFB300), // Make the star/folder icon golden yellow
                    onClick = onNavigateToFavorites
                )
            }

            // 6. Draw a small "MAINTENANCE" category title
            item { SectionTitle("Maintenance") }

            // 7. Draw the Duplicate Finder button
            item {
                UtilityTile(
                    title = "Scan Duplicates",
                    subtitle = "Find and remove visually identical copies",
                    icon = Icons.Rounded.CleaningServices,
                    color = colors.secondary,
                    onClick = onNavigateToDuplicates
                )
            }

            // 8. Draw the Library Scanner button
            item {
                UtilityTile(
                    title = "Library Scanner",
                    subtitle = "Deep index storage for new files",
                    icon = Icons.Rounded.Search,
                    color = colors.tertiary,
                    onClick = onNavigateToScanner
                )
            }

            // 9. Draw the Clean Cache button
            item {
                UtilityTile(
                    title = "Clean Cache",
                    subtitle = "Clear temporary thumbnails and logs",
                    icon = Icons.Rounded.AutoFixHigh,
                    color = Color(0xFF4CAF50), // Green color
                    onClick = {
                        // When they click this, we send the Janitor to the background to delete junk files.
                        scope.launch {
                            val bytesCleared = withContext(Dispatchers.IO) {
                                clearApplicationCache(context) // Run the janitor function
                            }
                            // Figure out exactly what message to show in the little popup box (Toast) at the bottom of the screen.
                            val detailedMessage = if (bytesCleared > 0) {
                                "Cleared ${Formatter.formatShortFileSize(context, bytesCleared)} of temporary storage"
                            } else {
                                "Cache is already pristine!"
                            }
                            Toast.makeText(context, detailedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

/**
 * --- THE HARD DRIVE GAUGE (StorageCard) ---
 * Draws a nice looking box with a progress bar showing how full the phone is.
 */
@Composable
fun StorageCard(usedBytes: Long, totalBytes: Long, percentage: Float) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(24.dp), // Extremely rounded corners
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) { // Padding inside the box
            // The top row with the icon and title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = colors.primary)
                Spacer(Modifier.width(12.dp))
                Text("Device Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))

            // The actual progress bar line
            LinearProgressIndicator(
                progress = { percentage }, // How far the bar is filled (0.0 to 1.0)
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape), // Round the ends of the bar
                // If the phone is 90% full, turn the bar red to warn the user! Otherwise, use the normal theme color.
                color = if (percentage > 0.9f) colors.error else colors.primary,
                trackColor = colors.onSurfaceVariant.copy(alpha = 0.2f), // The faint background color behind the bar
            )

            Spacer(Modifier.height(14.dp))

            // The bottom row with the exact numbers (e.g. "50% Used" and "32GB / 64GB")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${(percentage * 100).toInt()}% Used",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (percentage > 0.9f) colors.error else colors.primary
                )
                Text(
                    text = "${Formatter.formatShortFileSize(context, usedBytes)} / ${Formatter.formatShortFileSize(context, totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * --- INDIVIDUAL BUTTON BUILDER (UtilityTile) ---
 * This is a reusable blueprint. We use it to draw the Trash button, the Vault button, etc.
 * This saves us from writing the same code 6 times.
 */
@Composable
fun UtilityTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit // What happens when the user taps this specific tile
) {
    val colors = MaterialTheme.colorScheme

    // A surface acts as the clickable background shape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick), // Make the entire rectangle clickable
        color = colors.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The colored circle holding the icon on the left
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape), // Very faint colored background
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            }

            Spacer(Modifier.width(16.dp))

            // The Title and Subtitle text stacked on top of each other
            Column(Modifier.weight(1f)) { // "Weight 1" means take up all the middle space
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            // The tiny arrow icon pointing to the right on the far edge
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = colors.outline.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * --- CATEGORY TITLE BUILDER ---
 * Draws the small ALL CAPS text headers (like "ORGANIZE").
 */
@Composable
fun SectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        letterSpacing = 1.2.sp // Add tiny space between letters to look professional
    )
}

/**
 * THE JANITOR (clearApplicationCache).
 * Our app generates hundreds of tiny "Thumbnail" pictures so the main grid scrolls smoothly.
 * Over time, these can take up hundreds of Megabytes. This function goes into the "Cache" folder and deletes them all.
 */
private fun clearApplicationCache(context: Context): Long {
    var totalDeletedBytesBytes = 0L

    // Get the two folders where Android lets us store temporary junk files.
    val cacheDirs = listOfNotNull(
        context.cacheDir,
        context.externalCacheDir
    )

    // Send the Janitor into both folders
    cacheDirs.forEach { dir ->
        totalDeletedBytesBytes += getFolderSizeAndClean(dir)
    }
    return totalDeletedBytesBytes // Return the exact number of bytes we freed up
}

// A recursive function (a function that calls itself) that digs through folders inside of folders to delete everything.
private fun getFolderSizeAndClean(file: File): Long {
    var size = 0L
    // If it's a folder, open it, and run this exact same function on every item inside it.
    if (file.isDirectory) {
        file.listFiles()?.forEach { child ->
            size += getFolderSizeAndClean(child)
        }
    }
    // If it's a file, get its size, then delete it.
    size += file.length()
    file.delete()
    return size
}

/**
 * --- THE FILE TRANSLATOR (FileOpener) ---
 * When the user taps a Document (like a PDF or a Word file), our Gallery app doesn't know how to read it.
 * So we have to ask the Android operating system to find another app (like Microsoft Word or Adobe) to open it for us.
 */
object FileOpener {
    fun openFile(context: Context, file: File) {

        // Android is very secure. It doesn't let apps share raw file addresses.
        // We have to use a "FileProvider" to generate a special, temporary, safe link (URI) to the file.
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        // Android needs to know exactly what kind of file it is so it knows which app to wake up.
        // We check the end of the file name (the extension) to figure this out.
        val mimeType = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "jpg", "jpeg", "png", "webp" -> "image/*"
            "mp4", "mkv", "webm" -> "video/*"
            "mp3", "wav", "ogg" -> "audio/*"
            else -> "*/*" // We don't know what this is, just ask Android to figure it out.
        }

        // Package up the request and send it to the Android OS.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Give the other app permission to actually read the file
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Open it in a new window
        }

        try {
            // FIRE!
            context.startActivity(intent)
        } catch (e: Exception) {
            // If the user's phone doesn't have an app installed that can read this file (e.g. they don't have Word installed),
            // Android will throw an error. We catch it and show a polite message.
            Toast.makeText(
                context,
                "No app found to open this file",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * A math helper function.
 * If you load a massive 4K movie poster into memory, the app will crash from exhaustion.
 * This function calculates how much we need to "shrink" or "fold" the image so it fits comfortably on the screen.
 * For example, an `inSampleSize` of 2 means the image will be loaded at half its original size.
 */
fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        // Keep doubling the shrink factor until the image fits our target size
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}