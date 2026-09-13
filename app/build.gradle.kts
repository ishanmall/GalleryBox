// =======================================================
// --- THE APP RECIPE BOOK (build.gradle.kts) ---
// Think of this file as the master blueprint or recipe for your app.
// When you hit "Run", Android Studio's factory (called Gradle) reads this file
// to know exactly what tools to download, what rules to follow, and what Android versions to support.
// =======================================================

// --- PLUGINS (The Toolkits) ---
// Plugins are like giving special toolbelts to the factory workers.
// They add massive capabilities to the build process with just one line of code.
plugins {
    id("com.android.application") // Tells the factory: "We are building an Android App (not a library or a website)."
    id("org.jetbrains.kotlin.android") // Gives the factory the ability to read and compile Kotlin code.
    id("org.jetbrains.kotlin.plugin.compose") // Enables modern UI drawing (Jetpack Compose).
    id("com.google.devtools.ksp") // A robot that reads your code and writes repetitive boilerplate code for you automatically.
    id("com.google.dagger.hilt.android") // The "Tool Manager". It automatically hands the right databases and engines to the right screens.
    id("com.google.gms.google-services") // Connects the app to Google's Cloud (Firebase).
    id("kotlin-parcelize") // A tool that helps pack up complex data into tiny boxes so it can be passed between screens.
    id("org.jetbrains.kotlin.plugin.serialization") // A translator that converts raw internet data (JSON) into Kotlin objects.
    id("com.google.android.gms.oss-licenses-plugin") // Automatically generates a legal page showing the licenses of all the tools we use.
}

// --- WORKSPACE CLEANUP ---
// Sometimes, downloading hundreds of tools brings in old, broken, or duplicate pieces.
// This tells the factory to immediately throw away specific old tools so they don't cause crashes.
configurations.all {
    exclude(group = "androidx.legacy", module = "legacy-support-v4") // Throw away the ancient Android Support library
    exclude(group = "stax", module = "stax-api") // Throw away an old data-reading tool that clashes with modern ones
}

// --- ANDROID SPECIFICATIONS ---
// The main rules for how the app should be built.
android {
    // The internal identifying name of your app.
    namespace = "com.gallerybox"

    // The version of the Android Operating System we are using to compile the code. 36 is very modern.
    compileSdk = 36

    defaultConfig {
        // The unique global ID for your app on the Google Play Store. No other app in the world can have this ID.
        applicationId = "com.gallerybox"

        // The oldest phone allowed to install this app.
        // SDK 31 means the user MUST have at least Android 12 to use GalleryBox.
        minSdk = 31

        // The version of Android this app was perfectly designed and tested for.
        targetSdk = 36

        // Internal tracker: Every time you update the app on the Play Store, this number MUST go up by 1.
        versionCode = 95

        // The version number the user actually sees (e.g., "Version 95.0").
        versionName = "95.0"

        // The robot that runs automatic tests on the app to make sure it doesn't crash.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Allows us to use scalable graphic icons (Vectors) without them looking pixelated on older phones.
        vectorDrawables {
            useSupportLibrary = true
        }

        // If our app gets too huge (over 65,000 methods), this allows it to split into multiple code files.
        multiDexEnabled = true

        // Tells the app to only pack English text. If we download a tool that has Spanish or French text, throw it away to save space.
        resConfigs("en")

        // Advanced Settings for C++ code (NDK).
        // We set memory "page sizes" to 16KB, which is a strict new requirement for modern Android 15 phones to manage memory efficiently.
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-Wl,-z,max-page-size=16384")
            }
            ndkBuild {
                arguments += listOf("APP_LDFLAGS+=-Wl,-z,max-page-size=16384")
            }
        }
    }

    // --- APP SPLITTING (The Tailor) ---
    // Instead of giving everyone a giant app that contains code for EVERY type of phone processor,
    // we split the app. If someone has an "arm64" processor, the Play Store only gives them the "arm64" code.
    // This makes the app download size much, much smaller.
    splits {
        abi {
            isEnable = true // Turn on splitting
            reset()
            include(
                "arm64-v8a", // Modern 64-bit phones (almost all phones today)
                "armeabi-v7a" // Older 32-bit phones
            )
            isUniversalApk = false // Don't make a giant "one-size-fits-all" app.
        }
    }

    // --- BUILD TYPES ---
    // Rules for when we are testing vs when we release to the public.
    buildTypes {
        debug {
            // When we are just testing the app on our computer (Debug), don't bother splitting the app,
            // because it takes too long to build. Just build it fast.
            splits.abi.isEnable = false
        }
        release {
            // When building the final app for the Play Store (Release)...
            isMinifyEnabled = true // Turn on the Shrink Ray! Delete any code we aren't actually using.
            isShrinkResources = true // Throw away any images or icons we aren't actually using.

            // Proguard is a Code Scrambler. It renames your variables from "password" to "a",
            // making it extremely difficult for hackers to reverse-engineer your app.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Tells the factory that we are writing code using Java 17 features.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Tells the Kotlin translator to aim for Java 17 as well.
    kotlinOptions {
        jvmTarget = "17"
    }

    // Turn on special modern Android features.
    buildFeatures {
        compose = true // Jetpack Compose (The modern way to draw UI buttons and text)
        buildConfig = true // Allows the app to know if it is running in "Debug" or "Release" mode
    }

    // --- PACKAGING RULES ---
    // When the factory crams all your files into the final ZIP file (APK), sometimes multiple tools
    // try to include a file with the exact same name (like "LICENSE.txt"). The factory panics and crashes.
    // This section tells the factory: "Just ignore those text files, throw them out."
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/maven/**",
                "META-INF/proguard/**"
            )
        }
        // Tells the factory what to do with native C++ engine files (.so files).
        // "pickFirsts" means if two tools try to provide the exact same C++ file, just use the first one you find and don't crash.
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf(
                "lib/**/libc++_shared.so",
                "lib/**/libamplituda-native-lib.so",
                "lib/**/libmodpdfium.so",
                "lib/**/libjniPdfium.so"
            )
        }
    }
}

