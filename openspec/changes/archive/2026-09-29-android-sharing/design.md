## Context

See proposal.md for why. What the tree gives this phase:

- The ports are already platform-neutral where it matters. `Wake` names WorkManager and carries an Android-only
  `WakeId.LibraryChanged` with `WakeTrigger.LibraryChange(maxDelay)`; `Heartbeat.arm()` arms every `WakeId`, and
  iOS answers `LibraryChanged` with `ScheduleResult.Unsupported`. `BackgroundTime` is held by every wake
  (`compose/Wakes.kt`'s `WakeHold`), whose KDoc already anticipates "an Android worker's top-up".
- `GalleryReader.assets(policy)` takes the policy into the read; `SelectionPolicy.admits` is the one decision the
  upload, the device manifest, the status total `N` and the join preview all ask.
- Upload identity is `uploadKey(assetId, role, originalFilename)` — the canonical `AssetId` is the photo's identity
  in the event.
- `extensionRegistrable(osSupportsOsDrivenUpload = false, …)` is `false` everywhere, and iOS below 26.1 already runs
  with a `PhotoKitExtensionRegistry` that answers `Unsupported` / `NOT_REGISTERED`.
- `TailRunner` calls `heartbeat.arm()` only when a tail left work (`TailRunner.kt` re-arm rule), which arms BOTH
  wakes — so the library-change wake would lapse on a device that is caught up.
- `:app:android` refuses to start without the rig; the rig composes over `ANDROID_REAL_ADAPTERS` + mocks
  (`test/rig/src/androidMain/.../AndroidRig.kt`).

## Goals / Non-Goals

**Goals:**
- An Android member's own camera-folder photos reach the event in the background, under full or (14+) limited
  access, with the same guarantees `photo-sharing` and `background-upload` give an iPhone member except where the
  spec deltas say otherwise.
- iOS behaviour unchanged; `./gradlew build` and `compileIosMainKotlinMetadata` green at every PR.
- Every new adapter bound to its port contract on `ANDROID_EMU`, never silently skipped.

**Non-Goals:**
- Receiving (downloads, import, push/FCM, the album) — phase 4. The `Gallery` port's import and album writes
  answer the port's refusal values until then (D3).
- A production-startable `:app:android` — still refuses at start (downloads, push, crash reporting missing).
- A foreground service, user-initiated data-transfer jobs, resumable uploads, a periodic wake.
- Motion-photo conversion (phase 7).

## Decisions

### D1. The adapter defines the member's default gallery; the policy is the one decision over it

`GalleryReader`'s own-candidate reads (`assets(policy)`, `assetsById`, `resources`, and the selection snapshots
the change observer emits) return only assets in the platform's **default gallery**: on iOS the library (or the
selection, as today); on Android the DCIM folder — every MediaStore item whose `RELATIVE_PATH` starts with
`DCIM/` (case-insensitive), on **every** external volume (`MediaStore.getExternalVolumeNames`), trashed and
pending items excluded. The policy then decides over what the port returns, unchanged — including the album
denylist, because on Android an album IS a `DCIM` subfolder (D2).

- The upload, the manifest and `N` all read through the same port, so "the same policy gates bytes, manifest and
  N" stays true by construction.
- The allowlist is a small pure function in `:adapter:android` with a host unit test; the Android gallery
  contract gains a clause that `assets()` never returns an item outside it — the guard against an adapter bug
  sharing a whole camera roll.
- `assetsById` is scoped too. Its one production caller is the import-presence check (`GalleryAssetPresence` for
  `DownloadController`), a phase-4 concern; phase 4 imports into `DCIM/Camera` (decided here), so the scope
  never hides an import.
- Moving a photo out of `DCIM` removes it from the walk — the same path as a deletion — so it is
  withdrawn; moving it back re-admits it.
- `SelectionCalibration` stays one value; its denylist gains `Screenshots`, `Screen recordings` and
  `ScreenRecorder` (`Facebook` is already there). On iOS, which reads only user-created albums, these match only
  an album a user named so — harmless, and stated in the spec delta.
