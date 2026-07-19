# ios-photokit-upload Specification

## Purpose

The **OS-driven upload tier** for iOS ≥26.1: a PhotoKit background-upload app extension that the system
invokes on its own cadence, discovers newly-qualifying photos, drives the shared upload cycle, and lets the
OS perform the uploads — power- and network-aware, across suspension and lock. It exists because photos
must reach the event without the user ever opening the app, and only the OS can schedule that.

The extension is the **sole `LedgerWriter`** on this tier; the app reads the ledger read-only. The
platform-agnostic orchestration deliberately lives in `:capability:upload` (which declares a `jvm()` target
so the upload cycle is harness- and JVM-tested); what this capability covers is the iOS side of that seam —
the PhotoKit adapter, the thin Swift pass-through shell, discovery via the persistent change token, job
creation/retry/acknowledge disposition, the compile-time upload host, and the ATS constraint that the host
be HTTPS.

Uploads on iOS 18–26.0 are the app-driven tier instead — see `ios-url-session-upload`.

Decision record: `changes/archive/2026-06-19-ios-background-upload`.

The **Re-provision resets sync state** requirement was scoped explicitly to this tier in
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` (the disable→enable toggle is this tier's producer
`start()`, not universal host-app behavior).
## Requirements
### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the Keychain `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, and the generic HTTP adapters (`:adapter:generic`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **iOS platform adapters** (`IosPhotoKitUploadPlatform` — renamed from `IosBackgroundTransfer`) with the composition root (`UploadExtensionRoot`) and the compile-time host read (`uploadHostFromBundle`) SHALL live in a lean `:app:ios:extension` module (renamed from `:app:ios:photokit-extension` at migration step 13a) that **composes** `:domain` (which since migration step 8 also carries the upload receive seam in `feature/upload`; the former `:capability:upload` module is deleted), the extension-safe adapter module `:adapter:ios:ext-safe` (which, since migration step 4, carries the shared PhotoKit discovery — the `IosDiscovery` change-token walk + request builder + token archiver and the `IosDiscoveryStore` cursor store, shared with the `ios-url-session-upload` adapter, formerly `:app:ios:photokit-discovery` — plus the Keychain-backed `ConfigSource`, formerly `:capability:config`), and `:adapter:generic`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:extension` framework (which composes `:domain` and the adapter modules), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`BackgroundTransfer`/`UploadConfig` live in `:domain` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

#### Scenario: Extension adapters compose the capability
- **WHEN** the extension's composition root assembles a cycle
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscoveryStore`) implement the upload seams — sharing the `IosDiscovery` walk from `:adapter:ios:ext-safe` — and the root supplies them as `UploadPorts` to `uploadCore`, which constructs the `:domain` `feature/upload` `UploadCycle`, with the download-store / rejoin / manifest edges answered in the ports bundle rather than inside the feature

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same shared `:domain` orchestration assembled by the same `uploadCore`

#### Scenario: The extension's cycle is the shared composition
- **WHEN** `UploadExtensionRoot` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no cycle, gate, reconciler, or
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic`'s
  `HttpEnrollment`

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
`live` for the original `pairedVideo` (Live Photo) resource. The extension SHALL NOT upload edit
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
- **THEN** two `Resource`s are wrapped with filenames `L-primary.<ext>` and `L-live.<ext>`, yielding
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
per resource its `role`, `contentType`, `key` (the storage object name), and `filename` (the human
capture name)). It SHALL write or update an asset's accumulator entry on **every discovery** of that
asset — **even when the engine answers `AlreadyUploaded`** — so the accumulator is a rebuildable cache
reflecting every discovered-not-deleted asset, not a source of truth. The accumulator MUST NOT be
driven through the `SyncEngine`, the `createJob` path, or the ledger.

On each cycle the extension SHALL **project** the accumulator to the current event's `device.json`
(filtering to assets whose capture date meets the event's cutoff; under the current whole-library
scope the projection is the identity) and **PUT it synchronously, in-cycle**, to
`<host>/events/<eventId>/devices/<deviceId>` with `Content-Type: application/json` — **not** over a
background `URLSession`, and **not** via the engine or ledger. The extension SHALL be the **sole
writer** of `device.json`; each write is a complete, self-contained full-state snapshot (no
read-modify-write). It MAY skip the PUT when the projection is **byte-identical** to the last written
`device.json`. Because the resource field names (`key`, `filename`) are part of that snapshot content,
a build that changes them produces a projection that differs from any previously-stored snapshot, so
the first cycle on the new build re-PUTs `device.json` with the new names — no special one-shot flag
is needed. A process kill mid-PUT is benign — `device.json` is write-only in v1 and the next cycle
re-projects and re-PUTs, so the loss is caught and converges. The previous `PENDING`/`DONE` manifest
markers and the app's `handleEventsForBackgroundURLSession` manifest wiring are **removed**; the app
reads no manifest state.

#### Scenario: Accumulator entry is written on every discovery, including AlreadyUploaded

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** the extension still writes/updates `A`'s entry in the device-global accumulator (no job is
  created and the ledger is not written)

#### Scenario: Each cycle projects the accumulator and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the accumulator to the current event's `device.json` and PUTs it
  synchronously, in-cycle, to `<host>/events/<eventId>/devices/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine/ledger involvement, each
  resource carrying `key` (the storage object name) and `filename` (the human capture name)

