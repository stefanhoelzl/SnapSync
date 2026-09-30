# Design — timely-background-receiving

## Context

See `proposal.md` for why. The current state this design changes, as read from the tree:

- **One timed wake, the heartbeat** (`services/wake/Heartbeat.kt`): a one-shot request with a 60 s *earliest* bound
  and a network requirement — a lower bound that coalesces bursts of re-arms, not a timer. On iOS it is a
  `BGProcessingTaskRequest` on `app.snapsync.upload.heartbeat` (`IosWake`); on Android one unique WorkManager work
  (`AndroidWake`, `setInitialDelay`). Android also has a library-change wake (`WakeId.LibraryChanged`, a content-URI
  trigger); iOS answers it `Unsupported`.
- **The re-arm rule** (`feature/upload/TailRunner.kt`, `rearmFor` + `shouldSchedule`) decides on the tail's upload
  *outcome*: `SKIPPED` never re-arms; `COMPLETED`/`FAILED` re-arm only under `Rearm.ALWAYS`; a standing library watch
  demotes `ALWAYS` to `WHEN_WORK_REMAINS`. Leftover staged imports re-arm too. So iOS full-access sharers carry a
  perpetual heartbeat; everyone whose cycle answers `SKIPPED` — not joined, receive-only (`Declined`), held back
  (`Withheld`) — carries none once imports are drained; caught-up Android carries none.
- **The union reconcile** (`DownloadController.reconcile`, one `GET /events/:id/files`: one query, every photo of
  the event, a presigned URL per file, `no-store`) runs from the foreground flow, the silent-push receiver, the join,
  and a reconfigure — never from a timed wake.
- **The end-of-wake step** (`EventCompletion.finish`) runs after every full-scope tail (`WakeHold.finishAfter`,
  `TailComposition.heartbeatThenFinish`). Before the event's end it makes no request; after it, one
  `GET /events/:id` on **every** such wake.
- **Under limited access the selection is read only at host assembly** (`ComposedApp` → `installPermissionSubscriptions`
  → `gallery.observeChanges(true)`). A background start never assembles the host, so the selection stays `Unread`,
  the app's upload admission withholds (`UploadConfig.kt`, `scope != SelectionScope.Unread`), the cycle answers
  `SKIPPED` — and nothing uploads on a cold background wake, backlog included, although `background-upload` promises
  limited-access uploads "on its own wake-ups, iOS's background-upload completions, the silent wakes …".

**Measured for this change** (probe branch `probe/limited-background-read`, never merged; iPhone SE 2nd gen.,
iOS 26.6.2, 2026-09-30): with the selection observer opened at composition instead of host assembly, a cold
process that iOS relaunched only for finished background uploads (no foreground, no scene activation, no host
assembly in its log) read the limited selection:

| run | before the relaunch, with the app killed | the background read saw | then | system prompt |
|---|---|---|---|---|
| 1 | a camera photo taken and added to the selection in Settings | `n=17` (16 + the new one) | manifest published `200`, both resources of the new Live Photo enqueued | none |
| 2 | a camera photo taken, **not** added to the selection | `n=23`, the camera photo correctly absent | manifest published `200` | none, over ~2 min |

One device, one iOS release, one run each — the same limits `changes/archive/2026-09-21-correct-limited-access-alert-rule`
places on every prompt measurement.

## Goals / Non-Goals

**Goals:**
- A joined device always has a timed wake pending, and its cadence follows from what the device still has to do.
- A timed wake checks the event for others' new photos and for its close, bounded per hour per event.
- A cold background start under limited access uploads what it may, as the uploading spec already promises.

**Non-Goals:**
- Any change to push delivery, the backend, or the union route (a conditional `ETag`/`304` union read is a
  possible later optimisation, not needed at this cost).
- Any cadence change for full-access sharers without an OS library wake **before the event's end** — that is how
  those phones notice their own new photos.
- Stopping iOS's own uploader (the ≥26.1 extension) on a force-quit: `background-upload` promises it keeps working.
- Measuring Android's library trigger reliability (phase 6) or OEM background killers.

## Decisions

### D1 — One wake with two cadences, decided on "joined", not on the upload outcome

The re-arm becomes one pure rule returning **none / busy / idle**:

- **none** — not joined (and the trigger kinds that never re-arm, below).
- **busy** (60 s earliest) — uploads remain (`PROCESSING`/`Paused`), or staged imports remain, or the device is a
  **full-access sharer without an OS library wake before the event's end**.
- **idle** (1 h earliest) — every other joined device: receive-only, uploads held back (denied / not determined /
  the app uploader switched off), limited access, a caught-up Android device, a caught-up iOS ≥26.1 device whose
  extension is confirmed registered, and every caught-up device after the event's end.

"OS library wake" is an input the composition supplies: on Android, whether `Heartbeat.watchLibrary()` stands; on
iOS, whether the upload extension is **confirmed registered** (D3). Membership comes from the config, not from
`SKIPPED`, which conflates "not joined" with "declined" and "withheld". "Ended" is the config service's `hasEnded`.

