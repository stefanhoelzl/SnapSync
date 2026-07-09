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

Decision record: `changes/archive/2026-06-30-add-photo-download`.

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
tasks (enqueue more as tasks complete). Each completed download SHALL be moved out of the system
temporary location into a durable App-Group staging location and recorded in the download store. A
transfer failure SHALL leave the resource pending for retry (no terminal failure state); a response
whose received body is **shorter than its `Content-Length`** (a truncated download) SHALL be treated as
a **failed** transfer and retried, not accepted as complete — the integrity signal formerly guaranteed
by the download proxy, now evaluated against bunny's S3 GET response.

#### Scenario: A completed download is staged durably

- **WHEN** a background download task for a resource finishes
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
deferred import a safe retry.

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

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

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read, download enqueue, or import occurs (reconcile is a no-op)

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push for the device's active event arrives while joined with direction `UploadOnly`
- **THEN** the receive seam's active-event guard passes but reconcile performs no union read or download enqueue

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets, enqueuing downloads, and importing staged assets

