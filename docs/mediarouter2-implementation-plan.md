# MediaRouter2 Integration Plan

## Background

centuryplay discovers AirPlay speakers via mDNS (JmDNS) and renders them in a `RecyclerView`.
Selecting a speaker starts `AudioCaptureService`, which captures system audio with `MediaProjection`
and streams it over RAOP.

MediaRouter2 is Android's standard control-plane for presenting audio/video output destinations as
first-class system routes (accessible from the Cast button, Pixel Output Switcher, etc.).
The transport layer stays exactly as-is: RAOP via `AudioCaptureService`.

---

## Design Principles

- **Thin adapter only.** No changes to `DiscoveryRepository`, `AirPlayDiscovery`, `RaopClient`, or
  `AudioCaptureService` internals. MediaRouter2 is wired on top.
- **Discovery stays in JmDNS.** `DiscoveryRepository` remains the single source of truth; route
  additions/removals mirror its `devices` StateFlow.
- **MediaProjection consent is still required.** Route selection cannot start the service directly;
  it must go through the existing permission flow (permissions → `createScreenCaptureIntent()`).
- **FairPlay guard unchanged.** `RaopCapabilities.requiresUnsupportedFairPlay()` is still called
  before launching the projection request.
- **minSdk 29 is already satisfied.** `MediaRouter2` requires API 30; minSdk can be bumped to 30
  (was already effectively 30 due to `AudioPlaybackCaptureConfiguration` needing Android 10).

---

## Open Questions

> [!IMPORTANT]
> **minSdk bump:** The plan bumps minSdk from 29 → 30. Android 10 (API 29) and Android 11 (API 30)
> differ only by a few edge-case AudioPlaybackCapture behaviors that this app doesn't rely on.
> Confirm this is acceptable before execution, or we guard MediaRouter2 code behind `Build.VERSION.SDK_INT >= 30`.

> [!NOTE]
> **Route provider vs. RoutePublisherService:** `MediaRouter2` routes can be published either
> in-process (via a `MediaRouter2` instance held in a long-lived object) or out-of-process via a
> `MediaRoute2ProviderService`. The in-process approach requires the app process to be alive; since
> `DiscoveryRepository` already keeps JmDNS alive, this is fine and avoids IPC overhead. However,
> the system-level route picker (Output Switcher) requires `MediaRoute2ProviderService` to work
> outside the app's process — so we use the service approach.

---

## Proposed Changes

### Phase 1 — Dependencies & Manifest

#### [MODIFY] [app/build.gradle.kts](file:///Users/g8row/Documents/centuryplay/app/build.gradle.kts)

- Bump `minSdk` from `29` → `30`.
- `MediaRouter2` is in the platform SDK at API 30+; no extra Maven dependency needed for the core API.

#### [MODIFY] [app/src/main/AndroidManifest.xml](file:///Users/g8row/Documents/centuryplay/app/src/main/AndroidManifest.xml)

- Register `AirPlayRouteProvider` as a service (see Phase 2).
- Register `MediaProjectionConsentActivity` (see Phase 3).

---

### Phase 2 — Route Provider

#### [NEW] `app/src/main/java/com/airplay/streamer/router/AirPlayRouteProvider.kt`

Extends `MediaRoute2ProviderService` (API 30+). Responsibilities:

1. **Collects `DiscoveryRepository.devices`** StateFlow via a coroutine job.
2. **Converts each eligible `AirPlayDevice`** (RAOP-capable, non-FairPlay-only) into a
   `MediaRoute2Info`:
   - `id` → `"${device.host}:${device.raopPort ?: device.port}"` (stable, collision-free)
   - `name` → `device.displayName`
   - `features` → `listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO)`
   - `connectionState` → reflects `AudioCaptureService` streaming state
3. **Calls `notifyRoutes()`** when the device list changes.
4. **`onDiscoveryPreferenceChanged()`** → calls `DiscoveryRepository.startDiscovery()` /
   `stopDiscovery()` as observers arrive/leave.
5. **`onSelectRoute()`** → launches `MediaProjectionConsentActivity` with the target device as
   an extra. Cannot start the service directly because `MediaProjection` consent requires an
   `Activity`.
6. **`onDeselectRoute()`** → fires `AudioCaptureService.ACTION_STOP`.
7. **`onTransferRoute()`** → stop current session, start new one for the new device.
8. Collects `AudioCaptureService.streamingState` (new companion `StateFlow` — see Phase 4) to
   keep route `connectionState` in sync.

```kotlin
// Skeleton (illustrative only — full code written during execution)
@RequiresApi(30)
class AirPlayRouteProvider : MediaRoute2ProviderService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var devicesJob: Job? = null

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        if (preference.preferredFeatures.contains(MediaRoute2Info.FEATURE_LIVE_AUDIO)) {
            DiscoveryRepository.getInstance(this).startDiscovery()
            startWatchingDevices()
        } else {
            stopWatchingDevices()
            DiscoveryRepository.getInstance(this).stopDiscovery()
        }
    }

    private fun startWatchingDevices() {
        devicesJob?.cancel()
        devicesJob = serviceScope.launch {
            DiscoveryRepository.getInstance(this@AirPlayRouteProvider).devices.collect { devices ->
                val routes = devices
                    .filter { !RaopCapabilities.requiresUnsupportedFairPlay(it.features) }
                    .map { device ->
                        MediaRoute2Info.Builder(
                            "${device.host}:${device.raopPort ?: device.port}",
                            device.displayName
                        )
                            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                            .build()
                    }
                notifyRoutes(routes)
            }
        }
    }

    private fun stopWatchingDevices() {
        devicesJob?.cancel()
        devicesJob = null
    }

    override fun onSelectRoute(sessionHint: RoutingSessionInfo, route: MediaRoute2Info, transferReason: Int) {
        val intent = Intent(this, MediaProjectionConsentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ROUTE_ID, route.id)
        }
        startActivity(intent)
    }

    override fun onDeselectRoute(sessionId: String, route: MediaRoute2Info) {
        startService(Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        })
        notifySessionReleased(sessionId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
```

