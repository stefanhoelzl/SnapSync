# ios-background-upload Specification

## Purpose
TBD - created by archiving change ios-background-upload. Update Purpose after archive.
## Requirements
### Requirement: Background upload extension target

The system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The extension's logic SHALL live in a lean Kotlin Multiplatform module (`:app:ios:photokit-extension`) that depends on `:domain:engine`, `:capability:upload-url` (the real `EdgeUploadRequestProvider`), and `:capability:config` (the Keychain-backed `ConfigSource`) — no Compose/UI — packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:photokit-extension` framework, and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

### Requirement: In-extension discovery via persistent change token

On each `process()` invocation, the extension SHALL discover work itself (the system does not
enumerate). On first run (no token) it SHALL enumerate the whole library via `PHAsset.fetchAssets`
and capture `currentChangeToken` as baseline; in steady state it SHALL call
`fetchPersistentChanges(since:)` and derive the changed asset set. On `persistentChangeTokenExpired`
it SHALL re-enumerate the whole library, relying on the ledger to skip already-recorded keys. The
change token SHALL be **persisted across extension process death** in a shared App-Group store (an
archived `PHPersistentChangeToken`; see "Persisted change-token cursor"), so a short-lived wake
resumes incrementally instead of re-enumerating the whole library. The token SHALL be advanced
(persisted to `currentChangeToken`) **only at the end of a fully-drained cycle** — a cycle in which
every discovered resource was turned into a job with no `limitExceeded`. On a cap-truncated cycle the
token SHALL NOT advance, so the next wake re-derives the same change set (the engine's
`REQUESTED`-skip prevents duplicate jobs for work already created).

#### Scenario: First run enumerates the whole library
- **WHEN** `process()` runs with no persisted change token
- **THEN** the extension enumerates the full library and records the current change token as the
  baseline cursor in the App-Group store

#### Scenario: Cursor survives a process restart
- **WHEN** the extension process is torn down after a fully-drained cycle and later re-invoked
- **THEN** it loads the persisted token and calls `fetchPersistentChanges(since:)` from it, rather
  than re-enumerating the whole library

#### Scenario: Token does not advance on a cap-truncated cycle
- **WHEN** a cycle stops early because `creationRequestForJob` raised `PHPhotosErrorLimitExceeded`
- **THEN** the persisted token is left unchanged, so the next wake re-derives the same change set and
  the engine skips the resources whose jobs already exist (`REQUESTED`)

#### Scenario: Token expiry re-enumerates harmlessly
- **WHEN** `fetchPersistentChanges(since:)` reports `persistentChangeTokenExpired`
- **THEN** the extension re-enumerates the whole library and the ledger answers `AlreadyUploaded` for
  keys already recorded, so no duplicate jobs are created

### Requirement: Resource identity and fan-out

