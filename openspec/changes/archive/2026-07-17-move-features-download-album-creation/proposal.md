# Proposal: move-features-download-album-creation

## Why

Migration step 6 of the `module-architecture` plan (`test/architecture/migration/PLAN.md`,
"features II"). Steps 0–5 left three features stranded in legacy modules — download in
`:capability:download`, album in `:capability:album`, creation in `:capability:event-creation-ui` —
plus ten module skeletons that are sourceless (or become sourceless with these moves) and one last
illegal graph edge (`:domain:presentation → :capability:event-creation-ui`, the only edge the
beacon still counts). Moving the features into `domain/src/*/kotlin/app/snapsync/feature/<name>/`
puts them under the armed feature-blindness gate, kills that edge, retires the two ext-safe
interim adapter edges step 5 re-documented as dying here, and lets the module set shrink by ten.

## What Changes

Pure `commonMain` moves — behavior-preserving, bodies byte-identical, only `package`/`import`
lines change:

- **`feature/download`** (package `app.snapsync.feature.download`): `DownloadController`,
  `QueuedPhotoDownloadJobs`, `DownloadPushReceiver`, `StoreDownloadStatusSource` (from
  `:capability:download`) and the `DownloadStatusSource` read-model + `DownloadProgress` +
  `InMemoryDownloadStatusSource` (from `:domain:status`, per step-5's D6 — the read-model seats
  with the feature that implements it). `DownloadPushReceiver` moves although its upload twin
  stayed behind at step 5: the twin's file also carries `FanOutPushReceiver`, a cross-arm
  coordinator with no lawful feature seat before flow/compose exist; the download receiver has no
  such baggage and references only ports and its own feature (design D2).
- **`feature/album`** (package `app.snapsync.feature.album`): `AlbumCoordinator`,
  `AlbumMapSource`/`albumMapSource` (from `:capability:album`). The two album seam interfaces
  (`AlbumManager`, `AlbumMapStore`) go to **`ports/`** — they are the PhotoKit / shared-store I/O
  boundary, exactly what the ports law names (design D3).
- **`feature/creation`** (package `app.snapsync.feature.creation`): `CreateEvent`,
  `CreationStatus`/`CreationFailureReason`/`CreationStatusSource`/`EventCreator`/
  `MutableCreationStatusSource`/`NoOpEventCreator` (from `:capability:event-creation-ui`). This is
  what kills the `presentation→event-creation-ui` build edge: presentation reaches the same types
  through its existing `:domain` dependency.
- **`feature/upload` rider**: `ResourceEnumerator` (from `:domain:gallery`) — the platform-free
  `PhotoLibrary`-from-`RawAssetSource` composition the ext-safe adapter delegates to; seated with
  its primary consumer, the upload discovery walk, until `compose/` exists (step 7; design D4).
- **`feature/membership` rider**: `DeviceManifestProducer` (from `:domain:gallery`) — seated in
  membership, not upload, so the manifest object behind the `Enrollment` port keeps **one writer
  feature** (`ManifestDeviceEnroller` already writes it from membership; design D5).
- **Tests**: `QueuedPhotoDownloadJobsTest`, `AlbumCoordinatorTest`, `AlbumMapMigrationTest`,
  `CreateEventTest`, `DeviceManifestProducerTest` move with their subjects into `:domain`
  `commonTest` (they need only model/ports + in-file fakes). Three download tests
  (`DownloadControllerTest`, `DownloadPushReceiverTest`, `StoreDownloadStatusSourceTest`) need the
  legacy `InMemoryDownloadStore` fake and re-home to `:domain:download-store` `commonTest`; the two
  stay-behind status tests (`LedgerBackedSyncStatusSourceTest`, `OwnDeviceGalleryStatusSourceTest`)
  need the gallery fakes and re-home to `:domain:gallery` `commonTest` (design D6).
- **Ten modules deleted** (settings + build file + dirs; every consumer dep line pruned):
  `:capability:upload-url`, `:capability:config`, `:capability:join`, `:capability:membership`,
  `:capability:album`, `:capability:event-creation-ui`, `:capability:download`, `:domain:logging`,
  `:domain:permission`, `:domain:status`. Kept, each with content PLAN assigns to a later step:
  `:capability:upload` (push receivers → step 8), `:capability:attest` + `:domain:gallery` +
  `:domain:download-store` (honest doubles → step 10), `:capability:push` (registration/notify →
  steps 7–8), `:domain:engine` (contract tests → step 10), `:domain:keychain` (ProtectedData
  skeleton → step 12).
