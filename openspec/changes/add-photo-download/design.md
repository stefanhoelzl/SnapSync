## Context

The backend already supports reading an event's contributions: `device-identity` gives each install a
stable id, the producer writes a per-event `device.json` manifest, and `GET /event/<eventId>/files`
(the union) returns every contributing device's **complete** assets, each tagged with its owning
`deviceId` and each resource carrying `{role, contentType, key, filename, url}`. Nothing on-device
consumes these for download yet — the app is contribute-only.

Constraints inherited from the current architecture:
- The app holds **no storage credential**; it reads only via the edge (the union + per-resource
  download URLs). The union computes completeness server-side, so the client never sees a partial asset.
- The `SyncEngine` is **event-blind and upload-only**; status is **storage-truth** (the app reads no
  engine ledger for status). Downloads must fit this shape — they are an **app-side** concern, not an
  engine direction. The upload extension is the sole ledger writer and runs autonomously.
- iOS background-upload runs in a **separate extension process**; the app is a **separate process**.
  Photos can only be created from the app process (`PHAssetCreationRequest`), not the upload extension.

Device-verified during design: a Live Photo (and an edited one) rebuilds faithfully from its resources
via one `PHAssetCreationRequest`, and `PHPhotoLibrary.performChanges` **succeeds while the app is
backgrounded** — so "photos appear without opening the app" is achievable.

## Goals / Non-Goals

**Goals:**
- A joined device automatically imports other contributors' complete assets into its Photos library,
  full-fidelity, with no manual action and without the app being foregrounded for the initial batch.
- Never re-upload a downloaded asset (no echo loop); never re-download a deleted one; dedupe across
  events.
- Keep the engine and ledger untouched; keep all logic in tested `domain`/`capability` modules with
  `:app:ios`/Swift wiring-only.

**Non-Goals:**
- Background polling for later-added photos (foreground-only discovery) — accepted.
- A dedicated album, any volume cap, or video/audio upload scope changes — out of scope.
- Cross-device content dedup (the OS never exposes upload bytes; impractical).
- Changing the union/download/manifest backend contracts (they already exist).

## Decisions

**D1 — Storage-truth, app-side download; engine untouched.** Downloads consume the union and write a
separate app-owned store; the `SyncEngine` gains nothing. *Alternative considered:* a "bidirectional
engine" reading two ledgers across processes — rejected: it fights the post-`reconcile-in-extension`
architecture (app is ledger-free, status is storage-truth) and bloats the lean extension.

**D2 — Own/foreign by `deviceId`.** Foreign = union `asset.deviceId != myDeviceId`. *Alternative:*
"own = resolves in local library" — rejected: it mis-classifies a photo you uploaded then deleted (it
would re-download), whereas `deviceId` is stable and partition-accurate.

**D3 — Background `URLSession` for transfers; per-asset import when complete.** The union guarantees
completeness, so the client downloads all of an asset's resources then imports with one
`PHAssetCreationRequest` (no append-to-existing-asset API exists). Role mapping: `live`→`.pairedVideo`,
`primary`→`.photo`/`.video`/`.audio` by `contentType`. *Alternative:* foreground-only download —
rejected by the "appear without opening the app" requirement.

**D4 — Import without foreground, three lanes.** (1) background `URLSession` delegate import on
completion while backgrounded (verified); (2) `handleEventsForBackgroundURLSession` relaunch import
when terminated; (3) a `BGProcessingTask` backstop to drain the import tail (no download event wakes
the app after the last transfer). Durable App-Group staging + the store make any deferred import a
safe retry. *Alternative:* rely solely on URLSession relaunch — rejected: the last import can overrun
its wake window with nothing to wake it again.

**D5 — Echo-suppression via a marker written inside the change block.** The created `localIdentifier`
(`placeholderForCreatedAsset`) is persisted to the store **within** `performChanges`, before the asset
is observable; the extension's discovery drops suppressed `assetId`s before fan-out (an injected,
`commonTest`-exercised port). *Alternative:* mark in the completion handler — rejected: reopens the
race that re-uploaded the rebuilt asset on device.

**D6 — One unified store; schema in a lean shared module.** A single app-written App-Group SQLite DB
holds idempotency `(sourceDeviceId, sourceAssetId)` + per-resource staging + `createdLocalId`. The
schema + a read-only suppression projection live in `:domain:download-store`, linked by app (writer)
and extension (reader, over WAL — the mirror of the app reading the extension ledger). *Alternative:*
a derived plist projection for the extension — rejected: the extension already links SQLDelight +
`-lsqlite3` via the ledger, so a second store is cheap and keeps a single source of truth.

**D7 — Foreground-only discovery; permanent terminal rows.** Union read on join + foreground only.
Terminal rows survive leave/switch → never re-download after deletion, cross-event dedup for free.

**D8 — `motion`→`live` role rename.** Clean cutover (mutable device.json re-projected each cycle);
older `motion` manifests age out.

## Module / data flow

```
  :capability:download   HttpEventUnionSource · planner(union × myDeviceId × store) ·
                         PhotoDownloadJobs (bg URLSession) · PhotoLibraryImporter (PHAssetCreationRequest + suppression write)
  :domain:download-store SQLDelight schema + SuppressionSource (extension-read) + writer   ← app + extension link
  :domain:status         DownloadStatusSource (independent "downloaded X of Y")
  :app:ios:photokit-extension  UploadCycle gains `suppressed` port → drops suppressed assetIds pre-fan-out
  iosApp/ (Swift, thin)  bg URLSession adoption + handleEventsForBackgroundURLSession + BGTaskScheduler reg
  SnapSyncRoot           wiring + triggers (join/provision, foreground)
```

## Risks / Trade-offs

- **Relaunched-from-terminated background import unverified** → only the backgrounded-but-alive path is
  device-proven; wire the Swift `handleEventsForBackgroundURLSession` hook and verify on device; the
  staging+store fallback (foreground/backstop) guarantees eventual import regardless.
- **Import-tail with no wake event** → `BGProcessingTask` backstop drains it; if the OS defers the task,
  import completes on next foreground (still correct, just later).
- **Unbounded library/iCloud growth** → accepted: auto-importing every contributor's photos into the
  camera roll is the explicit product intent; bounded only by event contents (photos + Live Photo
  `live`, no standalone video).
- **Store grows unbounded** → acceptable at personal scale; prunable later (e.g., drop rows for assets
  no longer in any joined event).
- **`motion`→`live` cutover** → un-updated peer builds still emit `motion`; the importer treats only
  `live` as the paired video, so a stale `motion` resource is ignored until that peer updates
  (acceptable for a personal TestFlight app).

## Migration Plan

No data migration. Ship the new modules + the `motion`→`live` rename together; the producer rewrites
device.json next cycle. `docs/design.md §1` flips the contribute-only/no-download non-goal and records
the storage-truth download model. Rollback = remove the download capability + revert the role rename;
no stored state needs undoing (the download store is additive and app-local).

## Open Questions

- Exact `BGProcessingTask` cadence/identifier and whether the relaunch hook alone suffices in practice
  (resolve via the on-device verify above).
- Whether to later add a per-event album or volume controls (deferred; not in this change).
