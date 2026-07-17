# Proposal: split-mixed-files

## Why

Migration step 2 (`test/architecture/migration/PLAN.md`): files that mix a port interface (and its
model vocabulary) with a technology implementation cannot be moved cleanly in step 3a — the interface
goes to `:domain`'s `ports/` (or `model/`) while the Ktor/SQLDelight impl goes to an adapter, so a
mixed file forces hand-surgery instead of a `git mv`. Splitting them now, inside their current
modules and packages, makes every later move a whole-file move. The beacon's "port interfaces mixed
with technology impls" measurement goes 6 → 0.

## What Changes

Pure intra-module, intra-package file reorganization — no package renames, no module moves, no
signature or visibility changes, no behavior change. Convention (matching the existing
`HttpLeaveNotifier.kt`, `SqlDelightLedgerBackend.kt`, `InMemoryGalleryStatusSource.kt` precedents):
the port interface and its seam vocabulary keep the original file; each technology impl moves to
`<Tech><Name>.kt`; in-memory fakes to `InMemory<Name>.kt`; declarations bound for a *different*
step-3a zone than their file-mates get their own file named after the declaration.

The six beacon-counted files:

- `EventUnionSource.kt` (download): `HttpEventUnionSource` (+ its private DTOs) →
  `HttpEventUnionSource.kt`.
- `EventCreationClient.kt` (event-creation-ui): `HttpEventCreationClient` →
  `HttpEventCreationClient.kt`.
- `EventDetailsSource.kt` (join): `HttpEventDetailsSource` → `HttpEventDetailsSource.kt`.
- `DeviceFilesSource.kt` (membership): `HttpDeviceFilesSource` → `HttpDeviceFilesSource.kt`.
- `PushRegistration.kt` (push): `KtorPushHttpClient` → `KtorPushHttpClient.kt`; `PushHttpClient`
  (port) → `PushHttpClient.kt`; `PushTokenSource` (ports-bound at 3a) → `PushTokenSource.kt`;
  `ApnsPushToken` + the config-body DTOs + `deviceConfigJson` stay with `PushRegistration`.
- `DownloadStore.kt` (download-store): `SqlDelightDownloadStore` + the `DownloadDatabase` adapter
  factory → `SqlDelightDownloadStore.kt`.

The plan's remaining listed files (not beacon-counted — no Ktor/SQLDelight import — but mixing
declarations with different step-3a destinations):

- `Ledger.kt` (engine): `LedgerBackend` (port) → `LedgerBackend.kt`; `LedgerWriter` →
  `LedgerWriter.kt`; the vocabulary (`LedgerEntry`, `LedgerState`, `LedgerAggregates`,
  `PendingResource`) keeps `Ledger.kt`.
- `SyncEngine.kt` (engine): the transport vocabulary (`Resource`, `SyncEvent`, `UploadError`,
  `UploadRequest`, `UploadJob`, `SyncDecision`) → `SyncModel.kt`; `UploadRequestProvider` (seam) →
  `UploadRequestProvider.kt`; the `SyncEngine` class keeps `SyncEngine.kt`.
- `RawAsset.kt` (gallery): `RawAssetSource` (port) → `RawAssetSource.kt`; `InMemoryRawAssetSource`
  (fake) → `InMemoryRawAssetSource.kt`; `RawResource`/`RawAsset` + the raw-value consts keep
  `RawAsset.kt`.
- `DeviceManifestProducer.kt` (gallery): `DeviceManifestStore` (port) → `DeviceManifestStore.kt`;
  `DeviceManifestUploader` (port) → `DeviceManifestUploader.kt`; the pure
  `deviceManifestAssetsFromResources` mapping (model-bound at 3a) → `DeviceManifestMapping.kt`;
  the `DeviceManifestProducer` class keeps its file.

Everything stays in its current package, so no import changes anywhere outside the split files.

## Capabilities

No spec deltas. The step is behavior-preserving file reorganization (PLAN.md step 2: "no spec
deltas"); every touched module's capability contract — `photo-download`, `event-creation-ui`,
`join-event`, `event-membership` (leave/reconcile), `push-registration`, `download-store` /
`echo-suppression`, `sync-engine`, `gallery-status` / `device-manifest` — is untouched: same
declarations, same signatures, same packages, same modules.

## Impact

- 10 files split into 24 (14 new files, all in the same package as their source file).
- No build-file, settings, or dependency changes; no test changes (tests reference unchanged FQNs).
- Beacon: mixed 6 → 0 (total 97 → 91); all other law counts unchanged.
