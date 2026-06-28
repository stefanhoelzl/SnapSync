## Why

A download/restore client cannot currently tell when an asset is whole: the flat object list reveals
which resources are uploaded but never how many an asset *should* have, and edits append new resources
over time so the set never settles. The model is also iOS-shaped (resource keys carry `PHAssetResourceType`
names). We fix all three by making each asset an **immutable, original-only, fixed set of generically-typed
resources described by a per-asset manifest**, and by computing completeness **at read time** in the list
endpoint (which can read the manifest) rather than hoping uploads land in a particular order — which is
impossible on iOS, where the OS owns background-job scheduling.

This is **Change 1 of a two-change redesign**. It is self-contained and shippable: the producer side
(resources + manifest), the storage layout, the completeness-computing list endpoint, and rejoin seeding.
A documented follow-on (Change 2) decouples the app's status projection from the ledger to read the same
completeness listing; this change leaves the ledger-backed status stack untouched.

## What Changes

- **BREAKING — storage layout.** Resource keys drop the iOS-kind segment for generic **roles**:
  `<eventId>/<assetId>-primary.<ext>` and (Live Photos only) `<eventId>/<assetId>-motion.<ext>`. No
  migration: old `…-ios.<kind>.<ext>` objects are orphaned; new events use the new scheme. Rejoin won't
  match old keys, so a re-joined old event re-uploads under the new layout.
- **Immutable, original-only resources.** The extension enumerates **only original** `PHAssetResource`s
  and maps them to roles: `photo`/`video`/`audio` → `primary`, `pairedVideo` → `motion`. All edit
  artifacts — `fullSize*`, `adjustmentData`, `adjustmentBase*`, proxies, and the RAW `alternatePhoto`
  — are **no longer uploaded**. An asset's resource set is therefore fixed at capture and never grows.
- **Per-asset manifest (new).** For every asset the extension writes a manifest JSON to the App Group and
  uploads it to `<eventId>/<assetId>.manifest.json` via a **vanilla background `URLSession`** (the OS
  `PHAssetResource` job API cannot carry synthetic bytes). The manifest carries `version`, `assetId`,
  `creationDate`, and `resources[]` each `{ role, contentType, filename, originalFilename }`. The on-disk
  file is the manifest's own dedup/retry marker (re-enqueue while it exists and is absent from storage).
  The manifest is **not** an engine/ledger resource — it stands alone, so the engine and `createJob` stay
  photo-only and no second ledger writer is introduced.
- **BREAKING — completeness at read time.** `GET /event/<id>/files` stops returning a flat object array
  and instead returns **only complete assets**: it reads each manifest and includes an asset only when
  every resource the manifest names is present as an object. Because assets are immutable, a complete
  result is cacheable forever.
- **Rejoin seeds from the same endpoint.** Reconciliation seeds the ledger `COMPLETED` for the resources
  of each **complete** asset returned by the listing; the rare partially-uploaded asset simply re-uploads
  (idempotent, last-write-wins). No separate raw-object-list endpoint is added.

## Capabilities

### New Capabilities
- `asset-manifest`: the per-asset manifest — its JSON schema (`version`, `assetId`, `creationDate`,
  `resources[]{role, contentType, filename, originalFilename}`), the generic role vocabulary
  (`primary`/`motion`), and the side-channel upload lifecycle (App Group file as dedup/retry marker,
  background `URLSession` upload, completion observed via storage rather than recorded).

### Modified Capabilities
- `ios-background-upload`: resource fan-out becomes **original-only** with **role-based generic keys**
  (was: full fan-out of every `PHAssetResourceType` with iOS-kind keys); the extension additionally
  generates each asset's manifest and uploads it on the background `URLSession` side channel, and the app
  host wires `handleEventsForBackgroundURLSession` so the OS can deliver that session's events.
- `bunny-list-endpoint`: the listing returns **complete assets computed from manifests** (read each
  manifest, include only assets whose named resources are all present), with an immutable-complete cache —
  replacing the flat "every object" array and its per-object entry shape.
- `event-rejoin-reconciliation`: the file-list seam returns **assets** (not raw files); seeding marks the
  resources of each complete asset `COMPLETED` and lets partial assets re-upload, rather than matching
  every stored filename one-to-one.

## Impact

- **Code:** `:domain:gallery` resource enumeration (original-only + role keys, manifest synthesis);
  `app/ios/photokit-extension` (manifest generation + background `URLSession` path; resource fan-out);
  `iosApp` host (`handleEventsForBackgroundURLSession`); `backend` list endpoint (manifest read +
  completeness + cache); the `EventFilesSource` seam + reconciliation seeding.
- **Untouched this change (Phase 2):** `sync-engine` (resources stay opaque), `edge-upload-provider`
  (filename-agnostic URL building), `bunny-upload-endpoint` (any filename, incl. the manifest, PUTs the
  same way), `sync-status`/`gallery-status`/`observed-completion-overlay`/`sync-ledger` (the app's
  ledger-backed status stack — rewritten/decoupled in Change 2). Phase-1 caveat: the app's completed count
  is resource-based, so an asset may read "complete" a beat before its manifest lands; the server listing
  is the authoritative gate.
- **Docs:** `docs/design.md` — reverse the "full resource fidelity" decision; record immutability,
  the manifest, read-time completeness, and the Change 2 follow-on.
