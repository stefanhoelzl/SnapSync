## Why

Every OS wake runs (nearly) a whole upload/download cycle inside the OS's completion handler, bounded by
deadlines we invented (`ReceiptDeadlines`: 20 s / 20 s / 120 s), and a download-backstop BGTask exists to clean
up after the overruns. Field evidence shows the invented deadline is itself the damage: on an iPhone XS (iOS 18)
our 20 s receipt released on 45 % of download wakes and 66 % of upload-session wakes, iOS suspended the app
≤ 0.4 s later, and batches of ~24 photos that need 20–40 s were cut off mid-import (92 left staged on one day).
The backstop found work in **0 of 108** field runs. Apple's contract is different from ours: a silent push has a
documented 30 s budget but no expiry callback; a background-URLSession relaunch has no documented budget and its
completion handler must be called promptly (DTS: holding it is a watchdog-backed assertion — overrun is a kill
without warning); a BGTask has its own `expirationHandler` and a `BGProcessingTask` gets "minutes". In every case
Apple's answer to "time is running out" is its own signal (`expirationHandler`, `beginBackgroundTask`'s expiration
handler) and a cooperative stop — never a clock of ours. Background wakes also run the process in the kernel's
darwinbg role (~9× CPU clamp after the first ~1 s, measured), so work a wake does not need is the scarcest thing
it can spend.

## What Changes

- **Each OS wake does only its own work, then an opportunistic tail.** Own work per wake: silent push → union
  read + enqueue downloads; download-session relaunch → stage the delivered files; upload-session relaunch
  (18–26.0) → the transport records terminals; heartbeat BGTask (18–26.0) → nothing (it is the tail); limited-grant
  selection change → snapshot-fed discovery → manifest; foreground → reconcile, stored-upload settle, staged-byte
  reclaim, status and membership refresh (its import, top-up and walk run through the same tail runner). After it, one process-wide,
  single-flight **tail** runs until done or until the OS says time is up: ① import staged downloads ② upload
  top-up (re-create retry-spent failures, enqueue known rows) ③ discovery walk → manifest publish; if ③ added
  rows it loops to ②. An upload completion triggers ② only — never a walk (today: a full cycle per completion).
  Under a limited grant the tail runs ① and ② only (② reads no library), so a silent push now also tops up there.
- **BREAKING (internal contract): `ReceiptDeadlines` are deleted.** Handlers learn "time is up" only from Apple:
  a BGTask's `expirationHandler` (forwarded into the core through the inbound port); `beginBackgroundTask`'s
  expiration handler for the push and URLSession wakes (and foreground, which holds one too); never
  `backgroundTimeRemaining`. On expiry the running work stops at the next boundary (nothing new starts; the
  current PhotoKit change block / ledger write is not cancelled and runs on until suspension, a safe retry), and
  the task ends and the handler is released **at once**, without waiting for it — replacing "release the handler,
  let the work run on".
- **The OS handler is released right after the wake's own work** (push, URLSession); the tail runs under
  `beginBackgroundTask` (background time is per app, so this costs no time and gains an expiry signal). A BGTask
  holds `setTaskCompleted` until its tail finishes or its expiration handler fires (then at once).
- **The download backstop BGTask is removed.** Leftover staged imports are drained by any later wake's tail and
  by foreground.
- **The discovery walk is atomic** under a stop: abandoned on expiry (its decide stage writes nothing), retried
  next wake; a partial walk is never authoritative.
- **Walk memo (app process only):** an in-memory memo keyed on `PHPhotoLibrary.currentChangeToken` + the
  membership's selection policy + the grant reuses the last walk while the library is unchanged (measured ~2 ms vs
  1.3–2.0 s darwinbg walk); still authoritative for deletion. It ships in **shadow** (walks every time, logs a
  would-be-wrong answer at Error) until a device check shows an external change always moves the token. The upload
  extension (32 MB memory limit) keeps a fresh walk.
- **Event-album collection cache** in the importer, invalidated by the library change observer (never by a
  failed commit), gated on a device check of PhotoKit's behaviour on a deleted collection.
- **Ledger counts** refresh after tail units only while foregrounded.
- **The limited-grant snapshot source closes its grant-flip gap:** a selection change queued before the grant
  becomes full is no longer emitted after it (pre-existing; the baseline path already checked this).
- **Upload extension:** no cooperative stop is built — measured: its only end is assetsd's hard 60.0 s timer
  (SIGKILL; `notifyTermination` follows only normal returns; `performExpiringActivity` never fires), against
  0.6–1.4 s of work per `process()`. The shared cycle's self-chosen 12 s manifest timeout is removed on both tiers.