**Manifest entry:**
```xml
<service
    android:name=".router.AirPlayRouteProvider"
    android:exported="true"
    android:permission="android.permission.BIND_ROUTE_PROVIDER">
    <intent-filter>
        <action android:name="android.media.MediaRoute2ProviderService" />
    </intent-filter>
</service>
```

---

### Phase 3 — MediaProjection Consent Bridge

`MediaProjection` consent must originate from a user-visible `Activity`; a `Service` cannot call
`createScreenCaptureIntent()` directly.

#### [NEW] `app/src/main/java/com/airplay/streamer/router/MediaProjectionConsentActivity.kt`

A minimal transparent `Activity` (reuses `Theme.AirPlayStreamer.Dialog`) that:

1. Receives target route id as an extra; resolves the `AirPlayDevice` from `DiscoveryRepository`.
2. Checks `RECORD_AUDIO` / `POST_NOTIFICATIONS`; requests them if missing.
3. Launches `createScreenCaptureIntent()`.
4. On result `OK` → calls `startForegroundService(AudioCaptureService.ACTION_START)` with device
   extras and notifies `AirPlayRouteProvider` to call `notifySessionCreated()`.
5. On result `CANCEL` → route selection fails silently (no session created).
6. Finishes immediately after dispatching.

> [!NOTE]
> The existing `TileDeviceActivity` and `MainActivity` flows are **not changed**. This new activity
> is only triggered via the MediaRouter2 path.

**Manifest entry:**
```xml
<activity
    android:name=".router.MediaProjectionConsentActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:theme="@style/Theme.AirPlayStreamer.Dialog" />
```

---

### Phase 4 — Streaming State Broadcast

`AirPlayRouteProvider` needs to know when `AudioCaptureService` starts/stops (and which device id)
to set the route `connectionState` correctly in the system UI.

#### [MODIFY] [AudioCaptureService.kt](file:///Users/g8row/Documents/centuryplay/app/src/main/java/com/airplay/streamer/service/AudioCaptureService.kt)

Add a companion `MutableStateFlow`:

```kotlin
companion object {
    // existing …
    data class StreamingState(val isStreaming: Boolean, val deviceId: String? = null)
    val streamingState = MutableStateFlow(StreamingState(false))
}
```

Emit on `startCapture()` (after RAOP connects) and `stopCapture()`.
The existing `onStateChanged` callback is preserved for `MainActivity`.

`AirPlayRouteProvider` collects `AudioCaptureService.streamingState` and calls
`notifySessionInfoChanged()` to reflect CONNECTING → CONNECTED → DISCONNECTED states.

---

### Phase 5 — DiscoveryRepository Lifecycle (no code changes needed)

The existing reference-counted `startDiscovery()` / `stopDiscovery()` pattern already handles
multiple callers. `AirPlayRouteProvider.onDiscoveryPreferenceChanged()` simply calls these methods
the same way `AirPlayTileService.onStartListening()` / `onStopListening()` do today.

---

### Phase 6 — (Optional / Deferred) MediaRouteButton in Main UI

If desired, a `MediaRouteButton` can be added to `activity_main.xml` so the in-app picker also
surfaces the system route UI. This is additive and does not affect any existing code.

---

## File Summary

| File | Action | Notes |
|---|---|---|
| `app/build.gradle.kts` | Bump `minSdk` 29 → 30 | Platform MediaRouter2 requires API 30 |
| `AndroidManifest.xml` | Add 2 service + 1 activity entries | |
| `router/AirPlayRouteProvider.kt` | **NEW** | `MediaRoute2ProviderService` adapter |
| `router/MediaProjectionConsentActivity.kt` | **NEW** | Transparent consent bridge |
| `service/AudioCaptureService.kt` | Add `streamingState` companion `StateFlow` | Minimal, non-breaking |
| `discovery/DiscoveryRepository.kt` | **No changes** | |
| `discovery/AirPlayDiscovery.kt` | **No changes** | |
| `raop/RaopClient.kt` | **No changes** | |
| `MainActivity.kt` | **No changes** | In-app picker unchanged |
| `TileDeviceActivity.kt` | **No changes** | QS tile path unchanged |
| `AirPlayTileService.kt` | **No changes** | QS tile unchanged |

---

## Verification Plan

### Build
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```
Must compile cleanly with no unresolved `MediaRouter2` references.

### Install
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Functional Checks (on-device)
1. Open **Settings → Connected devices → Connection preferences → Cast** (or Output Switcher) —
   AirPlay speakers on the network should appear as routes.
2. Tap a route → consent dialog appears → after approval, streaming begins.
3. Pull-down notification shows streaming route chip.
4. Deselect the route → streaming stops.
5. While streaming, tap a different route → seamless switch.
6. FairPlay-only device (e.g. Samsung AirScreen) → must **not** appear as a route.

### Log Verification
```bash
adb logcat | rg 'RaopClient|AudioCaptureService|AirPlayRouteProvider|centuryplay'
```

### What Stays Unchanged
- All existing in-app discovery and selection flows
- Quick Settings tile
- `RaopClient` transport
- FairPlay filtering logic
