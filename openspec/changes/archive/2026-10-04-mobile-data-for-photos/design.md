# Design

## Context

See proposal.md for why. The outcomes are in `specs/mobile-data` (the rule) and the five modified
capabilities. What shapes the *how*:

- **Every photo byte crosses one of five transfer paths**, each with its own owner of "when may this
  run": iOS — the app's background `URLSession` uploader, the ≥26.1 PhotoKit upload extension (the OS
  runs the job from our `NSURLRequest` destination), the background `URLSession` downloader; Android — the
  in-process `AndroidUpload` PUTs (resumed by WorkManager wakes) and `DownloadManager`. All other traffic
  is the `Backend` port, which this change does not touch.
- **iOS builds both upload requests in one place** (`uploadUrlRequest`, ext-safe) and the download
  request in `IosDownload.start` — so the rule lands in two functions.
- **`EventConfig`** (the shared membership file both iOS processes read) carries the per-membership
  choices (`direction`, `saveToAlbum`), each with a decode default that reproduces old behaviour.
- **`NetworkMonitor`** (port + iOS/Android adapters + mock + contract) arrives from the
  `network-connection` branch, which this branch is rebased onto: `watch(): Flow<NetworkAccess>`,
  `ONLINE | OFFLINE | BLOCKED`, cold, app-only on iOS.
- **The status line's label** is chosen in `AppStatusLine` from the arrows (`ongoing` vs pending);
  presentation reduces `UiState` only from read-models.

### Measured (2026-10-03) — the facts the decisions rest on

Probe builds set the "avoid" flags on every photo request; control builds are unchanged; same phone,
same network, local backend.

| path | Low Data Mode (SE2, 26.6.2) | personal hotspot (SE2) | cellular (XS, 18.7.10) |
|---|---|---|---|
| app uploader, control → probe | ~5 s → **held** | ~3 s → **held** | ~3 s → **held** |
| downloader, control → probe | ~1 s → **held** | through → **held** | ~3 s → **held** |
| PhotoKit extension, control | **held by iOS** | **held by iOS** | not measurable (XS < 26.1, SE2 has no SIM) |

Every held transfer completed within seconds of returning to an unrestricted Wi-Fi.

Android emulator (API 36), control → probe: `DownloadManager` with `setAllowedOverMetered(false)` —
metered Wi-Fi 2 s → **held**, cellular 2 s → **held**, released within ~6 s on unmetered Wi-Fi;
`DownloadManager` already honours Data Saver itself; WorkManager `NetworkType.UNMETERED` stays unsatisfied
on metered Wi-Fi and cellular; the **in-process upload is not held by the OS at all**.

## Goals / Non-Goals

**Goals:**
- Hand the rule to whoever owns "when may this transfer run" on each path, so a held transfer survives
  process death and resumes in the background without new scheduling of our own.
- One read of "is the phone on a restricted network" for the status line and the Android upload gate.

**Non-Goals:**
- Re-queuing or cancelling transfers when the choice changes (the spec says new transfers only).
- A per-transfer record of the rule (decided: the waiting line is computed from the current choice).
- Any backend change; the choice never leaves the device.
- Measuring the extension on real cellular (no ≥26.1 phone with a SIM).

## Decisions

### D1. The choice is a field of `EventConfig`, defaulting to "mobile data allowed"
`EventConfig.mobileData: Boolean = true` (name settles in code review), set by join and by settings save
exactly like `saveToAlbum`. The default makes every config written before the field existed decode to
today's behaviour, and keeps the extension (which reads the same file) correct with no migration.
*Alternative*: a device-wide preference — rejected in the interview: the choice belongs to the
membership, and a new event starts from the default.

### D2. A `TransferNetwork` value rides on each transfer request
`model/`: `enum class TransferNetwork { ANY, UNRESTRICTED_ONLY }`, derived from the config at the moment a
transfer is created. `UploadTarget` gains `network` (built in `UploadTransferService`, both `create` and
`retry`); `Download.start(url, tag, network)` (called from `DownloadJobs`). The value — not the boolean —
crosses the port because it names the platform behaviour asked for, and it is what a contract clause can
assert. Because it is fixed when a transfer is created, "new transfers only" falls out with no extra
state.

