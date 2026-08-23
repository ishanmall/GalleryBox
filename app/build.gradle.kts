plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.android.gms.oss-licenses-plugin")
}

configurations.all {
    exclude(group = "androidx.legacy", module = "legacy-support-v4")
    exclude(group = "stax", module = "stax-api")
}

android {
    namespace = "com.gallerybox"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.gallerybox"
        minSdk = 31
        targetSdk = 36
        versionCode = 90

        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true
        resConfigs("en")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-Wl,-z,max-page-size=16384")
            }
            ndkBuild {
                arguments += listOf("APP_LDFLAGS+=-Wl,-z,max-page-size=16384")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(
                "arm64-v8a",
                "armeabi-v7a"
            )
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            splits.abi.isEnable = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
// --- DEPENDENCIES ---
// =======================================================
dependencies {
    // Core Android & Window
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.3.0")

    // Version Catalog (libs) imports
    implementation(libs.androidx.compose.remote.creation.core)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui)
    implementation(libs.firebase.database)


    // KotlinX & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Auth & Credentials
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Lifecycle
    val lifecycleVersion = "2.8.7"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Jetpack Compose BoM
    implementation(platform(libs.androidx.compose.bom))

    // Jetpack Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Compose Foundation & Material
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.compose.material.icons)

    // Compose Animation & Lottie
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // Navigation & Architecture (KSP Migrated)
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")
    implementation("com.opencsv:opencsv:5.9")
    implementation("org.jsoup:jsoup:1.18.1")

    // CameraX
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    // Media & Graphics
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("androidx.graphics:graphics-core:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.compose.material:material-icons-extended")
    // Media3
    val media3Version = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:${media3Version}")

    implementation("com.google.android.gms:play-services-oss-licenses:17.3.0")

    // Coil & Image Processing
    val coilVersion = "2.7.0"
    implementation("io.coil-kt:coil-compose:$coilVersion")
    implementation("io.coil-kt:coil-video:$coilVersion")
    implementation("io.coil-kt:coil-gif:$coilVersion")
    implementation("me.saket.telephoto:zoomable-image-coil:0.14.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    // AndroidSVG
    implementation("com.caverock:androidsvg-aar:1.4")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Utilities & Network
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")

    // Markdown & Archives
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.7")
    // Paging 3
    val pagingVersion = "3.4.2"
    implementation("androidx.paging:paging-runtime:$pagingVersion")
    implementation("androidx.paging:paging-compose:$pagingVersion")
    implementation("androidx.paging:paging-common:$pagingVersion")

    implementation("androidx.core:core-splashscreen:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}