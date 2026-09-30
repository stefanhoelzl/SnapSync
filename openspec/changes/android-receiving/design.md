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

**Staging is a copy.** The external files dir and the SHARED area are different mounts, so "move to staging" is a
copy plus delete. The completion receiver's budget is about 10 s. A large-video fixture measures the copy; if it
does not fit, `DownloadJobs`' staging root moves to the same volume (a change to where staging lives, not to the
port).

**Missed broadcasts.** A force-stopped app receives no broadcasts. On every process start, the adapter queries its
own rows and delivers every finished row still present through the same handlers. It calls `remove(id)`, which
also deletes the file, only after `onFinished` has returned. So delivery is at-least-once, which staging already
tolerates.

**`cancelAll`.** `DownloadManager.remove` broadcasts nothing, so the adapter reports `onCompleted(tag,
"cancelled")` itself for every row it removes, as the port promises.

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
3. `update(IS_PENDING = 0, DATE_TAKEN = creationDate)`: the one atomic point where the item goes live.
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

### D7. Capture date: the file first, `DATE_TAKEN` as the fallback, measured

The bytes are unchanged, so an iPhone photo carries EXIF `DateTimeOriginal` + `OffsetTimeOriginal`, and a MOV
carries `mvhd creation_time`. On publish, MediaProvider scans the file and fills `DATE_TAKEN` from them. Step 3
also writes `DATE_TAKEN = creationDate`: if the scanner overrides it, the instant is the same; if the file has no
date, ours is the only one.

Four emulator fixtures pin a contract clause, "a received item's `DATE_TAKEN` equals its capture time":
1. an iPhone HEIC with an offset;
2. an iPhone HEVC MOV;
3. a JPEG without EXIF;
4. an Android MP4.

If fixture 3 shows MediaStore drops the value, the fallback is to set the file's modification time to the
capture time before publishing. Whether vendor galleries and Google Photos sort by `DATE_TAKEN` or read EXIF
themselves is only visible on phase 6's devices. A display-name collision in `DCIM/Camera` gets MediaStore's
`IMG_1234 (1).HEIC`; the spec delta states it.

### D8. An FCM push runs the existing silent-push flow within FCM's budget

`AndroidPushNotifications` (a `FirebaseMessagingService`):
- **`onMessageReceived`** delivers `onMessage(PushMessage(remoteMessage.data), completion)` and blocks the FCM
  worker thread until `completion` is released. At about 9 s it signals "time is up" through the process's
  `BackgroundTime` expiry path, so the core's existing expiry handling applies and no wall-clock bound enters the
  core.
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
- **The completion receiver's 10 s budget vs a cross-volume copy of a large video** → measured with a fixture; D5
  names the fallback.
- **The `DATE_TAKEN` fallback may be dropped** for a file with no date → D7's fixture decides; the fallback sets
  the file's modification time.
- **A kill during import steps 1–2 under Android 14's limited access** leaves the sweep UNKNOWN (only a full grant
  may answer ABSENT), and that one photo waits until access widens → the window is milliseconds long. The
  presence rule is shared with iOS, so it stays; recorded here as a known limitation.
- **Mobile data for a Live Photo's discarded video** (D1) → until phase 7 uses it.
- **One extra push registration per iOS device** after the update (D3) → an idempotent UPDATE.
- **Rebase with phase 8**, which also edits `receiving-photos` → this change leaves the wake requirement alone;
  whichever lands second rebases.

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

## Open Questions

- Whether the staging copy fits the receiver's budget for the largest real video, and whether MediaStore keeps an
  app-written `DATE_TAKEN` for a file without a date. Both are measured during implementation, each with a named
  fallback that changes neither the specs nor the task list.
