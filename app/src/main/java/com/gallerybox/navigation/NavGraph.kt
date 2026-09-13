@file:Suppress("unused")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.navigation

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// ---------------------------------------------------------------------------
// 🖼️ APP SCREENS & VIEWMODELS
// ---------------------------------------------------------------------------
import com.gallerybox.about.AboutScreen
import com.gallerybox.ui.screens.ScanLibraryScreen
import com.gallerybox.ui.screens.album.*
import com.gallerybox.ui.screens.editor.EditorScreen
import com.gallerybox.ui.screens.file.*
import com.gallerybox.ui.screens.music.*
import com.gallerybox.ui.screens.picture.PictureScreen
import com.gallerybox.ui.screens.stories.StoriesScreen
import com.gallerybox.ui.screens.trash.TrashScreen
import com.gallerybox.ui.screens.vault.VaultSecureScreen
import com.gallerybox.ui.screens.videoplayer.VideoPlayerScreen
import com.gallerybox.ui.screens.wallpaper.WallpaperScreen
import com.gallerybox.viewmodel.*

// ---------------------------------------------------------------------------
// 🧠 ADAPTIVE LOGIC IMPORTS
// These are imported from the adaptive package we just built!
// ---------------------------------------------------------------------------
import com.gallerybox.ui.screens.adaptive.AdaptiveState
import com.gallerybox.ui.screens.adaptive.NavigationStyle
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState

/**
 * =========================================================================================
 * 🗺️ THE APP ROADMAP (ROUTES)
 * =========================================================================================
 * Think of this section as the "Addresses" in a GPS system.
 * Every single screen in the app has a specific address (a "Route").
 * When a user clicks a button to go somewhere, we tell the app to travel to one of these Routes.
 * =========================================================================================
 */
sealed interface Route {
    @Serializable data object Pictures : Route
    @Serializable data object Albums : Route
    @Serializable data object Stories : Route
    @Serializable data object Music : Route
    @Serializable data object Camera : Route
    @Serializable data object Vault : Route
    @Serializable data object Radio : Route
    @Serializable data object DigitalRadio : Route
    @Serializable data object OnlineFinder : Route
    @Serializable data object Equalizer : Route
    @Serializable data object DuoMusic : Route
    @Serializable data object About : Route
    @Serializable data object ScanLibrary : Route
    @Serializable data object Trash : Route
    @Serializable data object Hidden : Route
    @Serializable data object HideAlbums : Route
    @Serializable data object Duplicates : Route

    // Some routes carry "packages" of data with them (like a specific Video URI to play)
    @Serializable data class VideoPlayer(val uri: String, val position: Long = 0L) : Route
    @Serializable data class AlbumView(val albumId: String) : Route
    @Serializable data class Slideshow(val albumId: String? = null) : Route
    @Serializable data class MediaEditor(val uri: String, val mediaId: Long? = null) : Route
    @Serializable data class MoveCopy(val mode: String, val ids: String, val sourceAlbumId: String? = null) : Route
    @Serializable data class Wallpaper(val uri: String, val mediaId: Long? = null) : Route
}

/**
 * Helper tools to turn complex text (like file paths with slashes and symbols)
 * into a safe, messy string (Base64) so it doesn't break our navigation system.
 * It's like packing a fragile item in bubble wrap before mailing it.
 */
