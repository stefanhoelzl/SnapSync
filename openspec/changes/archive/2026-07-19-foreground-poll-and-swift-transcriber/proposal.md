# Foreground poll · Swift transcriber · ProtectedData never created

## Why

Migration step 12 (`test/architecture/migration/PLAN.md`) — the last big behavior step. Three
coupled changes, each sanctioned by a settled device-session forcing proof:

1. **The cross-process Darwin liveness ding is replaced by a foreground-gated poll.** The ding
   needed a `CFNotificationCenter` observer, a `staticCFunction` C bridge, a per-foreground
   register/unregister choreography, and a shared notification-name constant — all shell-resident,
   all untestable — to deliver one bit ("re-read the truth") that a 2-second local `aggregates()`
   read delivers with no cross-process channel at all, and without ever missing a signal.

2. **The `ProtectedData` port is never created; `:domain:keychain` dies** (module distance 4→3).
   Settled proof ④: **zero** `deferring` / `running deferred` lines across all production logs —
   the defer-and-resume queue was dead code. Its live residue (the unlock-hook config-StateFlow
   repair) is replaced by a **trigger-time membership re-read**: every OS-callback flow re-reads
   the persisted config before acting, which is strictly wider coverage than the unlock hook (it
   also heals cross-process staleness the unlock never signalled).

3. **Swift becomes a pure transcriber.** Every Swift decision keyword is burned down to zero:
   the silent push forwards `userInfo` whole, the Universal-Link `NSUserActivity` forwards whole,
   the scene-phase split moves to Kotlin-side `NSNotificationCenter` observation, and the
   extension's `process()` result — settled proof ①: `PHBackgroundResourceUploadProcessingResult`
   is Swift-only but `RawRepresentable` over `Int` — is decided in Kotlin as a tested raw-Int
   mapping and merely **constructed** in Swift (`init?(rawValue:)` + `?? .failure`, the one
   remaining pinned occurrence). Swift shell decisions 4 → 0 keywords, 1 pinned `??`.

## What changes

- `:domain` `feature/status` gains `LedgerCountsPoller` (cadence 2 s — the spec's staleness
  bound); the Foreground flow starts it, the Background flow stops it; the extension's ding-post,
  the app's Darwin observer + C bridge, and `UPLOAD_LIVENESS_DARWIN_NAME` are deleted.
- `:domain:keychain` (ProtectedDataGate/ProtectedDataAvailability) and `IosProtectedData` are
  deleted; `AppPorts.protectedDataGate` and `unregisterLiveness` are replaced by
  `AppPorts.reloadConfig`; the SilentPush/DownloadBackstop/Foreground flows re-read the membership
  first; `FileBackedConfigStore.reload()` retains the last good value on an unreadable read
  (pure `configAfterReload`).
- Swift shells: `iOSApp.swift` loses both `guard`s and the scenePhase `if` (payload codec
  `pushEventId`, activity codec `eventLinkFromUserActivity`/`forwardEventLink`, lifecycle
  observers installed by `SnapSyncRoot.onLaunch()`); `BackgroundUploadExtension.swift` loses its
  `switch` (`CycleResult.processingResultRawValue()` in `ports/`, pinned by commonTest).
  `SwiftShellGuardTest` counts `??` (per the spec's existing keyword list) and pins the table at
  one `??` in the extension shell; `EventLinkDeliveryTest` asserts the whole-activity forward.

## Impact

- Specs: `sync-status` (poll + latency bound), `ios-app-shell` (poll lifecycle · transcriber
  contract · protected-data posture), `ios-photokit-upload` (liveness requirement removed ·
  raw-value construction), `architecture-guards` (delivery-seam guard marker),
  `upload-lifecycle` / `photo-download` / `event-link` / `push-registration` (unlock-hook and
  gate-ceremony references re-pointed at the trigger-time re-read).
- Modules: `:domain:keychain` deleted (beacon module distance 4→3; beacon total 22→17).
- Device verification: **Session D before merge; soak after** (poll latency, transcriber flows,
  lifecycle transitions, the zero-resume prediction, and the ① raw values against the SDK).
