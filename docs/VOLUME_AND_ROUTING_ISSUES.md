# Volume & Routing Issues — Investigation Notes

## Issue 1: Quick Settings shows "audio streaming on this phone" instead of Century

### Observed behaviour
After connecting to a route, the Android quick settings media player shows local playback
("audio streaming on this phone") even though audio is actually being sent to Century via RAOP.
The volume panel correctly shows "audio will play on Century" because the MediaRouter2 session
exists — but the old MediaRouter API wins for quick settings display.

### Root cause
`onCreateSession` launches `MediaProjectionConsentActivity`. That activity stealing focus triggers
`MediaRouter.onRestoreRoute()` ~780ms after session creation, reverting the legacy MediaRouter
route back to Phone. MediaRouter2 session is unaffected, creating the split state.

### Key log evidence
```
03:19:41.351  onSessionCreated → session created for Century ✓
03:19:41.367  MediaRouter: Selecting route: Group (Century)
03:19:42.130  MediaRouter: onRestoreRoute() → route=Phone ← revert (780ms later)
```

### Fix
Implement persistent MediaProjection token (keep AudioCaptureService alive between sessions,
don't call `mediaProjection.stop()` between connections). No consent activity on reconnect means
no focus steal, no revert. Consent dialog only appears once per service lifecycle.

---

## Issue 2: System volume slider snaps back to 80 after ~1 second

### Observed behaviour
The volume slider appears correctly in the volume panel (MediaSession + VolumeProvider working).
Dragging the slider updates volume smoothly (80 → 74 → 68 → ... → 43) but exactly 1 second
after the last change, it snaps back to 80. RAOP volume does not change on the speaker.
Volume keys dispatch to Apple Music's session, not ours.

### Key log evidence
```
03:35:40.568  centuryplay: 43 of 100   ← slider dragged
03:35:41.578  centuryplay: 80 of 100   ← snaps back exactly 1000ms later
03:35:42.265  centuryplay: 47 of 100   ← try again
03:35:43.268  centuryplay: 80 of 100   ← snaps back again
```
`onSetRouteVolume` and `onSetSessionVolume` log lines never appear → system is not calling them.

### Root cause (suspected)
Android's `MediaSessionRecord.VOLUME_UPDATE_TIMEOUT_MS = 1000ms`. After calling
`VolumeProvider.onSetVolumeTo(vol)`, if the system reads back a stale volume (80) within 1 second
it resets. The `notifySessionUpdated` call from `AirPlayRouteProviderService`'s volumeState
observer may be racing with the timeout, or the VolumeProvider's `setCurrentVolume` is being
overwritten before the system reads it.

`toRoute2Info()` previously hardcoded `.setVolume(80)` — fixed to use
`AudioCaptureService.volumeState.value`. Session initial volume in `onCreateSession` also fixed.
Snap-back persists despite these fixes, suggesting the timeout race is the real cause.

### What was tried
- Added `MediaSession.setPlaybackToRemote(VolumeProvider)` → slider now appears ✓
- `VolumeProvider.onSetVolumeTo` emits to `_volumeState` StateFlow
- `AirPlayRouteProviderService` observes `volumeState` and calls `notifySessionUpdated`
- Fixed hardcoded `setVolume(80)` in `toRoute2Info()` and `onCreateSession`
- Added logging to `onSetRouteVolume` / `onSetSessionVolume` — neither is called during slider drag

### Next investigation steps
- Verify `VolumeProvider.onSetVolumeTo` is actually being called (add log there)
- Check if `setCurrentVolume(vol)` is being overwritten before the 1-second timeout fires
- Consider whether `notifySessionUpdated` triggers a system callback that resets the volume
- May need to remove the `volumeState` observer loop entirely and handle volume solely through
  `VolumeProvider` ↔ RAOP, bypassing `notifySessionUpdated` for volume changes
