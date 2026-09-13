package com.gallerybox.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gallerybox.R

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.ui.screens.adaptive.WindowWidthSize

/**
 * =========================================================================================
 * 📑 THE TABS MENU (The Table of Contents)
 * =========================================================================================
 * This is a simple list of the 4 pages available on this screen.
 * We use an "enum" (a fixed list of options) so the app never accidentally
 * tries to open a page that doesn't exist.
 */
enum class AboutTab(val title: String) {
    ABOUT("About"),
    TERMS("Terms"),
    PRIVACY("Privacy"),
    LICENSE("License")
}

/**
 * =========================================================================================
 * 🖼️ THE MAIN ABOUT SCREEN WRAPPER
 * =========================================================================================
 * This is the outer shell of the screen. It holds the Top Bar (with the back button),
 * the row of tabs, and the empty space where the content of the selected tab will go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateUp: () -> Unit = {},
    modifier: Modifier = Modifier,
    // We bring in our smart AdaptiveState here to know if we are on a phone, tablet, or foldable!
    adaptiveState: AdaptiveState = rememberAdaptiveState()
) {
    // This remembers which tab the user is currently looking at. It starts at 0 ("About").
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = AboutTab.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "About GalleryBox",
                        fontWeight = FontWeight.SemiBold,
                        // Scale the title slightly on huge screens
                        fontSize = (22 * adaptiveState.textScaleFactor).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->

        // This column stacks the Tabs on top and the Content on the bottom.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Draw the row of clickable tabs at the top
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = (14 * adaptiveState.textScaleFactor).sp
                            )
                        }
                    )
                }
            }

            // 2. The Content Area
            // We center the content on huge screens (like desktop monitors) so the text
            // doesn't stretch 20 inches wide, which is very hard for human eyes to read.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                // If the screen is huge, we restrict the max width. Otherwise, fill it.
                val contentModifier = if (adaptiveState.widthSize == WindowWidthSize.EXPANDED) {
                    Modifier.width(800.dp) // Looks like a nice clean document on tablets
                } else {
                    Modifier.fillMaxWidth() // Fills edge-to-edge on normal phones
                }

                // 3. Smooth Animations
                // When the user clicks a new tab, this slowly fades the old text out
                // and fades the new text in.
                AnimatedContent(
                    targetState = tabs[selectedTabIndex],
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "TabContentAnimation",
                    modifier = contentModifier.fillMaxHeight()
                ) { targetTab ->

                    // Look at which tab was clicked, and load the correct screen below.
                    when (targetTab) {
                        AboutTab.ABOUT -> AboutAppContent(adaptiveState)
                        AboutTab.TERMS -> LegalTextContent(getTermsText(), adaptiveState)
                        AboutTab.PRIVACY -> LegalTextContent(getPrivacyText(), adaptiveState)
                        AboutTab.LICENSE -> LegalTextContent(getLicenseText(), adaptiveState)
                    }
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 📱 PAGE 1: THE FEATURES & DEVELOPER INFO
 * =========================================================================================
 * This is the first tab. It scrolls through a list of all the cool things the app can do,
 * and ends with a card showing who built it.
 */
