## Why

A member who joins with **"Only receive the event's photos — you won't share yours"** has their
post-cutoff camera roll uploaded to the event anyway, on every app foreground, on **iOS 18–26.0**. The
status screen reads "In sync" throughout.

The direction gate for the upload arm was placed at the **invoker**. `changes/archive/2026-07-07-add-join-direction-mode`,
D3, reasoned: *"Under `DownloadOnly` the producer is never enabled, so the OS never invokes the upload
extension… No extension code changes."* That holds only where the OS is the invoker. The app-driven tier
(`changes/archive/2026-07-04-add-url-session-upload`, merged **three days earlier**) invokes its own cycle
from the app process, so `SnapSyncRoot.onForeground()` — gated on the iOS version alone — reaches
`UploadCycle` with nothing between it and the user's library. D4, in the same document, gated the
**download** arm at its choke point and is correct; the two arms have been gated at different layers ever
since.

Two things made it invisible. `:app:ios` is wiring-only and untested by project rule, so no test covers the
call site. And the status screen's upload arrow is **force-hidden** for a download-only membership
(`sync-status-screen`, D5) — the one surface that would have shown the user an upload they never asked for.

`main` is the public alpha channel: every merge reaches public TestFlight testers silently. This is live.

## What Changes

- **`Contribution`** — a new sealed type in `:domain:gallery` carrying what a membership contributes:
  `None` (contributes nothing) or `Since(cutoff)` (everything captured at/after the cutoff). Required, with
  **no default**, on both consumers. It replaces the `photoCutoff` scalar, so a membership's participation
  direction becomes an input to the selection policy rather than a fact only the lifecycle knows.
- **The upload arm gates at its choke point.** `UploadCycle.run()` — the one function every trigger on every
  tier funnels through — returns a new **`CycleResult.SKIPPED`** on `Contribution.None`, before any walk,
  job, manifest write, or notify. **BREAKING** for `CycleResult` consumers: a new variant makes every
  non-exhaustive `when` a compile error, by design.
- **The upload total `N` gates on the same input.** `OwnDeviceGalleryStatusSource` returns `0` for
  `Contribution.None` without enumerating, restoring its own stated invariant — *"the same set the upload
  cycle admits"*. N stops being a parallel path that no gate feeds.
- **The status arrows' direction masks are removed** (both of them). Once N is correct the upload arrow hides
  itself (`0 < 0`); the download arrow's mask has been dead since it was written, because that arm's total is
  already downstream of its gate. **BREAKING**: `syncHealth` no longer takes `direction`.
- **No heartbeat on a non-contributor.** The pump does not re-arm on `SKIPPED`, so a download-only device
  schedules no `BGProcessingTask`.
- **Posture-explicit bindings.** The `?: true` fallbacks are removed from both arms' direction reads: *no
  membership* means *no arm*, rather than resolving to "enabled".
- **New: silent push drives an upload scan** (iOS 18–26.0). A tested `UploadPushReceiver` mirrors
  `DownloadPushReceiver` — active-event guard, then `pump.onSilentPush()` — composed behind a fan-out
  receiver. `BGProcessingTask` is deferred by iOS at its discretion; a silent push is the reliable wake, and
  it clusters exactly when an event is live.
- **New: foreground re-arms the heartbeat**, closing the gap where a force-quit (which cancels
  `BGTaskScheduler` requests) left nothing to re-arm until the next `start()`.

## Capabilities

### New Capabilities

None. `Contribution` extends an existing capability's contract rather than introducing one.

### Modified Capabilities

- `photo-selection-policy`: participation direction becomes a **third selection input** alongside the cutoff
  and the origin exclusions — *whether at all*, next to *when* and *what it is*. Carried as `Contribution`,
  required on both the cycle and the total.
- `gallery-status`: the total `N` is bounded by the membership's `Contribution`, not by its cutoff alone; a
  non-contributing membership counts `0` without enumerating.
- `upload-lifecycle`: a new invariant — the upload arm's direction gate SHALL live at the choke point all
  triggers funnel through, never at the invoker, whose enumeration a new tier can silently invalidate. This
  supersedes D3 of `2026-07-07-add-join-direction-mode`.
- `ios-url-session-upload`: the pump gains an `onSilentPush` trigger; `onForeground` re-arms the heartbeat;
  the re-arm policy is stated for `SKIPPED` (never re-arm).
- `sync-status-screen`: the direction masks are removed from arrow derivation. Arrows derive from counts
  alone; a masked arrow can no longer conceal a direction contract the system is not keeping.
- `photo-download`: the direction gate's binding is posture-explicit — *no membership* is a distinct answer
  from *a membership that excludes download*, and neither enables the arm.

## Impact

**Code.** `:domain:gallery` (`Contribution`); `:capability:upload` (`UploadCycle`, `CycleResult`,
`BackgroundUploadPump`, new `UploadPushReceiver`); `:domain:status`
(`OwnDeviceGalleryStatusSource`); `:domain:presentation` (`syncHealth`); `:capability:download`
(binding); both iOS composition roots; `:test:world`; `:app:desktop` harnesses.

**Behavior.** A download-only membership on iOS 18–26.0 stops uploading — the fix. Its status screen still
reads "In sync", now because N is `0` rather than because an arrow is hidden. iOS ≥26.1 is unaffected: that
tier is already correct, because `setUploadJobExtensionEnabled(false)` deregisters the extension and the OS
stops invoking it.

**Risk.** Removing the upload arrow's mask depends on N being correct. If it is not, a download-only user
sees an arrow that never settles — visible, and shipped silently to public testers. Mitigated by exercising
a download-only preset in the full-stack harness (`:app:desktop:run`, whose counts emerge from the real
status source) before the mask is removed.

**Verification.** The first task is a **failing** test: a download-only membership creates no upload job.
If it passes, the analysis above is wrong and the change stops.
