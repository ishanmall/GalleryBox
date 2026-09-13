package com.gallerybox.ui.screens.adaptive

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * =========================================================================================
 * 📦 ADAPTIVE DATA CLASSES (The Vocabulary)
 * =========================================================================================
 * These classes define the words our app uses to describe the phone's shape and size.
 * By keeping them in this file, everything related to adaptive screens stays in one place.
 */

enum class WindowWidthSize {
    COMPACT,    // Normal phones
    MEDIUM,     // Small tablets or large landscape phones
    EXPANDED    // Big tablets and desktop monitors
}

enum class WindowHeightSize {
    COMPACT,    // Phones held sideways (very little vertical room)
    MEDIUM,     // Normal phones held upright
    EXPANDED    // Tall tablets
}

enum class NavigationStyle {
    BOTTOM_BAR,         // Buttons at the bottom
    NAVIGATION_RAIL,    // Slim buttons on the left edge
    PERMANENT_DRAWER    // Full menu permanently open on the left
}

enum class DevicePosture {
    NORMAL_FLAT,        // A normal flat screen
    HALF_OPENED,        // Bent like a laptop sitting on a table
    BOOK_MODE,          // Bent and held like a physical reading book
    MULTI_FOLD,         // Futuristic devices with 2 or more hinges
    COVER_SCREEN        // The small outside screen of a closed foldable
}

/**
 * This is the master package of information about the user's screen at this exact second.
 */
data class AdaptiveState(
    val widthSize: WindowWidthSize,
    val heightSize: WindowHeightSize,
    val screenWidthDp: Dp,
    val screenHeightDp: Dp,
    val posture: DevicePosture,
    val totalHinges: Int,
    val isLandscape: Boolean,
    val navigationStyle: NavigationStyle,
    val recommendedPadding: Dp,
    val recommendedItemSpacing: Dp,
    val textScaleFactor: Float,
    val safeInsets: PaddingValues
)

/**
 * =========================================================================================
 * 🧠 THE ADAPTIVE ENGINE (The Brains)
 * =========================================================================================
 * This function watches the device hardware. If the user unfolds their phone,
 * this function instantly updates and tells the rest of the app to redraw.
 */
@Composable
fun rememberAdaptiveState(): AdaptiveState {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current

    // 1. Measure the physical app window
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 2. Categorize the screen width
    val widthSize = when {
        screenWidth < 600.dp -> WindowWidthSize.COMPACT
        screenWidth < 840.dp -> WindowWidthSize.MEDIUM
        else -> WindowWidthSize.EXPANDED
    }

    // 3. Categorize the screen height
    val heightSize = when {
        screenHeight < 480.dp -> WindowHeightSize.COMPACT
        screenHeight < 900.dp -> WindowHeightSize.MEDIUM
        else -> WindowHeightSize.EXPANDED
    }

    // 4. Decide where the menu should go
    val navigationStyle = when (widthSize) {
        WindowWidthSize.COMPACT -> NavigationStyle.BOTTOM_BAR
        WindowWidthSize.MEDIUM -> NavigationStyle.NAVIGATION_RAIL
        WindowWidthSize.EXPANDED -> NavigationStyle.PERMANENT_DRAWER
    }

    // 5. Calculate spacing so the UI doesn't look squished
    val recommendedPadding = when (widthSize) {
        WindowWidthSize.COMPACT -> 16.dp
        WindowWidthSize.MEDIUM -> 24.dp
        WindowWidthSize.EXPANDED -> 32.dp
    }

    val recommendedItemSpacing = when (widthSize) {
        WindowWidthSize.COMPACT -> 8.dp
        WindowWidthSize.MEDIUM -> 12.dp
        WindowWidthSize.EXPANDED -> 16.dp
    }

    // 6. Scale text up for large TV/Tablet screens
    val textScaleFactor = when (widthSize) {
        WindowWidthSize.COMPACT -> 1.0f
        WindowWidthSize.MEDIUM -> 1.1f
        WindowWidthSize.EXPANDED -> 1.2f
    }

    // 7. Find the camera notch and gesture bars so we don't draw under them
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues(density)

    // 8. Hardware hinge detection
    var devicePosture by remember { mutableStateOf(DevicePosture.NORMAL_FLAT) }
    var totalHinges by remember { mutableIntStateOf(0) }

    LaunchedEffect(context) {
        if (context is Activity) {
            val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
            windowInfoTracker.windowLayoutInfo(context)
                .map { layoutInfo -> layoutInfo.displayFeatures }
                .collect { features ->
                    val foldingFeatures = features.filterIsInstance<FoldingFeature>()
                    totalHinges = foldingFeatures.size

                    if (foldingFeatures.isEmpty()) {
                        if (screenWidth < 400.dp && screenHeight > 600.dp) {
                            devicePosture = DevicePosture.COVER_SCREEN
                        } else {
                            devicePosture = DevicePosture.NORMAL_FLAT
                        }
                    } else if (foldingFeatures.size > 1) {
                        devicePosture = DevicePosture.MULTI_FOLD
                    } else {
                        val hinge = foldingFeatures.first()
                        devicePosture = when (hinge.state) {
                            FoldingFeature.State.FLAT -> DevicePosture.NORMAL_FLAT
                            FoldingFeature.State.HALF_OPENED -> {
                                if (hinge.orientation == FoldingFeature.Orientation.HORIZONTAL) {
                                    DevicePosture.HALF_OPENED
                                } else {
                                    DevicePosture.BOOK_MODE
                                }
                            }
                            else -> DevicePosture.NORMAL_FLAT
                        }
                    }
                }
        }
    }

    return AdaptiveState(
        widthSize = widthSize,
        heightSize = heightSize,
        screenWidthDp = screenWidth,
        screenHeightDp = screenHeight,
        posture = devicePosture,
        totalHinges = totalHinges,
        isLandscape = isLandscape,
        navigationStyle = navigationStyle,
        recommendedPadding = recommendedPadding,
        recommendedItemSpacing = recommendedItemSpacing,
        textScaleFactor = textScaleFactor,
        safeInsets = safeInsets
    )
}

