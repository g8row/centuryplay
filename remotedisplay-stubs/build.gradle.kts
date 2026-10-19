// Compile-only stubs for the platform's com.android.media.remotedisplay shared library
// (frameworks/base/media/lib/remotedisplay). The real classes come from the device at runtime
// through <uses-library>; constants must match the platform since they are inlined.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.android.media.remotedisplay"
    compileSdk = 35
    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