fun String.toSafeRouteArgs() = Base64.encodeToString(this.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun String.fromSafeRouteArgs() = try {
    String(Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
} catch (e: Exception) {
    this // If bubble wrap removal fails, just return the original text
}

/**
 * A blueprint for the main menu buttons (Photos, Albums, Music).
 */
data class BottomTab(
    val route: Route,
    val routeClass: KClass<out Route>,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
)

/**
 * =========================================================================================
 * 🛡️ THE SECURITY GATEKEEPER (GalleryNavHost)
 * =========================================================================================
 * This is the very first thing the app loads. Before showing any photos, it checks:
 * "Is AppLock enabled? Does the user need to enter a PIN code?"
 * If locked, it shows the Vault screen. If unlocked, it lets them in.
 * =========================================================================================
 */
@Composable
fun GalleryNavHost(
    securityVM: SecurityViewModel = hiltViewModel(),
    sharedGalleryViewModel: GalleryViewModel = hiltViewModel(),
    sharedMusicViewModel: MusicViewModel = hiltViewModel(),
    sharedTrashViewModel: TrashViewModel = hiltViewModel(),
    sharedRadioViewModel: RadioViewModel = hiltViewModel()
) {
    val isUnlocked by securityVM.isUnlocked.collectAsState()
    var isAppLockEnabled by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // When the app starts, silently check if the user turned on the App Lock in settings.
    LaunchedEffect(Unit) {
        isAppLockEnabled = withContext(Dispatchers.IO) { securityVM.isAppLockEnabled() }
        if (isAppLockEnabled) {
            securityVM.lock() // Lock the doors!
        }
        isInitializing = false
    }

    // This watcher notices if the user leaves the app and comes back.
    // If they come back, we check the lock status again.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isAppLockEnabled = withContext(Dispatchers.IO) { securityVM.isAppLockEnabled() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Deciding what screen to show to the user right now:
    if (isInitializing) {
        // Still thinking... show a loading circle.
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (isAppLockEnabled && !isUnlocked) {
        // The doors are locked! Show the PIN/Fingerprint screen.
        VaultSecureScreen(
            isGlobalAppGuard = true,
            onBack = {},
            onUnlockGlobalSuccess = {
                securityVM.unlockReal() // User entered correct PIN, unlock it!
            }
        )
    } else {
        // They are allowed in. Show the actual Gallery App!
        GalleryAppContent(
            sharedGalleryViewModel = sharedGalleryViewModel,
            sharedMusicViewModel = sharedMusicViewModel,
            sharedTrashViewModel = sharedTrashViewModel,
            sharedRadioViewModel = sharedRadioViewModel,
            onLockApp = { securityVM.lock() }
        )
    }
}

/**
 * =========================================================================================
 * 📱 THE MAIN APP SHELL & ADAPTIVE NAVIGATION
 * =========================================================================================
 * This handles the main layout. It asks our AdaptiveState: "What shape is this phone?"
 * Based on the answer, it moves the menu buttons to the bottom, the side, or a giant drawer.
 * =========================================================================================
 */
@Composable
fun GalleryAppContent(
    sharedGalleryViewModel: GalleryViewModel,
    sharedMusicViewModel: MusicViewModel,
    sharedTrashViewModel: TrashViewModel,
    sharedRadioViewModel: RadioViewModel,
    onLockApp: () -> Unit
) {
    // 🧠 1. Get the adaptive state (knows if we are on a foldable, tablet, or phone)
    val adaptiveState = rememberAdaptiveState()

    // 2. Setup the "driver" that actually switches the screens (NavHostController)
    val navController = rememberNavController()
    val context = LocalContext.current

    // 3. Remember where the user was last time they closed the app
    val sharedPrefs = remember { context.getSharedPreferences("app_nav_prefs", Context.MODE_PRIVATE) }
    val initialStartDestination = remember {
        when (sharedPrefs.getString("last_main_tab", "Albums")) {
            "Pictures" -> Route.Pictures
            "Music" -> Route.Music
            else -> Route.Albums
        }
    }

    // 4. Update the saved memory whenever they click a new main tab (using Kotlin KTX logic)
    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, dest, _ ->
            when {
                dest.hasRoute(Route.Pictures::class) -> sharedPrefs.edit { putString("last_main_tab", "Pictures") }
                dest.hasRoute(Route.Albums::class) -> sharedPrefs.edit { putString("last_main_tab", "Albums") }
                dest.hasRoute(Route.Music::class) -> sharedPrefs.edit { putString("last_main_tab", "Music") }
            }
        }
    }

    // 5. Define the Main Menu Buttons
    val tabs = remember {
        listOf(
            BottomTab(Route.Pictures, Route.Pictures::class, Icons.Filled.Photo, Icons.Outlined.Photo, "Photos"),
            BottomTab(Route.Albums, Route.Albums::class, Icons.Filled.PhotoAlbum, Icons.Outlined.PhotoAlbum, "Albums"),
            BottomTab(Route.Stories, Route.Stories::class, Icons.Filled.AutoStories, Icons.Outlined.AutoStories, "Memories"),
            BottomTab(Route.Music, Route.Music::class, Icons.Filled.MusicNote, Icons.Outlined.MusicNote, "Music")
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    var isFullScreenMediaOpen by remember { mutableStateOf(false) }

    // 6. Should we show the menu right now? (We hide it when watching a video in full screen)
    val showMainNavigation by remember(currentDestination, isFullScreenMediaOpen) {
        derivedStateOf {
            if (isFullScreenMediaOpen) {
                false
            } else {
                currentDestination?.hasRoute(Route.Pictures::class) == true ||
                        currentDestination?.hasRoute(Route.Albums::class) == true ||
                        currentDestination?.hasRoute(Route.Stories::class) == true ||
                        currentDestination?.hasRoute(Route.Music::class) == true
            }
        }
    }

    // A reusable shortcut to jump to the Video Player from anywhere
    val navigateToVideo = remember(navController) {
        { rawUriString: String, _: List<String> ->
            navController.navigate(Route.VideoPlayer(rawUriString.toSafeRouteArgs())) {
                popUpTo(Route.VideoPlayer::class) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // 🎨 7. THE ADAPTIVE LAYOUT WRAPPER
    // Depending on the screen shape, we wrap the app in a different layout structure.
    if (!showMainNavigation) {
        // User is deep in the app (like watching a video). Just show the screen, no menus.
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            AppNavHost(navController, initialStartDestination, padding, context, navigateToVideo, { isFullScreenMediaOpen = it }, sharedGalleryViewModel, sharedTrashViewModel, sharedMusicViewModel, sharedRadioViewModel, onLockApp)
        }
    } else {
        when (adaptiveState.navigationStyle) {

            // 📱 NORMAL PHONE (Or Foldable Cover Screen) -> Put menu on the BOTTOM
            NavigationStyle.BOTTOM_BAR -> {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { BottomNavigationBar(tabs, currentDestination, navController) }
                ) { padding ->
                    AppNavHost(navController, initialStartDestination, padding, context, navigateToVideo, { isFullScreenMediaOpen = it }, sharedGalleryViewModel, sharedTrashViewModel, sharedMusicViewModel, sharedRadioViewModel, onLockApp)
                }
            }

            // 📖 FOLDABLE (Unfolded) OR SMALL TABLET -> Put menu on the LEFT EDGE (Rail)
            NavigationStyle.NAVIGATION_RAIL -> {
                Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppSideNavigationRail(tabs, currentDestination, navController)
                    // The actual screens go next to the rail
                    Box(modifier = Modifier.weight(1f)) {
                        AppNavHost(navController, initialStartDestination, PaddingValues(0.dp), context, navigateToVideo, { isFullScreenMediaOpen = it }, sharedGalleryViewModel, sharedTrashViewModel, sharedMusicViewModel, sharedRadioViewModel, onLockApp)
                    }
                }
            }

            // 🖥️ BIG TABLET OR DESKTOP -> Put a FULL MENU on the LEFT (Drawer)
            NavigationStyle.PERMANENT_DRAWER -> {
                PermanentNavigationDrawer(
                    drawerContent = { AppDesktopDrawer(tabs, currentDestination, navController) }
                ) {
                    AppNavHost(navController, initialStartDestination, PaddingValues(0.dp), context, navigateToVideo, { isFullScreenMediaOpen = it }, sharedGalleryViewModel, sharedTrashViewModel, sharedMusicViewModel, sharedRadioViewModel, onLockApp)
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔀 THE NAV HOST (The actual list of screens)
 * =========================================================================================
 * We extracted this into its own function so we can easily inject it into our Adaptive
 * Layouts (Bottom Bar vs Side Rail) without copying and pasting the code 3 times.
 */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDest: Route,
    padding: PaddingValues,
    context: Context,
    navToVid: (String, List<String>) -> Unit,
    onViewerStateChanged: (Boolean) -> Unit,
    sharedGalleryViewModel: GalleryViewModel,
    sharedTrashViewModel: TrashViewModel,
    sharedMusicViewModel: MusicViewModel,
    sharedRadioViewModel: RadioViewModel,
    onLockApp: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDest,
        modifier = Modifier.padding(padding)
    ) {
        // Group 1: The Main Tab Screens (Photos, Albums, Music)
        mainTabs(
            nav = navController,
            ctx = context,
            navToVid = navToVid,
            onViewerStateChanged = onViewerStateChanged,
            galleryViewModel = sharedGalleryViewModel,
            trashViewModel = sharedTrashViewModel,
            musicViewModel = sharedMusicViewModel
        )
        // Group 2: Opening specific albums and hidden folders
        albumGraphs(
            nav = navController,
            navToVid = navToVid,
            onViewerStateChanged = onViewerStateChanged,
            galleryViewModel = sharedGalleryViewModel,
            trashViewModel = sharedTrashViewModel
        )
        // Group 3: Photo & Video Editing
        editorGraphs(navController)

        // Group 4: Utilities (Trash, Scan, Lock Screen, Music Player plugins, etc.)
        toolsAndUtilityGraphs(
            nav = navController,
            ctx = context,
            navToVid = navToVid,
            onLock = onLockApp,
            galleryViewModel = sharedGalleryViewModel,
            trashViewModel = sharedTrashViewModel,
            musicViewModel = sharedMusicViewModel,
            radioViewModel = sharedRadioViewModel
        )
    }
}

/**
 * Below are the extension functions that define exactly what Composable Screen
 * to load when the app arrives at a specific Route (address).
 */

private fun NavGraphBuilder.mainTabs(
    nav: NavHostController,
    ctx: Context,
    navToVid: (String, List<String>) -> Unit,
    onViewerStateChanged: (Boolean) -> Unit,
    galleryViewModel: GalleryViewModel,
    trashViewModel: TrashViewModel,
    musicViewModel: MusicViewModel
) {
    composable<Route.Pictures> {
        PictureScreen(
            viewModel = galleryViewModel,
            trashViewModel = trashViewModel,
            onViewerStateChanged = onViewerStateChanged,
            onNavigateToCamera = { safeLaunchCamera(ctx) },
            onNavigateToTrash = { nav.navigate(Route.Trash) },
            onNavigateToHidden = { nav.navigate(Route.Hidden) },
            onNavigateToDuplicates = { nav.navigate(Route.Duplicates) },
            onNavigateToSlideshow = { nav.navigate(Route.Slideshow()) },
            onNavigateToWallpaper = { uri, id -> nav.navigate(Route.Wallpaper(uri.toSafeRouteArgs(), id)) },
            onNavigateToAlbum = { raw -> nav.navigate(Route.AlbumView(raw.toSafeRouteArgs())) },
            onNavigateToScan = { nav.navigate(Route.ScanLibrary) },
            onNavigateToVideoPlayer = navToVid,
            onNavigateToEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
            onNavigateToMoveCopy = { m, ids, src -> nav.navigate(Route.MoveCopy(m, ids, src?.toSafeRouteArgs())) },
            onNavigateToAbout = { nav.navigate(Route.About) }
        )
    }

    composable<Route.Albums> {
        AlbumScreen(
            viewModel = galleryViewModel,
            trashViewModel = trashViewModel,
            securityViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            actions = AlbumActions(
                onAlbumClick = { a -> nav.navigate(Route.AlbumView(a.id.toSafeRouteArgs())) },
                onNavigateToFavorites = { nav.navigate(Route.AlbumView("virtual_favorites".toSafeRouteArgs())) },
                onNavigateToTrash = { nav.navigate(Route.Trash) },
                onNavigateToHidden = { nav.navigate(Route.HideAlbums) },
                onNavigateToDuplicates = { nav.navigate(Route.Duplicates) },
                onNavigateToScan = { nav.navigate(Route.ScanLibrary) }
            )
        )
    }

    composable<Route.Stories> {
        StoriesScreen(
            viewModel = galleryViewModel,
            storyViewModel = hiltViewModel()
        )
    }

    composable<Route.Music> {
        MusicScreen(
            viewModel = musicViewModel,
            trashViewModel = trashViewModel,
            onViewerStateChanged = onViewerStateChanged,
            onNavigateToEqualizer = { nav.navigate(Route.Equalizer) },
            onNavigateToRadio = { nav.navigate(Route.Radio) },
            onNavigateToDuoPlayer = { nav.navigate(Route.DuoMusic) }
        )
    }
}

private fun NavGraphBuilder.albumGraphs(
    nav: NavHostController,
    navToVid: (String, List<String>) -> Unit,
    onViewerStateChanged: (Boolean) -> Unit,
    galleryViewModel: GalleryViewModel,
    trashViewModel: TrashViewModel
) {
    composable<Route.AlbumView> { backStack ->
        AlbumDetailScreen(
            albumId = backStack.toRoute<Route.AlbumView>().albumId.fromSafeRouteArgs(),
            viewModel = galleryViewModel,
            trashViewModel = trashViewModel,
            securityViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            actions = DetailActions(
                onBack = { nav.popBackStack() },
                onNavigateToPhotoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoPlayer = navToVid,
                onNavigateToMoveCopy = { m, ids, src -> nav.navigate(Route.MoveCopy(m, ids, src?.toSafeRouteArgs())) },
                onNavigateToTrash = { nav.navigate(Route.Trash) },
                onNavigateToHidden = { nav.navigate(Route.Hidden) },
                onNavigateToWallpaper = { uri, id -> nav.navigate(Route.Wallpaper(uri.toSafeRouteArgs(), id)) }
            )
        )
    }

    composable<Route.Hidden> {
        AlbumDetailScreen(
            albumId = "virtual_hidden",
            viewModel = galleryViewModel,
            trashViewModel = trashViewModel,
            securityViewModel = hiltViewModel(),
            onViewerStateChanged = onViewerStateChanged,
            actions = DetailActions(
                onBack = { nav.popBackStack() },
                onNavigateToPhotoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoEditor = { uri, id -> nav.navigate(Route.MediaEditor(uri.toSafeRouteArgs(), id)) },
                onNavigateToVideoPlayer = navToVid,
                onNavigateToMoveCopy = { m, ids, src -> nav.navigate(Route.MoveCopy(m, ids, src?.toSafeRouteArgs())) },
                onNavigateToTrash = { nav.navigate(Route.Trash) },
                onNavigateToHidden = { nav.navigate(Route.Hidden) },
                onNavigateToWallpaper = { uri, id -> nav.navigate(Route.Wallpaper(uri.toSafeRouteArgs(), id)) }
            )
        )
    }
}

private fun NavGraphBuilder.editorGraphs(nav: NavHostController) {
    composable<Route.MediaEditor> { backStack ->
        val args = backStack.toRoute<Route.MediaEditor>()
        val mediaId = args.mediaId
        if (mediaId != null && mediaId != 0L) {
            EditorScreen(
                mediaId = mediaId,
                onBack = { nav.popBackStack() }
            )
        } else {
            // Safety fallback: if no ID was passed, just go back.
            LaunchedEffect(Unit) {
                nav.popBackStack()
            }
        }
    }
}

private fun NavGraphBuilder.toolsAndUtilityGraphs(
    nav: NavHostController,
    ctx: Context,
    navToVid: (String, List<String>) -> Unit,
    onLock: () -> Unit,
    galleryViewModel: GalleryViewModel,
    trashViewModel: TrashViewModel,
    musicViewModel: MusicViewModel,
    radioViewModel: RadioViewModel
) {
    composable<Route.HideAlbums> { HideScreen(viewModel = galleryViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.About> { AboutScreen(onNavigateUp = { nav.popBackStack() }) }
    composable<Route.Radio> { RadioScreen(viewModel = radioViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.Equalizer> { EqualizerScreen(viewModel = musicViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.DuoMusic> { DuoMusicScreen(viewModel = musicViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.DigitalRadio> { DigitalRadioScreen(viewModel = radioViewModel, onBack = { nav.popBackStack() }) }
    composable<Route.OnlineFinder> { OnlineSongFinderScreen(onBack = { nav.popBackStack() }) }

    composable<Route.Wallpaper> { backStack ->
        val args = backStack.toRoute<Route.Wallpaper>()
        val decodedUri = args.uri.fromSafeRouteArgs()
        val rawMedia by galleryViewModel.rawMedia.collectAsState()
        val mediaItem = galleryViewModel.getMediaItemById(args.mediaId ?: -1L) ?: rawMedia.find { m -> m.uri.toString() == decodedUri }

        if (mediaItem != null) {
            WallpaperScreen(item = mediaItem, onBack = { nav.popBackStack() })
        } else {
            val isBusy by galleryViewModel.isBusy.collectAsState()
            if (!isBusy) LaunchedEffect(Unit) { nav.popBackStack() }
        }
    }

    composable<Route.Trash> {
        TrashScreen(
            trashViewModel = trashViewModel,
            galleryViewModel = galleryViewModel,
            musicViewModel = musicViewModel,
            onBack = { nav.popBackStack() }
        )
    }

    composable<Route.Duplicates> { DuplicatesScreen(viewModel = galleryViewModel, trashViewModel = trashViewModel, onBack = { nav.popBackStack() }) }

    composable<Route.ScanLibrary> {
        ScanLibraryScreen(
            onBack = { nav.popBackStack() },
            galleryViewModel = galleryViewModel,
            musicViewModel = musicViewModel,
            onLockApp = onLock
        )
    }

    composable<Route.Slideshow> {
        SlideshowScreen(
            albumId = it.toRoute<Route.Slideshow>().albumId?.fromSafeRouteArgs(),
            viewModel = galleryViewModel,
            onBack = { nav.popBackStack() }
        )
    }

    composable<Route.Vault> {
        VaultSecureScreen(
            onBack = { nav.navigateUp() },
            onNavigateToPicker = { nav.navigate(Route.Pictures) }
        )
    }

    composable<Route.MoveCopy> {
        val args = it.toRoute<Route.MoveCopy>()
        MoveCopyScreen(
            operationMode = if (args.mode == "COPY") OperationMode.COPY else OperationMode.MOVE,
            selectedMediaIds = args.ids.split(",").mapNotNull { id -> id.toLongOrNull() },
            sourceAlbumId = args.sourceAlbumId?.fromSafeRouteArgs(),
            onBack = { nav.popBackStack() },
            onOperationComplete = { nav.popBackStack() }
        )
    }

    // Notice we support "Deep Links" here. If another app asks to play a video using GalleryBox, it routes here directly!
    composable<Route.VideoPlayer>(deepLinks = listOf(navDeepLink<Route.VideoPlayer>(basePath = "gallerybox://video"))) {
        val args = it.toRoute<Route.VideoPlayer>()
        val decodedUri = args.uri.fromSafeRouteArgs()

        VideoPlayerScreen(
            initialVideoUrl = decodedUri,
            viewModel = galleryViewModel,
            onBackPress = { nav.popBackStack() },
            onLockApp = onLock
        )
    }
}

/**
 * =========================================================================================
 * 🔘 MENU COMPONENT 1: THE BOTTOM BAR (For Normal Phones)
 * =========================================================================================
 * Your exact custom Bottom Bar implementation with perfectly rounded corners.
 */
@Composable
fun BottomNavigationBar(
    tabs: List<BottomTab>,
    currentDest: NavDestination?,
    nav: NavHostController
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // Ensures background extends behind system nav bar fixing clipping white area
                .padding(horizontal = 12.dp)
                .padding(top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = currentDest?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 36.dp)
                        ) {
                            if (!selected) {
                                nav.navigate(tab.route) {
                                    // Pop up to start to prevent massive back-stacks of switching tabs
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

/**
 * =========================================================================================
 * 🔘 MENU COMPONENT 2: THE SIDE RAIL (For Foldables & Small Tablets)
 * =========================================================================================
 * Uses official Material 3 Side Rail. Puts buttons vertically on the left edge.
 */
@Composable
fun AppSideNavigationRail(
    tabs: List<BottomTab>,
    currentDest: NavDestination?,
    nav: NavHostController
) {
    NavigationRail {
        Spacer(Modifier.weight(1f)) // Push items to center
        tabs.forEach { tab ->
            val selected = currentDest?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
            NavigationRailItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.label) },
                label = { Text(tab.label) }
            )
        }
        Spacer(Modifier.weight(1f)) // Push items to center
    }
}

/**
 * =========================================================================================
 * 🔘 MENU COMPONENT 3: THE DESKTOP DRAWER (For Big Tablets & Desktop Monitors)
 * =========================================================================================
 * A permanently open menu on the left side of a giant screen.
 */
@Composable
fun AppDesktopDrawer(
    tabs: List<BottomTab>,
    currentDest: NavDestination?,
    nav: NavHostController
) {
    PermanentDrawerSheet(modifier = Modifier.width(240.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "GalleryBox",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        tabs.forEach { tab ->
            val selected = currentDest?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
            NavigationDrawerItem(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.label) },
                label = { Text(tab.label) }
            )
        }
    }
}

/**
 * Simple helper to safely open the phone's default Camera app.
 */
fun safeLaunchCamera(context: Context) {
    try {
        context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to launch camera", Toast.LENGTH_SHORT).show()
    }
}