@Composable
private fun AboutAppContent(adaptiveState: AdaptiveState) {
    val features = remember { getFeatureList() }

    // LazyColumn is a smart list. It only draws the items you can currently see on the screen,
    // which saves battery and memory.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // We use adaptive padding so it breathes well on tablets but stays tight on phones.
        contentPadding = PaddingValues(adaptiveState.recommendedPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "📱 GalleryBox — Complete Feature List",
                fontSize = (24 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Loops through our giant list of features and draws a nice rounded card for each one.
        items(features) { feature ->
            FeatureSectionCard(feature, adaptiveState)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "👨‍💻 Developer & Open Source",
                fontSize = (22 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            DeveloperProfileCard(adaptiveState)
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "© 2026 Balanand Mishra. All rights reserved.\nLicensed under Apache 2.0",
                fontSize = (12 * adaptiveState.textScaleFactor).sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * A helper function that draws one specific Feature Card (like "Trash" or "Video Player").
 */
@Composable
private fun FeatureSectionCard(section: FeatureSection, adaptiveState: AdaptiveState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${section.emoji} ${section.title}",
                fontSize = (16 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Draws the bullet points for this specific feature
            section.items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = item,
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = (20 * adaptiveState.textScaleFactor).sp
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🧑‍💻 DEVELOPER PROFILE CARD
 * =========================================================================================
 * Shows the author's picture, name, and clickable links to their Github and Website.
 */
@Composable
private fun DeveloperProfileCard(adaptiveState: AdaptiveState) {
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            // The top row with the circular profile picture and name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.isha_ndisha_1785611777_3954320604862031420_77465641188),
                    contentDescription = "Balanand Mishra",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Balanand Mishra",
                        fontSize = (22 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Android Software Developer",
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // The Open Source Box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Open Source Code",
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The entire source code for this application is freely available. You can use and modify it under the Apache 2.0 License, provided that you give proper credit to the original developer wherever the code is utilized.",
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = (18 * adaptiveState.textScaleFactor).sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SocialLinkItem(
                        icon = Icons.Outlined.Code,
                        platform = "GitHub Repository",
                        handle = "github.com/balanandmishra",
                        adaptiveState = adaptiveState,
                        onClick = {
                            // This opens the web browser on the user's phone!
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/balanandmishra"))
                            context.startActivity(intent)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The Portfolio Box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Developer Portfolio",
                        fontSize = (14 * adaptiveState.textScaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Visit my official website for detailed developer information, professional experience, education, training, certificates, and contact details.",
                        fontSize = (12 * adaptiveState.textScaleFactor).sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        lineHeight = (18 * adaptiveState.textScaleFactor).sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SocialLinkItem(
                        icon = Icons.Outlined.Language,
                        platform = "Official Website",
                        handle = "portfolio-b1973.web.app",
                        adaptiveState = adaptiveState,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://portfolio-b1973.web.app/"))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

/**
 * A tiny reusable button that looks like a row with an Icon, a title, and a subtitle.
 */
@Composable
private fun SocialLinkItem(icon: ImageVector, platform: String, handle: String, adaptiveState: AdaptiveState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = platform,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = platform,
                fontSize = (12 * adaptiveState.textScaleFactor).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = handle,
                fontSize = (14 * adaptiveState.textScaleFactor).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * =========================================================================================
 * 📄 PAGES 2, 3, & 4: THE LEGAL DOCUMENTS
 * =========================================================================================
 * This is a highly optimized text viewer. It allows the user to select/copy text,
 * and formats everything cleanly.
 */
@Composable
private fun LegalTextContent(text: String, adaptiveState: AdaptiveState) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // SelectionContainer allows the user to long-press and copy text
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        horizontal = adaptiveState.recommendedPadding,
                        vertical = adaptiveState.recommendedPadding
                    )
            ) {
                Text(
                    text = text,
                    fontSize = (14 * adaptiveState.textScaleFactor).sp,
                    lineHeight = (24 * adaptiveState.textScaleFactor).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 📦 RAW DATA STORAGE
// ---------------------------------------------------------------------------

data class FeatureSection(val title: String, val emoji: String, val items: List<String>)

private fun getFeatureList(): List<FeatureSection> = listOf(
    FeatureSection("Pictures / Gallery", "🖼️", listOf(
        "All photos and videos in one place", "Samsung Gallery-style interface", "4×4 default grid",
        "Adjustable grid size from 1 to 8 columns", "Reverse grid-size control", "Photo/video thumbnails",
        "Fast MediaStore scanning", "Automatic media synchronization", "Latest media shown first",
        "Search media", "Selection mode", "Multi-select", "Share", "Delete", "Move", "Copy", "Slideshow",
        "Favorite/unfavorite", "Hide media", "Recently added/media section", "Video filtering",
        "Favorite filtering", "Storage information"
    )),
    FeatureSection("Albums", "📂", listOf(
        "Automatic albums from device folders (Camera, Screenshots, Downloads, WhatsApp, Videos, etc.)",
        "Create custom albums", "Rename albums", "Delete albums", "Move media between albums",
        "Copy media between albums", "Merge albums", "Pin albums", "Hide albums", "Drag-and-drop album reordering",
        "Album thumbnails", "Latest image/video as album thumbnail", "Album media count",
        "Empty-album handling", "Virtual albums"
    )),
    FeatureSection("Smart / Special Albums", "⭐", listOf(
        "Recent", "Favorites", "Camera", "Video", "Screenshot", "Download", "WhatsApp",
        "Custom albums", "Hidden albums", "Pinned albums", "Inside albums: All, Photos, Videos",
        "Synchronized with actual bucketId/MediaStore data"
    )),
    FeatureSection("Trash / Recycle Bin", "🗑️", listOf(
        "Deleted media goes to Trash", "30-day automatic expiration", "Restore deleted media",
        "Permanently delete", "Multi-select deletion", "Trash count", "Automatic cleanup of expired items"
    )),
    FeatureSection("Favorites", "❤️", listOf(
        "Favorite photos", "Favorite videos", "Favorite status synchronized globally",
        "Favorites album automatically updates (disappears when empty)", "Favorite from picture view or album view"
    )),
    FeatureSection("Hidden Media", "🔒", listOf(
        "Hide individual photos/videos", "Hidden media stored separately", "Unhide media",
        "Hidden media excluded from normal gallery", "Hidden albums", "Hidden album management"
    )),
    FeatureSection("SD Card / External Storage", "💾", listOf(
        "SD-card support", "Storage Access Framework", "Browse SD-card media", "Create albums on SD card",
        "Move media", "Copy media", "Rename", "Delete", "Manage external-storage content"
    )),
    FeatureSection("Duplicate Detection", "🔍", listOf(
        "Duplicate media detection", "MD5/hash-based comparison", "Identify duplicate files",
        "Help clean duplicate photos/videos"
    )),
    FeatureSection("Stories", "📖", listOf(
        "Automatic story generation", "Time-based clustering", "Location-based clustering", "Story grouping",
        "Manual stories", "Add photos/videos to stories", "Story thumbnails", "Story viewing", "Story management"
    )),
    FeatureSection("Music Player", "🎧", listOf(
        "Offline local music player", "Songs, Albums, Artists, Playlists", "Queue, Shuffle, Repeat",
        "Play/Pause, Previous/Next, Seek bar", "Mini Player & Full Music Player", "Background playback",
        "Sleep Timer", "Playback Speed & Pitch Changer", "Equalizer, Bass Boost, Virtualizer"
    )),
    FeatureSection("Duo Music Player", "🎧🎧", listOf(
        "Two music tracks/players", "Independent playback controls", "Two-track playback interface",
        "Separate seek controls", "Play/Pause controls", "Track selection",
        "Switch/change either track", "Independent music experience"
    )),
    FeatureSection("Live Radio", "📻", listOf(
        "Live global internet radio stations", "Radio station browsing by genre and country",
        "Play/pause and Station switching", "Background playback", "Radio mini-player", "Full radio player"
    )),
    FeatureSection("Online Music Search", "🌐", listOf(
        "Find songs online natively", "Search across YouTube, Google, and Spotify",
        "Quick access to lyrics and music videos", "Unified search interface"
    )),
    FeatureSection("Video Player", "🎬", listOf(
        "Offline video playback", "Picture-in-Picture (PiP) mode", "Background audio playback",
        "Playback speed control (up to 8x)", "Sleep Timer", "Play/pause, Seek bar, Previous/next",
        "Video rotation (0° / 90°)", "Full-screen playback", "Video sound & controls"
    )),
    FeatureSection("Basic Photo Editor", "🪄", listOf(
        "Crop, rotate, and straighten photos", "Adjust brightness, contrast, saturation, and exposure",
        "Apply custom filters and color effects", "Add text overlays and stickers",
        "Draw and highlight on images", "Export edited photos seamlessly"
    )),
    FeatureSection("Basic Video Editor", "✂️", listOf(
        "Video editing & trimming/cutting", "Export & Media3 Transformer", "Shader-based effects",
        ".cube LUT support & presets", "Frames", "Stickers/assets"
    )),
    FeatureSection("Live Wallpaper ", "🎨", listOf(
        "Set local videos, photos, or GIFs as live wallpaper", "Preview wallpaper before applying",
        "Crop/fit video", "Mute/unmute wallpaper audio", "Choose wallpaper source from GalleryBox",
        "Apply to Home Screen and/or Lock Screen (where supported)", "Offline operation, Android WallpaperService implementation",
        "Battery-conscious playback (Pauses when required by device)"
    )),
    FeatureSection("Storage Management", "📱", listOf(
        "Internal & SD-card information", "Used & Free storage", "Media statistics (Photo, Video, Music count)",
        "Storage indicator", "MediaStore synchronization"
    )),
    FeatureSection("Smart Media Classification", "🏷️", listOf(
        "Camera", "Screenshots", "Downloads", "WhatsApp", "Videos", "Favorites", "Recent",
        "Other automatically detected categories"
    )),

    FeatureSection("Offline Database", "🗄️", listOf(
        "Trash", "Stories", "Favorites", "Hidden media", "Hidden albums", "Pinned albums",
        "Smart tags", "Album metadata", "Everything remains local on the device"
    )),
    FeatureSection("Privacy", "🔐", listOf(
        "100% offline", "No Google login or account required", "No cloud gallery or automatic upload",
        "No Google Photos dependency", "Local media processing & database", "User-controlled storage permissions"
    )),
    FeatureSection("Gallery Navigation", "🎯", listOf(
        "Primary structure: Pictures → Albums → Stories → Music → Live Wallpaper",
        "Additional sections: Favorites, Trash, Hidden, Settings, Storage, Media Player, Video Editor, Live Wallpaper"
    )),
    FeatureSection("Biometric App Lock", "🔒", listOf(
        "Fingerprint & Face unlock (where supported)", "Device PIN/pattern/password fallback",
        "Lock the entire GalleryBox app automatically", "Re-lock when returning to the app",
        "Android BiometricPrompt integration", "No biometric data stored by GalleryBox",
        "Works completely offline", "Enable/disable App Lock from Settings"
    ))
)

private fun getTermsText(): String = """
Terms of Use
Last Updated: August 18, 2026

Welcome to GalleryBox – Music & Video Editor. By downloading, installing, or using GalleryBox, you agree to these Terms of Use.

1. About GalleryBox
GalleryBox is an Android application designed for managing, viewing, organizing, playing, and editing supported media stored on your device.
Features may include photo and video management, albums, stories, favorites, hidden media, trash, music playback, video playback, basic editing tools, emojis, and other media-related functionality.

2. Acceptance of Terms
By using GalleryBox, you agree to comply with these Terms of Use and applicable laws.
If you do not agree with these terms, please discontinue use of the application.

3. Your Content
You retain all ownership rights to your photos, videos, music, and other content stored on your device.
GalleryBox does not claim ownership of your personal content.
You are solely responsible for the content you store, access, edit, share, or manage using GalleryBox.

4. Device Permissions
GalleryBox may request permissions required to provide its features, including access to photos, videos, audio, microphone, notifications, biometric authentication, and other device functionality.
You may control permissions through Android system settings. Some GalleryBox features may not work if the required permission is denied.

5. Media Management
GalleryBox may allow you to copy, move, rename, hide, favorite, edit, or delete media.
You are responsible for confirming actions before deleting or modifying important files.
Deleted files may not always be recoverable, depending on your device, Android version, storage location, and the action performed.

6. Backups and Data Loss
GalleryBox is not a replacement for a backup service.
You should maintain independent backups of important photos, videos, music, and other files.
To the maximum extent permitted by applicable law, the developer is not responsible for data loss caused by accidental deletion, storage failure, device failure, operating-system restrictions, corrupted files, or improper use of the application.

7. Third-Party Services
GalleryBox may use third-party libraries or services required for specific application functionality.
Such third-party services may have their own terms and privacy policies.

8. Acceptable Use
You agree not to use GalleryBox:
• For unlawful purposes.
• To infringe another person's intellectual property or privacy rights.
• To distribute illegal or harmful content.
• To interfere with or attempt to compromise the application's security.
• To misuse any feature of the application.

9. Application Changes
The developer may add, modify, improve, suspend, or remove features of GalleryBox in future versions.
Some features may behave differently depending on the Android version, device manufacturer, hardware, or available storage.

10. Disclaimer
GalleryBox is provided on an "as available" basis.
While reasonable efforts are made to provide a reliable application, the developer does not guarantee that GalleryBox will always be completely uninterrupted, error-free, or compatible with every Android device.

11. Limitation of Liability
To the maximum extent permitted by applicable law, the developer shall not be liable for indirect, incidental, consequential, or other damages resulting from the use or inability to use GalleryBox, including loss of data or media.

12. Changes to These Terms
These Terms of Use may be updated from time to time.
Any updated version will be made available within GalleryBox or through the application's official information page.

13. Contact
For questions regarding these Terms of Use:
Developer: Balanand Mishra
Website: https://portfolio-b1973.web.app
© 2026 Balanand Mishra. All rights reserved.
""".trimIndent()

private fun getPrivacyText(): String = """
Privacy Policy
Last Updated: August 18, 2026

GalleryBox – Music & Video Editor ("GalleryBox", "we", "our", or "the app") respects your privacy.
This Privacy Policy explains how GalleryBox handles information and device permissions when you use the application.

1. Privacy at a Glance
GalleryBox is designed primarily for local media management.
Your personal photos, videos, music, and other supported media are processed on your device for the application's core gallery, organization, playback, and editing features.
GalleryBox does not require you to create an account to use its core functionality.
GalleryBox does not upload your personal photos, videos, or music to GalleryBox servers as part of its core media-management functionality.

2. Photos and Videos
GalleryBox may request access to photos and videos stored on your device.
This access is required to provide features such as:
• Viewing photos and videos.
• Creating and managing albums.
• Searching and organizing media.
• Creating stories.
• Managing favorites.
• Managing hidden media.
• Managing Trash.
• Playing videos.
• Editing supported media.
• Sharing or exporting media.
Your media remains stored on your device unless you explicitly use another application or service to share, upload, or transfer it.

3. Music and Audio
GalleryBox may request access to audio files stored on your device to provide music playback and related functionality.
Music files are accessed locally for features such as playback, playlists, queues, and audio controls.
GalleryBox does not claim ownership of your music or audio files.

4. Microphone
GalleryBox may request microphone access when a feature requires audio recording or microphone input.
Microphone access is not intended to be used continuously without an applicable feature being active.
You can control microphone permission through Android settings.

5. Location and Media Metadata
GalleryBox may access media-related location metadata when required by supported media-management functionality.
This does not mean that GalleryBox continuously tracks your physical location.

6. Biometric Authentication
GalleryBox may use Android's biometric authentication functionality to protect supported private or hidden content.
GalleryBox does not receive or store your fingerprint, face data, or biometric template.
Authentication is handled by the Android device's supported security mechanisms.

7. Notifications
GalleryBox may request notification permission when notifications are required for supported features, such as media playback or other application functions.
You can control notification permissions through Android settings.

8. Network Access
GalleryBox may require network access for features that specifically depend on an internet connection.
The core local gallery functionality is designed to operate without cloud storage or a GalleryBox account.

9. Personal Information
GalleryBox does not intentionally collect your personal photos, videos, music, or other private media for storage on GalleryBox servers.
GalleryBox does not sell your personal media or personal information.

10. Third-Party Libraries and Services
GalleryBox may contain third-party software libraries required for application functionality.
These libraries may process information according to their respective purposes and privacy policies.
Only third-party services actually included and used by the released version of GalleryBox should be considered part of this policy.

11. Data Security
GalleryBox uses Android platform security mechanisms and application-level protections where applicable.
However, no software or storage system can guarantee absolute security.
You should use your device's security features and maintain backups of important files.

12. Children's Privacy
GalleryBox does not knowingly collect personal information from children.
If you believe that personal information has been provided to GalleryBox in a manner that violates applicable law, please contact us.

13. Your Choices
You can control many permissions through Android settings, including:
• Photos and videos
• Music and audio
• Microphone
• Notifications
• Biometrics
• Other device permissions
You may also uninstall GalleryBox at any time.

14. Changes to This Privacy Policy
This Privacy Policy may be updated when GalleryBox's features, technologies, or services change.
The latest version will be made available within GalleryBox or through its official information page.

15. Contact
If you have questions, concerns, or requests regarding this Privacy Policy, contact:
Developer: Balanand Mishra
Website: https://portfolio-b1973.web.app
© 2026 Balanand Mishra. All rights reserved.
""".trimIndent()

private fun getLicenseText(): String = """
Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

"License" shall mean the terms and conditions for use, reproduction,
and distribution as defined by Sections 1 through 9 of this document.

"Licensor" shall mean the copyright owner or entity authorized by
the copyright owner that is granting the License.

"Legal Entity" shall mean the union of the acting entity and all
other entities that control, are controlled by, or are under common
control with that entity. For the purposes of this definition,
"control" means (i) the power, direct or indirect, to cause the
direction or management of such entity, whether by contract or
otherwise, or (ii) ownership of fifty percent (50%) or more of the
outstanding shares, or (iii) beneficial ownership of such entity.

"You" (or "Your") shall mean an individual or Legal Entity
exercising permissions granted by this License.

"Source" form shall mean the preferred form for making modifications,
including but not limited to software source code, documentation
source, and configuration files.

"Object" form shall mean any form resulting from mechanical
transformation or translation of a Source form, including but
not limited to compiled object code, generated documentation,
and conversions to other media types.

"Work" shall mean the work of authorship, whether in Source or
Object form, made available under the License, as indicated by a
copyright notice that is included in or attached to the work
(an example is provided in the Appendix below).

"Derivative Works" shall mean any work, whether in Source or Object
form, that is based on (or derived from) the Work and for which the
editorial revisions, annotations, elaborations, or other modifications
represent, as a whole, an original work of authorship. For the purposes
of this License, Derivative Works shall not include works that remain
separable from, or merely link (or bind by name) to the interfaces of,
the Work and Derivative Works thereof.

"Contribution" shall mean any work of authorship, including
the original version of the Work and any modifications or additions
to that Work or Derivative Works thereof, that is intentionally
submitted to Licensor for inclusion in the Work by the copyright owner
or by an individual or Legal Entity authorized to submit on behalf of
the copyright owner. For the purposes of this definition, "submitted"
means any form of electronic, verbal, or written communication sent
to the Licensor or its representatives, including but not limited to
communication on electronic mailing lists, source code control systems,
and issue tracking systems that are managed by, or on behalf of, the
Licensor for the purpose of discussing and improving the Work, but
excluding communication that is conspicuously marked or otherwise
designated in writing by the copyright owner as "Not a Contribution."

"Contributor" shall mean Licensor and any individual or Legal Entity
on behalf of whom a Contribution has been received by Licensor and
subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of
this License, each Contributor hereby grants to You a perpetual,
worldwide, non-exclusive, no-charge, royalty-free, irrevocable
copyright license to reproduce, prepare Derivative Works of,
publicly display, publicly perform, sublicense, and distribute the
Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of
this License, each Contributor hereby grants to You a perpetual,
worldwide, non-exclusive, no-charge, royalty-free, irrevocable
(except as stated in this section) patent license to make, have made,
use, offer to sell, sell, import, and otherwise transfer the Work,
where such license applies only to those patent claims licensable
by such Contributor that are necessarily infringed by their
Contribution(s) alone or by combination of their Contribution(s)
with the Work to which such Contribution(s) was submitted. If You
institute patent litigation against any entity (including a
cross-claim or counterclaim in a lawsuit) alleging that the Work
or a Contribution incorporated within the Work constitutes direct
or contributory patent infringement, then any patent licenses
granted to You under this License for that Work shall terminate
as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the
Work or Derivative Works thereof in any medium, with or without
modifications, and in Source or Object form, provided that You
meet the following conditions:

(a) You must give any other recipients of the Work or
Derivative Works a copy of this License; and

(b) You must cause any modified files to carry prominent notices
stating that You changed the files; and

(c) You must retain, in the Source form of any Derivative Works
that You distribute, all copyright, patent, trademark, and
attribution notices from the Source form of the Work,
excluding those notices that do not pertain to any part of
the Derivative Works; and

(d) If the Work includes a "NOTICE" text file as part of its
distribution, then any Derivative Works that You distribute must
include a readable copy of the attribution notices contained
within such NOTICE file, excluding those notices that do not
pertain to any part of the Derivative Works, in at least one
of the following places: within a NOTICE text file distributed
as part of the Derivative Works; within the Source form or
documentation, if provided along with the Derivative Works; or,
within a display generated by the Derivative Works, if and
wherever such third-party notices normally appear. The contents
of the NOTICE file are for informational purposes only and
do not modify the License. You may add Your own attribution
notices within Derivative Works that You distribute, alongside
or as an addendum to the NOTICE text from the Work, provided
that such additional attribution notices cannot be construed
as modifying the License.

You may add Your own copyright statement to Your modifications and
may provide additional or different license terms and conditions
for use, reproduction, or distribution of Your modifications, or
for any such Derivative Works as a whole, provided Your use,
reproduction, and distribution of the Work otherwise complies with
the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise,
any Contribution intentionally submitted for inclusion in the Work
by You to the Licensor shall be under the terms and conditions of
this License, without any additional terms or conditions.
Notwithstanding the above, nothing herein shall supersede or modify
the terms of any separate license agreement you may have executed
with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade
names, trademarks, service marks, or product names of the Licensor,
except as required for reasonable and customary use in describing the
origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or
agreed to in writing, Licensor provides the Work (and each
Contributor provides its Contributions) on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied, including, without limitation, any warranties or conditions
of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
PARTICULAR PURPOSE. You are solely responsible for determining the
appropriateness of using or redistributing the Work and assume any
risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory,
whether in tort (including negligence), contract, or otherwise,
unless required by applicable law (such as deliberate and grossly
negligent acts) or agreed to in writing, shall any Contributor be
liable to You for damages, including any direct, indirect, special,
incidental, or consequential damages of any character arising as a
result of this License or out of the use or inability to use the
Work (including but not limited to damages for loss of goodwill,
work stoppage, computer failure or malfunction, or any and all
other commercial damages or losses), even if such Contributor
has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing
the Work or Derivative Works thereof, You may choose to offer,
and charge a fee for, acceptance of support, warranty, indemnity,
or other liability obligations and/or rights consistent with this
License. However, in accepting such obligations, You may act only
on Your own behalf and on Your sole responsibility, not on behalf
of any other Contributor, and only if You agree to indemnify,
defend, and hold each Contributor harmless for any liability
incurred by, or claims asserted against, such Contributor by reason
of your accepting any such warranty or additional liability.
""".trimIndent()