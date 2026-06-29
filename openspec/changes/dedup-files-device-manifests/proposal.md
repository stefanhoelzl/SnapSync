## Why

The backend uploads every qualifying photo **once per event** and writes **one immutable manifest
object per asset**. Two costs follow: switching events re-uploads photos that are already in storage
(no cross-event dedup — `flatten-event-namespace` accepted this), and one tiny manifest upload per
asset is a lot of background round-trips. Separately, there is **no contributor identity**, so a
future restore feature could never tell a device's own (possibly-deleted) photos from another
contributor's — `flatten-event-namespace` explicitly foreclosed this.

This change repoints the same machinery at a **device-partitioned, event-independent byte store**
(`/files/<device-id>/…`): each photo uploads **once** and is *linked* into any number of events by
reference, never re-uploaded. It **replaces the per-asset manifest objects with one per-event device
manifest** (`/events/<eventId>/device/<device-id>.json`), collapsing N tiny uploads into one. And it
**records a stable per-device id** (minted in the shared Keychain) — folded into the byte path and the
manifest, *preparing* a future restore without building it.

It **supersedes two archived changes**: `flatten-event-namespace` (device-id returns, as a path level
on `/files/` and the device.json key) and `immutable-asset-manifests` (the per-event device.json is
**mutable**, so the write-once / permanently-cacheable property is dropped). The engine and ledger are
untouched and stay event-blind.

## What Changes

- **BREAKING — storage layout.** Three object kinds, no migration (clean cutover; old
  `<eventId>/<file>` + `<eventId>/<assetId>.manifest.json` objects are orphaned and ignored; a
  re-joined old event re-uploads under the new scheme):
  ```
  /files/<device-id>/<assetId>-<role>.<ext>     bytes — device-global, uploaded once, reused across events
  /events/<event-id>/metadata.json              event marker {eventId,name,createdAt}  (was events/<id>.json)
  /events/<event-id>/device/<device-id>.json    per-event device manifest (mutable full-state projection)
  ```
- **Device identity (new).** A per-install UUID minted in the **shared Keychain** (app + extension,
  survives reinstall). It is the `/files/` partition and the device.json key — the "which manifest is
  mine" handle a future restore needs.
- **Cross-event dedup.** The ledger already keys by the **bare filename** (`<assetId>-<role>.<ext>`,
  event-independent); ack-path recovery already reads the URL's last segment. So dedup needs **no
  engine/ledger change** — only the reconcile **seed source**: seed *additively* from the **per-device**
  file listing (`GET /files/device/<id>`) and **never clear** on an event switch, so a switch
  re-uploads nothing already in `/files/`.
- **Device manifest (new, replaces per-asset manifests).** device.json is a **mutable, full-state
  projection** of a **device-global accumulator** (all discovered-not-deleted assets with their
  manifest detail). Each event's device.json is the date-filtered projection PUT to that event's path.
  The extension is its **sole writer** and **PUTs it synchronously in-cycle** (no background
  `URLSession`, no app involvement). Deletion prunes the accumulator entry → manifest drops the asset.
- **Status becomes own-device progress.** `completed` = the device's qualifying assets whose every
  expected resource (from the shared `gallery-status` enumeration seam) is present per `GET
  /files/device/<id>`; `pending` = qualifying − completed. **`PendingManifestsSource` and the
  manifest-completeness list logic are removed.** The app reads **no** device.json.
- **Edge read surface.** `GET /files/device/<id>` (file list, keeps `url`) and `GET
  /files/device/<id>/<file>` (byte download — no v1 consumer, kept as restore forward-prep). The
  **event-wide union is not an edge endpoint** — the external/admin viewer reads bunny directly (§3.5).
- **BREAKING — ungated byte uploads.** `PUT /files/device/<id>/<file>` drops the event-existence gate
  that `/event/<id>/file/...` enforces today (accepted, eyes-open for a personal TestFlight app; App
  Attest is the future hardening, `§8`). The **device.json write stays gated** on event existence.

## Capabilities

