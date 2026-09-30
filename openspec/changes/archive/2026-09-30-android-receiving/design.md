## Context

See proposal.md for why. What the tree gives this phase:

- **Download port.** `Download` is `start(url, tag)` + `cancelAll()` with `DownloadHandlers` (`onFinished(tag,
  facts, tempPath)` — inline, the platform deletes `tempPath` when it returns — `onCompleted`, `onInvalidated`,
  `onBackgroundEvents(completion)`, `onEventsDrained`). `DownloadJobs` stages each finished body through
  `StagingService` (the SHARED area; on Android `filesDir/shared`).
- **Download planning.** `DownloadController` plans every resource the union lists for an asset (`PRIMARY` and,
  for a Live Photo, `LIVE`) and imports an asset once all its planned resources are staged. After a confirmed
  import it releases the asset's staged bytes (`releaseStagedBytes(ref)`).
- **Import and suppression.** `GalleryImport.import(request)` is one platform transaction; `onImportPlaceholder`
  records the created id **before** the asset can be observed (it is `NotEcho`'s suppression handle), and
  `onImportSettled` records the outcome from the completion. `sweepInterruptedImports()` runs once per process:
  PRESENT settles, ABSENT clears the marker and re-imports, UNKNOWN (no full grant) leaves the row.
- **Android gallery stubs** (phase 3, D3): `createAlbum` → `null`, `addToAlbum` → `Failed`, `import` → `Failed`.
- **Push.** `PushNotifications` is `register()` + `PushHandlers(onToken, onTokenFailure, onMessage(payload,
  completion))`. The model type is `ApnsPushToken(token, env)`; `HttpBackend` sends `kind: "apns"` as a literal;
  the registration record's key is `env \n deviceId \n token`. `pushEventId(payload)` reads `payload["eventId"] as?
  String`. Android's root already builds `PushTokenSource("sandbox")` as a placeholder.
- **Backend.** `devices` stores `push_kind`, `push_token`, `push_env`; the push route accepts any kind; the fan-out
  selects rows where all three are non-null; `apns.ts` skips a non-`apns` kind. Every wake — a new photo and the
  close — goes through one `sendSilent(tokens, eventId)` call, whose payload is only the event id.
- **Plain build.** Android's real `DevicePorts` has no `download`, `pushNotifications`, `crashReporter` or
  `processInfo`; `DevicePorts` fills a missing one with `absent(...)`, which throws on first use.
  `snapSyncProcess` reads `crashReporter` at start even with `dsn = null`, so the plain build could not start even
  if `ProdPlatformAdapters` stopped refusing.
- **Presigned links** last 7 days (`PRESIGN_EXPIRY_SECONDS`), re-presigned on every union read.

## Goals / Non-Goals

**Goals:**
- Other members' photos and videos arrive in an Android member's camera roll, in the background, exactly once,
  never shared back, with the guarantees `receiving-photos` gives an iPhone member except where the delta says
  otherwise.
- Silent wakes reach Android through FCM for new photos and for the close.
- The plain `:app:android` build starts and is fully functional without crash reporting.
- iOS behaviour unchanged (one harmless push re-registration aside, D3); `./gradlew build` and
  `compileIosMainKotlinMetadata` green at every commit.
- Every new adapter bound to its port contract on `ANDROID_EMU`, never silently skipped.

**Non-Goals:**
- A periodic receiving check, and any change to "The app SHALL NOT poll in the background" (phase 8, workspace
  `periodic-check`). The wake requirement's "iOS drops or delays the wake" wording is also left for phase 8, which
  restates that requirement anyway; touching it here would only make the two deltas conflict.
- Live Photo → motion-photo conversion, and transcoding any video (phase 7).
- Sentry, a real `ProcessMetrics`, release signing, Play delivery (phase 5).
- Push for devices without Google Play services.

## Decisions

### D1. A received Live Photo is downloaded whole; the Android import uses its still

Planning stays platform-blind: both resources are downloaded and staged. The Android import builds the item from
the `PRIMARY` resource only and ignores `LIVE`; the core's `releaseStagedBytes(ref)` frees both staged files once
the import confirms, so nothing is left behind (spec: "Received photos do not take up space twice").

- **Rejected: filter by role in planning** (the gallery import states which roles it can import). This saves the
  video's mobile data, but adds a platform fact to the core for a stopgap. Phase 7 will make Android import the
  `LIVE` resource into a motion photo, and then the downloaded video is needed anyway.
- **Rejected: a backend filter by platform.** An API change that ties the listing to a platform.

### D2. `GalleryReader.supportsAlbumWrites`; presentation hides the album choice where it is false

`GalleryReader` gains `val supportsAlbumWrites: Boolean`, beside the `createAlbum`/`addToAlbum` it describes.
They sit on the reader because the iOS upload extension files photos into the album and gets only the reader.
`IosGalleryReader` answers `true`, `AndroidGalleryReader` `false`, and `PhotoLibraryMock` has an operator switch
defaulting to `true`. The composition reads it once and hands presentation a plain Boolean, since presentation
cannot see ports. Presentation then:
- carries an "album offered" flag on the join and settings forms, so the screens omit the album section and its
  notes;
- saves `saveToAlbum = false` on every join and settings save where it is false, so the stored membership is
  honest and `AlbumCoordinator` never runs on Android.

On Android `createAlbum → null` and `addToAlbum → Failed` stop being stubs and stay: they are consistent with the
declared `false`. The KDoc states the reason, so nobody "fixes" the flag by creating a folder. An Android album is
a folder, and a file lives in one folder, so filing a received photo would mean moving it out of the camera roll
or copying it.

- **Rejected: `supportsAlbums`.** Android *reads* albums: DCIM subfolders are the albums the denylist matches.
- **Rejected: a `PlatformCapabilities` port.** A port named for "the platform" rather than a need, and one that
  would collect unrelated facts; a constant also needs no contract host. **Rejected: a Boolean on `AppPorts`.**
  The fact belongs to an external system (the photo library), so the adapter answers it.
- **Rejected: hiding the switch while keeping `saveToAlbum = true` underneath.** The album code would then fail
  quietly on every Android join.

### D3. The push port states its kind; the registration carries it

`PushNotifications` gains `val kind: String`: the iOS adapter answers `"apns"`, the Android adapter `"fcm"`, and
`PushServiceMock` has a setting defaulting to `"apns"`.
- **The model.** `ApnsPushToken(token, env)` becomes `PushEndpoint(kind, token, env)` (not `PushRegistration`, which
  is already the feature class that publishes it), and `PushToken(hex)` becomes `PushToken(value)`, since FCM tokens
  are not hex.
- **The token source** carries the kind beside the env: each root builds `PushTokenSource(kind, env)` with the kind
  read from its push port and the env it states.
- **The request.** `HttpBackend` sends the registration's `kind`; for iOS the request body stays byte-identical.
- **The key.** `registrationKey` gains the kind, so an install whose kind changed can never read as "already
  registered". Each iOS device's stored key misses once after the update and re-publishes once. The route is an
  idempotent UPDATE, so nothing observable changes. Rejected: keeping the old key format for `apns`, a
  special case forever to save one request per device.
- **`env` stays root-stated on both platforms.** It follows the build's signing on iOS (`bakedApnsEnv()`), and
  the Firebase project on Android (D9). Moving it onto the port would change iOS startup for no gain.

### D4. Android downloads run on DownloadManager, into the app's external files dir

`AndroidDownload` enqueues each `start(url, tag)` with:
- `VISIBILITY_HIDDEN`, which needs the install-time `DOWNLOAD_WITHOUT_NOTIFICATION` permission;
- a destination in `getExternalFilesDir()/downloads/`;
- metered and roaming networks allowed;
- the tag in the request's description.

The OS owns the transfer, so it survives process death and resumes, like iOS's background `URLSession`.
- **Rejected: a WorkManager worker** with Range-resume or restart. It reimplements what DownloadManager does, under
  the expedited quota.
- **Rejected: DownloadManager writing straight into `DCIM/Camera`.** The download provider would own the file, and
  a partial file could be visible.
- **Rejected: the default visible notification.** One entry per photo floods the shade, and the app's status line
  already shows receiving. The specs never promise a system notification.

### D5. Completions: a manifest receiver per broadcast, plus a recovery pass at every start

`ACTION_DOWNLOAD_COMPLETE` is sent to the package that queued the download, so a manifest receiver gets it with
the process dead. The process starts, `Application.onCreate` composes (no UI is built), and the receiver maps one
broadcast onto the handlers as one small wake:

| iOS | Android |
|---|---|
| `onBackgroundEvents(completion)` | `goAsync()`; `completion` = `pendingResult.finish()` |
| `onFinished(tag, facts, tempPath)` | `STATUS_SUCCESSFUL`: the tag from the description; facts = 200, `TOTAL_SIZE_BYTES`, `BYTES_DOWNLOADED_SO_FAR`; the local file |
| `onCompleted(tag, error)` | always; the error names `COLUMN_REASON` on failure |
| `onEventsDrained()` | after that one row |
| `onInvalidated()` | never |

**Staging is a copy.** The external files dir and the SHARED area are different mounts, so the core's "move to
staging" (`Files.adopt`, a non-atomic `java.nio` move) copies and deletes; no adapter change is needed. The receiver
holds the broadcast (`goAsync`) until the core releases its completion, at most 50 s — a background broadcast is allowed
about a minute. Measured 2026-09-30 on the emulator: a 500 MB copy across the two mounts took 0.49 s, so even a phone
several times slower stays far inside it, and the core's background-time hold (an expedited job) keeps the process
alive past the broadcast anyway. A completion broadcast, like a background `URLSession` relaunch, has no expiry signal
of its own: `onExpired` never runs (the `Completion` contract), and its only "time is up" is that hold.

**Missed broadcasts.** A force-stopped app receives no broadcasts. On every process start, the adapter queries its
own rows and delivers every finished row still present through the same handlers. It calls `remove(id)`, which
also deletes the file, only after `onFinished` has returned. So delivery is at-least-once, which staging already
tolerates.

**`cancelAll`.** `DownloadManager.remove` broadcasts nothing, so the adapter reports `onCompleted(tag,
"cancelled")` itself for every row it removes, as the port promises.

**Two contract clauses gain a second honest shape.** An HTTP error status and a body with no declared length are
*finished* transfers under `URLSession` (the status reported, the owner's integrity check refusing it) but *failed*
ones under DownloadManager (the status as the reason; "can't know size of download"). The core treats both the same —
nothing is staged and the resource stays pending — so `AN_ERROR_STATUS_IS_A_FINISHED_TRANSFER_OF_ITS_BODY` and
`NO_LENGTH_IS_NEGATIVE` accept either, and still forbid the one lie: an error or an unsized body reported as a success.
Every presigned object declares its length, so the second never meets a real download.

**Expired links.** DownloadManager retries network errors and 5xx itself, but a 403 from an expired link is final:
`STATUS_FAILED`, then the broadcast, then `onCompleted` with an error. The resource stays pending, and the next
reconcile plans it with a fresh link. That is the existing mechanism, with no new core logic.

### D6. The Android import: pending insert → record → write → publish

`AndroidGallery.import` works in four steps:
1. Insert into `MediaStore.Images` (for an `image/*` `PRIMARY`) or `MediaStore.Video` (for `video/*`), with
   `RELATIVE_PATH = DCIM/Camera/`, the display name from `ImportNaming`, the MIME type, and `IS_PENDING = 1`.
   The `_ID` becomes the `AssetId`, recorded through `onImportPlaceholder(ref, id)`. A pending item is invisible to
   every other app and to our own reads, so this satisfies "before it can be observed".
2. Copy the staged `PRIMARY` bytes through `openOutputStream`, unchanged: a HEIC stays HEIC, a MOV stays
   `video/quicktime`, with no transcoding.
3. Stamp the file's modification time with `creationDate` (D7), then `update(IS_PENDING = 0)`: the one atomic point
   where the item goes live. An original that does not decode, and a content type that is neither image nor video,
   are refused for good before step 1 — MediaStore stores whatever bytes it is given.
4. `onImportSettled(Imported(id))`, after which the core releases the staged bytes.

Any other content type is answered as a permanent failure (spec: "A photo the library rejects").

| Killed after | Left behind | Next start |
|---|---|---|
| before step 1 | nothing | a normal import |
| step 1, before the record lands | an unrecorded pending row | re-import; the orphan is cleaned up |
| steps 1–2 | a recorded id, the row still pending | sweep ABSENT (pending is not returned) → re-import; the orphan is cleaned up |
| step 3 | a visible, recorded item | sweep PRESENT → settled, no second copy |

The item is visible exactly once in every row of that table, and the uploader never sees it unrecorded.

**Orphan cleanup.** MediaStore expires pending items after 7 days anyway, but the adapter deletes its own pending
rows in `DCIM/Camera` once per process, before its first import, so a half-written video does not hold space for
a week. Only the app's own pending rows are visible to it.

The rest of the Gallery import contract, bound on `ANDROID_EMU`, is unchanged. Its new clauses kill the import at
each of the three points and check the next start.

### D7. Capture date: the file's own metadata, and the modification time where it has none — measured

The bytes are unchanged, so an iPhone photo carries EXIF `DateTimeOriginal` + `OffsetTimeOriginal`, and a movie its
`mvhd creation_time`. On publish, MediaProvider scans the file and fills `DATE_TAKEN` from them.

Measured on the emulator (API 36, 2026-09-30), with fixtures generated to carry an iPhone's metadata
(`adapter/android/src/androidDeviceTest/resources/import/`):
- **An iPhone HEIC with `+02:00`, an iPhone HEVC MOV and an Android MP4:** `DATE_TAKEN` is exactly the capture instant,
  from the file.
- **A JPEG with no date of its own:** `DATE_TAKEN` stays empty. MediaProvider **ignores an app's `DATE_TAKEN`**
  ("Ignoring mutation of datetaken") — written with the insert, with the publish, or in an update after it — because
  only the scan writes it. So the import sets the pending file's **modification time** to the sender's capture time
  before publishing, and the scan's `DATE_MODIFIED` carries it. Rejected: writing an EXIF date into such a file, which
  would change the original's bytes.

The Android import test pins both halves; the `GalleryImport` contract's binding reports the date the library sorts
by (`DATE_TAKEN`, else `DATE_MODIFIED`). Whether a gallery app sorts an undated item by `DATE_MODIFIED` is only
visible on phase 6's devices; every photo an iPhone sends carries its own date. A display-name collision in
`DCIM/Camera` gets MediaStore's `IMG_1234 (1).HEIC`; the spec delta states it.

### D8. An FCM push runs the existing silent-push flow within FCM's budget

`AndroidPushNotifications` (a `FirebaseMessagingService`):
- **`onMessageReceived`** delivers `onMessage(PushMessage(remoteMessage.data), completion)` and blocks the FCM
  worker thread until `completion` is released, at most about 9 s, then returns. The completion has no expiry signal
  of its own (the `Completion` contract: a silent push's only "time is up" is the process's background time, which the
  core already holds across the wake), so no wall-clock bound enters the core.
- **The tail.** `TailRunner` requests its `BackgroundTime` hold (an expedited WorkManager job) before the
  completion is released, as it already does, so the job is enqueued while a high-priority message has the app
  on its temporary allowlist.
- **Tokens.** `onNewToken` becomes `onToken`. `register()` calls `FirebaseMessaging.getInstance().token` and
  reports its answer through `onToken` or `onTokenFailure`. A device without Google Play services reports
  `onTokenFailure` and never gets a wake; the spec already covers that ("Wakes are best effort").

**Firebase configuration.** `FirebaseApp.initializeApp(context, options)` runs in `Application.onCreate`, with the
options built from `BuildConfig` values the resolved deployment renders (`firebaseProjectId`,
`firebaseApplicationId`, `firebaseApiKey`, `firebaseSenderId`). There is no `google-services.json` and no
google-services plugin, matching how every other deployment value reaches the build. Firebase's auto-init
provider is disabled in the manifest, so the rig and tests never start FCM unasked.

### D9. The FCM wire, and `push_env` = the Firebase project id

```
POST https://fcm.googleapis.com/v1/projects/<projectId>/messages:send
Authorization: Bearer <OAuth access token>
{ "message": { "token": "<fcm token>", "data": { "eventId": "<id>" },
               "android": { "priority": "HIGH", "collapse_key": "<id>" } } }
```

- **Data only.** There is no `notification` block, so the app handles every message. The data arrives as
  `Map<String, String>`, so `pushEventId` reads it unchanged.
- **One collapse key per event**, the counterpart of `apns-collapse-id`.
- **Authentication is two steps.** A jose `SignJWT` (RS256, `iss` = the service account's `client_email`, `scope`
  = `https://www.googleapis.com/auth/firebase.messaging`, `aud` = `https://oauth2.googleapis.com/token`) is
  exchanged for an access token, cached for about 50 min.
- **`push_env` = the Firebase project id.** FCM has no sandbox/production split, but tokens are bound to a project.
  The FCM sender sends only when `env` equals its configured `firebaseProjectId` and otherwise answers `skipped`,
  as APNs does for an unknown env. A second Firebase project added later then fails soft, with a named reason.
- **One sender for both.** `createPushSender` fronts `apns.ts` and `fcm.ts`, picks one per token by kind, keeps
  `SendOutcome[]`, and never throws. Every call site of `apns.sendSilent`, the close wake included, goes through it
  unchanged.
- **A missing key never stops the backend.** Like the APNs key, the FCM key is imported on first use; without it
  every FCM token is `skipped`.

### D10. The plain build composes its real adapters

`real` gains `download = AndroidDownload`, `pushNotifications = AndroidPushNotifications`, `processInfo =
AndroidProcessInfo` (`UserManager.isUserUnlocked()` → AVAILABLE/UNAVAILABLE, Android's counterpart of "protected
data available") and `crashReporter = NoCrashReporter`. `NoCrashReporter` is a new inert binding in
`compose/Inert.kt`, beside `NoProcessMetrics`, where the laws place inert bindings. It starts, captures and
listens to nothing; the no-DSN save path of the diagnostic dump is checked so it never relies on the reporter.
`ProdPlatformAdapters` returns the real set with `InertDevControls`, the plain UI and a boot line.
`MockContainmentTest` already fails a plain build that links a mock.

### D11. The reinstall gap is stated, on both platforms

The download store is the only record of what this device received (the rejected alternatives were a filename
prefix, which is visible and not robust, and an XMP marker, which changes the bytes). Deleting the app deletes it:
the App-Group container on iOS, and app data on Android, where Auto Backup is excluded. The device id survives on
both (Keychain; `ANDROID_ID`). A reinstall followed by a rejoin of the same event therefore:
- **receives the event's photos again**, which is not stated anywhere today; and
- **may share received photos back as the member's own** if they lie in the capture range.

Both platforms already behave this way. The delta states both gaps rather than implying a guarantee.

## Risks / Trade-offs

- **FCM downgrades silent high-priority messages** that never lead to a user-visible notification, to normal
  priority, which Doze then holds for hours → photos still arrive on the next opening (spec), and phase 8's
  periodic check becomes more important on Android than on iOS. Measured only in phase 6's closed test.
- **Vendor task-killers** (Xiaomi, Huawei, …) block FCM and broadcasts until the app is opened → same fallback as a
  dropped wake; D5's recovery at every start catches finished downloads.
- **MOV playback** depends on the device's HEVC decoder → accepted as a platform limit; phase 6 checks it on real
  devices.
- **A multi-gigabyte video's staging copy on a slow phone** → the broadcast is held at most 50 s and the core's
  background-time hold keeps the process alive past it; measured 0.49 s for 500 MB on the emulator (D5).
- **A received file with no date of its own has no `DATE_TAKEN`** (MediaProvider takes it only from the file) → its
  `DATE_MODIFIED` is the capture time (D7); a gallery app that ignores `DATE_MODIFIED` would sort it by arrival —
  phase 6 shows which do. iPhone originals always carry their date.
- **A kill during import steps 1–2 under Android 14's limited access** leaves the sweep UNKNOWN (only a full grant
  may answer ABSENT), and that one photo waits until access widens → the window is milliseconds long. The
  presence rule is shared with iOS, so it stays; recorded here as a known limitation.
- **Mobile data for a Live Photo's discarded video** (D1) → until phase 7 uses it.
- **One extra push registration per iOS device** after the update (D3) → an idempotent UPDATE.
- **Firebase Messaging 25 deprecates `getToken()`/`onNewToken`** for `register()`/`onRegistered` → kept, with explicit
  suppressions: the core asks for the token at every entry and compares, which is `getToken()`'s semantics; whether
  `onRegistered` re-delivers an unchanged token to a new process is unmeasured until a Firebase project exists (closed
  test). Moving is a change inside `AndroidPushNotifications` alone.
- **Rebase with phase 8**, which also edits `receiving-photos` → this change leaves the wake requirement alone;
  whichever lands second rebases.

## Verification on the emulator (2026-09-30)

- **A real FCM wake, end to end.** Emulator (Google APIs image, API 36), the rig build with every system real but the
  crash reporter, against a local api run with the FCM key (the dev rig lets exactly Google's two FCM endpoints
  through when given a key). FCM issued a token; the app registered it (`kind fcm`, env `snapsync-142c3`). With the
  app's process killed, a second member shared a photo: the api pushed through FCM (`1 pushed`), FCM started the
  dead process, the silent-push flow reconciled the active event, and the photo landed in `DCIM/Camera` — no activity,
  no UI. On the new process `getToken()` answered the unchanged token, recognized and not re-published (D8).
- **Receiving over a local api**, and **nothing shared back**: the walk after the import added no rows; the union
  held only the other member's asset.
- **The plain build** cold-starts on all its real adapters, and a completion broadcast starts a dead process with no
  host built.
- **The FCM service account** is a dedicated `snapsync-fcm-sender` with only *Firebase Cloud Messaging API Admin*
  in `snapsync-142c3` — not the broad *firebase-adminsdk* account. A new role took about three minutes to reach FCM
  after IAM already reported it (a `validate_only` send answered 403, then 400 on the dummy token).

## Migration Plan

1. **Before the api commit can merge — you (config before code):**
   1. Create one Firebase project with an Android app for the application id.
   2. Put its public values (project id, application id, API key, sender id) into
      `deployments/components/android.json`.
   3. Create a service account with the *Firebase Cloud Messaging API Admin* role, and set its JSON key as the
      Edge Script secret `FCM_SERVICE_ACCOUNT_KEY`.

   `api/src/deployment.ts` gains `firebaseProjectId` and `fcmServiceAccountKey: { env: "FCM_SERVICE_ACCOUNT_KEY"
   }`. The PR does not merge until the secret is confirmed set.
2. **No database migration.** The push columns exist, and `kind: "fcm"` registrations are accepted today.
3. **Rollback.** Reverting the PR restores APNs-only sending. FCM rows stay in `devices` and are skipped by the old
   `apns.ts`. On iOS, reverting re-publishes once more.