#### Scenario: Unchanged projection skips the PUT

- **WHEN** a cycle's projection is byte-identical to the last written `device.json`
- **THEN** the extension MAY skip the PUT for that cycle

#### Scenario: Field-name change re-PUTs the manifest once

- **WHEN** the first cycle runs on a build that renamed the resource fields to `key`/`filename` and a
  previously-stored `device.json` uses the old field names
- **THEN** the projection is no longer byte-identical to the stored snapshot, so the extension re-PUTs
  `device.json` with the new field names (clean cutover, no separate backfill)

#### Scenario: A kill mid-PUT is caught next cycle

- **WHEN** the extension process is killed while the synchronous `device.json` PUT is in flight
- **THEN** the partial write is discarded and the next cycle re-projects the accumulator and re-PUTs
  `device.json`, converging without any cross-process marker

### Requirement: Extension owns the single ledger writer

**On iOS ≥26.1** (the two-process PhotoKit tier) the extension process SHALL be the single holder of the `LedgerWriter` over the App-Group ledger, and the host app SHALL NOT construct a `LedgerWriter`. This binds the ledger's single-record-writer invariant (see `sync-ledger`) to the extension process on this tier. On iOS 18–26.0 there is no extension process and the **app** holds the writer (see `ios-url-session-upload`); the invariant is preserved on both tiers, only its process binding differs.

#### Scenario: Only the extension writes on ≥26.1
- **WHEN** the app and extension are both assembled on iOS ≥26.1
- **THEN** the extension constructs the `LedgerWriter` and the app constructs none — it reads the ledger only through `LedgerStore`'s read and reset-family operations

### Requirement: iOS 26.1 deployment deviation

The extension SHALL target iOS 26.1 and use the deprecated `PHBackgroundResourceUploadExtension` protocol (the only one runnable on current GM devices), accepting deprecation in exchange for on-device verification now. Because all logic is Kotlin, a later migration to the iOS 27 `PHBackgroundResourceUploadJobExtension` async API SHALL be confined to the Swift shell and the deployment target.