- The law's wording changes: `CLAUDE.md`, `SelectionPolicy.kt`'s "Where it lives" section (which says platform
  narrowing "can neither widen nor narrow the admitted set"), `docs/architecture.md` where it states the policy
  law, and the `Gallery.kt` port KDoc — *the platform defines the default gallery; the policy is the one decision
  over it*.

*Alternatives rejected:* a location fact on `AssetFacts` plus an `InCameraFolder` rule emitted everywhere (inert on
iOS) — every platform's facts would carry a field one platform uses, and an Android mapping that forgot it would
admit everything; a platform parameter to `selectionRulesFor` or a per-platform `SelectionCalibration` — a root
that names the wrong platform gets the wrong policy silently.

### D2. Android asset facts and resources

- **Identity:** `AssetId` is MediaStore's `_ID`, by identity (a decimal already obeys the canonical rule, as
  `AssetId`'s KDoc foresaw). One database spans every external volume, so it is unique across them; MediaStore owns
  it, so it survives an app reinstall ("a reinstalled app keeps its photos").
- **Capture date:** `DATE_TAKEN` (epoch ms) as ISO-8601 in UTC; null or `0` → the empty date, which `CaptureAfter`
  excludes (the spec's "an undated photo is not shared"; decided: no `DATE_ADDED` fallback).
- **Facts:** `isVideo` from the media type; `pixelArea = WIDTH × HEIGHT`, null when either is 0 (admit on doubt,
  as `AssetFacts` defines); `isEdited = false` (Android does not say — the floors apply to edited photos, as the
  spec states); `isScreenshot` / `isScreenRecording = false` (Android does not say; the phone's screenshot and
  screen-recording folders are excluded as albums instead).
- **Resources:** one `PRIMARY` resource per asset — a motion photo is its one file; `originalFilename` =
  `DISPLAY_NAME`; MIME = `MIME_TYPE`. Bytes are read through `MediaStore.setRequireOriginal(uri)` when
  `ACCESS_MEDIA_LOCATION` is held, so the location stays in the original.
- **Albums:** an album is a `DCIM` subfolder — MediaStore's bucket, which Android's gallery apps also show as
  albums. `albums()` lists the buckets under `DCIM` by `BUCKET_DISPLAY_NAME` (the folder's own name), `albumsById`
  looks them up, and `albumMembers(album, since)` queries the bucket's items. The bucket is on each item, so
  unlike iOS the album rule works under `LIMITED` too (spec delta). Nested folders: the bucket is the immediate
  folder, so `DCIM/Camera/Screenshots` would be the album `Screenshots`.

### D3. The `Gallery` port's phase-4 surface answers refusals

The album reads are real (D2). `createAlbum` → `null`, `addToAlbum` → a refused `WriteOutcome`, `import` → a
failed `ImportResult`; `export` is
implemented if the uploader needs a staged file (D6). These are values the core already handles, reachable only on
the rig build, and phase 4 replaces them.

A binding cannot decline a single clause — only a state it does not declare — so `GalleryReaderContract`'s five
album-write clauses move from `GRANTED_SEEDED` to a state of their own, `GRANTED_SEEDED_ALBUMS_WRITABLE`, which iOS
and the mocks declare and Android answers `Unreachable` (reported `NotRunHere`). Clause order is unchanged, so every
clause keeps its capture window. Two states only Android reaches cover what is new: `GRANTED_SEEDED_IN_A_FOLDER`
(`A_FOLDER_IS_A_LISTED_ALBUM_OF_ITS_ASSETS` — a `DCIM` folder is an album of exactly its assets) and
`GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY` (`ASSETS_OUTSIDE_THE_DEFAULT_GALLERY_ARE_NOT_READ`).

*Alternative rejected:* splitting `Gallery` into read/write interfaces now — an interface change across iOS, the
mocks and the contracts for a gap that lasts one phase.

### D4. Photo access on Android

| Android state | `GalleryAccess` |
|---|---|
| API 33+: `READ_MEDIA_IMAGES` **and** `READ_MEDIA_VIDEO` granted · API 30–32: `READ_EXTERNAL_STORAGE` granted | `GRANTED` |
| API 34+: only `READ_MEDIA_VISUAL_USER_SELECTED` granted | `LIMITED` |
| none granted, the app has asked before | `DENIED` |
| none granted, never asked | `NOT_DETERMINED` |

- Android does not report "never asked", so the adapter records in `Preferences` that it has asked.
- `requestAccess()` requests the media permissions plus `ACCESS_MEDIA_LOCATION` through an activity-result bridge
  on the `AndroidUi` activity; `widenSelection()` re-requests them, which on 34+ shows the system selection sheet
  (it also offers "Allow all"). After a refusal the access line opens the settings page instead of asking again —
  iPhone parity, and the spec's "A refused Android member is not asked twice".
- Under `LIMITED`, a MediaStore query returns only the selection, so the selection snapshot IS the scoped query;
  the iOS rule "read only at the baseline and on the observer" exists because of iOS's prompt and does not bind
  Android, but the same composition path (`SelectionScopedDiscovery`) is used.
- The change observer is a `ContentObserver` on the images and video collections of every volume, registered
  while `observeChanges(true)`, emitting re-queried snapshots. A grant change is re-read on `onForeground`;
  revoking in Settings kills the process anyway.

### D5. The library-change wake stays armed while the membership contributes

`Heartbeat` splits in two: [`arm`] requests the timed heartbeat, [`watchLibrary`] the library-change wake and answers
whether one now stands; `cancel` (a leave, or access that is not usable) withdraws both. `TailRunner` renews the watch
after **every** tail that ran the uploads for a contributing membership — any trigger but an import-only one, any
outcome but `SKIPPED` — so a caught-up device still notices the next photo. Join and start are covered: each runs a
tail. iOS answers the wake `Unsupported`, so nothing changes there.

**The heartbeat's "always" re-arm is relaxed where a watch stands** (found while implementing). Every OS wake runs the
tail as `HEARTBEAT`, and five triggers re-arm the heartbeat `ALWAYS` — even after a drained cycle. On iOS that
perpetual heartbeat is how the app looks at its library at all (it has no library-change wake), and iOS schedules a
`BGProcessingTask` sparsely. WorkManager honours the 60 s delay closely, so the same rule would wake a caught-up
Android phone every minute for as long as it is joined. So where `watchLibrary` answers that a watch stands, `ALWAYS`
becomes `WHEN_WORK_REMAINS`: a timed wake only while work remains, and the library-change wake for new photos. A
platform that refuses the watch falls back to the perpetual heartbeat. Pinned by `TailRunnerTest` (both tables).

The watch is not cancelled at the event's close: after the close nothing new is admitted, so a wake then is one cheap
walk, and the member leaves on its own once it has everything, which cancels it. No periodic wake (decided: the
heartbeat covers work left; a periodic job would be lost with the same WorkManager database as the others; revisit if
phase 6 shows missed photos).

### D6. The WorkManager-backed adapters

- **`Wake`:** one unique one-time work per `WakeId`.
  - `Heartbeat` → initial delay `earliest`, `NetworkType.CONNECTED`.
  - `LibraryChanged` → content-URI triggers on the images and video collections (descendants included),
    `setTriggerContentMaxDelay(maxDelay)`.
  - `schedule` uses `ExistingWorkPolicy.APPEND_OR_REPLACE`, because the tail re-arms from **inside** the running
    worker and `REPLACE` would cancel it.
  - The worker's `doWork` calls `WakeHandlers.onWake(id, completion)` and suspends until the completion is
    released; `onStopped` is `Completion.onExpired` — the OS's expiry, the only "time is up".
- **`BackgroundTime`:** `begin(label, onExpiry)` enqueues an expedited one-time "hold" work
  (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`) whose `doWork` waits for `end()`; its `onStopped` fires
  `onExpiry`. This keeps a process the member just left, or one a worker started, from being cached and frozen
  mid-unit. If the hold cannot be enqueued, that is an immediate expiry, as the port already allows.
- **`Upload` (the app uploader, `appUpload`):**
  - Transfers run in-process as Ktor PUTs, streamed from the content URI, or from a staged export if the port's
    `UploadSourceKind` requires a file.
  - At most 4 live transfers; the next `create` answers `LIMIT_EXCEEDED`.
  - Each end is reported inline through `UploadHandlers.onFinished`.
  - The adapter holds one `BackgroundTime` hold while any transfer is live, so leaving the app does not freeze
    them.
  - Transfers die with the process, so the adapter keeps a small **job journal** in `Preferences`. At the next
    start, every journaled job that never finished is reported as failed. The existing failure path returns its
    row to `DISCOVERED` for a free retry; nothing sits `REQUESTED` forever. Its port events are registered by the
    host, like the iOS `URLSession` adapter's.
- **No OS-driven tier:** `osSupportsOsDrivenUpload = false`; `cycleUpload` unset; `ExtensionRegistry` is an
  `:adapter:android` adapter answering `RegistrationAnswer.Unsupported` / `NOT_REGISTERED`, exactly as iOS below
  26.1.
- **Workers are entry ports on the one composition.** `Application.onCreate` composes as today; a worker that
  cold-starts the process builds no UI (the status host stays lazy until an activity pulls the screen).

**Force-stop, Doze, first unlock:**
- **Force-stop** cancels every WorkManager job until the app is next opened, when WorkManager reschedules. This is
  the force-quit requirement.
- **Doze and App Standby** defer work to maintenance windows. The spec allows "the system MAY schedule…".
- **Before the first unlock** the app, which is not direct-boot aware, never runs, so nothing guesses.

### D7. System UI

`AndroidSystemUi`: `share` → `ACTION_SEND` chooser, `openUrl` → `ACTION_VIEW`, `openSettings` →
`ACTION_APPLICATION_DETAILS_SETTINGS`. It is in this phase because the access flow's "open Settings" needs it; the
other two are a few lines each.

### D8. Rig and contracts

- `ANDROID_REAL_ADAPTERS` gains the library, wake, background-time, upload-session, extension-registry and
  system-UI systems; `AdapterChoice`'s coherence rules are extended where a real system needs another real (e.g.
  real uploads with a mocked backend reach the mock's base).
- Seeding (measured 2026-09-29): MediaStore hides a photo the SHELL owns (`adb push`, `UiAutomation`) from every other
  app, so a fixture is inserted by the reading process itself — its own items, which it reads without any grant and
  deletes without a confirmation — with the capture date in EXIF as well as `DATE_TAKEN`, since publishing re-derives
  it from the file. The same holds for an end-to-end run: an `adb push`ed photo is invisible to the app.
- Contracts on `ANDROID_EMU`:
  - The photo-library contracts (`GalleryReader`, `Gallery`, `PhotoAccess`), over a MediaStore seeded by the
    instrumented test itself: inserts into `DCIM/Camera/` and into other folders, each clause in its own
    capture-date window, as today. Grants via `UiAutomation.grantRuntimePermission`.
  - `LIMITED` needs the system selection sheet. It is driven with UiAutomator if that proves stable, and reports
    `NotRunHere` with the reason otherwise.
  - The `UploadContract` against the loopback transfer fixture (the emulator reaches the host at `10.0.2.2`).
  - The `WakeContract` through WorkManager's test driver (delays, constraints and content triggers fired
    deterministically).
  - The extension-registry contract's refusal clauses.
- End to end on the emulator: the rig build, the real library, uploader and wake, against a local `api/`
  (`deno task dev:local`), on an event this session creates.

### D9. One PR

The whole phase ships as one PR (label `internal`): this change, the gallery and photo access, the uploader, the
rewording of the policy law, and the sync/archive with the mission text. Nothing in it reaches an App Store
customer, and the Android build is not released. The tasks keep their groups as work order, not as PRs.

## Risks / Trade-offs

- **[Media outside `DCIM`, and unknown folders inside it]** In-app cameras (Instagram, Snapchat) and messengers
  save outside `DCIM`, so those photos are silently never shared — accepted. Inside `DCIM`, a folder no list names
  is shared (admit on doubt), so a new app that saves received media into `DCIM` leaks until its title is added.
  → The denylist grows with app releases; phase 6's closed test is where both kinds of miss surface.
- **[Screenshots saved outside a named folder]** A maker that puts screenshots or screen recordings in a `DCIM`
  folder with another name has them shared unless the image floor catches them (a typical screenshot is ~2.6 MP,
  below it; a QHD one is not). → Add the title when seen.
- **[MediaStore identity changes]** A media-database rebuild, or a file manager that copies and deletes instead of
  moving, gives a photo a new `_ID`. It is then uploaded again as a new photo, and members who kept the first copy
  receive a second one. → Accepted and rare; noted for phase 6.
- **[OEM background killers]** Some makers defer or drop content-URI jobs, or turn swipe-away into force-stop. →
  The spec words these as the maker's; measure in phase 6; a periodic wake is the known fallback.
- **[Large videos]** A worker gets about 10 minutes; a large video on a slow link restarts from zero each time. →
  Measure on the emulator with `adb emu network speed` (order of magnitude only — no real radio, no Doze under
  motion). If it stalls, the escalation is resumable uploads in the api, not a foreground service.
- **[Revoke kills the process mid-upload]** → The job journal reports the transfer as failed at the next start;
  retried once access returns (spec delta).
- **[`ACCESS_MEDIA_LOCATION` and `setRequireOriginal`, unmeasured]** The emulator cannot produce the case headlessly
  (measured 2026-09-29): MediaStore redacts location only from ANOTHER app's photo, but a photo the shell writes (`adb
  push`, `UiAutomation`) is invisible to every other app, and the emulator's AOSP camera — whose photos are visible —
  writes no GPS even with a location fix (and saves to `Pictures/`, not `DCIM`). So the original read is the platform's
  documented mechanism, unmeasured here. → Phase 6's closed test checks that a real camera photo arrives with its
  location, under `GRANTED` and `LIMITED`; if not, the spec's "location travels" gets an Android caveat.
- **[In-place edits]** A gallery app that saves an edit over the file changes its bytes and facts. An edit that
  lands below the floor withdraws the photo; an edit made before upload is what is uploaded (spec delta). →
  Accepted.
- **[Phase 4 imports into `DCIM/Camera`]** The download store (`NotEcho`) becomes the only guard against
  sharing a download back; a reinstall that loses the store and rejoins the same event would re-share downloads
  as the member's own. → Phase 4's to mitigate; recorded as its input.
- **[Play policy]** Holding `READ_MEDIA_*` needs the Photo & Video Permissions declaration. → Phase 5.

## Measured on the emulator (2026-09-29, API 36, rig build, local api)

- **Access:** the join raised Android's dialog; "Allow limited access" opened the selection sheet; the joined screen
  then read "In sync" with "Choose more photos" and "Allow full access", no attention line.
- **Background upload:** joined under a full grant and sent home, the app had exactly one job pending — the
  library-change wake, no heartbeat. Four photos seeded into `DCIM/Camera` (two above the 3 MP floor, two below)
  woke it (`onWake(id=LibraryChanged)`) within the minute; exactly the two above-floor photos landed, `N` read 2, and
  the watch was re-armed.
- **Withdrawal:** deleting a shared photo from MediaStore woke the app again; the walk saw one deletion and the
  manifest PUT dropped it from the event's photo set.
- **Force-stop:** cancelled the pending wake; the next opening restored it.
- **Cold wake:** a library change after the process was killed started it through WorkManager; the wake ran a full
  walk and built no screen.
- **Seeding:** a photo the shell writes is invisible to the app, so the rig's `device/gallery/seed` inserts the app's
  own photos where the library is real.

**Large videos — not measurable here.** The local api is reached over `adb reverse`, which bypasses the emulator's
modem, so `adb emu network speed` shapes nothing on that path. What stands is arithmetic over WorkManager's documented
10-minute execution window, which bounds the hold a transfer runs under: at a 5 Mbit/s uplink one window carries
~375 MB, at 20 Mbit/s ~1.5 GB; a 4K clip is ~350–400 MB a minute. A video that cannot finish inside one window restarts
from zero every run and never lands. Phase 6's closed test is where a real uplink shows whether this bites; resumable
uploads (the api assembling parts) are the escalation, not a foreground service.

## Migration Plan

None for iOS (no behaviour change; the `Heartbeat` split is inert there). Android has no released build. Rollback is
a revert of the one PR.

## Open Questions

- Whether `UploadSourceKind` lets the Android uploader stream from a content URI or requires a staged file — either
  fits D6; answered by reading the port in task 4.4.
- Whether any composition path requires a real `ProcessInfo` on Android (it is diagnostic-only on iOS); if so, a
  one-line adapter over `UserManager.isUserUnlocked` (task 2.10).