For each discovered asset the extension SHALL fan the asset out to its `PHAssetResource`s, wrapping each as an engine `Resource` with `filename = "<localId>-<kind>.<ext>"` (the PHAsset's `localIdentifier` with `/` replaced by `_`), `version = the asset's modificationDate`, the `PHAssetResource` as opaque `data`, and **empty metadata** (the bunny native Storage API has no custom-metadata channel). v1 is a **single-device, one-way backup**, so the per-device `localIdentifier` is the resource identity: it requires **no iCloud account** and is always available. The `/`→`_` substitution keeps the filename a single slash-free segment, so the edge endpoint — which percent-decodes the `file/<…>` path param and **rejects any decoded `/`** — accepts it and composes a flat storage key. The extension SHALL NOT resolve `PHCloudIdentifier` and SHALL NOT skip any asset for an unresolved cloud id.

#### Scenario: Each resource becomes a distinct key
- **WHEN** an asset with localIdentifier `L` has multiple resources (e.g. original photo and edited render)
- **THEN** each resource is wrapped as a `Resource` whose `filename` is `L-<kind>.<ext>`, yielding distinct ledger keys

#### Scenario: No iCloud account required
- **WHEN** the device has no iCloud account (no asset has a resolvable cloud identifier)
- **THEN** assets are still discovered and keyed by their `localIdentifier`, and uploads proceed — none are skipped for a missing cloud id

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

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build
the destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built
edge URL `<host>/event/<eventId>/file/<filename>`, no signing), create a system
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

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from two sources: the runtime `EventConfigPayload` (`eventId`) read synchronously from the **shared Keychain** via the `:capability:config` `ConfigSource`; and the compile-time edge **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). When the Keychain payload is **absent** (the extension woke before the user joined an event), the extension SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never crashing.

#### Scenario: Config present — provider built from host + eventId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase` and `eventId` from the payload

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

### Requirement: Extension registration is a disable→enable toggle

On a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension.

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

### Requirement: Device-visible (un-redacted) logging

Both the app and the extension SHALL route Kermit through a log writer whose messages appear **un-redacted** in the device unified log / `idevicesyslog` (the default os_log path redacts dynamic content as `<private>`), so on-device discovery/decision/upload logs are readable without a Mac.

#### Scenario: Log content is readable on device
- **WHEN** the extension logs a message containing dynamic content (e.g. a key or URL) on device
- **THEN** the message text appears verbatim in `idevicesyslog`, not as `<private>`

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

### Requirement: Cap-aware creation and tri-state processing result

When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle, leave the change token un-advanced, and surface a **processing**
result so the system re-invokes it promptly; on the next wake, re-derivation plus the engine's
`REQUESTED`-skip resumes exactly the un-created remainder with no duplicate jobs and no persisted
residue list.

Because the OS invokes the extension lazily (on library changes, not when an upload quietly
finishes), a drained cycle that reported `completed` would leave already-succeeded jobs
un-acknowledged until the next change. Therefore, whenever the cycle would otherwise complete but the
ledger still has **pending** (in-flight) rows, the extension SHALL instead surface **processing** to
request another invocation so those completions are recorded promptly; it reports `completed` only
once the ledger has no pending rows (everything backed up), letting the system rest. (The OS
throttles re-invocation, so this polls at its cadence rather than looping.)

The Kotlin `process()` SHALL return a tri-state result (`completed` / `processing` / `failure`) that
the Swift principal class maps to `PHBackgroundResourceUploadProcessingResult` (`.completed` /
`.processing` / `.failure`); if the iOS 26.1 SDK lacks a `.processing` case the Swift shell SHALL
fall back to `.completed` (correctness is unaffected — the un-advanced token / pending rows are
drained on the next system-scheduled wake; only promptness is lost).

#### Scenario: Cap during discovery yields a processing result
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** the extension stops creating jobs, does not advance the token, and `process()` returns a
  processing result (mapped to `.processing`, or `.completed` if unavailable)

#### Scenario: Pending in-flight work requests re-invocation
- **WHEN** a cycle drains and creates with no cap, but the ledger still has pending (in-flight) rows
- **THEN** `process()` returns a processing result so the system re-invokes the extension to record
  their completions, rather than resting until the next library change

#### Scenario: Fully backed up reports completion
- **WHEN** a cycle ends with no pending rows in the ledger
- **THEN** `process()` returns `completed` and the system rests

#### Scenario: Re-entry resumes the remainder without duplicates
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the same change set is re-derived, the already-created jobs are skipped (`REQUESTED`), and
  only the previously un-created resources get new jobs

### Requirement: Persisted change-token cursor

The discovery cursor SHALL be persisted in the shared App-Group store written by the extension. The
extension SHALL archive the `PHPersistentChangeToken` (via its `NSSecureCoding` support) to `Data`
and store it in App-Group `NSUserDefaults` (suite `group.app.snapsync`), reading it back at cycle
start. The cursor's load/advance orchestration SHALL be platform-free (a `commonMain` port over
opaque token bytes) so it is exercised on the simulator with a fake; the `NSUserDefaults` archiving
is untested iosMain wiring. Persistence is an efficiency optimization only: a cold start with no
stored token re-enumerates the whole library, which the ledger makes harmless.

#### Scenario: Token round-trips through the App-Group store
- **WHEN** the extension advances the cursor at the end of a fully-drained cycle
- **THEN** the archived token bytes are written to App-Group `NSUserDefaults` and a subsequent
  process reads them back and resumes `fetchPersistentChanges(since:)` from that token

#### Scenario: Missing token falls back to full enumeration
- **WHEN** `process()` runs with no token in the App-Group store
- **THEN** the extension enumerates the whole library and the ledger skips already-recorded keys

### Requirement: Re-provision resets sync state

On a **valid `snapsync://` config (re)scan**, the host app SHALL re-provision: clear the ledger
(`LedgerBackend.clear()`), clear the persisted discovery cursor (remove the App-Group
`NSUserDefaults` token under the shared key), and re-register the extension (the disable→enable
toggle) — so the (possibly new) config re-uploads the whole library from scratch. The app decodes
the deeplink only to gate this on a valid payload; the authoritative decode/validate/persist still
happens in the shared container intent. Resetting an already-empty ledger on the first scan is a
harmless no-op. The discovery-cursor suite/key are shared constants (`LEDGER_APP_GROUP` /
`DISCOVERY_TOKEN_KEY`) so the app's reset and the extension's writer cannot drift.

Note: clearing the ledger is the one sanctioned app-side ledger write (a deliberate reset, not a
sync write); the engine remains the only writer of `REQUESTED`/`FAILED`/`COMPLETED`. Re-upload after
a reset begins on the next OS extension invocation (a library change reliably triggers one; the OS
owns scheduling).

#### Scenario: Valid re-scan clears and re-registers
- **WHEN** a valid `snapsync://` config URL is opened
- **THEN** the ledger is cleared, the discovery cursor is removed, and the extension is
  re-registered (disable→enable), so the next cycle re-enumerates and re-uploads the whole library