- **Push registration becomes change-driven:** the app asks the OS for the token at every app entry (cold start
  and each foreground entry) and PUTs only when (token, env, deviceId) differs from the persisted
  last-registered value, plus on join and on a fresh credential. No unconditional launch PUT (today 102 of 108
  process starts PUT, 0.5–3 s each in background).
- **Spec sync for the merged stage-1 performance commits** (behaviour-preserving; wording only): batched download
  planning (`planAll`), folded limited-grant selection changes, the grant-gated album lookup, rows created from
  the walk's resources, the PhotoKit QoS lane, the in-memory attestation token and `provideForRetry`,
  `SyncEngine.isWork`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ios-app-shell`: OS completion handlers — released after the wake's own work; Apple expiry signals replace
  `ReceiptDeadlines`; cooperative stop; BGTask expiration forwarded; backstop task gone.
- `module-architecture`: the inbound port carries the OS's expiry signals; a `beginBackgroundTask`-shaped outbound
  port; dispatcher lanes name the PhotoKit read lane and its pinned QoS (stage-1 sync).
- `photo-download`: import-without-foreground no longer has a backstop; the relaunch wake releases after staging
  and imports under a background task; planning wording (stage-1 sync); album-collection cache.
- `ios-url-session-upload`: the pump is replaced by the tail runner; completions trigger top-up only; relaunch and
  heartbeat engines re-expressed as own work + tail; row resolve wording (stage-1 sync).
- `upload-lifecycle`: how triggers reach the uploader under the tail runner.
- `push-registration`: silent-push fan-out does own work (download) and hands the rest to the tail; registration
  timing becomes change-driven.
- `sync-status`: ledger counts refresh only while foregrounded.
- `sync-ledger`: the walk memo (amends the always-full-enumerate decision); resolve wording (stage-1 sync).
- `ios-photokit-upload`: the extension's measured end (60 s kill, no expiry signal, 32 MB limit); no cooperative
  stop; walk memo not applied in the extension; manifest timeout removed.
- `diagnostic-logging`: the expiry log line is about Apple's signal, not our deadline.
- `limited-photo-access`: the grant-flip gap is closed (a change queued before the grant becomes full is not
  emitted after it); under a limited grant the tail runs ① and ② from the snapshot, never ③; stage-1 fold wording.
- `architecture-guards`: runtime identity pins drop the backstop task id and require `BGTaskSchedulerPermittedIdentifiers`
  to equal the pinned set (and the Swift registrations to equal it too); the handler-holding guard names
  `OsCompletions`, the type that replaces `OsReceipt`; a new guard keeps the walk memo out of the extension.
- `harness-world-model`: the world's uploader units are driven by the real tail runner, each OS entry's tail runs
  the real import drain, the world holds an operator-expirable background-time table, and the last-registered push
  record is durable across a relaunch.
- `testing-architecture`, `upload-state-reconciliation`, `reconfigure-membership`, `join-event`: requirements that
  named the pump or the backstop restated for the tail runner (no behaviour change beyond this change's).
- `photo-selection-policy`, `edge-upload-provider`, `device-attestation`, `sync-engine`: wording synced to the merged
  stage-1 commits (no behaviour change).

## Impact

- Code: `domain/compose/AppEntries.kt` + `ExtensionCore.kt` (entry logic), `domain/ports/PlatformEntries.kt` +
  `OsReceipt.kt` (deleted/replaced), `BackgroundUploadPump` (replaced by the tail runner), `flow/` (SilentPush,
  DownloadBackstop removed, Background), `DownloadController`, `UploadCycle`, `IosDiscovery`/candidate source
  (memo), `IosPhotoLibraryImporter` (album cache), push registration, the Swift shell's BGTask registration (one
  task identifier removed; expiration forwarded), `Info.plist` `BGTaskSchedulerPermittedIdentifiers`.
- Contracts: `PlatformEntriesContract` (backstop clauses removed, expiry clauses added),
  `BackgroundSchedulerContract` (backstop), new clauses for the background-time port and the change-token read.
- Evidence: SE2 benchmark harness (uncommitted, `scratchpad/bench`) measures after this change against the
  baseline and stage 1. The SE2 never reproduced the XS's cut-off imports (all 113 staged-but-unimported keys in
  its runs were duplicates of a separate, pre-existing enqueue bug), so the expiry part's gain is field-evidenced
  only.
