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

For each discovered asset the extension SHALL fan the asset out to its **original** `PHAssetResource`s
only, mapping each to a generic role and wrapping it as an engine `Resource` with
`filename = "<assetId>-<role>.<ext>"`, where `assetId` is the PHAsset's `localIdentifier` with `/`
replaced by `_`, and `role` is `primary` for the original `photo`/`video`/`audio` resource and
`motion` for the original `pairedVideo` (Live Photo) resource. The extension SHALL NOT upload edit
artifacts — `fullSizePhoto`/`fullSizeVideo`/`fullSizePairedVideo` renders, `adjustmentData`,
`adjustmentBasePhoto`/`adjustmentBasePairedVideo`/`adjustmentBaseVideo`, the RAW `alternatePhoto`, or
proxies — so an asset's resource set is fixed at capture and never grows. Each wrapped `Resource`
carries the `PHAssetResource` as opaque `data` and **empty metadata** (the bunny native Storage API
has no custom-metadata channel) and no content version (an uploaded resource is immutable). v1 is a
**single-device, one-way backup**, so the per-device `localIdentifier` is the asset identity: it
requires **no iCloud account** and is always available. The `/`→`_` substitution keeps the filename a
single slash-free segment, so the edge endpoint — which percent-decodes the `file/<…>` path param and
**rejects any decoded `/`** — accepts it and composes a flat storage key. The extension SHALL NOT
resolve `PHCloudIdentifier` and SHALL NOT skip any asset for an unresolved cloud id.

#### Scenario: Each original resource becomes a distinct role key

- **WHEN** a Live Photo with localIdentifier `L` is discovered (original still plus original paired video)
- **THEN** two `Resource`s are wrapped with filenames `L-primary.<ext>` and `L-motion.<ext>`, yielding
  distinct ledger keys

#### Scenario: Edit artifacts are excluded

- **WHEN** a discovered asset has been edited (it exposes a full-size render and adjustment data alongside its original)
- **THEN** only its original resource(s) are wrapped; the render and adjustment data are not wrapped and never uploaded

#### Scenario: No iCloud account required

- **WHEN** the device has no iCloud account (no asset has a resolvable cloud identifier)
- **THEN** assets are still discovered and keyed by their `localIdentifier`, and uploads proceed — none are skipped for a missing cloud id

### Requirement: Per-asset manifest generation and side-channel upload

The extension SHALL maintain a durable, device-global **accumulator** in the shared App-Group store
of per-asset manifest entries (per `device-manifest`: per asset its `assetId`, `creationDate`, and
per resource its `role`, `contentType`, `filename`, and `originalFilename`). It SHALL write or update
an asset's accumulator entry on **every discovery** of that asset — **even when the engine answers
`AlreadyUploaded`** — so the accumulator is a rebuildable cache reflecting every discovered-not-deleted
asset, not a source of truth. The accumulator MUST NOT be driven through the `SyncEngine`, the
`createJob` path, or the ledger.

On each cycle the extension SHALL **project** the accumulator to the current event's `device.json`
(filtering to assets whose capture date meets the event's cutoff; under the current whole-library
scope the projection is the identity) and **PUT it synchronously, in-cycle**, to
`<host>/event/<eventId>/device/<deviceId>` with `Content-Type: application/json` — **not** over a
background `URLSession`, and **not** via the engine or ledger. The extension SHALL be the **sole
writer** of `device.json`; each write is a complete, self-contained full-state snapshot (no
read-modify-write). It MAY skip the PUT when the projection is unchanged from the last write. A
process kill mid-PUT is benign — `device.json` is write-only in v1 and the next cycle re-projects and
re-PUTs, so the loss is caught and converges. The previous `PENDING`/`DONE` manifest markers and the
app's `handleEventsForBackgroundURLSession` manifest wiring are **removed**; the app reads no manifest
state.

#### Scenario: Accumulator entry is written on every discovery, including AlreadyUploaded

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** the extension still writes/updates `A`'s entry in the device-global accumulator (no job is
  created and the ledger is not written)

