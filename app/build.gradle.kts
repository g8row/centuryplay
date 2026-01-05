plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airplay.streamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airplay.streamer"
        minSdk = 29  // Android 10+ required for AudioPlaybackCapture
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
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
    
    // jmDNS for AirPlay discovery (mDNS/Bonjour)
    implementation("org.jmdns:jmdns:3.5.9")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // RecyclerView for speaker list
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Bouncy Castle for SRP-6a crypto (AirPlay 2 Pairing)
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
}
