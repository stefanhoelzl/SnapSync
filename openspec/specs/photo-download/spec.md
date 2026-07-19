# photo-download Specification

## Purpose

The **receive** half of an event: a joined device automatically downloads the *other* contributors' complete
assets from the event-wide union listing and imports them, full-fidelity, into the system Photos library — so
a shared event's photos appear on every participant's phone without anyone opening the app.

This is what turns a one-way contribution client into photo sharing. Foreign assets are selected by
`deviceId` (anything not this device's); transfers run on a background `URLSession` over Wi-Fi and cellular;
import preserves the original capture date so photos sort by when they were taken. Downloaded photos are
suppressed from re-upload — no echo — and a photo the user deletes locally is never re-imported, because
respecting a deletion matters more than completeness.

The app renders **no gallery of its own**: collected photos live in the camera roll (and, per `event-album`,
in a per-event album). Whether this device downloads at all is governed by the membership's participation
direction (`join-event`).

Decision record: `changes/archive/2026-06-30-add-photo-download` (the download client),
`changes/archive/2026-07-12-fix-download-session-lifecycle` (the transfer/session lifecycle: why
cancellation is task-level and never invalidates the background session, and the transport seam that puts
that logic under test).
## Requirements
### Requirement: Foreign-asset selection by device identity

The download client SHALL consume the event-wide union read (`GET /events/<eventId>/files`) for the
joined event and SHALL select for download exactly those assets whose owning `deviceId` is **not**
this install's `deviceId` (from `device-identity`) and that are **not** already recorded as imported
in the download store. Assets owned by this device SHALL NOT be downloaded (they are already in this
library). The union returns only **complete** assets, so the client SHALL NOT perform any
completeness computation of its own.

#### Scenario: Own-device assets are skipped

- **WHEN** the union lists an asset whose `deviceId` equals this install's device id
- **THEN** the client does not download or import it

#### Scenario: Foreign, not-yet-imported assets are selected

- **WHEN** the union lists an asset whose `deviceId` differs from this device and no terminal
  download-store row exists for `(deviceId, assetId)`
- **THEN** the client selects every resource of that asset for download

#### Scenario: Already-imported foreign assets are skipped

- **WHEN** the union lists a foreign asset that the download store records as imported
- **THEN** the client does not download or import it again

### Requirement: Background resource download to durable staging

Selected resources SHALL be downloaded via a background `URLSession` (so transfers continue while the
app is suspended), fetching each resource **directly** from the presigned S3 URL carried in its union
`resource.url` (capability `bunny-list-endpoint`) — i.e. straight from bunny's S3 endpoint, not through
the backend. The session SHALL allow **both Wi-Fi and cellular** (not Wi-Fi-only) and SHALL NOT be
discretionary, so downloads make progress on mobile networks too, with a bounded number of in-flight
tasks (enqueue more as tasks complete). A transfer failure SHALL leave the resource pending for retry (no
terminal failure state).

A finished transfer is not a successful one, and SHALL NOT be staged on the strength of having finished.
Before its bytes are moved into staging, the download client SHALL evaluate the transfer's outcome — the
HTTP status, the expected byte count, and the received byte count — and SHALL treat as a **failed**
transfer, to be retried:

- a response whose status is not a success status (`2xx`); and
- a response whose received body is **shorter than its `Content-Length`** (a truncated download) — the
  integrity signal formerly guaranteed by the download proxy, now evaluated against bunny's S3 GET response.

A status check is not redundant with the transport's own error reporting: a background `URLSession` reports
an HTTP error as a *successful transfer of an error body* — the download-finished callback fires with the
error document in hand and the completion error is absent — so the status check is the only thing that sees
a `502`.

A transfer SHALL be rejected only on **positive evidence** that its bytes are bad, never on the absence of
evidence. Where the expected byte count is **unknown** (the server omitted `Content-Length`), the transfer
SHALL NOT be rejected for length; the status check stands alone. A received count **exceeding** the expected
count SHALL NOT be rejected either. Where the status is unknown, the transfer SHALL NOT be rejected for
status. Only a known-and-under count is a truncation, and only a known non-2xx status is a failure. The
reason is not caution but arithmetic: a rejected transfer is retried, and a retry only helps when the
condition can change. A server that omits `Content-Length` omits it on every attempt, so rejecting on an
unknown length is not a retry — it is an unbounded loop in which the photo never arrives, the same
permanent, invisible loss as accepting bad bytes, reached from the other side.

An accepted transfer's bytes SHALL be moved out of the system temporary location into a durable App-Group
staging location and recorded in the download store. A **rejected** transfer's bytes SHALL NOT be moved or
recorded: the resource stays un-staged, which is the existing pending-for-retry state rather than a new
terminal one, and the next reconcile re-downloads it. Because staging replaces whatever occupies the
destination path, evaluating the outcome **before** the move also prevents a rejected body from destroying
an earlier good file.

The transport seam SHALL carry these outcome facts to the client, and the decision SHALL be taken in code
covered by `commonTest` rather than in the platform edge, which is the platform boundary and nothing more.

#### Scenario: A completed download is staged durably

- **WHEN** a background download task for a resource finishes with a success status and no short read
- **THEN** its bytes are moved to durable App-Group staging and the resource is marked downloaded in
  the store

#### Scenario: A resource is fetched directly from bunny's S3 endpoint

- **WHEN** a resource is enqueued for download
- **THEN** the background task targets its presigned S3 `url` directly (no backend byte proxy), needing
  no per-task authorization header

#### Scenario: A failed transfer is retried, not failed

- **WHEN** a download task completes with an error
- **THEN** the resource remains pending and is retried on a later sync; no terminal failure state is
  recorded

#### Scenario: A short read is treated as a failed download

- **WHEN** a download returns a body shorter than its `Content-Length`
- **THEN** the transfer is treated as failed and retried; the truncated bytes are not accepted as the
  complete object

#### Scenario: A non-2xx response is not a photo

- **WHEN** a transfer finishes with status `502` and an error body, and the transport reports no error
- **THEN** it is treated as a failed transfer and retried; its bytes are not staged

#### Scenario: An unknown Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and the response carries no `Content-Length`
- **THEN** it is accepted and staged, because an unknown expected count cannot establish a short read, and
  rejecting it would recur on every retry rather than resolve

#### Scenario: A body longer than its Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and a received body larger than its `Content-Length`
- **THEN** it is accepted and staged, because an over-long body is not a short read

#### Scenario: Rejected bytes never become the store's truth

- **WHEN** a transfer is rejected on status or length
- **THEN** the resource is not recorded as staged, so the next reconcile re-downloads it rather than
  re-importing it

#### Scenario: A rejection does not destroy an earlier good file

- **WHEN** a transfer is rejected and a previously staged, valid file already exists at that resource's
  staging path
- **THEN** the existing file is left intact, because a rejected transfer's bytes are never moved into staging

### Requirement: Expired presigned download URLs self-heal on rediscovery

A presigned download `url` that expires before its background transfer runs SHALL be **superseded by
a freshly-minted URL** on the next foreground reconcile and retried — so an expired link recovers
automatically rather than failing permanently. The `url` is a **time-limited** presigned S3 URL
(7-day expiry); because the client re-reads the union on join and on every foreground entry (see
"Foreground-only discovery of later additions"), and the download store refreshes the stored `url` of
a **not-yet-staged** resource from that read (capability `download-store`), that supersession happens
on its own. The client SHALL NOT need any credential to fetch a presigned URL; the query signature is
the sole authorization.

#### Scenario: An expired link is re-presigned and retried

- **WHEN** a resource's presigned `url` expires before its background download completes, and the app
  is next foregrounded
- **THEN** the re-read union supplies a fresh presigned `url`, the store replaces the pending resource's
  stale url with it, and the transfer is retried against the fresh url

#### Scenario: Staged and imported resources are not disturbed by re-presign

- **WHEN** the union is re-read while some of an asset's resources are already staged or the asset is
  already imported
- **THEN** only not-yet-staged resources take a refreshed url; staged bytes and terminal rows are
  untouched

### Requirement: Full-fidelity per-asset import into the camera roll

When **every** resource of a foreign asset is staged, the client SHALL import the asset with a single
`PHAssetCreationRequest` adding all of its resources, mapping each resource by `role`: `live` →
`.pairedVideo`; `primary` → `.photo`/`.video`/`.audio` selected by its `contentType`. An unrecognized
`contentType` SHALL be logged and skipped, not force-imported. The asset SHALL be imported into the
photo library (camera roll). The import SHALL reuse the existing full-library-access grant and add no new
permission state.

When the membership opted into an event album (`EventConfig.saveToAlbum`, capability `event-album`) and
that album already exists, the importer SHALL, **in the same `PHPhotoLibrary.performChanges` commit** as
the creation, add the newly-created asset to the event album (via the album's
`PHAssetCollectionChangeRequest` and the creation request's placeholder), so a received photo is
atomically already-in-the-album and never briefly loose. The album identifier SHALL be sourced from the
shared `eventId → albumLocalId` map via an injected lookup; when no album identifier is available (album
not yet created, or `saveToAlbum` is false), the importer SHALL import into the camera roll only and add
to no album. The album add SHALL be best-effort — it SHALL never fail or defer the import.

#### Scenario: A Live Photo round-trips

- **WHEN** a foreign Live Photo's `primary` (image) and `live` (paired video) are both staged
- **THEN** one `PHAssetCreationRequest` recreates a working Live Photo in the library

#### Scenario: Import waits for the complete resource set

- **WHEN** only some of an asset's resources are staged
- **THEN** the asset is not imported until every resource is staged

#### Scenario: Imported asset lands in the camera roll

- **WHEN** an asset import succeeds for a membership that did not opt into an album
- **THEN** the asset is present in the photo library and is added to no SnapSync album

#### Scenario: An album-opted import lands atomically in the album

- **WHEN** an asset import succeeds for a `saveToAlbum` membership whose event album exists
- **THEN** the asset is created and added to the event album in a single commit, never appearing outside it

#### Scenario: Import proceeds when the album is not yet created

- **WHEN** an import runs for a `saveToAlbum` membership before the event album has been created
- **THEN** the asset is imported into the camera roll and no album add is attempted for it

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry. The backstop's coordination — the trigger-time membership re-read
(`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail cleanly
before first unlock*), the attestation wake, then the import drain — SHALL be the
`flow/DownloadBackstop` trigger (`:domain` `flow/`, built in `compose/` with the re-read and wake
injected as effect lambdas); the untested app shell keeps only the entry-point log wrap, the
re-arm, and the OS task-completion handler. A backstop wake landing before the first unlock since
boot fails cleanly and converges at the next wake (the import's reads are caught; the adapters
distinguish unreadable from absent; nothing mints, clears, or leaves).

That last property is **conditional, and the transfer check is its condition**. A deferred import is a safe
retry only because staged bytes were accounted for at transfer time. Absent that check, a permanently
invalid body — an error document staged under a photo's path — makes the retry a trap rather than a
safeguard: the import fails on every reconcile, and the transfer is never re-run, because a resource
recorded as staged is never re-planned. The asset is then permanently unimportable and permanently retried,
and the photo never arrives. Retrying a failed import is correct for a transient failure and poison for
invalid bytes; only rejecting bad bytes before staging keeps the two apart.

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable

### Requirement: Deletion is respected — no re-download

Once a foreign asset is imported (a terminal download-store row), the client SHALL NOT download or
import it again, even if the user later deletes the imported asset from their library. The same asset
linked into more than one event SHALL be imported only once (cross-event dedup via the store).

#### Scenario: A deleted collected photo is not restored

- **WHEN** the user deletes a previously-imported foreign photo and a later sync runs
- **THEN** the asset is not re-downloaded or re-imported

#### Scenario: The same asset across two events imports once

- **WHEN** a device's asset appears in the unions of two events this install joins
- **THEN** it is imported only the first time and skipped thereafter

### Requirement: Event-driven discovery of later additions

The client SHALL re-read the union on join/(re)provision, on foreground entry, **and** when it receives
a silent push for its **active event** (capability `push-registration`). It SHALL NOT run a background
**poll** of the union (no timer, no periodic background fetch); background discovery is **event-driven**
(woken by a push), not polled. Assets contributed by others **after** the initial read SHALL be
discovered on the next of: foreground entry, or a silent push for the active event. A push whose event
is **not** the active event SHALL NOT trigger discovery (the active-event guard lives in the receive
seam, capability `push-registration`). Transfers and imports already enqueued SHALL continue in the
background regardless of foreground state. Because push delivery is best-effort (OS-throttled and
coalesced), foreground entry remains the standing backstop, so no asset is lost — only, at worst,
delayed to the next foreground visit.

#### Scenario: A push for the active event triggers background discovery

- **WHEN** another contributor adds photos and a silent push for this device's active event arrives
  while the app is not foregrounded
- **THEN** the client reconciles in the background — reading the union, enqueueing the new foreign
  resources' downloads, and importing any already-staged asset — without a foreground visit

#### Scenario: Later-added foreign photos still appear on next foreground

- **WHEN** another contributor adds photos while this app is not foregrounded and no push is delivered
  (throttled/coalesced/dropped)
- **THEN** those photos are discovered and enqueued on the next foreground entry (the backstop)

#### Scenario: No background poll

- **WHEN** the app is backgrounded and no silent push arrives
- **THEN** the client runs no periodic union poll; discovery happens only on a push or the next
  foreground entry

#### Scenario: Initial-join transfers complete in background

- **WHEN** the app reads the union on join and is then backgrounded
- **THEN** the enqueued downloads and imports complete in the background without reopening the app

### Requirement: Download is gated on the membership's participation direction

The download reconcile SHALL be a **no-op** for any membership whose persisted participation direction
excludes download (`UploadOnly`) — at **every** trigger (join/(re)provision, foreground entry, and
silent push). The gate SHALL live at the **single choke point** through which all triggers funnel
(`DownloadController.reconcile`), reading the persisted `EventConfig.direction`, so the skip decision
sits in a **tested capability** rather than being duplicated across the untested app shell's call sites.
When the direction is `Both` or `DownloadOnly`, reconcile SHALL run exactly as before. This gate is
**orthogonal** to the existing active-event guard in the silent-push receive seam (capability
`push-registration`): the active-event guard answers "is this push for my current event," the direction
gate answers "should this device ever download for its current event." A push for the active event on an
`UploadOnly` membership SHALL therefore be received (active-event guard passes) yet perform **no**
reconcile (direction gate blocks), leaving no foreign photos downloaded or imported.

The gate's read SHALL be **posture-explicit**: *no membership* is a distinct answer from *a membership whose
direction excludes download*, and **neither** enables the arm. The read SHALL NOT resolve an absent
membership to "enabled" via a permissive fallback, and the gate SHALL carry **no default value** that would
let a caller omit the posture entirely. A three-valued read collapsed into a permissive boolean is what
allowed an upload producer to be enabled for an event that did not exist (capability `upload-lifecycle`); the
same collapse here would run a reconcile with no membership to reconcile against.

Because the download total is populated **only** by this reconcile (`store.plan` is reached only past this
gate), an `UploadOnly` membership's download total is `0`, and its download arrow is hidden by the ordinary
completeness rule with no masking in the status projection (capability `sync-status-screen`).

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read, download enqueue, or import occurs (reconcile is a no-op)

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push arrives for the active event on an `UploadOnly` membership
- **THEN** the push is received (the active-event guard passes) and reconcile is a no-op — no foreign photo
  is downloaded or imported

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets, enqueuing downloads, and importing staged assets

#### Scenario: An absent membership enables nothing
- **WHEN** the direction gate is read with no membership configured
- **THEN** the answer is "no arm" — the reconcile does not run, rather than defaulting to enabled

#### Scenario: Upload-only's download total is zero without a mask
- **WHEN** the membership is `UploadOnly` and the status projection reads the download total
- **THEN** the total is `0` because nothing was ever planned, so the download arrow is hidden by the
  completeness rule rather than by a direction mask

### Requirement: Transfer cancellation is task-level; the background session is never invalidated

Cancelling the download client's transfers (on leave, switch, or re-provision) SHALL cancel the
outstanding background **tasks** and SHALL leave the background `URLSession` **alive and reusable**. The
client SHALL NOT call `invalidateAndCancel` (or any other invalidation) as a cancellation mechanism.

Invalidation is terminal: creating a task on an invalidated `NSURLSession` throws an Objective-C
`NSException`, which Kotlin/Native cannot catch and which aborts the process. Because a cancel is always
followed by a later download (a re-join, a foreground reconcile, or a silent push), a session destroyed by
cancellation is a crash waiting for the next reconcile. Cancellation therefore acts on tasks, never on the
transport. The background-session identifier SHALL remain stable across cancellations and app launches, so
`handleEventsForBackgroundURLSession` can re-adopt the session and redeliver completions.

#### Scenario: Cancelling transfers leaves the session usable

- **WHEN** all transfers are cancelled and a resource is subsequently enqueued for download
- **THEN** a new download task is created and started successfully — the cancel did not destroy the
  transport

#### Scenario: Leave then re-join downloads again

- **WHEN** the user leaves an event (cancelling in-flight transfers) and later re-joins an event whose
  union lists foreign assets
- **THEN** the reconcile enqueues those resources and their downloads run; the app does not abort

#### Scenario: Cancellation stops the in-flight transfers

- **WHEN** transfers are cancelled while downloads are in flight
- **THEN** those tasks are cancelled, the pending queue is emptied, and the bounded in-flight window
  frees up for later enqueues

### Requirement: A session invalidated by the system is rebuilt, not reused

The client SHALL discard a background `URLSession` that the system has invalidated (delivered as the
delegate's `didBecomeInvalidWithError`) and SHALL build a fresh session — reusing the same stable
identifier — before creating any further task. It SHALL NOT create a task on a session it has been told is
invalid.

#### Scenario: A system-invalidated session self-heals

- **WHEN** the system invalidates the background session and a resource is subsequently enqueued
- **THEN** the client builds a fresh session and creates the download task on it, rather than reusing the
  invalidated session

### Requirement: A resource whose URL is not fetchable is skipped, not fatal

A planned resource whose `url` is not a fetchable `http`/`https` URL with a host SHALL be logged and
skipped, and SHALL NOT be handed to the background session (which raises an uncatchable Objective-C
exception for an unsupported URL). This covers both a `url` that fails to parse and one that parses to an
unsupported scheme or has no host. Skipping SHALL leave the resource pending for a later reconcile,
consistent with "a failed transfer is retried, not failed" — no terminal failure state is recorded.

#### Scenario: An unsupported download URL is skipped

- **WHEN** a planned resource carries a URL that is not an `http`/`https` URL with a host
- **THEN** it is logged and skipped, the remaining resources still enqueue, and the app does not abort

### Requirement: Download-job orchestration is tested behind a transport seam

The download client's orchestration SHALL live in a platform-independent unit covered by `commonTest`
(running on **both** JVM and `iosSimulatorArm64`), with the `NSURLSession` / `NSFileManager` calls behind a
narrow **transport** seam that a test fake can substitute. That orchestration comprises the pending queue,
the bounded in-flight window and its refill, the task-description encoding that carries
`(deviceId, assetId, resourceKey)` through a transfer, the staging-path computation, the URL guard, and the
cancellation lifecycle above.

The fake SHALL be able to model a destroyed transport (task creation after destruction fails), so that
"cancellation never destroys the transport" is pinned by an automated test rather than by device
verification alone. Faking only the outer `PhotoDownloadJobs` seam is insufficient — it replaces the very
implementation that carries this logic.

#### Scenario: The cancellation lifecycle is covered without a device

- **WHEN** the download client's tests run on JVM and `iosSimulatorArm64`
- **THEN** a fake transport that fails task creation after destruction demonstrates that cancelling
  transfers and then enqueueing still creates tasks

#### Scenario: The bounded window and task-description codec are covered

- **WHEN** the download client's tests run
- **THEN** the in-flight window's refill-on-completion behavior and the round-trip of
  `(deviceId, assetId, resourceKey)` through the task description are asserted without any iOS runtime

