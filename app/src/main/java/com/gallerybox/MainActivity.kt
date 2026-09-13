// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling a strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("UnsafeOptInUsageError", "OPT_IN_USAGE")

package com.gallerybox

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need for this specific file,
// such as tools for asking for permissions, drawing the screen, and checking the Android version.
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.gallerybox.navigation.GalleryNavHost
import com.gallerybox.ui.theme.GalleryBoxTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint

/**
 * --- THE FRONT DOOR (MainActivity) ---
 * Every Android app has a "Main Activity". It is the very first physical screen that is
 * created when a user taps your app icon on their home screen.
 * Think of it as the empty stage where all the other screens (like the photo grid or settings) will perform.
 *
 * `@AndroidEntryPoint` tells our helper tool (Hilt) to automatically prepare all the databases
 * and background workers we might need before the user even steps through the door.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * `onCreate` is the setup phase. It runs exactly once when the app is launched.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // This tells the app to stretch all the way to the absolute edges of the phone glass,
        // painting *behind* the top clock/battery bar and the bottom swipe-up bar for a modern look.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // `setContent` is where we actually start painting the UI (User Interface) on the blank stage.
        setContent {
            // Check if the user's phone is currently set to Dark Mode (e.g., at nighttime)
            val isDarkTheme = isSystemInDarkTheme()

            // Apply our custom colors and fonts based on whether it's Dark or Light mode
            GalleryBoxTheme(darkTheme = isDarkTheme) {
                // A 'Surface' is just a blank canvas that fills the entire screen with the correct background color.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Before we show any photos, we must pass through the Security Guard to check permissions.
                    PermissionGuard {
                        // If the guard lets us through, AND the phone is running at least Android 10 (Q),
                        // we finally load the `GalleryNavHost` (the actual photo gallery screen).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            GalleryNavHost()
                        }
                    }
                }
            }
        }
    }
}

/**
 * --- THE SECURITY GUARD (PermissionGuard) ---
 * Android is very strict about privacy. An app cannot just look at a user's photos without asking.
 * This function acts as a bouncer. If we have the keys (permissions), it lets the user in.
 * If we don't, it pauses everything and pops up a system message asking the user to click "Allow".
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGuard(content: @Composable () -> Unit) {
    // 1. Build a list of the exact permissions we need.
    // Android constantly changes its privacy rules every year, so we have to check what version of Android the phone is running.
    val permissionsToRequest = mutableListOf<String>().apply {

        // UPSIDE_DOWN_CAKE is the secret code name for Android 14.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 added a feature where a user can say "I only want to give this app access to 3 specific photos, not my whole gallery."
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) // This represents the "partial access" choice.

            // TIRAMISU is the secret code name for Android 13.
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 split the old "Storage" permission into three specific ones (Photos, Videos, Audio).
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)

            // For older phones (Android 12 and below)
        } else {
            // Older phones just have one master key for the entire hard drive.
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toList()

    // `permissionState` is our clipboard. It holds the "Allowed" or "Denied" status of every permission we just listed.
    val permissionState = rememberMultiplePermissionsState(permissionsToRequest)

    // Check our clipboard: Do we have what we need to proceed?
    val hasRequiredPermissions = permissionState.allPermissionsGranted ||
            // Special rule for Android 14: If they only gave us "partial" access, that is still good enough to proceed!
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    permissionState.permissions.any {
                        it.permission == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && it.status.isGranted
                    })

    if (hasRequiredPermissions) {
        // We have the keys! Run the `content()` block (which loads the actual Gallery app).
        content()
    } else {
        // We do NOT have the keys.
        // `LaunchedEffect` is a trigger that runs immediately. It tells Android to pop up the "Allow Gallery to access photos?" dialog.
        LaunchedEffect(Unit) {
            permissionState.launchMultiplePermissionRequest()
        }
    }
}

/**
 * --- THE RUSSIAN NESTING DOLL UNWRAPPER (Context Extension Functions) ---
 *
 * In Android, a `Context` is like a person's surroundings. A tiny button on the screen only knows its immediate surroundings (its Context).
 * Sometimes, that tiny button needs to talk to the "Main Stage" (the Activity) to do something big, but it can't see it directly.
 *
 * Android wraps Contexts inside other Contexts, like Russian nesting dolls.
 * This function unwraps the dolls one by one (`context.baseContext`) until it finds the actual `Activity` doll inside.
 */
fun Context.findActivity(): Activity? {
    var context = this
    // Keep unwrapping as long as there is another layer to unwrap
    while (context is ContextWrapper) {
        // If the layer we just unwrapped is the Activity, we found it! Return it.
        if (context is Activity) {
            return context
        }
        // Otherwise, peel off another layer and look deeper.
        context = context.baseContext
    }
    // If we unwrapped everything and didn't find an Activity, return null (nothing).
    return null
}

/**
 * Does the exact same thing as the function above, but looks specifically for a `FragmentActivity`.
 * A `FragmentActivity` is just a special type of Activity that can hold smaller sub-screens (Fragments) inside it.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}