## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Expired presigned download URLs self-heal on rediscovery

A resource's download `url` is a **time-limited** presigned S3 URL (7-day expiry). Because the client
re-reads the union on join and on every foreground entry (see "Foreground-only discovery of later
additions"), and the download store refreshes the stored `url` of a **not-yet-staged** resource from
that read (capability `download-store`), a presigned URL that expires before its background transfer
runs SHALL be **superseded by a freshly-minted URL** on the next foreground reconcile and retried — so
an expired link recovers automatically rather than failing permanently. The client SHALL NOT need any
credential to fetch a presigned URL; the query signature is the sole authorization.

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