#### Scenario: Deviation is contained to the shell
- **WHEN** the project later migrates to the iOS 27 async extension protocol
- **THEN** only the Swift principal class and the deployment target change, and the Kotlin discovery/engine/ledger core is unaffected

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built,
**event-independent** edge URL defined by `edge-upload-provider`, no signing), create a
system upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(job)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery reads the destination URL's **last path segment**, which is the
unchanged `filename` (the byte URL's last segment; format per `edge-upload-provider`). On
`AlreadyUploaded` it SHALL
create no job and write nothing. Completion and failure outcomes are reduced into the ledger by the
drain (see "Completion and retry adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real device-partitioned edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally by `edge-upload-provider`,
  a system upload job is created with it, and only after the create succeeds does the extension report
  `UploadStarted`, which records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED`)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
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
ledger (the `LedgerWriter`'s per-key `entry` read); the `resource`, when still present, is reused
only to re-create a
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

When the extension reconstructs the engine `Resource` for a returned job whose **ledger row is absent**
(pruned), it SHALL derive the resource `assetId` from the job key via the **shared**
`assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see `gallery-status`) — never a
placeholder such as an empty string — and SHALL record a terminal state only for a job whose key is
recoverable. It SHALL NOT write a `COMPLETED` (or other) row carrying a phantom `assetId=""`.

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

#### Scenario: A pruned-row completion derives assetId from the key
- **WHEN** a succeeded job is completed but its ledger row was already pruned (no entry)
- **THEN** the reconstructed resource carries the `assetId` parsed from the job key by
  `assetIdFromUploadKey` (not an empty string), and no phantom `assetId=""` row is recorded

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

**Kotlin decides; Swift constructs** (migration step 12, settled forcing proof ①:
`PHBackgroundResourceUploadProcessingResult` is Swift-only — declared in the SDK's swiftinterface
with no ObjC header — but `RawRepresentable` over `Int`). The mapping from `CycleResult` to the
system result SHALL be the tested, **exhaustive** Kotlin function
`CycleResult.processingResultRawValue()` (`:domain` `ports/`, raw values pinned in `commonTest`:
`failure` = 0, `processing` = 1, `completed` = 2; `completed` and `skipped` — nothing to do — both
map to the completed raw value). The extension root SHALL expose it as `processRawValue()` (wiring
only, no branch), and the Swift principal class SHALL construct the result via
`init?(rawValue:)`, mapping a `nil` (a raw value the SDK enum does not carry) to `.failure` — so
an untaught value surfaces as a retried, visible failure, never a silently "successful" upload
cycle. A future Kotlin `CycleResult` case cannot slip through untaught: the exhaustive `when`
stops compiling instead.

#### Scenario: Cap during discovery yields a processing result
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** the extension stops creating jobs, does not advance the token, and the cycle surfaces a
  processing result (raw value 1, constructed as `.processing`)

#### Scenario: Pending in-flight work requests re-invocation
- **WHEN** a cycle drains and creates with no cap, but the ledger still has pending (in-flight) rows
- **THEN** the cycle surfaces a processing result so the system re-invokes the extension to record
  their completions, rather than resting until the next library change

#### Scenario: Fully backed up reports completion
- **WHEN** a cycle ends with no pending rows in the ledger
- **THEN** the cycle surfaces the completed raw value and the system rests

#### Scenario: Re-entry resumes the remainder without duplicates
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the same change set is re-derived, the already-created jobs are skipped (`REQUESTED`), and
  only the previously un-created resources get new jobs

#### Scenario: An unconstructible raw value surfaces as failure
- **WHEN** the raw value forwarded to `init?(rawValue:)` is one the SDK enum does not carry
- **THEN** the shell reports `.failure`, so the system retries and the defect stays visible, rather
  than reporting a successful cycle that cannot be trusted

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
On a **valid event-link (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and driving the upload arm through the tier-neutral lifecycle
(`upload-lifecycle`). The mechanism below is **this tier's** (iOS ≥26.1) and SHALL NOT be applied on
the app-driven tier, which has no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

On this tier the re-provision's `start()` SHALL re-register the extension (the disable→enable toggle).
On its next cycle the extension reconciles against the per-device file listing (capability
`bunny-list-endpoint`, see `event-rejoin-reconciliation`): it **`resetTo`s** (atomic clear-and-seed)
the ledger to one already-uploaded row per stored file and **clears the discovery cursor** (forcing a
full re-enumeration). The device-global listing re-seeds the same files as already-uploaded, so
**nothing already stored re-uploads**, while the clear drops stale/phantom rows and the cursor clear
re-enumerates to find genuinely-unstored work. The device-global accumulator is **kept** and the
extension **re-projects** it to the **new** event's `device.json` path, then sets the joined-event
marker. The app decodes the event link only to gate this on a valid payload; the authoritative
decode/validate/persist still happens in the shared container intent.

The re-provision itself SHALL NOT clear the **ledger** (`upload-lifecycle`): only the reconciliation's
`resetTo` re-baselines it, from the authoritative per-device listing. The **discovery cursor** is cleared
twice over on this path, both deliberately and neither by the provisioning logic: by the re-register's
disable half (see "Disabling the extension clears orphaned REQUESTED rows", which requires it on **every**
disable) and by the reconciliation itself. Both are repairs, and both cost only a re-enumeration — the
ledger they leave intact is what knows the work is already done.

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing, clears the discovery cursor, keeps the accumulator, and
  re-projects `device.json` to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `bunny-upload-endpoint`)
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger, cursor, accumulator, and joined-event marker are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the app-driven producer's `start()` runs instead

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

The app SHALL recover the in-flight jobs wiped by a disable. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension it SHALL, immediately after the disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to **both** disable paths: the
disable half of the `disable→enable` re-register, and the leave use-case's extension-disable.

The disable-and-clear SHALL be **awaited off the main thread and completed before any re-enable**. The
`clearRequested()` write SHALL run on `Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`),
never on the `Dispatchers.Main` scope — it is a synchronous SQLite `DELETE` that on the main thread is
a hang risk under cross-process WAL contention — and SHALL use a small bounded retry around the write.
The `disable→enable` re-register SHALL NOT call `setUploadJobExtensionEnabled(true)` until the clear
has completed, so the re-enabled extension's freshly recorded `REQUESTED` rows can never be deleted by
a still-running clear. The clear SHALL NOT be fire-and-forget. The bounded-retry, off-main clear is
pure logic and SHALL live in a tested `domain`/`capability` helper injected into both disable paths,
not in the untested app shell; only the sequencing of the two iOS platform calls remains in the shell.

