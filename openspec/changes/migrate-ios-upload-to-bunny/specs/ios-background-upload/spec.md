## MODIFIED Requirements

### Requirement: Background upload extension target

The system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The extension's logic SHALL live in a lean Kotlin Multiplatform module (`:app:ios:photokit-extension`) that depends on `:domain:engine`, `:capability:upload-url` (the real `EdgeUploadRequestProvider`), and `:capability:config` (the Keychain-backed `ConfigSource`) — no Compose/UI — packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits), and SHALL include an App Transport Security `NSAllowsLocalNetworking` exception so a plaintext `PUT` to a private/local host (a local Deno backend on the LAN) is permitted (the public HTTPS edge endpoint is unaffected).

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, and `NSAllowsLocalNetworking`, and it links the `:app:ios:photokit-extension` framework

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

### Requirement: Resource identity and fan-out

For each discovered asset the extension SHALL fan the asset out to its `PHAssetResource`s, wrapping each as an engine `Resource` with `filename = "<localId>-<kind>.<ext>"` (the PHAsset's `localIdentifier` with `/` replaced by `_`), `version = the asset's modificationDate`, the `PHAssetResource` as opaque `data`, and **empty metadata** (the bunny native Storage API has no custom-metadata channel). v1 is a **single-device, one-way backup**, so the per-device `localIdentifier` is the resource identity: it requires **no iCloud account** and is always available. The `/`→`_` substitution keeps the filename a single slash-free segment, so the edge endpoint — which percent-decodes the `file/<…>` path param and **rejects any decoded `/`** — accepts it and composes a flat storage key. The extension SHALL NOT resolve `PHCloudIdentifier` and SHALL NOT skip any asset for an unresolved cloud id.

#### Scenario: Each resource becomes a distinct key
- **WHEN** an asset with localIdentifier `L` has multiple resources (e.g. original photo and edited render)
- **THEN** each resource is wrapped as a `Resource` whose `filename` is `L-<kind>.<ext>`, yielding distinct ledger keys

#### Scenario: No iCloud account required
- **WHEN** the device has no iCloud account (no asset has a resolvable cloud identifier)
- **THEN** assets are still discovered and keyed by their `localIdentifier`, and uploads proceed — none are skipped for a missing cloud id

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build
the destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built
edge URL `<host>/event/<eventId>/device/<deviceId>/file/<filename>`, no signing), create a system
upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(job)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). On `AlreadyUploaded` it SHALL create no job and
write nothing. Completion and failure outcomes are reduced into the ledger by the drain (see
"Completion and retry adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally, a system upload job is created with it,
  and only after the create succeeds does the extension report `UploadStarted`, which records
  `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED` at the same version)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources: the runtime `EventConfigPayload` (`eventId`) read synchronously from the **shared Keychain** via the `:capability:config` `ConfigSource`; the compile-time edge **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary); and the **`deviceId`** obtained from the App-Group device-id store (see "Extension supplies an App-Group-persisted device id"). When the Keychain payload is **absent** (the extension woke before the user joined an event), or the `deviceId` cannot be obtained, the extension SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never crashing.

#### Scenario: Config present — provider built from host + eventId + deviceId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain and a device id available
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase`, `eventId` from the payload, and the persisted `deviceId`

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

#### Scenario: Device id unavailable — cycle skipped cleanly
- **WHEN** `process()` runs but the device id cannot be obtained
- **THEN** the extension logs and returns a terminal success, creating no upload job and writing nothing

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first), and reduce each outcome into the engine. It
SHALL recover a returned `PHAssetResourceUploadJob`'s ledger key from the job's **destination URL**
(the last path segment) — the only field reliably present for every job state, since `resource` is
**nil for succeeded jobs** (the system releases it after upload). Version/attempt come from the
ledger (`LedgerReader`); the `resource`, when still present, is reused only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose key is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `FAILED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED` at the incremented attempt).
- **`fetchJobsWithAction(.acknowledge)` (terminal):** `state == Succeeded` → `UploadCompleted`
  (records `COMPLETED`) then `acknowledge`; already-`COMPLETED` in the ledger → `acknowledge`
  (idempotent no-op); otherwise (a retry-spent `Failed`/`Cancelled` job) → `UploadFailed` (records
  `FAILED`) and, **if `resource` is still available**, create a fresh job with
  `creationRequestForJob(rebuiltURL, job.resource)` then `UploadStarted`. The job SHALL be
  acknowledged **regardless of the re-create outcome** (on the cap, acknowledge and let rediscovery
  retry the key — never leave a presented job un-acknowledged). Retry has no attempt budget (retry
  forever).

#### Scenario: Succeeded job records COMPLETED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension reads its key from the job's destination URL, reports `UploadCompleted`
  (the ledger becomes `COMPLETED`), and acknowledges the job

#### Scenario: First failure retries with a rebuilt URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a locally rebuilt edge
  destination (byte-identical to the original — no expiry), calls `retryWithDestination(:)`, and
  reports `UploadStarted` so the ledger holds `REQUESTED` at the incremented attempt

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension reports `UploadFailed`, creates a fresh job using `job.resource`, reports
  `UploadStarted`, and acknowledges the original

#### Scenario: Every presented job is acknowledged
- **WHEN** a returned job's key cannot be recovered, or its re-create hits the cap, or its resource
  is unavailable
- **THEN** the job is still acknowledged (no `COMPLETED`/`UploadStarted` recorded), so the system
  never reports error 50008

#### Scenario: Already-completed re-handed job is a no-op
- **WHEN** a returned job maps to a key the ledger already holds as `COMPLETED`
- **THEN** the job is acknowledged and nothing is written or re-created

## ADDED Requirements

### Requirement: Extension supplies an App-Group-persisted device id

The extension SHALL obtain a stable `deviceId` — a **canonical UUID** that scopes this device's
uploads within an event (the `<deviceId>` path segment) — from a shared App-Group store, **lazily
minting** it on first need: read the device id from App-Group `NSUserDefaults` (suite
`group.app.snapsync`); if absent, generate a new UUID with Foundation (`NSUUID`, **not** UIKit /
`identifierForVendor`, so it is available even in a background-launched, locked-device extension and
is never `nil`), persist it, and reuse it thereafter. The minted id SHALL be lowercase canonical
UUID form accepted by the edge endpoint's validator. It is stable for the install and rotates only
when the App Group is wiped (uninstall) — accepted; a re-provision does not change it. The
mint/load orchestration over an opaque store port SHALL be platform-free (`commonMain`) so it is
exercised on the simulator with a fake; the `NSUserDefaults` access is untested iosMain wiring.

#### Scenario: First need mints and persists a device id
- **WHEN** the extension needs a `deviceId` and none is stored in the App Group
- **THEN** it generates a canonical UUID with `NSUUID`, persists it to App-Group `NSUserDefaults`,
  and uses it — without any UIKit/`identifierForVendor` call

#### Scenario: Subsequent cycles reuse the same device id
- **WHEN** the extension needs a `deviceId` and one is already stored
- **THEN** it reads and reuses the stored value (the same `<deviceId>` path segment across cycles
  and across process restarts)

#### Scenario: Re-provision preserves the device id
- **WHEN** a valid `snapsync://` config rescan re-provisions sync
- **THEN** the stored device id is left unchanged, so this device keeps the same `<deviceId>` folder
  under the (possibly new) event
