# Proposal: extract-adapter-modules

## Why

Migration step 4 (`test/architecture/migration/PLAN.md`): the `module-architecture` law "Ports are
the I/O boundary named for the need" places every technology implementation in an adapter module,
named for the technology, **placed by linkage** — extension-safe vs app-only vs generic. Today the
iosMain adapters are scattered across nine `domain/*`/`capability/*` modules and two `:app:ios:*`
satellite modules, which is the prerequisite blocker for the feature moves (steps 5–6): a feature
cannot move into `:domain` while its module still carries an iosMain source set.

## What Changes

Behavior-preserving moves only — every moved file is a whole-file `git mv` with **zero body edits
and zero package renames** (packages are organization, and this step deliberately keeps the diff
verifiable as pure motion; package normalization rides the feature-move steps, which already do
tree-wide renames).

- **Create `:adapter:generic`** (jvm + iosArm64 + iosSimulatorArm64): the platform-free technology
  impls — the eight Ktor clients (`HttpAttestClient`, `HttpEnrollment`, `HttpEventDirectory`,
  `HttpDeviceFilesSource`, `HttpLeaveNotifier`, `HttpEventUnionSource`, `HttpEventCreation`,
  `KtorPushHttpClient`) and the two SQLDelight stores (`SqlDelightLedgerStore`,
  `SqlDelightDownloadStore`) with their `.sq`/`.sqm` schemas and the SQLDelight plugin config
  (both databases, generated packages unchanged). Self-contained MockEngine tests of moved
  clients move along.
- **Create `:adapter:ios:ext-safe`** (iosArm64 + iosSimulatorArm64): every iosMain impl the
  extension process links — ledger/download-store native drivers, manifest store, PhotoKit
  enumerator, discovery walk + cursor store (from `:app:ios:photokit-discovery`), the Keychain
  module's impls (`IosKeychain`, `KeychainDeviceIdentity` — the SecItem containment module
  **moves here**), config/attest/album Keychain stores, `IosAttestKey`, `darwinHttpClient`,
  `IosJoinedEventMarker`, the two device-log writers.
- **Create `:adapter:ios:app-only`** (iosArm64 + iosSimulatorArm64): the adapters only the app
  process links — `IosUrlSessionUploadPlatform` + `IosBackgroundScheduler` (from
  `:app:ios:url-session-upload`), `IosDownloadTransport`, `IosPhotoLibraryImporter`,
  `PhotoLibraryPermission`.
- **Delete the two emptied modules** `:app:ios:photokit-discovery` and
  `:app:ios:url-session-upload`; prune both from `appShellSources`.
- **Rewire the framework exports**: `SnapSyncUploadKit` (`:app:ios:photokit-extension`) and
  `SnapSyncKit` (`:app:ios`) now compose the three adapter modules; `baseName`s unchanged
  (pinned by `RuntimeIdentityTest`).
- **Guard flips**: `KeychainContainmentTest` owning module → `:adapter:ios:ext-safe`; the
  extension-safety gate arms (its `adapter/ios/ext-safe` scope now exists) — verified by a
  deliberate red; diagram scan lists extended with `adapter`.
- Emptied-but-kept skeletons (`:domain:permission`, `:capability:config`, `:domain:engine`
  production, `:domain:download-store` SQLDelight half) get minimal build-file cleanup; module
  deletions beyond the two named are steps 5/6.

## Capabilities

### Modified
- `architecture-guards`: the Keychain-containment owning module becomes `:adapter:ios:ext-safe`.
- `device-identity`: `KeychainDeviceIdentity` placement `:domain:keychain` → `:adapter:ios:ext-safe`.
- `ios-app-shell`: the `iosLedgerStore()` factory placement `:domain:engine` → `:adapter:ios:ext-safe`.
- `ios-photokit-upload`: the shared discovery placement `:app:ios:photokit-discovery` →
  `:adapter:ios:ext-safe`; the extension composes the adapter modules.
- `ios-url-session-upload`: the app-driven adapters' placement `:app:ios:url-session-upload` →
  `:adapter:ios:app-only`.

## Impact

- New modules: `:adapter:generic`, `:adapter:ios:ext-safe`, `:adapter:ios:app-only` (all three
  already in the beacon's `targetModules`). Deleted: `:app:ios:photokit-discovery`,
  `:app:ios:url-session-upload`. Expected beacon Δ: modules −5; shells shrink by the moved
  `:app:ios:*` decisions.
- Runtime identity: every pinned literal rides a whole-file move; `RuntimeIdentityTest` green is
  the proof of zero drift.
- Consumers rewired (build files only): `:app:ios`, `:app:ios:photokit-extension`, `:test:world`,
  `:test:integration`, `:app:desktop`, `:capability:push` (test dep), `:domain:engine` /
  `:domain:download-store` (their contract tests now test the moved impls across the module line).