/**
 * =========================================================================================
 * 🎨 THE REAL ADAPTIVE CONTENT LAYOUT (No Fake Data)
 * =========================================================================================
 * This acts as a smart wrapper. Instead of drawing fake UI, it asks you to provide
 * your REAL UI components as parameters (primaryContent and secondaryContent).
 *
 * Think of `primaryContent` as your photo grid.
 * Think of `secondaryContent` as your video player or photo detail view.
 *
 * This layout will automatically organize them based on how the user is holding their phone.
 */
@Composable
fun AdaptivePaneLayout(
    adaptiveState: AdaptiveState,
    primaryContent: @Composable () -> Unit,
    secondaryContent: (@Composable () -> Unit)? = null
) {
    val spacing = adaptiveState.recommendedItemSpacing
    val padding = adaptiveState.recommendedPadding

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {

        // Scenario 1: We only have one thing to show (just the gallery grid)
        // Or, the phone is a standard flat phone, so we only show one thing at a time.
        if (secondaryContent == null || (adaptiveState.posture == DevicePosture.NORMAL_FLAT && adaptiveState.widthSize == WindowWidthSize.COMPACT)) {
            Box(modifier = Modifier.fillMaxSize()) {
                primaryContent()
            }
        }

        // Scenario 2: The device has multiple folds (Triple-fold devices)
        // We put the main content on the outer edges and the secondary content in the middle.
        else if (adaptiveState.totalHinges >= 2) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { primaryContent() }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { secondaryContent() }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { primaryContent() }
            }
        }

        // Scenario 3: Book Mode (Foldable held vertically like reading a book)
        // Put the gallery list on the left page, and the photo/video on the right page.
        else if (adaptiveState.posture == DevicePosture.BOOK_MODE || (adaptiveState.widthSize != WindowWidthSize.COMPACT && adaptiveState.posture == DevicePosture.NORMAL_FLAT)) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    primaryContent()
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    secondaryContent()
                }
            }
        }

        // Scenario 4: Tabletop / Half-Opened Mode (Foldable bent like a tiny laptop)
        // Put the video player on the top screen, and the gallery list on the bottom screen.
        else if (adaptiveState.posture == DevicePosture.HALF_OPENED) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    secondaryContent()
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    primaryContent()
                }
            }
        }
    }
}