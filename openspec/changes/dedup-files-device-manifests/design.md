# Design — dedup file store + per-device manifests + device identity

## Context

Today (`migrate-ios-upload-to-bunny` + `immutable-asset-manifests` + `flatten-event-namespace` +
`reconcile-in-extension`): photo bytes live at `<eventId>/<assetId>-<role>.<ext>`, one immutable
manifest object per asset at `<eventId>/<assetId>.manifest.json`, no device id, no content dedup, and
the app's status reads server-computed completeness. The OS background-upload job API **never exposes
the bytes to the extension** (it reads them itself), so content-hash keys are impractical and remain
out of scope.

This change keeps the engine, the ledger, the ack-path recovery, and the discovery cursor exactly as
they are, and changes only **where bytes land**, **how the manifest is shaped/written**, **what
identity the device carries**, and **how status and reconcile read storage**.

## Goals / non-goals

- **Goal:** upload each photo once, link it into any number of events (cross-event dedup).
- **Goal:** collapse N per-asset manifest uploads into one per-event device manifest.
- **Goal:** record a stable per-device id now, to *prepare* (not build) a future restore.
- **Non-goal:** cross-device content dedup (needs hashing the OS won't allow). Dedup is
  **same-device, across events** only — keyed by the device-local `assetId`.
- **Non-goal:** restore/download-to-device behavior, and any event-wide union read — both deferred.

## Storage layout

```
/files/<device-id>/<assetId>-<role>.<ext>     bytes · device-global · uploaded once · reused across events
/events/<event-id>/metadata.json              event marker {eventId,name,createdAt}   (was events/<id>.json)
/events/<event-id>/device/<device-id>.json    per-event device manifest (mutable full-state projection)
```

Dedup is structural: bytes are event-independent, so two events reference one copy.

```
  /events/A/device/<id>.json ─┐
  /events/B/device/<id>.json ─┴──▶ /files/<id>/<assetId>-primary.jpg   ← one copy, linked twice
```

## Data flows

```
UPLOAD CYCLE (extension, processJobs)
  discover ──(shared gallery-status enumeration seam)──▶ (filename, assetId, version)
     │
     ├─ engine.handle(ResourceChanged)
     │     ledger[filename] = COMPLETED/REQUESTED → AlreadyUploaded
     │     else → Upload ──OS PUT──▶ /files/device/<device-id>/<filename>   (UNGATED)
     │
     └─ write/update entry in the device-global ACCUMULATOR   (ALWAYS — even AlreadyUploaded)
  project(accumulator, captureDate ≥ event.start) ─▶ device.json
     └─ synchronous PUT /event/<event>/device/<id>   (event-gated; skip if unchanged)

STATUS (app — reads NO device.json)
  total    ← PhotoKit gallery count (qualifying)
  expected ← shared gallery-status enumeration seam (per-asset filenames) ─┐
  present  ← GET /files/device/<device-id>                                 ─┴▶ completed = all-present
                                                                              pending   = qualifying − completed

RECONCILE (extension, marker-gated by joinedEventId)
  configured eventId vs joinedEventId
    same      → upload directly
    different → additive-seed ledger from GET /files/device/<id>   (NEVER clear)
                re-project device.json to the new event path; set marker
  reinstall (ledger empty) → same additive seed restores dedup;
                             accumulator rebuilds gradually via discovery
```

## Key decisions

| Area | Decision | Rationale |
|---|---|---|
| Dedup scope | Same-device, across events; `/files/<device-id>/<assetId>-<role>.<ext>` | The OS never shows the extension the bytes, so content hashing (cross-device dedup) is impractical; `assetId` (device-local `localIdentifier`) is free and dedups the join-A-then-switch-to-B case. |
| device-id | Minted UUID in shared Keychain | Survives reinstall, so a reinstalled device still recognizes its own past `/files/` partition + manifest — required for future deletion-correct restore. |
| device.json shape | Mutable full-state snapshot, full rewrite each cycle | Reconstructed locally; each write is a complete, self-contained snapshot → no read-modify-write, no lost update under last-write-wins, self-healing, deletion-aware. Drops the immutable/permanent-cache property (acceptable: cache via ETag/Last-Modified). |
| device.json source | Device-global accumulator → per-event date-filtered projection | The accumulator, ledger, and cursor are all device-global; only the storage path and the date cutoff are event-scoped, so an event switch is a re-projection, not a reset. |
| Completeness / status | Own-device: shared enumeration seam (expected) × per-device file list (present) | Avoids multi-cycle ledger lag and needs no device.json read; expected sets come from the **same** seam the producer uses, so app and extension agree byte-for-byte (`gallery-status`). |
| Dedup mechanism | Reconcile seeds additively from the **device** listing; never clears | The ledger key is already the bare, event-independent filename; the only reason a switch re-uploaded was the old reconcile clearing + reseeding from the **event** listing. |
| device.json upload | Synchronous in-cycle PUT by the extension (sole writer) | No background `URLSession`, no app completion draining, no in-flight-file mutation question; a kill mid-PUT is lost and caught next cycle (benign — device.json is write-only in v1 and converges). |
| Edge read surface | `GET /files/device/<id>` (list, keeps `url`) + `GET /files/device/<id>/<file>` (download) | The only v1 read the device performs is the per-device list; the byte download is restore forward-prep; the event-wide union is external/admin-direct (§3.5), not an edge route. |
| Upload gating | `/files/` bytes **ungated**; device.json write **event-gated** | Hot per-resource path stays gateless (accepted abuse trade-off); the infrequent per-cycle manifest write keeps the cheap event-existence gate to avoid orphan manifests. |
| Migration | Clean cutover, no backfill | Personal TestFlight app, disposable data; old objects orphaned; re-join reconciles against the new per-device list (finds nothing → re-uploads once). |

## Resolved engineering notes (the would-have-been risks)

- **device.json ordering / torn writes — moot.** With a synchronous in-cycle PUT there is no
  background task pinning a file, so there is no in-flight-mutation/torn-read question. device.json is
  **write-only in v1** (no in-app consumer — status reads PhotoKit + the per-device list), so transient
  staleness is benign and self-heals each cycle.
- **Accumulator persistence.** One durable App-Group accumulator; an entry is written on **every
  discovery** (even `AlreadyUploaded`), so it is a rebuildable cache, not a source of truth. Pruned on
  deletion. After a reinstall (App-Group wiped, Keychain device-id + `/files/` survive) it **rebuilds
  gradually** as discovery re-encounters each present asset; deleted-from-library assets correctly stay
  out; status is unaffected (PhotoKit + per-device list are both available immediately).
- **Ledger key / ack-path — no change.** The engine already keys by `resource.filename`
  (`SyncEngine.ledger.entry(resource.filename)`), and ack recovery already reads
  `destination.URL.lastPathComponent` (prefix-agnostic). The new `/files/device/<id>/<file>` path
  works through both unchanged.
- **Single-writer — trivially satisfied.** The extension is the sole writer of device.json; the app
  reads no manifest state for status, so the `PENDING/DONE` two-writer dance dissolves.
- **Switch + date filter.** With the (current) whole-library scope the projection is the identity, so
  a switch is free. When `startDate` discovery returns, switching to an event with an **earlier** start
  needs a re-enumeration (cursor reset) to pick up older photos the accumulator never saw — handled
  alongside the date-filter work, not here.

## Accepted trade-offs (eyes open)

- **Ungated `/files/` writes.** Removing the event-existence gate on byte uploads widens the abuse
  surface beyond today (any client with the IPA-baked edge host can write; device-id is self-asserted).
  Accepted for a personal TestFlight app; App Attest (or similar) is the future hardening path (`§8`).
  The device.json write keeps the event gate.
- **Mutable manifest.** Dropping per-asset immutability forfeits the "complete asset permanently
  cacheable" optimization from `bunny-list-endpoint`; the per-device list is a plain directory listing
  with ordinary HTTP caching.
- **Restore + union deferred.** device-id is recorded now but nothing consumes it in v1; the event-wide
  union is external/admin-direct.

## Spec-delta map

```
REMOVE   asset-manifest                 (superseded by device-manifest)
NEW      device-identity                Keychain device id (app + extension)
NEW      device-manifest                per-event device.json schema + lifecycle
REWRITE  bunny-upload-endpoint          /files/device/<id>/<file> ungated + event-gated device.json route
REWRITE  edge-upload-provider           URL → /files/device/<deviceId>/<enc>; inject deviceId
REWRITE  bunny-list-endpoint            GET /files/device/<id> raw list; drop manifest/completeness/cache
REWRITE  bunny-download-endpoint        GET /files/device/<id>/<file>; public URL format
REWRITE  event-rejoin-reconciliation    additive device-list seed; no clear; switch keeps state
REWRITE  ios-background-upload          accumulator + projection + synchronous device.json PUT; prune
TOUCH    event-creation                 marker → /events/<id>/metadata.json
TOUCH    sync-status                     own-device sources; remove PendingManifestsSource
TOUCH    gallery-status                  enumeration seam feeds the app-side status consumer
TOUCH    sync-ledger                     event-independent key; baseline-reset unused on switch
UNTOUCHED sync-engine, deeplink-config, permission-gate, leave-event
```
