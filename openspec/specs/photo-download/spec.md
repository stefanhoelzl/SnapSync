# photo-download Specification

## Purpose
TBD - created by archiving change add-photo-download. Update Purpose after archive.
## Requirements
### Requirement: Foreign-asset selection by device identity

The download client SHALL consume the event-wide union read (`GET /event/<eventId>/files`) for the
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
photo library (camera roll); no dedicated album is created. The import SHALL reuse the existing
full-library-access grant and add no new permission state.

#### Scenario: A Live Photo round-trips

- **WHEN** a foreign Live Photo's `primary` (image) and `live` (paired video) are both staged
- **THEN** one `PHAssetCreationRequest` recreates a working Live Photo in the library

#### Scenario: Import waits for the complete resource set

- **WHEN** only some of an asset's resources are staged
- **THEN** the asset is not imported until every resource is staged

#### Scenario: Imported asset lands in the camera roll

- **WHEN** an asset import succeeds
- **THEN** the asset is present in the photo library and no SnapSync album is required

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

### Requirement: Foreground-only discovery of later additions

The client SHALL re-read the union on join/(re)provision and on foreground entry. It SHALL NOT run a
background poll of the union; assets contributed by others **after** the initial read SHALL be
discovered on the next foreground entry. Transfers and imports already enqueued SHALL continue in the
background regardless of foreground state.

#### Scenario: Later-added foreign photos appear on next foreground

- **WHEN** another contributor adds photos to the event while this app is not foregrounded
- **THEN** those photos are discovered and enqueued on the next foreground entry (not before)

#### Scenario: Initial-join transfers complete in background

- **WHEN** the app reads the union on join and is then backgrounded
- **THEN** the enqueued downloads and imports complete in the background without reopening the app

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