### D3. iOS: three request flags, set in the two request builders
`UNRESTRICTED_ONLY` → `allowsCellularAccess = false`, `allowsExpensiveNetworkAccess = false`,
`allowsConstrainedNetworkAccess = false` on the request (`uploadUrlRequest`; `IosDownload.start` switches
from `downloadTaskWithURL` to `downloadTaskWithRequest`). Per-request flags, not session flags, because one
background session serves transfers under both rules, and the measurement shows nsurlsessiond honours the
request's flags and resumes on Wi-Fi by itself. The extension needs nothing beyond the shared builder:
iOS already holds its jobs on expensive and constrained paths; the flags there are belt-and-braces for
cellular, which could not be measured.
*Consequence worth knowing*: with mobile data **on**, the extension still does not upload on a hotspot
or in Low Data Mode (iOS's own policy); the app's uploader, which always runs beside it, carries those
photos. No change needed, but it is why "uploads work on a hotspot" must never be asserted of the
extension.

### D4. Android downloads: `DownloadManager`'s own flag
`UNRESTRICTED_ONLY` → `setAllowedOverMetered(false)` (roaming follows). DownloadManager persists the
request and schedules its own unmetered job, so a held download survives process death. Data Saver is
already honoured by DownloadManager on metered networks.

### D5. Android uploads: the adapter waits, a wake resumes
The in-process upload has no OS gate (measured), so `AndroidUpload` holds a `UNRESTRICTED_ONLY` PUT until
`ConnectivityManager` reports an unmetered default network without Data Saver restriction — playing the
role nsurlsessiond plays on iOS, so the core sees the same "job created, not yet finished" either way. A
transfer the process dies with is already journaled; to resume it, `WakeTrigger.After.requiresNetwork:
Boolean` becomes a requirement `NONE | ANY | UNRESTRICTED`, and the upload flow schedules its wake with
`UNRESTRICTED` while membership's choice is off and work remains. AndroidWake maps it to
`NetworkType.UNMETERED`; iOS's `BGTaskScheduler` cannot express it and maps it to "requires network".
*Alternative*: AndroidUpload schedules its own unmetered WorkManager job — rejected in the interview: a
second, adapter-private wake path invisible to the Wake port and its mock.

### D6. `NetworkAccess.ONLINE` becomes `Online(restricted: Boolean)`
`NetworkAccess` turns into a sealed type `Online(restricted) | Offline | Blocked`. iOS: restricted =
`nw_path_is_expensive || nw_path_is_constrained`; Android: not `NET_CAPABILITY_NOT_METERED`, or
`restrictBackgroundStatus` enabled while metered. The offline banner (from `network-connection`) matches
`Online` and ignores the flag. *Alternatives*: an `ONLINE_RESTRICTED` enum value (every "is online"
`when` must name two values — easy to get wrong in the banner); a second `watchRestricted()` flow (two
collections, two monitors that can disagree for a moment).

### D7. The waiting line is computed from the current choice
A status read-model combines the membership's choice, `NetworkMonitor`, and the arrows: waiting =
some arrow shown, none pulsing, choice off, `Online(restricted = true)`. `UiState` carries it; `AppStatusLine`
picks "Waiting for Wi-Fi…" over "Synchronization pending…". After turning mobile data back on, the few
transfers still holding their Wi-Fi-only rule show "Synchronization pending…" until Wi-Fi — accepted
(decided) over recording each transfer's rule in the ledger and the download store.

### D8. Contracts carry the measured facts
Per the project rule (a measured platform fact is a contract clause, not a sentence in a spec):
- `NetworkMonitorContract`: restricted vs unrestricted — live on `ANDROID_EMU`
  (`cmd netpolicy set metered-network AndroidWifi true`, `cmd netpolicy set restrict-background true`),
  recorded on `IOS_DEVICE_APP` keyed by the network precondition (hotspot / Low Data), as the branch
  already keys `ONLINE`/`OFFLINE`.
- `UploadContract` / `DownloadContract`: "an `UNRESTRICTED_ONLY` transfer does not complete on a
  restricted network, and completes once unrestricted" — `ANDROID_EMU` live; iOS recorded on the SE2
  (Low Data / hotspot). The PhotoKit tier's clause states what was measured: held on a restricted
  network whatever the rule.
- `WakeContract`: an `UNRESTRICTED` wake is pending-unsatisfied on a metered network (`ANDROID_EMU`).

## Risks / Trade-offs

- [Extension on real cellular unmeasured] → flags are set anyway; if iOS ignored them the photo would
  still be uploaded by a job the member asked to keep off cellular. Mitigation: re-measure when a
  ≥26.1 phone with a SIM is available; until then the design doc records the gap.
- [Turning mobile data on leaves a Wi-Fi-only tail] → bounded (download queue ≤ 24, upload slots), and
  the spec allows it; the line reads "pending" for that tail (D7).
- [A member stays off Wi-Fi until the event closes] → their unsent photos are never shared; accepted in
  the interview, the waiting line is the only warning.
- [Android: the adapter's wait and the wake may both resume one transfer] → the journal already makes a
  resumed transfer idempotent; a duplicate upload of the same object is the existing worst case.
- [Rig Debug builds crash on iOS 18 (strong-linked `PHAssetResourceUploadJob`)] → blocks device tests on
  the XS until workspace `rig-ios18-crash` lands its fix.

## Migration Plan

- No data migration: the new config field and the request field default to today's behaviour.
- Depends on `network-connection` merging first (D6 modifies its port).
- Rollback: an older build ignores the unknown config field and transfers on any network — the
  pre-change behaviour, no corruption.
