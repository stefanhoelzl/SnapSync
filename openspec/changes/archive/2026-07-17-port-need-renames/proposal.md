# Proposal: port-need-renames

## Why

Migration step 3b (`test/architecture/migration/PLAN.md`): the `module-architecture` law "Ports are
the I/O boundary named for the need" requires every port name to describe the need it serves — a
name that must survive a second platform — never the technology or the mechanism satisfying it.
Step 3a moved every port into `:domain` keeping its legacy name; this step renames the ones PLAN
names to their target need-names. Kept separate from 3a so "mechanical" stays a verifiable claim on
both PRs.

## What Changes

Pure Kotlin-rename semantics — no signature, parameter, behavior, or visibility change anywhere.
Eight type renames (types, filenames via `git mv`, and all references tree-wide), from PLAN 3b's
explicit list:

- `LedgerBackend` → `LedgerStore` — **keeping its `model/` seat**: a ports/-seat trial turned the
  armed model-purity gate red (`LedgerWriter` in `model/` would import `ports/`), confirming 3a's
  D3; the seat moves at step 5 when the writer becomes feature code.
- `GalleryResourceEnumerator` → `PhotoLibrary` (the "gallery enumeration" seam)
- `PermissionRequester` → `PhotoAccessRequester`, `PermissionStatusSource` →
  `PhotoAccessStatusSource` (the "permission pair", stem `PhotoAccess`; the pair stays two ports —
  merging would be an API change, out of a rename step's scope)
- `UploadJobPlatform` → `BackgroundTransfer`
- Backend seams: `EventDetailsSource` → `EventDirectory`, `EventCreationClient` → `EventCreation`,
  `DeviceManifestUploader` → `Enrollment`
- PLAN's fourth backend-seam name, `TransferNotify`, is **not assigned**: the only candidate,
  `PushHttpClient`, carries two needs (registration `PUT` + notify `POST`); renaming it would
  mislabel the registration path and splitting it is not a rename. Deferred to the step that splits
  backend access into need-named ports.

Implementations, fakes, and contract tests whose names embed a renamed port follow it (technology/
fake prefix kept): `InMemoryLedgerStore` ×2, `SqlDelightLedgerStore`(+`Test`), `IosLedgerStore`,
`FakeLedgerStore`, `WorldLedgerStore`, `NativeLedgerStoreTest`, `LedgerStoreContract`,
`iosLedgerStore` (factory), `InMemoryPhotoLibrary`, `FakeBackgroundTransfer`, `HttpEventDirectory`
(+`Test`), `HttpEventCreation`(+`Test`), `HttpEnrollment` ×2, `IosEnrollment` ×2. Impl names that
embed only a fragment of an old port name (`PhotoLibraryResourceEnumerator`, `ResourceEnumerator`,
`IosUrlSessionUploadPlatform`, `IosPhotoKitUploadPlatform`, `PhotoLibraryPermission`,
`ConstSyncStatusSource`-style neighbours) are untouched — partial-embed renames are judgment calls
a mechanical step refuses.

Ride-alongs: CLAUDE.md module rows/prose; PLAN.md forward-looking references (+ the 3b row's
Δ-note); the beacon's deletion-ledger pattern `class \w*DeviceManifestUploader` → `class
\w*Enrollment` (a loud-stale list, updated in-PR so the ×4-keep-1 debt stays counted and the
beacon's ledger distance stays 2); two identifier mentions in comments (`ios.yml`,
`iosApp.entitlements`); `architecture/` regenerated.

Ports with **no spec/PLAN target keep their names**: `ConfigStore`/`ConfigSource`/`ConfigReader`,
`Keychain` seams, `RawAssetSource`, `GalleryStatusSource`, `DeviceManifestStore`,
`DownloadStore`/`SuppressionSource`/`DownloadTransport`/`DownloadTask`/`DownloadTransportHost`/
`PhotoDownloadJobs`/`PhotoLibraryImporter`, `EventUnionSource`, `DeviceFilesSource`,
`BackgroundScheduler`, `DiscoveryStore`, `JoinedEventMarker`, `PushReceiver`/`PushTokenSource`/
`PushHttpClient`, `AttestKey`/`AttestClient`/`AttestStore`. Method names are untouched throughout —
the spec names no methods.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

Deltas restate, name-for-name, every requirement whose contract text names a renamed port — meaning
unchanged: `architecture-guards`, `desktop-test-harness`, `event-creation-ui`, `full-stack-harness`,
`harness-world-model`, `ios-app-shell`, `ios-photokit-upload`, `ios-url-session-upload` (requirement
header renamed), `join-event`, `permission-gate` (plus its `## Purpose`, edited in place — deltas
cannot carry Purpose text), `sync-ledger`, `sync-status`.

Modules touched with no delta: `:domain` and every module that merely references a renamed type
(`:capability:*`, `:domain:*`, `:app:*`, `:test:*`) — behavior-preserving renames; their
capabilities' contract text names none of the old identifiers (verified by grep).
