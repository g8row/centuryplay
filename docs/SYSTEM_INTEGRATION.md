# system integration (v2.1)

How centuryplay becomes part of the OS, and what was verified on Android 17 (Redmi Pad Pro, Evolution X, KernelSU
Next + ReZygisk + Vector) against shairport-sync receivers.

## tiers

| install | capture | output switcher | cast tile |
|---------|---------|-----------------|-----------|
| plain apk | MediaProjection prompt (or adb pre-approval) | stand-in routing session per playing app | – |
| apk + shizuku | AudioPolicy reroute as shell | same | – |
| root module / rom (system mode) | AudioPolicy reroute in-process | same | remote display provider |
| + lsposed / vector | same | SettingsLib hooks: real active session, listed next to bluetooth, idle too | same |

## findings

- **reroute captures apple music.** `AudioPolicyMix` only filters `AUDIO_FLAG_NO_MEDIA_PROJECTION` /
  `NO_SYSTEM_CAPTURE` players for loopback+render mixes. A pure `ROUTE_FLAG_LOOP_BACK` mix (needs
  MODIFY_AUDIO_ROUTING) gets every matching player at 44.1 kHz stereo, so there's no need for
  `allowPrivilegedPlaybackCapture` (16 kHz mono only). Rerouted audio is recorded at full fixed volume.
- **android 17 local network.** `ACCESS_LOCAL_NETWORK` is a runtime permission; without it NSD fails with error 7.
- **android 17 volume keys.** `MediaSessionService.dispatchAdjustVolumeLocked` ignores other apps' sessions while
  the key's app is playing (b/432003816), so the remote session never gets the keys. Rerouting: the music stream
  index is parked mid-scale and every step is applied to the speakers.
- **output switcher.** SettingsLib's `InfoMediaManager.getActiveRoutingSession()` returns the system session for
  any app that plays locally, so a provider session "for" the app is listed but never shown as active. For the system
  session it offers the routes the app's own router can see (none of ours for ordinary players). The hooks fix both;
  picking a speaker then goes through `requestCreateSessionWithManager` → our provider's `onCreateSession`.
- **mirroring routes (`enable_mirroring_in_media_router_2`)** aren't usable on 17: system media session creation in
  `SystemMediaRoute2Provider2` is still a TODO that throws.
- **cast tile.** `RemoteDisplayProviderWatcher` binds providers that hold REMOTE_DISPLAY_PROVIDER (signature|privileged)
  and require BIND_REMOTE_DISPLAY; the tile, its picker/controller and Settings → Cast then list the speakers.
- **root module gotchas.**
  - root solutions hide module files from app processes (KernelSU's per-app "umount modules", denylists), which breaks
    the app itself and System UI/launcher reading its resources. The module therefore also installs the apk to /data
    as an update of the system copy: same apk, privileged grants kept, visible everywhere.
  - module files report a fixed timestamp, so the package parse cache keeps the previous manifest after an upgrade
    (new permissions aren't granted): the installer deletes that cache entry.
  - a privileged permission is only granted to an updated system app if the system copy requests it too.
  - the system copy and any installed copy must share a signing key.