Trigger kinds keep their say: `UPLOAD_COMPLETED` and `DOWNLOAD_STAGED` (`Rearm.NEVER`, in-process completions)
still schedule nothing; every other trigger applies the rule. The exhaustive `when` over `CycleResult` stays, so a
new result variant is still a compile error.

*Why after the end:* the event's end is the capture-date ceiling — nothing new can enter the event, so the busy
re-check that notices new captures has nothing left to notice; photos taken before the end that still wait are
"uploads remain", which is busy anyway.

*Rejected:* a second periodic wake beside the heartbeat — iOS sharers already carry a perpetual one, so a second
one buys nothing there and doubles the plumbing; keying on the member's type — it misses the held-back sharer and
reads membership out of an upload result.

### D2 — iOS: idle is a `BGAppRefreshTaskRequest` on its own identifier

`WakeTrigger.After` gains the cadence (`idle`); the core names no task type. `IosWake`:

- busy → `BGProcessingTaskRequest` on `app.snapsync.upload.heartbeat` (unchanged);
  idle → `BGAppRefreshTaskRequest` on a new identifier, `app.snapsync.heartbeat.idle`, earliest 1 h.
- **Each submit cancels the other identifier.** iOS keeps one pending request per identifier, and the two coexist —
  without the cancel a pending 60 s processing request survives an idle re-arm and the cadence never drops.
- `listen` registers both launch handlers (required before launch finishes); both route to `WakeId.Heartbeat`.
  `cancel(Heartbeat)` cancels both.
- **A refused refresh falls back** to a `BGProcessingTaskRequest` with a 1 h earliest bound, logged at warn. Refresh
  is unavailable when the user turned Background App Refresh off or Low Power Mode is on; processing usually still
  runs, if mostly on the charger — worse, never nothing.
- Expiry: the refresh task gets roughly 30 s. It rides the existing `TaskCompletion` → `runner.stop` path; work the
  stop leaves makes the next re-arm busy (D1).

Plist: `fetch` joins `UIBackgroundModes`; the identifier joins `BGTaskSchedulerPermittedIdentifiers`; the
`RuntimeIdentityTest` pin (every OS-held literal exactly once) and the rig's `MockEntryDriver` routing learn it.

*Rejected:* idle as a processing request with a longer earliest bound — iOS tends to run processing only when idle
or charging, i.e. overnight; kept only as the fallback.

**Android:** the same unique work, `setInitialDelay(1 h)` for idle. No new work kind.

### D3 — "Confirmed registered" is the OS's own answer, read under a full grant

