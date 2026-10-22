import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.coverflow.wear"
    compileSdk = 35

    defaultConfig {
        // MUST be identical to :mobile's applicationId (Wear Data Layer requirement).
        applicationId = "com.coverflow.app"
        minSdk = 30 // Wear OS 3+ : Galaxy Watch4 and newer
        targetSdk = 34
        versionCode = 1
        // (Which round of changes this is: LiquiWatch's trace recorder, in its tools/trace, notes it.)
        versionName = "1.0-r36"
    }

    buildTypes {
        // The fast build: optimized and shrunk by R8, and signed with the same debug key as the
        // debug build (so it installs from Android Studio like it, and the phone and watch apps
        // still match, which the Wear Data Layer needs). Pick "release" in Build Variants.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear:1.3.0") // always-on (ambient) support

    implementation("androidx.core:core-ktx:1.15.0")
    // Installs the libraries' ready-made startup profiles (Compose ships them), so their code is
    // compiled ahead of time instead of interpreted at first: smoother from the first scroll.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // The album art and album colour complications the watch faces read (faces can't talk to the
    // phone). 1.3.0 hands updates straight to the watch's complication system on Wear OS 6 (see
    // face/FaceUpdates).
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
