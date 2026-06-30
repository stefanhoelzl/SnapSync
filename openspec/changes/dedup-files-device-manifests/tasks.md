## 1. Device identity (`device-identity`)

- [x] 1.1 Add a `DeviceIdentity` seam in a tested module (commonMain) exposing a stable `deviceId: String` (UUID), with a shared-Keychain-backed impl that mints once and persists, and a settable fake.
- [x] 1.2 Wire the impl into both composition roots (app + extension) reading the **same** Keychain group, so app and extension observe one id; assert mint-once / read-stable.
- [x] 1.3 `commonTest`: first read mints + persists; subsequent reads return the same value; a pre-seeded store is returned verbatim (reinstall-stable contract documented).

## 2. Edge upload provider — device-partitioned byte URL (`edge-upload-provider`)

- [x] 2.1 Inject `deviceId` into `EdgeUploadRequestProvider` (alongside the existing host) and change the URL template to `<host>/files/device/<deviceId>/<encoded-filename>`; keep the deterministic-and-injective filename encoding.
- [x] 2.2 Update the composition root to supply `deviceId` from `DeviceIdentity`.
- [x] 2.3 `commonTest`: URL shape, percent-encoding, determinism+injectivity; Content-Type-only header set unchanged.

## 3. Backend — byte upload, device.json write, ungating (`bunny-upload-endpoint`)

- [x] 3.1 Add `PUT /files/device/<deviceId>/<filename>` → stream into the bunny key `/files/<deviceId>/<filename>`; **ungated** (no marker read); validate `deviceId` (UUID) and `filename` (safe, no `/`).
- [x] 3.2 Add `PUT /event/<eventId>/device/<deviceId>` → stream JSON into `/events/<eventId>/device/<deviceId>.json` with `Content-Type: application/json`; **gated on event existence** (marker `GET` of `/events/<eventId>/metadata.json`; 404 → reject, non-404 read error → 502).
- [x] 3.3 Retire the old `PUT /event/<id>/file/<name>` byte route (clean cutover); last-write-wins + faithful-outcome (2xx only on confirmed store) unchanged on both new routes.
- [x] 3.4 Backend tests: byte PUT stores bare `/files/<id>/<file>` and is ungated; device.json PUT gated (unknown event→reject, transient→502); filename/deviceId validation; faithful 5xx on upstream failure.

## 4. Backend — per-device listing & download (`bunny-list-endpoint`, `bunny-download-endpoint`)

- [x] 4.1 Replace the event-files listing with `GET /files/device/<deviceId>` → a **single** bunny LIST of `/files/<deviceId>/`, returning a flat `[{ filename, size, url }]` (no manifest read, no completeness, no immutable cache). `url` per the download route.
- [x] 4.2 Faithful outcome: a malformed `deviceId` → `400`; an empty/unknown partition → `200 []`; any upstream LIST failure → `502` (never a partial list).
- [x] 4.3 Move the object download to `GET /files/device/<deviceId>/<filename>` (single ungated streaming GET; relayed headers, short-read contract unchanged); update the public URL format to match so list `url`s and the route agree by construction.
- [x] 4.4 Remove the event-wide / manifest-completeness listing from the edge (external/admin reads bunny directly).
- [x] 4.5 Backend tests: list shape + empty + 400 + 502; download path + relay + short-read; `url` round-trips with the uploaded filename.

## 5. Backend — event marker path (`event-creation`)

- [x] 5.1 `POST /event` writes the marker at `/events/<eventId>/metadata.json` (was `events/<eventId>.json`); existence/metadata reads (`GET /event/<id>`) read the new key.
- [x] 5.2 Backend tests: create writes the metadata key; existence gate (used by the device.json write) reads it; 502 on upstream failure.

## 6. Device manifest producer (`device-manifest`, `ios-background-upload`)