// =======================================================
// --- DEPENDENCIES (The Shopping List) ---
// =======================================================
// This is where we tell the factory to go out to the internet and download
// pre-built "LEGO Blocks" made by Google or other smart developers.
// We use these blocks so we don't have to invent things like "Video Players" from scratch.
dependencies {
    // --- Core Android & Window ---
    // Basic life-support tools for the Android operating system.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1") // The smooth loading screen when the app opens
    implementation("androidx.window:window:1.3.0") // Helps the app know if it's running on a foldable phone
    implementation(libs.androidx.benchmark.common)

    // Used for dynamic UI features from a shared catalog
    implementation(libs.androidx.compose.remote.creation.core)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui)
    implementation(libs.firebase.database)

    // --- KotlinX & Serialization ---
    // "Coroutines" are background warehouse workers. They do heavy lifting (like loading videos) without freezing the screen.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // Tool to convert raw internet text into structured data
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // --- Auth & Credentials ---
    // Tools to let users sign in with their Google accounts securely.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Military-grade encryption tools for the Secure Vault.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Lifecycle ---
    // "Lifecycle" tools make sure that if the user rotates their phone, or puts the app in the background,
    // the app doesn't forget what it was doing and crash.
    val lifecycleVersion = "2.8.7"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion") // The "Managers" of the screens
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // --- Jetpack Compose BoM (Bill of Materials) ---
    // A master list that guarantees all our UI drawing tools are the exact same compatible version.
    implementation(platform(libs.androidx.compose.bom))

    // --- Jetpack Compose UI ---
    // The actual paintbrushes used to draw the app.
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview") // Lets us preview the screen on our computer without running the app
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Compose Foundation & Material ---
    // Pre-built Google Material Design buttons, sliders, and icons.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.compose.material.icons)

    // --- Compose Animation & Lottie ---
    // Tools to make buttons bounce, screens slide, and play Adobe AfterEffects animations (Lottie).
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // --- Navigation & Architecture ---
    // Tools to move the user from Screen A to Screen B.
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt is the "Tool Manager". It builds engines (like databases) and hands them to the screens automatically.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler) // The robot that writes the Hilt wiring code for us.
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Third-party tools for reading Word Documents, Excel Spreadsheets, and web pages (HTML).
    implementation("org.apache.poi:poi-scratchpad:5.2.5")
    implementation("com.opencsv:opencsv:5.9")
    implementation("org.jsoup:jsoup:1.18.1")

    // --- CameraX ---
    // Google's tool to easily open the phone's camera, take photos, and record video.
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion") // Gives access to Night Mode / Portrait Mode

    // --- Media & Graphics ---
    implementation("androidx.tracing:tracing:1.2.0") // Tool to measure how fast the app runs
    implementation("androidx.graphics:graphics-core:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.compose.material:material-icons-extended") // Extra Google icons

    // --- Media3 (ExoPlayer) ---
    // The absolute best Video and Audio engine available for Android. Made by Google.
    val media3Version = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version") // The actual player engine
    implementation("androidx.media3:media3-ui:$media3Version") // The play/pause buttons and sliders
    implementation("androidx.media3:media3-session:$media3Version") // Connects the music to the phone's lock screen
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version") // Video filters (cropping, rotating, color)
    implementation("androidx.media3:media3-transformer:$media3Version") // Engine to permanently save edited videos to the hard drive
    implementation("androidx.media3:media3-exoplayer-rtsp:${media3Version}") // Tool to stream live security camera footage

    implementation("com.google.android.gms:play-services-oss-licenses:17.3.0")

    // --- Coil & Image Processing ---
    // Coil is the "Image Loader". It grabs photos from the hard drive or internet and displays them instantly without freezing the app.
    val coilVersion = "2.7.0"
    implementation("io.coil-kt:coil-compose:$coilVersion")
    implementation("io.coil-kt:coil-video:$coilVersion") // Allows Coil to pull a picture frame out of a video
    implementation("io.coil-kt:coil-gif:$coilVersion") // Allows Coil to play moving GIFs
    implementation("me.saket.telephoto:zoomable-image-coil:0.14.0") // Tool that lets the user pinch-to-zoom into photos
    implementation("androidx.palette:palette-ktx:1.0.0") // Tool that looks at a photo and finds its most dominant colors
    implementation("androidx.exifinterface:exifinterface:1.3.7") // Tool to read the hidden GPS/Camera data saved inside photos
    implementation("io.coil-kt:coil-svg:2.7.0") // Allows Coil to draw vector stickers

    implementation("org.apache.poi:poi-ooxml:5.2.5") // More document reading tools
    implementation("androidx.documentfile:documentfile:1.1.0") // Helps the app talk to SD Cards safely
    implementation("androidx.pdf:pdf-viewer:1.0.0-alpha15") // Tool to display PDF files

    // AndroidSVG
    implementation("com.caverock:androidsvg-aar:1.4") // Engine for drawing sharp vector sticker files

    // --- Firebase ---
    // Google's Cloud platform.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0")) // The master version list
    implementation("com.google.firebase:firebase-analytics") // Tracks anonymous data like "Which screen is used the most?"
    implementation("com.google.firebase:firebase-auth") // Cloud login system

    // --- Room ---
    // Room is our local Database manager. It builds SQL tables to save our stories, favorites, and trash bin lists right on the phone.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler) // The robot that writes the actual SQL database code for us

    // --- Utilities & Network ---
    implementation("androidx.biometric:biometric:1.2.0-alpha05") // Allows the app to scan the user's Fingerprint or Face ID
    implementation("androidx.work:work-runtime-ktx:2.10.0") // WorkManager: Schedules jobs (like emptying the trash) to run even if the app is closed
    implementation("androidx.datastore:datastore-preferences:1.1.1") // A modern digital notebook for saving user settings

    // Retrofit is a tool for talking to servers on the internet (e.g., pulling live internet radio stations).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0") // Translates internet text into Kotlin
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0") // Prints internet network errors to the developer console
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("com.google.accompanist:accompanist-permissions:0.36.0") // A helper for asking the user for privacy permissions (like Camera access)
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6") // Tool that lets the user drag-and-drop items in a list
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0") // Allows downloading parts of the app later from the Play Store

    // --- Markdown & Archives ---
    implementation("org.commonmark:commonmark:0.22.0") // Tool to format text beautifully with bold/italics
    implementation("org.apache.commons:commons-compress:1.26.2") // Tool to open ZIP files
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7") // Prevents accidental data changes

    // --- Paging 3 (The Waiter) ---
    // If you have 50,000 photos, loading them all crashes the phone. Paging acts like a waiter, bringing you exactly 50 photos at a time as you scroll.
    val pagingVersion = "3.4.2"
    implementation("androidx.paging:paging-runtime:$pagingVersion")
    implementation("androidx.paging:paging-compose:$pagingVersion")
    implementation("androidx.paging:paging-common:$pagingVersion")

    // Extra tools for ExoPlayer to allow it to stream live internet TV formats
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.4.1")

    // --- Testing ---
    // These tools are used by developers or robots to automatically click buttons in the app and make sure nothing crashes.
    // They are NOT included in the final app that users download from the store.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}