#### Scenario: Each cycle projects the accumulator and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the accumulator to the current event's `device.json` and PUTs it
  synchronously, in-cycle, to `<host>/event/<eventId>/device/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine/ledger involvement

#### Scenario: Unchanged projection skips the PUT

- **WHEN** a cycle's projection is byte-identical to the last written `device.json`
- **THEN** the extension MAY skip the PUT for that cycle

#### Scenario: A kill mid-PUT is caught next cycle

- **WHEN** the extension process is killed while the synchronous `device.json` PUT is in flight
- **THEN** the partial write is discarded and the next cycle re-projects the accumulator and re-PUTs
  `device.json`, converging without any cross-process marker

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
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built,
**event-independent** edge URL `<host>/files/device/<deviceId>/<filename>`, no signing), create a
system upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(job)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery reads the destination URL's **last path segment**, which is the
unchanged `filename` under the new `/files/device/<deviceId>/` prefix. On `AlreadyUploaded` it SHALL
create no job and write nothing. Completion and failure outcomes are reduced into the ledger by the
drain (see "Completion and retry adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real device-partitioned edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally at `<host>/files/device/<deviceId>/<filename>`,
  a system upload job is created with it, and only after the create succeeds does the extension report
  `UploadStarted`, which records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED`)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfigPayload` (`eventId`) read from the **shared Keychain** via the
`:capability:config` Keychain store; the stable per-install `deviceId` read from the **shared
Keychain** (per `device-identity`); and the compile-time edge **host** read from the extension
bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). The `deviceId` SHALL be used to build
the event-independent byte URLs (`<host>/files/device/<deviceId>/<filename>`) and as the `device.json`
key. The extension SHALL re-read the Keychain payload **freshly at the start of every `process()`
cycle** — it MUST NOT cache a value read once at process construction. The extension process outlives
a single invocation, and an event (re)joined by the **app** process writes the Keychain but does not
notify the extension's in-memory config; a cached value would make a long-lived extension keep
uploading to a stale, previously-joined event even after the app shows the new one as joined. The
shared store therefore exposes a refresh (`reload()`) the extension calls before each read. When the
Keychain payload is **absent** (the extension woke before the user joined an event), the extension
SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never
crashing.

#### Scenario: Config present — provider built from host, eventId, and deviceId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase`,
  `eventId` from the payload, and `deviceId` from the shared Keychain, so byte URLs target
  `<host>/files/device/<deviceId>/<filename>` and `device.json` is keyed by that `deviceId`

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle
- **WHEN** the extension process has already run a cycle for one event, the app then joins a different event (writing the new `eventId` to the shared Keychain), and the same extension process runs its next `process()` cycle
- **THEN** the extension re-reads the Keychain, builds `EdgeUploadRequestProvider` for the **newly-joined** `eventId` (the `deviceId` is stable across the switch), and uploads to the new event — it does not keep uploading to the event it read at process construction

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

