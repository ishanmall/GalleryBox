/**
 * =========================================================================================
 * ⚙️ PROJECT SETTINGS & "APP STORES" FOR CODE (settings.gradle.kts)
 * =========================================================================================
 */

// -----------------------------------------------------------------------------------------
// 1. PLUGIN MANAGEMENT (Where to find the building tools)
// This section tells Android Studio where to download the background tools (plugins)
// needed to compile and build your app (like the Kotlin compiler or Android build tools).
// -----------------------------------------------------------------------------------------
pluginManagement {
    repositories {
        google()                // Google's official tool repository
        mavenCentral()          // The biggest global repository for Java/Kotlin tools
        gradlePluginPortal()    // The official store for specific Gradle build plugins
    }
}

// -----------------------------------------------------------------------------------------
// 2. GLOBAL PLUGINS
// -----------------------------------------------------------------------------------------
plugins {
    // This handy plugin automatically downloads the correct version of Java (JDK)
    // needed to build your app, so anyone who opens your code doesn't have to install it manually.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// -----------------------------------------------------------------------------------------
// 3. DEPENDENCY RESOLUTION (Where to find the app's features/libraries)
// This section tells your app where to download the actual open-source libraries
// you use in your code (like Coil for images, ExoPlayer for video, or Room for databases).
// -----------------------------------------------------------------------------------------
dependencyResolutionManagement {
    // This strict rule says: "Only use the internet links listed right here."
    // It prevents random modules inside the app from downloading code from untrusted places.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // The official libraries made by Google (Jetpack Compose, AndroidX, etc.)
        google()

        // The standard global library for most open-source Java/Kotlin code
        mavenCentral()

        // A popular service that turns standard GitHub projects directly into usable app libraries
        maven { url = uri("https://jitpack.io") }

        // The official server for VLC media player code (used for advanced video playback)
        maven { url = uri("https://download.videolan.org/pub/videolan/maven/") }

        // A specific third-party server hosting specialized open-source tools your app needs
        maven { url = uri("https://andob.io/repository/open_source") }
    }
}

// -----------------------------------------------------------------------------------------
// 4. PROJECT STRUCTURE
// -----------------------------------------------------------------------------------------

// The master name of your entire project workspace
rootProject.name = "GalleryBox"

// Tells the build system to look inside the folder named "app".
// This is where all your actual Android UI, logic, and screen code lives.
include(":app")