- **Interim adapter edges die**: `:adapter:ios:ext-safe` drops `api(":capability:album")` and
  `api(":domain:gallery")` — everything it referenced now arrives via `api(":domain")`.
- CLAUDE.md modules list updated (deleted rows removed, changed rows rewritten); diagrams
  regenerated; beacon `targetModules` untouched (none of the deleted modules were in it).

## Capabilities

### Modified (spec deltas)

- **`event-creation-ui`**: placement of the create seams (`feature/creation` zone) and the
  details-client cross-reference (`EventDirectory` is a `ports/` port, `HttpEventDirectory` an
  `:adapter:generic` adapter — `:capability:join` is gone).
- **`sync-status`**: `SyncStatus`/`SyncProgress` placement language (`model/` zone, seated at
  step 3a; `:domain:status` no longer exists).
- **`gallery-status`**: "Module placement keeps the seam off presentation" restated for zones
  (`:domain:status` deleted; the boundary is now presentation's dependency set), the enumeration
  seam's composition (`ResourceEnumerator` in `feature/upload`; fakes remain in `:domain:gallery`
  until step 10), and the round-trip parser placement (`model/`; the `:capability:membership`
  mention retired).
- **`permission-gate`**: contracts placement (`PermissionStatus` in `model/`, the two ports in
  `ports/`; `:domain:permission` deleted) and the iOS adapter placement
  (`:adapter:ios:app-only`, where step 4 put it).
- **`join-event`**: "One details client" placement (`ports/` + `:adapter:generic`).
- **`sync-status-screen`**: clock-free-projection language re-anchored from `:domain:status` to
  the `feature/status` zone.
- **`photo-selection-policy`**: `Contribution`'s stated home (`model/`, visible to both policy
  consumers — the ":domain:gallery as the only shared module" rationale is obsolete).
- **`ios-url-session-upload`**: `UploadCycle` (feature/upload) and `EdgeUploadRequestProvider`
  (`model/`) placement in the app-driven host requirement.
- **`ios-photokit-upload`**: config-assembly requirement's `:capability:config` store reference →
  the ext-safe `KeychainConfigStore`.
- **`event-invite-qr`**: the share-action requirement's `:capability:config` dependency language →
  the `model/` `EventLink` codec reached via `:domain`.

### Touched without a delta (accounting, per the archive gate)

- **`photo-download`**, **`event-album`**: behavior-preserving moves; neither spec's requirements
  name a module placement for the moved types (capability cross-references only).
- **`diagnostic-logging`**, **`edge-upload-provider`**: only their **Purpose** prose named the dead
  modules (`:domain:logging`, `:capability:upload-url`); Purpose is not delta-addressable, so the
  prose is reconciled in-place in the main specs (the reconciliation step-5's accounting deferred
  to this step). Same for the stale `:domain:status`/`:domain:gallery` Purpose sentences of
  `sync-status` and `gallery-status`.
- **`device-attestation`** (`:capability:attest` untouched), **`push-registration`** /
  **`upload-completion-notify`** (`:capability:push` untouched), **`harness-world-model`** /
  **`full-stack-harness`** / **`desktop-test-harness`** (import-line updates only in world and the
  harness controllers), **`ios-app-shell`** (composition roots update imports only),
  **`architecture-guards`** (no gate text changes; the feature-blindness gate covers the new
  features by its derived scope — verified red on a planted sibling reference).
- **`gallery-status`** inherited-stale placements NOT fixed here (accounted): "GalleryStatusSource
  seam ... in a new `:domain:gallery` module", "Platform backing"'s iOS-implementation location,
  and the raw-walk seam's `:domain:gallery` definition — all falsified by steps 3a/4 (ports moved
  to `ports/`, iOS impls to `:adapter:ios:ext-safe`), not by this step; the in-memory-fake claims
  those requirements also make are still true of `:domain:gallery` today. They reconcile when the
  fakes re-home at step 10.
- **`ios-photokit-upload`** line-27 orchestration placement (":capability:upload") — falsified at
  step 5 (cycle moved to `feature/upload`), not here; `:capability:upload` still exists and dies at
  step 8, which owns that reconciliation.

## Impact

- `./gradlew build`, `compileIosMainKotlinMetadata`, `:test:architecture:test` green; diagrams
  regenerated; no shell (`app/**`, Swift) source changed beyond import lines.
- Beacon: 66 → 55 — edges −1 (1 → 0, the last illegal graph edge), module set −10 (27 → 17);
  shells 36, mixed 0, ledger 2 unchanged. Exactly the PLAN row's "edges −1 (→0) · modules Δ".
- No runtime identity string moves (`RuntimeIdentityTest` green); no threading or behavior change.