Without `clearRequested()`, the rows stay `REQUESTED` forever: the engine treats `REQUESTED` as
in-flight and never re-issues it, there is no API to enumerate live jobs to detect that the job is
gone, and a same-event cycle never reconciles — so the photos that were mid-upload at the disable are
permanently abandoned. With both clears, the next full enumeration re-discovers the cleared keys and
re-creates exactly the not-yet-stored jobs (stored files remain `COMPLETED` and are skipped). The app
SHALL route both disable paths through a single helper so they cannot diverge, and SHALL use the
`LedgerStore` directly (constructing no `LedgerWriter`), since `clearRequested` is an app-side
reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `clearRequested()` drops the `REQUESTED` rows, and the
  discovery cursor is reset — so the next cycle's full re-enumeration re-discovers and re-creates the
  not-yet-stored jobs (bytes resume landing), with no permanently-stuck `REQUESTED`

#### Scenario: The re-enable does not race the clear

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `clearRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is deleted by the clear

#### Scenario: The clear runs off the main thread

- **WHEN** a disable triggers `clearRequested()`
- **THEN** the SQLite delete executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes

### Requirement: Discovery suppresses downloaded assets

The upload cycle's discovery SHALL consult the download store's suppression projection (the set of
`createdLocalId`s of foreign assets this device downloaded and imported) and SHALL drop every
discovered resource whose `assetId` — **normalized `'/'→'_'` to match the stored `createdLocalId`
form** — is in that set **before** engine fan-out (no upload job created)
and before `retainAssets`. This prevents the download→import→re-upload echo: an imported foreign asset
gets a fresh local `localIdentifier` that discovery would otherwise treat as a new local asset and
upload back. The normalization SHALL be the **same** transform the shared gallery enumeration applies
when deriving the upload key, so the two sides meet byte-for-byte. The suppression read SHALL be
read-only and cross-process (the extension reads the
app-written store over WAL). The filter SHALL live in the platform-free upload-cycle core (a injected
suppression port), not in untested platform wiring, so it is exercised in `commonTest`.

#### Scenario: A downloaded-then-imported asset is never re-uploaded

- **WHEN** discovery encounters a resource whose `assetId` (normalized `'/'→'_'`) is in the
  suppression set
- **THEN** no upload job is created for it and it is excluded from `retainAssets`

#### Scenario: Suppression is consulted before fan-out

- **WHEN** a discovery cycle runs
- **THEN** suppressed assets are removed from the discovered set before the engine is asked to create
  any upload job

#### Scenario: Suppression matching normalizes the assetId

- **WHEN** a discovered resource's raw `assetId` contains `'/'` and the stored `createdLocalId` is its
  `'/'→'_'` normalized form
- **THEN** the two are treated as the same identity and the resource is suppressed

#### Scenario: Non-suppressed assets upload normally

- **WHEN** discovery encounters a resource whose `assetId` is not suppressed
- **THEN** it is handed to the engine and uploaded as before

### Requirement: The extension root contains only what is tier-specific

`process()` SHALL contain only the two concerns that cannot be shared with another upload tier:

- **The synchronous OS contract** — the cycle is driven to completion and its result returned, because the
  OS invokes `process()` synchronously and the process does not outlive it.
- **The pending→processing requeue** — because the OS invokes this tier lazily, on library changes rather
  than on upload completion, this tier alone must ask to be re-invoked while jobs are still in flight.

(The cross-process liveness notification this list used to carry is deleted — migration step 12:
the app's foreground-gated `aggregates()` poll replaced it; see `sync-status`.)

Everything else the root does today — the membership read's decision, the leave-side reconciliation, the
engine and cycle assembly, the manifest and notify hooks, the cutoff and contribution derivation — SHALL
move to the shared cycle (capability `upload-lifecycle`). What remains SHALL be translation: mapping this
platform's storage and bundle into the shared decision function's arguments, with no branch a second tier
could answer differently.

The root is `iosMain`-only and untestable by project rule (`:app:ios` and the extension's composition root
are wiring-only). That rule is a constraint on what may live there, not a licence: a decision placed in an
untested root reaches whichever tiers its author enumerated, which is how the reconciliation, the direction
gate, and the membership read each shipped on one tier and not the other.

#### Scenario: The skip decision is not made in the root
- **WHEN** the extension is invoked and its membership is unreadable
- **THEN** the skip is decided by the shared cycle, and the root neither branches on the read nor
  reconciles

#### Scenario: A drained cycle with pending jobs still asks for re-invocation
- **WHEN** the cycle would otherwise report completed and the ledger still holds pending rows
- **THEN** the extension surfaces processing instead, unchanged

### Requirement: The extension root's skip diagnostic survives the move

The forensics for a skipped cycle SHALL remain a single log line carrying why the read failed — the
membership read's status and whether the device identity resolved. The skip decision is made in shared
code, which cannot see either; the root SHALL therefore supply the detail with the decision, and the cycle
SHALL log it verbatim.

An unreadable membership is invisible on a device except through this line: nothing else distinguishes "we
skipped, correctly" from "we did nothing, wrongly". `debug.log` is the canonical un-redacted channel for
it, and one line in one file is the readable form.

#### Scenario: A skipped cycle names the cause
- **WHEN** a cycle is skipped because protected data is unavailable
- **THEN** one log line records the membership read's status, whether the device identity resolved, and
  that this was not treated as a leave

### Requirement: Extension assembles config from the shared config store and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfig` (`eventId`) read through the shared three-state config store —
`:adapter:ios:ext-safe`'s file-backed store over the App-Group config file, with its
written-through Keychain fallback while that lasts (capability `event-link`) — the stable
per-install `deviceId` read from the **shared Keychain** (per `device-identity`); and the
compile-time edge **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle`
info dictionary). The `deviceId` SHALL be used to build the event-independent byte URLs
(capability `edge-upload-provider`) and as the `device.json` key. The extension SHALL read the
persisted config **freshly at the start of every `process()` cycle** — one three-state
`ConfigReader.read()` per cycle (capability `upload-lifecycle`, the port-pure entry gate); it MUST
NOT cache a value read once at process construction. The extension process outlives a single
invocation, and an event (re)joined by the **app** process writes the shared store but does not
notify the extension's in-memory state; a cached value would make a long-lived extension keep
uploading to a stale, previously-joined event even after the app shows the new one as joined. When
the persisted config is **definitively absent** (the extension woke before the user joined an
event), the extension SHALL log and complete the cycle as a successful no-op — creating no job and
writing nothing — never crashing.

#### Scenario: Config present — provider built from host, eventId, and deviceId

- **WHEN** `process()` runs with an `EventConfig` persisted in the shared config store
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from
  `BackgroundUploadURLBase`, `eventId` from the persisted config, and `deviceId` from the shared
  Keychain, so byte URLs are built by `edge-upload-provider` and `device.json` is keyed by that
  `deviceId`

#### Scenario: Config absent — cycle skipped cleanly

- **WHEN** `process()` runs with no persisted config in the shared store
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job
  and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle

- **WHEN** the extension process has already run a cycle for one event, the app then joins a
  different event (persisting the new `eventId` through the shared store), and the same extension
  process runs its next `process()` cycle
- **THEN** the extension re-reads the persisted config, builds `EdgeUploadRequestProvider` for the
  **newly-joined** `eventId` (the `deviceId` is stable across the switch), and uploads to the new
  event — it does not keep uploading to the event it read at process construction