### New Capabilities
- `device-identity`: a stable per-install device id, minted once and persisted in the shared Keychain
  (readable by both app and extension), exposed as the `/files/` partition segment and the device.json
  key.
- `device-manifest`: the per-event `device.json` — its JSON schema (a `deviceId` and an `assets[]` of
  per-asset entries `{assetId, creationDate, resources[]{role, contentType, filename,
  originalFilename}}`), its derivation as a date-filtered projection of the device-global accumulator,
  and its mutable, synchronously-PUT, write-once-per-cycle lifecycle (sole writer = the extension).

### Modified Capabilities
- `bunny-upload-endpoint`: byte `PUT` moves to `/files/device/<deviceId>/<filename>` and is **ungated**
  (the event-existence gate is removed); the stored key is `/files/<deviceId>/<filename>`. A second,
  **event-gated** write route accepts the device manifest at `/event/<eventId>/device/<deviceId>`.
- `edge-upload-provider`: the URL builder composes `/files/device/<deviceId>/<encoded-filename>` and is
  injected the **deviceId** (alongside the eventId it already carries).
- `bunny-list-endpoint`: becomes a **per-device raw file listing** `GET /files/device/<deviceId>` (no
  manifest read, no completeness computation, no immutable cache); the event-wide / manifest-completeness
  listing is removed from the edge.
- `bunny-download-endpoint`: the object route moves to `/files/device/<deviceId>/<filename>`; the public
  URL format follows.
- `event-rejoin-reconciliation`: seeds **additively** from the per-device file listing, **never clears**
  the ledger; an event **switch** keeps ledger + cursor + accumulator and merely re-projects device.json
  to the new path.
- `event-creation`: the event marker/metadata object moves to `/events/<eventId>/metadata.json`.
- `sync-status`: own-device progress from the gallery enumeration seam × the per-device file listing;
  `PendingManifestsSource` and the manifest-based `CompletedAssetsSource` are removed.
- `ios-background-upload`: the per-asset manifest side-channel is replaced by the device-global
  accumulator + synchronous in-cycle device.json `PUT`; re-provision/switch and deletion-prune behavior
  follow the accumulator/reconcile model.
- `gallery-status`: the existing resource-enumeration seam additionally feeds the app-side status
  consumer (expected resource sets); enumeration logic itself is unchanged.
- `sync-ledger`: clarified event-independence (key is the bare filename) and that the atomic baseline
  reset is no longer used on an event switch (reconcile seeds additively instead).

### Removed Capabilities
- `asset-manifest`: superseded by `device-manifest`. The per-asset, immutable, write-once manifest
  object and its read-time completeness no longer exist.

## Impact

- **Code:** `device-identity` (new Keychain-backed seam, app + extension); `edge-upload-provider`
  (deviceId + URL template); `backend/` (upload route + new device.json route + ungating, per-device
  list, download path, metadata path); `app/ios/photokit-extension` (accumulator + projection +
  synchronous device.json PUT, replacing the manifest `URLSession` path; reconcile seed source);
  `:capability:rejoin` (`EventFilesSource` → per-device list seam, additive seed, switch handling);
  `:domain:status` (own-device sources, remove `PendingManifestsSource`); `iosApp` host (drop manifest
  `handleEventsForBackgroundURLSession` wiring).
- **Untouched:** `:domain:engine` (event-blind; key already bare filename; ack-path already
  last-segment), `:domain:presentation`/`:domain:ui` (no `UiState` change — status counts only),
  `deeplink-config`, `permission-gate`, `leave-event`.
- **Docs:** `docs/design.md` — reverse `flatten-event-namespace` (device-id returns) and
  `immutable-asset-manifests` (mutable device.json); record the `/files/<device-id>/` store, the device
  manifest, own-device status, the ungated-upload trade-off, and the deferred restore/union.
- **Accepted, eyes open:** ungated `/files/` writes widen the abuse surface vs today's event-gated
  upload; device-id is self-asserted. Acceptable for a personal TestFlight app; App Attest is the
  hardening path. Restore behavior and any event-wide union read are **deferred**.