- [x] 6.1 Add a platform-neutral device-manifest model in commonMain: `{ deviceId, assets[]{ assetId, creationDate, resources[]{ role, contentType, filename, originalFilename } } }`, with JSON serialization.
- [x] 6.2 Add a durable **device-global accumulator** (App-Group) of per-asset manifest entries; write/update an asset's entry on **every discovery** (even `AlreadyUploaded`); prune an asset's entry on deletion (alongside `deleteByAssetId`).
- [x] 6.3 Project the accumulator to the current event's device.json (date-filter cutoff; identity while whole-library) and **PUT it synchronously in-cycle** to `/event/<eventId>/device/<deviceId>`; skip the PUT when unchanged since the last successful one.
- [x] 6.4 Remove the per-asset manifest side-channel: delete the `URLSession` manifest upload, the `PENDING/DONE` markers, and the app's manifest `handleEventsForBackgroundURLSession` wiring.
- [x] 6.5 `commonTest`: accumulator add/update/prune; projection equals the accumulator under whole-library; serialization round-trip; unchanged-skip; gradual rebuild from empty via repeated discovery.

## 7. Reconcile — resetTo device-list seed + clear cursor (`event-rejoin-reconciliation`, `sync-ledger`)

- [x] 7.1 Change the `EventFilesSource` seam (iOS HTTP + fakes) to the **per-device** listing `GET /files/device/<deviceId>` returning filenames.
- [x] 7.2 On a marker mismatch: **`resetTo`** (atomic clear-and-seed) the ledger to one `COMPLETED` row per stored filename (key = filename) from that listing, and **clear the discovery cursor** (force a full re-enumeration); re-project device.json to the new event path; set `joinedEventId`. The global device listing re-seeds the same files `COMPLETED`, so a switch re-uploads nothing already stored, and an empty ledger (reinstall) is restored by the same `resetTo`.
- [x] 7.3 Use `resetTo` (clear-and-seed) in the reconcile path — the clear drops stale/phantom rows (e.g. a `REQUESTED` row whose job never materialized) the engine would otherwise treat as in-flight forever; a **leave** clears only the `joinedEventId` marker (ledger kept). Document the event-independent (bare-filename) key in `sync-ledger`.
- [x] 7.4 `commonTest`: a switch `resetTo`s from the global device list so prior `COMPLETED` rows are re-seeded (no re-upload) and a stale/phantom `REQUESTED` row is dropped; reinstall seeds from the device list; a seeded filename yields `AlreadyUploaded`; listing failure defers without settling the marker.

## 8. Status — own-device progress (`sync-status`, `gallery-status`)

- [x] 8.1 Replace `CompletedAssetsSource` (manifest completeness) with a per-device completeness derived from the shared `gallery-status` enumeration seam (expected resource sets) × `GET /files/device/<deviceId>` (present files): an asset is complete when all its expected filenames are present.
- [x] 8.2 Remove `PendingManifestsSource`; `pending` = qualifying assets not yet complete. `total` = gallery count unchanged. Re-read on foreground entry (no manifest-completion ding).
- [x] 8.3 `commonTest`: all-present→complete, missing-resource→pending, keep-last-good on a failed listing, counts-by-photo classification unchanged.

## 9. Docs

- [x] 9.1 Update `docs/design.md`: reverse `flatten-event-namespace` (device-id returns via `/files/<device-id>/` + device.json) and `immutable-asset-manifests` (mutable device.json); record the store, the device manifest, own-device status, the ungated-upload trade-off, synchronous device.json PUT, and the deferred restore/union.

## 10. Verification

- [x] 10.1 `./gradlew build` green (all targets + JVM/Compose tests, incl. new `commonTest`s).
- [x] 10.2 `./gradlew compileIosMainKotlinMetadata` green (iOS source-set proxy).
- [x] 10.3 Backend `deno` test suite green.
- [ ] 10.4 On-device (manual, iOS 27): a fresh-event provision uploads to `/files/device/<id>/`, writes `/events/<id>/device/<id>.json`, status shows own-device progress; an event **switch** re-uploads nothing already stored.