#### Scenario: Invalid deeplink does not reset
- **WHEN** an opened URL fails config decoding
- **THEN** no reset occurs (the ledger and cursor are untouched)

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL prune ledger rows for assets removed from the library, via two paths driven
from its discovery cycle (both as `LedgerWriter` writes, preserving the single-writer invariant).
This keeps the ledger honest about what still exists on device and, critically, removes a row
left non-`COMPLETED` by an asset deleted mid-upload — which would otherwise keep `pending > 0`
forever and hold the extension in the perpetual `processing` re-invocation loop (see
"Cap-aware creation and tri-state processing result"). No S3 object is deleted; the one-way model
is unchanged.

- **Incremental (every cycle):** when deriving the changed set from
  `fetchPersistentChanges(since:)`, the extension SHALL also collect each change record's
  `deletedLocalIdentifiers()` and, for each removed `localIdentifier` `L` (normalized `/`→`_` to
  match the stored `assetId`), call `deleteByAssetId(L)` so all of that asset's resource rows are
  removed.
- **Reconcile (backstop):** on a full enumeration that completes with no `PHPhotosErrorLimitExceeded`,
  the extension SHALL call `retainAssets(liveAssetIds)`, where `liveAssetIds` is the set of
  `assetId`s of the resources it built during enumeration — pruning rows for assets no longer
  present, closing the gap for deletions that occurred while the persistent-change token was expired.

A re-added asset (e.g. recovered from "Recently Deleted") whose rows were pruned SHALL be treated
as new work: discovery finds no ledger entry, so the engine returns `Upload` and a fresh
(idempotent) job is created. No `DELETED` state is introduced and the upload decision is unchanged.

#### Scenario: Removed asset's rows are pruned incrementally
- **WHEN** `fetchPersistentChanges(since:)` reports `deletedLocalIdentifiers` containing asset `L`,
  and the ledger holds rows for `L`'s resources
- **THEN** the extension calls `deleteByAssetId(L)` and those rows are removed, so `L` no
  longer contributes to `pending`/`completed`

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a non-`COMPLETED` ledger row, and a
  later cycle's change feed reports that asset as removed
- **THEN** the extension prunes the row, the ledger reaches no pending rows, and `process()` can
  return `completed` instead of looping on `processing`

#### Scenario: Full enumeration reconciles against the live library
- **WHEN** a full enumeration completes with no `limitExceeded` and the ledger holds rows for an
  asset that is no longer present in the library
- **THEN** the extension calls `retainAssets(liveAssetIds)` and the absent asset's rows are removed

#### Scenario: Reconcile is skipped on a cap-truncated cycle
- **WHEN** a full enumeration stops early because job creation raised `limitExceeded`
- **THEN** the extension SHALL NOT call `retainAssets`, so the un-enumerated tail's rows are not
  wrongly pruned (reconcile runs only when enumeration completed fully — the same gate that
  advances the change token)

#### Scenario: Re-added asset re-uploads after pruning
- **WHEN** an asset whose rows were pruned reappears in the library (e.g. recovered from
  "Recently Deleted")
- **THEN** discovery finds no ledger entry for its resources, the engine returns `Upload`, and a
  fresh job is created (the idempotent PUT targets the unchanged key)

### Requirement: App reads succeeded upload jobs (read-only observation)

The `:app:ios` module SHALL provide the iOS `ObservedCompletionsSource` by reading the system's upload
jobs from the **app** process via `PHAssetResourceUploadJob.fetchJobsWithAction(.acknowledge)`,
keeping the jobs whose state is `succeeded`, and mapping each to its ledger key via the destination
request URL's last path segment (the same key mapping the extension uses; the only field reliably
present for every job state). This read SHALL be **strictly read-only**: it SHALL NOT call
`acknowledge`, `retry`, or any change request, so it never consumes a job the extension must still
acknowledge — the extension remains the single ledger writer. The read SHALL be guarded by the same
iOS-version check as the extension registration, returning the empty set where the background-upload
API is unavailable. As a device-only PhotoKit binding it lives in the untested `:app:ios` shell; the
key mapping it relies on is exercised by the extension's existing logic.

#### Scenario: Succeeded jobs map to observed keys

- **WHEN** the app process refreshes the source and the system holds two `succeeded`, unacknowledged
  jobs
- **THEN** the source's set contains exactly those two jobs' keys (each the destination URL's last
  path segment), and no `acknowledge`/`retry` is performed

#### Scenario: Unavailable API yields the empty set

- **WHEN** the background-upload API is unavailable on the running OS
- **THEN** the source yields the empty set and performs no PhotoKit job call

### Requirement: Extension posts the cross-process ledger ding once per cycle

The extension SHALL post the cross-process ledger notification **once**, after its `process()` cycle
completes, rather than per `put`. The App-Group backend SHALL NOT post the Darwin notification on each
`put`. A cycle that performs no `put` MAY still post (a redundant ding is harmless); a crash before
the post defers the app's update to its next trigger (foreground re-read or poll), which is safe
because the ledger is durable and dings are level-triggered.

#### Scenario: One ding per cycle regardless of write count

- **WHEN** a `process()` cycle records several rows and then returns
- **THEN** the app process receives a single cross-process ding for that cycle, not one per recorded
  row

