/**
 * =========================================================================================
 * 🔌 THE MASTER PLUGIN LIST (Root build.gradle.kts)
 * =========================================================================================
 * This block tells the Android Studio build system (Gradle) which external tools
 * the project is going to need.
 *
 * Note: "apply false" means we are downloading the tool so the whole project knows about it,
 * but we aren't turning it on right here. We will turn them on individually inside the
 * actual "app" folder when we need them.
 */
plugins {

    // --- ANDROID CORE TOOLS ---
    // The main engine required to build a standalone Android application (an APK or App Bundle).
    id("com.android.application") version "8.9.1" apply false

    // The engine used to build an Android "Library" (a piece of code or module that the main app uses).
    id("com.android.library") version "8.9.1" apply false


    // --- KOTLIN LANGUAGE TOOLS ---
    // Tells the build system how to read, understand, and compile Kotlin code into an Android app.
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false

    // The magic compiler that makes Jetpack Compose (the modern way to build Android UI) work seamlessly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false

    // A tool that automatically converts your Kotlin data classes into text/JSON (and vice versa).
    // Extremely useful for saving data to the phone or sending it to a web server.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false


    // --- CODE GENERATION ---
    // KSP (Kotlin Symbol Processing). A super-fast tool that reads your code and automatically
    // writes repetitive "boilerplate" code for you. (Used heavily by Room Databases and Hilt).
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false


    // --- APP ARCHITECTURE & SERVICES ---
    // Hilt (Dependency Injection). This handles the complex "invisible wiring" of your app,
    // making sure different parts (like ViewModels and Databases) can talk to each other easily.
    id("com.google.dagger.hilt.android") version "2.57" apply false

    // Google Services. This links your app to Google's backend servers, which is required
    // if you want to use Firebase, Google Analytics, or Crashlytics.
    id("com.google.gms.google-services") version "4.4.2" apply false


    // --- LEGAL & COMPLIANCE ---
    // A handy tool that automatically finds all the free/open-source libraries your app uses
    // and generates a "Licenses" screen for you so you don't get sued or kicked off the Play Store.
    id("com.google.android.gms.oss-licenses-plugin") version "0.13.0" apply false

}