The input for iOS is `osSupportsOsDrivenUpload && permission == GRANTED && extensionRegistration.isRegistered() == true`
(and the rig's per-uploader pin not off). `isRegistered()` is grant-dependent — it read `false` under
`NOT_DETERMINED` for a live record (SE2 / 26.6) — so it is trusted only under `GRANTED`, which the input already
requires. `null` or `false` → not confirmed → busy, today's behaviour.

*Why this and not `extensionRegistrable`:* that says whether registration is *allowed*, not whether it *happened*;
a refused or stale registration would silently drop a sharer to hourly with nothing noticing new photos.

The extension is invoked by the OS on library changes (measured: `process()` 4 s after a new photo joined the
selection, SE2/26.6), which is why the app's own frequent re-check is redundant there. The extension does not run
the end-of-wake step; the close still reaches such a device through the close push and its idle wake.

### D4 — The throttled photo check

- **Where it runs:** the end-of-wake step of every full-scope tail gains a photo check before the close check:
  `DownloadController.reconcileIfDue(eventId)`, beside the unthrottled `reconcile`. Push, foreground and join keep
  calling `reconcile` in their own work, unchanged.
- **The bound:** at most one union read per event per hour from this path. A per-event "last checked" time in a
  small service over the existing `Preferences` port (`reconcile.lastAt.<eventId>`; the App-Group `UserDefaults`
  on iOS, `SharedPreferences` on Android) — it must survive process death, because a background wake is usually a
  fresh process.
- **Every check writes the time** — the unthrottled ones too, so a wake right after a push skips its own — and
  **every attempt counts**, a failed one included: otherwise a failing backend takes one request per busy wake. A
  stored time in the future (the user moved the clock) counts as due. Time comes from the `Clock` port.
- **Cost:** `reconcile` already fetches nothing for an upload-only membership, so upload-only sharers pay 0.
- Leave and reset delete the event's keys, so a re-join checks at once.

*Rejected:* check on every wake (up to one full union per iOS busy wake); check only on idle wakes (a full-access
sharer below 26.1 never has one before the end, so it would never check); a conditional union read (backend
change, not needed at this cost).

### D5 — The close check under the same bound

`EventCompletion.finish` keeps its order; its `GET /events/:id` is skipped when a close check for the event ran
within the hour (`close.lastAt.<eventId>`, same service, same attempt/clock rules). The bound is bypassed for the
wakes that exist to answer it or are watched: `SILENT_PUSH` (the close push), `FOREGROUND`, `ARM`. The end-of-wake
step learns which trigger it follows — it already does, in `WakeHold.finishAfter(trigger)`.

It cannot be dropped: `manage-membership` allows leaving only on the server's word, never on the local clock alone,
and a member held back by a photo that fails to arrive leaves only when the server says the event finished.

*Cost of the bound:* a missed close push delays the automatic leave by up to an hour. The event is closed then —
nobody joins, nothing changes — so the late leave has no visible effect beyond the event staying on screen longer.

### D6 — Under limited access, a background start reads the selection

The selection subscription and the observer move out of `installPermissionSubscriptions` into an idempotent
`installSelectionObserver()` the host composition calls on composition, for every start. The permission-grant
collectors, the album grant subscription and the interrupted-import sweep stay at host assembly — the reason
`changes/archive/2026-07-17-create-flow-zone-and-drain-shell` D6 gives (a cold wake must not replay the grant into
the upload transitions) is about those, not about the selection.

The observer reads only under `LIMITED` (the lane opens only then), so a full grant costs nothing new. This
**supersedes** `changes/archive/2026-07-20-accept-limited-photo-access` D4's "cold-launch baseline + observer,
nothing else" and closes that record's open question ("does an observer-triggered read during a background wake
queue alerts? … measure before ever adding one"): measured above, none on 26.6.2. `CLAUDE.md` fact ① is corrected
to say the observer opens at composition.

*What it buys:* the uploading spec's promise on cold background wakes, backlog included (run 1). What it cannot
buy: a camera photo never enters a limited selection by itself (run 2), so the busy cadence would find nothing new —
which is why limited access sits on idle (D1).

### D7 — Not the backstop that `own-work-per-wake` deleted

`changes/archive/2026-09-25-own-work-per-wake` D7 deleted the download-backstop `BGTask`: it only **finished known
work** (draining staged imports), found work in 0 / 108 field runs, and every later wake and opening drain it. The
idle wake **finds new work** — others' photos, the event's close — when no trigger arrives at all; the 0 / 108
measured the first case, not the second. The backstop's case stays covered by D1 (staged imports → busy).

### D8 — Testing

- **Wake contract** (`WakeContract`, recorded name `BackgroundScheduler`) gains `IDLE_ARMS_ONE`,
  `IDLE_REPLACES_BUSY`, `BUSY_REPLACES_IDLE`, `CANCEL_CLEARS_IDLE`; the `pendingWakes` handle counts both iOS
  identifiers. Hosts: the mock on the JVM, `AndroidWorkContractTest` on `ANDROID_EMU`, the iOS device recording
  re-taken (the adapter's OS calls change) plus `…IOS_DEVICE_APP.REFRESH_OFF.rec` with Background App Refresh off,
  which replays the fallback against real answers.
- **Rig:** `/device/os-record` gains `pendingWake` (`{cadence, earliestSeconds}` or `null`) from the wake mock's
  pending map — the state reached, not a count; `heartbeatsScheduled` stays. `/os/app/onBackgroundTask` accepts the
  idle identifier. The backend mock exposes union/event read counts if it does not already.
- **Integration** (`:test:integration`, JVM host): the cadence table of D1 row by row, including the end-of-event
  switch and a leave → `null`; the photo-check bound (two wakes 10 min apart → 1 union read; +1 h → 2; a push in
  between → the next wake reads none); the close-check bound after the end.
- **Unit:** the pure cadence rule; the last-checked service over the `Preferences` mock (attempts count, future
  time is due, leave clears).
- **Force-quit / force-stop** is not a contract clause (it kills the process that would observe it). Android: a
  non-gating script beside `scripts/android-device-tests` — idle wake pending → `adb shell am force-stop` → no job
  pending (`dumpsys jobscheduler`) → start → pending again. iOS: Apple's documented behaviour, as
  `background-upload` already relies on; a device check needs a human swipe.

## Risks / Trade-offs

- [iOS hands out refreshes by app usage; an app opened rarely during a short event may get few] → the spec says
  "as the phone's system allows"; each idle wake logs its trigger, so field dumps show the real rate.
- [Background App Refresh off / Low Power Mode] → D2 fallback to processing with 1 h earliest.
- [Prompt behaviour of a background selection read on other iOS releases is unmeasured; 26.5.x leaked in the
  foreground] → measured clean on 26.6.2 only; re-measure at the next iOS major, as the alert-rule record already
  requires; if it leaks, the read goes back behind host assembly and limited access stays idle-only.
- [≥26.1 caught-up full-access sharers wake less often — shipped behaviour changes] → keyed on the OS's
  confirmed registration, not the OS version; unconfirmed stays busy. Their new photos are the extension's job,
  which the OS drives.
- [A failed union read waits up to an hour on the wake path] → push and foreground still read at once.
- [Phase 4 edits `receiving-photos` concurrently] → whichever lands second rebases the delta; this change leaves
  the Android wording to phase 4.
- [Until phase 4 lands, the Android reconcile has no real download adapter] → built and tested against the ports.

## Migration Plan

App update only; no backend or data migration. A pending processing request left by the previous version is
replaced by the first re-arm under the new rule (each submit cancels the other identifier). Rollback is an app
release; the preferences keys are inert to an older build.