On a **valid `snapsync://` config (re)scan**, the host app SHALL re-provision the (possibly new)
event. The extension SHALL be re-registered (the disable→enable toggle). On its next cycle the
extension reconciles against the per-device file listing (`GET /files/device/<deviceId>`, see
`event-rejoin-reconciliation`): it **`resetTo`s** (atomic clear-and-seed) the ledger to one
already-uploaded row per stored file and **clears the discovery cursor** (forcing a full
re-enumeration). The device-global listing re-seeds the same files as already-uploaded, so **nothing
already stored re-uploads**, while the clear drops stale/phantom rows and the cursor clear
re-enumerates to find genuinely-unstored work. The device-global accumulator is **kept** and the
extension **re-projects** it to the **new** event's `device.json` path, then sets the joined-event
marker. The app decodes the deeplink only to gate this on a valid payload; the authoritative
decode/validate/persist still happens in the shared container intent.

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `snapsync://` config URL is opened for a different event
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing, clears the discovery cursor, keeps the accumulator, and
  re-projects `device.json` to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present under
  `/files/device/<deviceId>/…`
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid deeplink does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger, cursor, accumulator, and joined-event marker are untouched)

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL prune ledger rows **and** accumulator entries for assets removed from the library,
via two paths driven from its discovery cycle (the ledger writes preserve the single-writer
invariant). This keeps the ledger honest about what still exists on device and, critically, removes a
row left non-`COMPLETED` by an asset deleted mid-upload — which would otherwise keep `pending > 0`
forever and hold the extension in the perpetual `processing` re-invocation loop (see "Cap-aware
creation and tri-state processing result"). On deletion the extension SHALL **also prune the asset's
device-global accumulator entry**, so the next projected `device.json` stops listing that asset (the
basis for a future deletion-correct restore). No remote object is deleted; the one-way model is
unchanged.

- **Incremental (every cycle):** when deriving the changed set from
  `fetchPersistentChanges(since:)`, the extension SHALL also collect each change record's
  `deletedLocalIdentifiers()` and, for each removed `localIdentifier` `L` (normalized `/`→`_` to
  match the stored `assetId`), call `deleteByAssetId(L)` so all of that asset's resource rows are
  removed, and remove `L`'s accumulator entry.
- **Reconcile (backstop):** on a full enumeration that completes with no `PHPhotosErrorLimitExceeded`,
  the extension SHALL call `retainAssets(liveAssetIds)`, where `liveAssetIds` is the set of
  `assetId`s of the resources it built during enumeration — pruning ledger rows and accumulator
  entries for assets no longer present, closing the gap for deletions that occurred while the
  persistent-change token was expired.

A re-added asset (e.g. recovered from "Recently Deleted") whose rows were pruned SHALL be treated
as new work: discovery finds no ledger entry, so the engine returns `Upload` and a fresh
(idempotent) job is created, and its accumulator entry is re-written. No `DELETED` state is
introduced and the upload decision is unchanged.

#### Scenario: Removed asset's rows and accumulator entry are pruned incrementally
- **WHEN** `fetchPersistentChanges(since:)` reports `deletedLocalIdentifiers` containing asset `L`,
  and the ledger holds rows for `L`'s resources
- **THEN** the extension calls `deleteByAssetId(L)` and removes `L`'s accumulator entry, so `L` no
  longer contributes to `pending`/`completed` and the next projected `device.json` omits it

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a non-`COMPLETED` ledger row, and a
  later cycle's change feed reports that asset as removed
- **THEN** the extension prunes the row (and its accumulator entry), the ledger reaches no pending rows, and `process()` can
  return `completed` instead of looping on `processing`

#### Scenario: Full enumeration reconciles against the live library
- **WHEN** a full enumeration completes with no `limitExceeded` and the ledger holds rows for an
  asset that is no longer present in the library
- **THEN** the extension calls `retainAssets(liveAssetIds)` and the absent asset's ledger rows and accumulator entry are removed

#### Scenario: Reconcile is skipped on a cap-truncated cycle
- **WHEN** a full enumeration stops early because job creation raised `limitExceeded`
- **THEN** the extension SHALL NOT call `retainAssets`, so the un-enumerated tail's rows are not
  wrongly pruned (reconcile runs only when enumeration completed fully — the same gate that
  advances the change token)

#### Scenario: Re-added asset re-uploads after pruning
- **WHEN** an asset whose rows were pruned reappears in the library (e.g. recovered from
  "Recently Deleted")
- **THEN** discovery finds no ledger entry for its resources, the engine returns `Upload`, a fresh
  job is created (the idempotent PUT targets the unchanged key), and its accumulator entry is re-written

### Requirement: Disabling the extension clears orphaned REQUESTED rows

Disabling the upload extension (`setUploadJobExtensionEnabled(false)`) deletes the system's
`AssetResourceUploadJobConfiguration` and therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension it SHALL, immediately after the disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to **both** disable paths: the
disable half of the `disable→enable` re-register, and the leave use-case's extension-disable.

Without `clearRequested()`, the rows stay `REQUESTED` forever: the engine treats `REQUESTED` as
in-flight and never re-issues it, there is no API to enumerate live jobs to detect that the job is
gone, and a same-event cycle never reconciles — so the photos that were mid-upload at the disable are
permanently abandoned. With both clears, the next full enumeration re-discovers the cleared keys and
re-creates exactly the not-yet-stored jobs (stored files remain `COMPLETED` and are skipped). The app
SHALL route both disable paths through a single helper so they cannot diverge, and SHALL use the
`LedgerBackend` directly (constructing no `LedgerWriter`), since `clearRequested` is an app-side
reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `clearRequested()` drops the `REQUESTED` rows, and the
  discovery cursor is reset — so the next cycle's full re-enumeration re-discovers and re-creates the
  not-yet-stored jobs (bytes resume landing), with no permanently-stuck `REQUESTED`

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes

