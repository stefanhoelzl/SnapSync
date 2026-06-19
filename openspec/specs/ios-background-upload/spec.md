# ios-background-upload Specification

## Purpose
TBD - created by archiving change ios-background-upload. Update Purpose after archive.
## Requirements
### Requirement: Background upload extension target

The system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The extension's logic SHALL live in a lean Kotlin Multiplatform module (`:app:ios:photokit-extension`) that depends only on `:domain:engine` (no Compose/UI), packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and a `BackgroundUploadURLBase`, and it links the `:app:ios:photokit-extension` framework

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

### Requirement: In-extension discovery via persistent change token

On each `process()` invocation, the extension SHALL discover work itself (the system does not enumerate). On first run (no token) it SHALL enumerate the whole library via `PHAsset.fetchAssets` and capture `currentChangeToken` as baseline; in steady state it SHALL call `fetchPersistentChanges(since:)` and advance the change token. On `persistentChangeTokenExpired` it SHALL re-enumerate the whole library, relying on the ledger to skip already-recorded keys. **v1 simplification:** the token is held **in-process only** (a cold extension start re-enumerates, which the ledger makes harmless); persisting it per change record across restarts is a follow-up.

#### Scenario: First run enumerates the whole library
- **WHEN** `process()` runs with no persisted change token
- **THEN** the extension enumerates the full library and records the current change token as the baseline cursor

#### Scenario: Token expiry re-enumerates harmlessly
- **WHEN** `fetchPersistentChanges(since:)` reports `persistentChangeTokenExpired`
- **THEN** the extension re-enumerates the whole library and the ledger answers `AlreadyUploaded` for keys already recorded, so no duplicate jobs are created

### Requirement: Resource identity and fan-out

For each discovered asset the extension SHALL resolve its `PHCloudIdentifier` (batch-resolved once per cycle) and fan the asset out to its `PHAssetResource`s, wrapping each as an engine `Resource` with `filename = "<cloudId>-<kind>.<ext>"`, `version = the asset's modificationDate`, and the `PHAssetResource` as opaque `data`. Assets whose cloud identifier is unresolved (`identifierNotFound`) SHALL be **skipped this cycle** and SHALL NOT be keyed by a fallback identifier; the routine full re-enumeration on token expiry retries them once their cloud id resolves (a persisted deferred set for prompter retry is a follow-up, out of scope here).

#### Scenario: Each resource becomes a distinct key
- **WHEN** an asset with cloud identifier `X` has multiple resources (e.g. original photo and edited render)
- **THEN** each resource is wrapped as a `Resource` whose `filename` is `X-<kind>.<ext>`, yielding distinct ledger keys

#### Scenario: Unresolved cloud identifier is skipped
- **WHEN** an asset's `PHCloudIdentifier` resolves to `identifierNotFound`
- **THEN** the asset is skipped — no job or ledger entry is created, and no per-device fallback key is used

### Requirement: Engine-gated dummy job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with `ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build the destination request from a `DummyUploadRequestProvider` (a non-uploading provider that mints and logs a dummy destination such as `https://dummy.invalid/<encoded key>`), create a system upload job via `creationRequestForJob(destination:resource:)`, and the engine SHALL `recordRequested`. On `AlreadyUploaded` it SHALL create no job and write nothing. The extension SHALL never record `COMPLETED` in this capability (no real upload occurs).

#### Scenario: New resource emits a dummy destination and records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a dummy destination is minted and logged, a system upload job is created with it, and the ledger records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource
- **THEN** no system job is created and the ledger is not written

### Requirement: Drain-all job disposition

The extension SHALL acknowledge every system upload job it is handed regardless of the system's upload outcome (the dummy destinations cannot succeed), so the queue drains and `process()` returns a terminal result. It SHALL NOT perform retry/backoff adjudication or completion recording in this capability. A re-handed job whose key is already recorded SHALL be acknowledged as an idempotent no-op (no new destination, no ledger write).

#### Scenario: Jobs are acknowledged to drain
- **WHEN** `process()` fetches existing system upload jobs
- **THEN** each is acknowledged so the queue drains, without recording completion or issuing a real retry

#### Scenario: Already-recorded re-handed job is a no-op
- **WHEN** a re-handed job maps to a key the ledger already holds
- **THEN** the job is acknowledged and no dummy destination is emitted and nothing is written

### Requirement: Extension owns the single ledger writer

The extension process SHALL be the single holder of the `LedgerWriter` over the App-Group ledger. The host app SHALL NOT construct a `LedgerWriter`. This preserves the engine's single-writer invariant across the two processes.

#### Scenario: Only the extension writes
- **WHEN** the app and extension are both assembled
- **THEN** the extension constructs the `LedgerWriter` and the app constructs only `LedgerReader`/`LedgerWatcher`

### Requirement: iOS 26.1 deployment deviation

The extension SHALL target iOS 26.1 and use the deprecated `PHBackgroundResourceUploadExtension` protocol (the only one runnable on current GM devices), accepting deprecation in exchange for on-device verification now. Because all logic is Kotlin, a later migration to the iOS 27 `PHBackgroundResourceUploadJobExtension` async API SHALL be confined to the Swift shell and the deployment target.

#### Scenario: Deviation is contained to the shell
- **WHEN** the project later migrates to the iOS 27 async extension protocol
- **THEN** only the Swift principal class and the deployment target change, and the Kotlin discovery/engine/ledger core is unaffected

