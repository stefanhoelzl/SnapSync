## Why

SnapSync is contribute-only today: a device uploads its own photos to an event but never gets the
**other** contributors' photos back. The backend already exposes everything needed to close that
loop — the event-wide union read (`GET /event/<eventId>/files`) returns every contributing device's
**complete** assets (tagged with their owning `deviceId`, each resource carrying a download `url`),
and `device-identity` gives each install a stable id. This change adds the **device side**: a joined
device automatically downloads the other contributors' photos and imports them, full-fidelity, into
the local Photos library — so a shared event's photos **just appear on every participant's phone**,
without anyone opening the app.

## What Changes

- **BREAKING (scope reversal).** `docs/design.md §1` flips from "No in-app viewing / download —
  contribute-only" to **download-and-import is in scope**. The engine stays upload-only and
  event-blind; downloads are a **storage-truth, app-side** concern (not an engine direction).
- **Auto-download foreign assets.** On join and on each foreground, the app reads the union, selects
  assets whose `deviceId != myDeviceId` and that it has not already imported, and downloads each
  resource (background `URLSession`, Wi-Fi **and** cellular, non-discretionary) to durable App-Group staging. Discovery is
  **foreground-only**: photos added later by others are picked up on the next foreground; the initial
  join's transfers + imports complete in the **background** with no reopening.
- **Full-fidelity per-asset import.** When all of an asset's resources are staged, one
  `PHAssetCreationRequest` recreates it (`live`→`.pairedVideo`; `primary`→`.photo`/`.video`/`.audio`
  by `contentType`) into the **camera roll**. Background import is device-verified; the
  relaunched-from-terminated path uses a Swift `handleEventsForBackgroundURLSession` hook, with an
  optional `BGProcessingTask` backstop for the import tail. No terminal failure state — retried.
- **Echo-suppression.** An imported asset gets a new local `localIdentifier`; without intervention the
  upload extension would re-upload it (an echo, duplicated across devices). The importer records that
  created `localIdentifier` into the download store **inside the `performChanges` change block**
  (before the asset is discoverable); the extension's discovery skips any asset in that suppression
  set before creating upload jobs. Permanent and global (survives leave/switch).
- **Idempotency / dedup.** A unified download store keyed by `(sourceDeviceId, sourceAssetId)` records
  imported foreign assets; terminal rows are **permanent**, so a deleted collected photo is never
  re-downloaded and the same asset linked into multiple events imports once (cross-event dedup).
- **Status.** An independent **"downloaded X of Y"** line (foreign complete assets) on the joined
  screen; the own-device upload "Completed" is unaffected.
- **`device-manifest` role rename:** `motion` → `live` (clean cutover; the producer re-projects each
  device.json next cycle).

## Capabilities

### New Capabilities
- `photo-download`: the device-side download+import pipeline — union consumption, own/foreign split by
  `deviceId`, background-`URLSession` resource download to App-Group staging, full-fidelity per-asset
  `PHAssetCreationRequest` import (camera roll), background/relaunch import + `BGProcessingTask`
  backstop, foreground-only discovery, and the no-FAILED retry posture.
- `download-store`: a per-install App-Group SQLite store (its schema + read/write seams) recording one
  row per imported foreign asset `{sourceDeviceId, sourceAssetId, createdLocalId, state}` plus
  per-resource staging state; app-written, with a `createdLocalId` suppression projection the upload
  extension reads. Lives in a lean shared module both the app and the extension link.

### Modified Capabilities
- `ios-background-upload`: the upload cycle's discovery gains a **suppression filter** — resources
  whose `assetId` is in the download store's suppression set are dropped before job fan-out and
  `retainAssets`, so a downloaded-then-imported asset is never re-uploaded.
- `device-manifest`: the Live-Photo paired-video resource `role` is renamed `motion` → `live`
  (schema + producer); the union passes the role through unchanged.
- `sync-status`: adds an independent download-progress projection (foreign complete assets
  downloaded/total) surfaced as a separate line; own-device upload status is unchanged.
- `bunny-list-endpoint`: documents the on-device consumer of the union read (the download client);
  the endpoint contract itself is unchanged.

## Impact

- **New code:** `:capability:download` (union source, planner, `PhotoDownloadJobs` background
  `URLSession`, `PhotoLibraryImporter`); `:domain:download-store` (SQLDelight schema +
  `SuppressionSource` + writer, linked by app and extension); `:domain:status` `DownloadStatusSource`.
- **Modified:** `:app:ios:photokit-extension` `UploadCycle` (+ suppression port and iosMain reader);
  `:domain:gallery` `DeviceManifest`/producer (`motion`→`live`); `iosApp/` Swift host (background
  `URLSession` adoption + `handleEventsForBackgroundURLSession`, `BGTaskScheduler` registration);
  `SnapSyncRoot` wiring + join/foreground triggers; `domain/presentation` `UiState` (download line).
- **Entitlements/Info.plist:** background modes for `BGTaskScheduler` (processing) if the backstop is
  used; the App-Group store file.
- **Untouched:** `:domain:engine`/`sync-ledger` (event-blind, single-writer), the backend (the union,
  download, and device-manifest endpoints already exist), `deeplink-config`, `permission-gate`
  (Photos write reuses the existing full-access grant).
- **Device-verify (carried into impl):** relaunched-from-terminated background import via the Swift
  hook; the `BGProcessingTask` import-tail backstop.
