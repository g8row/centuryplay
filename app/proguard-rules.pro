# Add project specific ProGuard rules here.
-keep class com.airplay.streamer.** { *; }

# Remove verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Keep Bouncy Castle crypto
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep jmDNS
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Provided at runtime by the device: LSPosed / Vector (xposed/) and the platform's remote
# display library (router/AirPlayCastProviderService).
-dontwarn de.robv.android.xposed.**
-dontwarn com.android.media.remotedisplay.**
