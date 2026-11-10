# Proposal

## Why

Nobody can say how long a photo takes from the camera to another member's gallery, where it waits on the way, or
what woke the app at each step. The service sees only its own requests: the steps that happen on a phone (found
in the library, queued, push received, saved to the gallery) are invisible, and so are the phone's conditions and
its software. At the same time, diagnostic data is scattered across tables kept for operations (the read record
in the union log, the version on the device row) and a nightly job summary. This change makes the api's request
log the one place the operator learns how delivery behaves and how SnapSync is used, and leaves the operational
tables holding only what operations read.

## What Changes

- **The app reports delivery facts with requests it already makes** — no new request, no new route:
  - every request: the phone's operating system and version, model, kind of build (store, TestFlight, Play
    internal, development), photo access, network type, whether low power is on and background work is allowed;
  - each photo upload: when the photo was taken, when the app found it and why, when the upload was queued;
  - the photo-list read a push caused: when the push arrived and which announcement it answered;
  - the progress report a receiver sends once it holds new photos: when each was downloaded and added to the
    library, and why.
- **The service records more of what it already sees**: the kind of each upload attempt (fresh, resumed,
  restarted) and its range, the resume point it hands out, the media type, the cursor of each read, the
  download start and who started it, which photos arrived before the receiver joined, when a device had
  shared and received everything, membership changes, event attributes at creation, and when the nightly
  sweep completes or deletes an event.
- **One record, the request log.** The read record moves out of the union log into it; the device row's app
  version, attestation time and push-update time, and the union log's timestamps, are dropped; the version
  column of the request log moves into its fields. The nightly sweep's job summary is removed; the sweep keeps
  its deletions, its exit code and its daily report of server errors.
- **The record outlives the event**: kept 30 days from when it was made, no longer deleted with the event's
  photos (12 months once encrypted event creation ships — a later change).
- **The Privacy Policy describes the delivery record**: what it holds, that it holds nothing that identifies
  the person, why, and how long it is kept. Store data declarations are unchanged (diagnostics and app
  performance data are already declared).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: the record of photo-list reads becomes a delivery record (what it holds, its retention no
  longer tied to the event); the web-visitor requirement refers to it; the device-log requirement separates the
  delivery facts sent with requests from the activity log; the Privacy Policy requirement adds the delivery
  record to what it must describe.

## Impact

- **api** (`api/src`): the request log's fields and their documented key contract; the byte upload, upload
  probe, union read, download redirect, manifest, received, join, leave and create routes; the wake; the union
  log and device writes; the nightly sweep (event records, summary removed); `deno task logs`; two migrations
  shipped in separate deploys (stop using, then drop); `api/openapi.yaml` (new optional headers and the
  received body's delivery list).
- **app**: the context headers on every request and every background transfer; per-photo stage times kept
  until reported; the received report's delivery list; the push's announcement echoed on the read.
- **site**: the Privacy Policy section on what leaves the device.
- **docs**: `docs/architecture.md` (request log, union log, routes), `docs/deployment.md` (nightly sweep),
  `metadata/play/declarations.md` (the read record's classification, now the delivery record).
