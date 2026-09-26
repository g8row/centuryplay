plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.airplay.streamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airplay.streamer"
        minSdk = 30  // Android 11+ required for MediaRouter2 (AudioPlaybackCapture needs 10+)
        targetSdk = 35
        versionCode = 4
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
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
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    
    // MediaStyle notifications
    implementation("androidx.media:media:1.7.0")

    // AndroidX MediaRouter (required to fix Apple Music crash by providing proper MediaRoute2Info extras)
    implementation("androidx.mediarouter:mediarouter:1.6.0")

    // jmDNS for AirPlay discovery (mDNS/Bonjour)
    implementation("org.jmdns:jmdns:3.5.9")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // RecyclerView for speaker list
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Bouncy Castle for SRP-6a crypto (AirPlay 2 Pairing)
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    // dd-plist for AirPlay 2 binary plist (bplist00) RTSP bodies
    implementation("com.googlecode.plist:dd-plist:1.28")

    // Shizuku: optional privileged helper (skip consent dialogs, silent-phone capture)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
}
