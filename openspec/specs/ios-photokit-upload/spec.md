# ios-photokit-upload Specification

## Purpose

The **OS-driven upload tier** for iOS ≥26.1: a PhotoKit background-upload app extension that the system
invokes on its own cadence, discovers newly-qualifying photos, drives the shared upload cycle, and lets the
OS perform the uploads — power- and network-aware, across suspension and lock. It exists because photos
must reach the event without the user ever opening the app, and only the OS can schedule that.

The extension is the **sole `LedgerWriter`** on this tier. The app reads the ledger, and at membership
transitions invokes only the store's reset family — the leave's clear and the join-time load (`changes/archive/2026-09-21-join-loads-leave-clears`) —
never a record write. The
platform-agnostic orchestration deliberately lives in `:domain`'s `feature/upload` zone (which declares a `jvm()` target
so the upload cycle is harness- and JVM-tested); what this capability covers is the iOS side of that seam —
the PhotoKit adapter, the thin Swift pass-through shell, discovery by full enumeration (there is no persisted
change-token cursor since `changes/archive/2026-09-21-always-full-enumerate`), job
creation/retry/acknowledge disposition, the compile-time upload host, and the ATS constraint that the host
be HTTPS.

Uploads on iOS 18–26.0 are the app-driven tier instead — see `ios-url-session-upload`.

Decision record: `changes/archive/2026-06-19-ios-background-upload`.

The **Re-provision resets sync state** requirement was scoped explicitly to this tier in
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` (the disable→enable toggle is this tier's producer
`start()`, not universal host-app behavior).

The change-token advance was re-conditioned in `changes/archive/2026-08-27-fix-cap-truncation-loop` — from *every job was created* to *every fact
the walk produced is durable* — which replaced the requirement that the token not advance on a
cap-truncated cycle.
## Requirements
### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), the library-read seam (`UploadDiscovery`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per capability `testing-architecture` ("Every test runs on every target its module declares"). The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, the PhotoKit discovery (`IosDiscovery`, bound once as the `UploadDiscovery`), and the generic HTTP adapters (`:adapter:generic:app`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery the roots bind (the `IosDiscovery` full-enumeration walk, implementing `UploadDiscovery`) and the shared upload-request builder, both shared with the `ios-url-session-upload` adapter, and the file-backed `ConfigSource`. The **compile-time host read** (`bakedUploadBase`, the `uploadBase` value read from the bundled `Deployment.plist`) SHALL live in `:adapter:ios:ext-safe` beside the build-version read the boot banner uses, for the same two reasons: **both processes** read it (each `NSBundle.mainBundle` being its own bundle), and its absent-key defaulting is a **decision**, which the zero-decision shell gate forbids a wiring-only root to hold — the same reasoning that seated `IosPhotoKitUploadPlatform` there at the migration finale. The composition root (`UploadExtensionRoot`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic:app`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension bundle SHALL carry the generated `Deployment.plist` (capability `deployment-configuration`), whose `uploadBase` is the compile-time edge host the app and the extension **read** when they build upload requests. The extension `Info.plist` SHALL **additionally** declare `BackgroundUploadURLBase`, carrying that same base URL: it is read not by this app but by **`assetsd`**, which validates the registration insert against the value in the bundle's own `Info.plist` and can see no resource the app bundles. With the key absent, `setUploadJobExtensionEnabled(true)` SHALL be expected to fail with a bare `PHPhotosErrorDomain -1` and empty `userInfo`, the OS never launches the extension, and nothing uploads on this tier. Because an `Info.plist` substitution can only read a build setting and `//` opens a comment anywhere on an xcconfig line with no escape, the value SHALL be **composed** in the `Info.plist` from build settings that cannot themselves contain `//` — a scheme enum and a bare host — rather than carried as one URL-valued build setting. The app bundle SHALL carry the key on the same terms: the registration call is made by the app process, and which bundle the daemon reads has not been established. What IS established is a device A/B (SE2, iOS 26.6, 2026-08-28, one variable): key absent → enable fails `-1`, disable fails `3201`; key present as `https://<domain>/api/v1` → both succeed and the read-back is `true`. The daemon's **matching rule** — whether it compares host, origin or prefix — is NOT established, and this spec SHALL NOT assert one. ⏰ Re-measure at the next iOS major, with the other PhotoKit platform facts. The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, its bundle carries `Deployment.plist` with a non-empty `uploadBase`, its `Info.plist` declares a non-empty `BackgroundUploadURLBase` equal to that `uploadBase`, it links the `:app:ios:extension` framework (which composes `:domain` and the adapter modules), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`BackgroundTransfer`/`UploadDiscovery`/`UploadConfig` live in `:domain` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

#### Scenario: Extension adapters compose the capability
- **WHEN** the extension's composition root assembles a cycle
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscovery`) implement the upload seams — `IosDiscovery` shared with the app-driven tier from `:adapter:ios:ext-safe` and bound once as the `UploadDiscovery` — and the root supplies them as `UploadPorts` to `uploadCore`, which constructs the `:domain` `feature/upload` `UploadCycle`, with the download-store / manifest edges answered in the ports bundle rather than inside the feature

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same shared `:domain` orchestration assembled by the same `uploadCore`

#### Scenario: The extension's cycle is the shared composition
- **WHEN** `UploadExtensionRoot` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no cycle, gate, join marker, or
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic:app`'s
  `HttpEnrollment`

#### Scenario: The registration is refused when the daemon's key is absent

- **WHEN** the extension bundle carries no `BackgroundUploadURLBase` in its `Info.plist`
- **THEN** `setUploadJobExtensionEnabled(true)` fails with `PHPhotosErrorDomain -1` and empty `userInfo`,
  the OS launches the extension never, and no diagnosis is available from the error itself

#### Scenario: The baked value is composed from `//`-free build settings

- **WHEN** the `Info.plist` value is rendered
- **THEN** it is composed from a scheme enum and a bare host emitted as separate build settings, so no
  single build setting carries a value containing `//`, which the xcconfig grammar would truncate silently

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

### Requirement: Device manifest projection and side-channel upload

The extension SHALL project the current event's `device.json` from the **upload ledger's `COMPLETED`
rows** (capability `sync-ledger`), which carry the manifest's presentation detail (per `device-manifest`:
per asset its `assetId` and `creationDate`, and per resource its `role`, `contentType`, `key` (the
storage object name), and `filename` (the human capture name)). It SHALL keep **no** second durable
structure of manifest entries: the ledger is the only record the projection reads, so there is nothing
that can drift out of step with it.

On each cycle the extension SHALL **project** those rows to the current event's `device.json` — keeping
exactly the assets the membership's selection policy admits by capture date (capability
`photo-selection-policy`), applying that one policy rather than a date comparison of its own — and **PUT
it synchronously, in-cycle**, to `<host>/events/<eventId>/devices/<deviceId>` with `Content-Type:
application/json` — **not** over a background `URLSession`, and **not** through the `SyncEngine` or the
`createJob` path. The extension SHALL be the **sole writer** of `device.json`; each write is a complete,
self-contained full-state snapshot (no read-modify-write). It MAY skip the PUT when the projection is
**byte-identical** to the last written `device.json` **for the same event**, recorded in the App-Group
`last-uploaded.json` marker — event-keyed, so a switch to a new event never compares equal to the prior
event's write and skips the new event's still-absent document. Because the resource field names (`key`,
`filename`) are part of that snapshot content, a build that changes them produces a projection that
differs from any previously-stored snapshot, so the first cycle on the new build re-PUTs `device.json`
with the new names — no special one-shot flag is needed. A process kill mid-PUT is benign —
`device.json` is write-only in v1 and the next cycle re-projects and re-PUTs, so the loss is caught and
converges. There are no `PENDING`/`DONE` manifest markers and no `handleEventsForBackgroundURLSession`
manifest wiring in the app; the app reads no manifest state.

#### Scenario: An already-uploaded asset stays listed without a new job

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** no job is created, and `A`'s already-`COMPLETED` rows are still projected into `device.json`,
  so an already-uploaded asset never drops out of the manifest

#### Scenario: Each cycle projects the ledger and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the ledger's `COMPLETED` rows to the current event's `device.json` and
  PUTs it synchronously, in-cycle, to `<host>/events/<eventId>/devices/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine or job-creation involvement,
  each resource carrying `key` (the storage object name) and `filename` (the human capture name)

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
- **THEN** the partial write is discarded and the next cycle re-projects the ledger and re-PUTs
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
`UploadStarted(request)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery reads the destination URL's **last path segment**, which is the
unchanged `filename` (the byte URL's last segment; format per `edge-upload-provider`). On
`AlreadyUploaded` it SHALL
create no job and write nothing. Completion and failure outcomes are reduced into the ledger by the
drain (see "Completion and retry adjudication"), so a success is recorded `COMPLETED` and a failure returns its
row to `DISCOVERED`.

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

**On iOS ≥26.1** the app SHALL register the background-upload extension with a **disable→enable toggle**
whenever it registers it — forced at a **join**, and at a reconfigure, a permission change, a launch or an
override change only when the compared reconcile finds it wanted and absent (`upload-lifecycle`, "Membership
transitions reconcile the upload mechanisms in one tested place") — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; the transitions arm and disarm the app-driven engine instead (see `ios-url-session-upload`).

A stale record that still **reads** enabled is repaired only at the next join: the launch reconcile compares
rather than forcing, so the extension's in-flight jobs survive app launches (`upload-lifecycle`, "Launch
reconciles by comparison; only a join forces the repair").

The registration change SHALL be made through a **port** in `:domain` `ports/`, named for the need, whose
iOS adapter — the only implementation that calls `PHPhotoLibrary.setUploadJobExtensionEnabled` or
`isUploadJobExtensionEnabled` — lives in `:adapter:ios:app-only`, because only the app process ever
registers. The mechanism that performs the ritual SHALL hold no platform call of its own — its repair reaches
the ledger through the ledger's own port (see "Re-registering the extension demotes orphaned REQUESTED rows") — and SHALL therefore live in `:domain` `feature/upload` beside the
app-driven tier's mechanism, named for the need rather than for the platform. This is the ports law applied where it was not: the call sat in
`:app:ios`, which is wiring-only and gated at `CyclomaticComplexMethod` threshold 2, so it could report the
platform's raw facts but could hold no decision about them. Behind a port, the ritual, its repair,
and every arm of the outcome classification become executable on any host that can implement the port,
including JVM.

The registration record is OS state that this repo does not own, exactly as the upload-job queue is. Where
a target's host cannot hold such a record, the port's binding for that target answers in its place; see
"The upload-job subsystem binding is fixed by the compilation target".

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

#### Scenario: The mechanism holds no platform call
- **WHEN** the mechanism that performs the disable→enable ritual is compiled
- **THEN** it names no platform API at all — the registration change and its read-back are reached through
  the registration port, and the repair through the ledger port — so it compiles for every
  target the platform-free core does

#### Scenario: The ritual is executable off a device
- **WHEN** the ritual runs against a port implementation that reports a pre-existing configuration record
- **THEN** the leading disable reports that a record existed and was removed, the enable reports success,
  and the sequence is asserted without a physical device

#### Scenario: The repair completes before the re-enable
- **WHEN** the ritual runs while the ledger holds orphaned `REQUESTED` rows
- **THEN** the rows are demoted to `DISCOVERED` **before** the enable is attempted, so the repair cannot demote
  rows belonging to the registration it is about to re-create

#### Scenario: Deregistering is the disable alone
- **WHEN** the extension is deregistered — on a leave, a download-only join, or a pin away from this mechanism
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and no ledger
  row is touched; the next bring-up repairs any row left `REQUESTED`

### Requirement: Device-visible (un-redacted) logging

Both the app and the extension SHALL route Kermit through a log writer whose messages appear **un-redacted** in the device unified log / `idevicesyslog` (the default os_log path redacts dynamic content as `<private>`), so on-device discovery/decision/upload logs are readable without a Mac.

#### Scenario: Log content is readable on device
- **WHEN** the extension logs a message containing dynamic content (e.g. a key or URL) on device
- **THEN** the message text appears verbatim in `idevicesyslog`, not as `<private>`

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first). It SHALL recover a returned
`PHAssetResourceUploadJob`'s ledger row by matching the job's **destination URL path** against the
`destinationPath` recorded for that row when the job was created (capability `sync-ledger`), read through the
`TransferRecord` the adapter is given. The destination is the only field reliably present for every job
state, since `resource` is **nil for succeeded jobs** (the system releases it after upload) — but under the v2
byte route its last path segment is the resource's **role**, not the ledger key, so the key SHALL NOT be read
from it.

For a job whose destination path matches no recorded row — including one created by a build that predates
the recorded path — the extension SHALL fall back to recovering the key from the destination URL's **last
path segment**, which is correct for the v1 destination shape and for nothing else.

A job whose row cannot be recovered by either route SHALL be **counted and reported at `Error` severity**,
naming how many such jobs a cycle saw. It SHALL NOT be silently drained: an unrecoverable job means an
upload whose outcome is being discarded, and a device in that state uploads bytes that are never recorded,
never listed in the manifest, and never visible to another member — with the row left `REQUESTED`, which no
routine path clears.

It SHALL likewise recover the job's **content type** from that same destination's `Content-Type` header
(matched case-insensitively, a blank value treated as absent), falling back to the `resource`'s uniform
type identifier and then to `application/octet-stream`. Deriving the content type from `resource` alone is
silently wrong for the same reason the key is not taken from it: a succeeded job has none, so a retried
upload rebuilt its request as `application/octet-stream` and every object that had ever failed once was
stored with that type. That the destination's headers survive the system's job store — not merely its URL —
is measured on device (SE2 / iOS 26.6), on both the `.retry` and `.acknowledge` sets; re-measure if the tier
moves to the iOS 27 `PHBackgroundResourceUploadJobExtension`.
The `resource`, when still present, is reused
only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose row is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `DISCOVERED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED`). The system job `retryWithDestination(:)` is applied to SHALL be
  found by the **same route** the job's key was recovered by — its destination path against the recorded
  `destinationPath`, then the v1 last-segment fallback — and SHALL NOT be found by comparing the destination's
  last path segment to the ledger key, which under the v2 route is the resource's role and matches no key.
  A retry whose system job is no longer in the `.retry` set SHALL be logged and SHALL NOT be silent.
- **`fetchJobsWithAction(.acknowledge)` (terminal):** the adapter SHALL record the outcome into the ledger
  itself, through the guarded `markTerminal` of its `TransferRecord` (`sync-ledger`), and acknowledge the job
  **in place** — `state == Succeeded` → record `COMPLETED`, then acknowledge; a key already in a terminal
  state → acknowledge (the guard applies to nothing, an idempotent no-op); otherwise (a retry-spent
  `Failed`/`Cancelled` job) → record `DISCOVERED` (the failed outcome), then acknowledge. The job SHALL be acknowledged **regardless** of
  whether its guarded write applied and regardless of any re-create outcome (never leave a presented job
  un-acknowledged). Retry has no attempt budget (retry forever).

A succeeded job SHALL become `COMPLETED` directly. Nothing a completion used to trigger is still owed: the
device manifest declared the resource when it was discovered (capability `device-manifest`), and the
event-album placement happened when its upload was first enqueued (capability `event-album`). No later pass
reads or re-settles the row.

Only **retry-spent failures whose `resource` is still available** SHALL be returned from the drain, so the
cycle can re-create them in the same cycle from a live resource. No succeeded job and no terminal fact SHALL
cross the port.

When the extension reconstructs a resource for a returned job whose **ledger row is absent**
(pruned), it SHALL derive the resource `assetId` from the recovered key via the **shared**
`assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see `gallery-status`) — never a
placeholder such as an empty string — and SHALL record a terminal state only for a job whose row is
recoverable. It SHALL NOT write a row carrying a phantom `assetId=""`.

#### Scenario: A returned job is matched by its destination path
- **WHEN** a job in the `.acknowledge` set carries a destination whose path equals the `destinationPath`
  recorded for a ledger row
- **THEN** that row is the job's row, whatever the destination's last path segment happens to be

#### Scenario: The role token is never mistaken for the key
- **WHEN** a job's destination is the v2 byte route, whose last path segment is the resource's role
- **THEN** the extension does not treat that segment as a ledger key, and no row keyed `primary` or `live`
  is ever written

#### Scenario: A job created by the previous build still resolves
- **WHEN** a job's destination path matches no recorded row and its shape is the v1 byte route
- **THEN** the key is recovered from the destination's last path segment and the job is adjudicated normally

#### Scenario: An unrecoverable job is reported, not drained silently
- **WHEN** a cycle presents one or more jobs whose rows cannot be recovered by either route
- **THEN** the count is reported at `Error` severity, and every such job is still acknowledged

#### Scenario: Succeeded job records COMPLETED
- **WHEN** a job in the `.acknowledge` set has `state == Succeeded`
- **THEN** the extension resolves its row from the job's destination path, records that row `COMPLETED`, and
  acknowledges the job — and no later pass of the cycle reads or writes that row again

#### Scenario: A retried upload keeps its original content type
- **WHEN** a job is returned for retry or re-creation, so its `Resource` is rebuilt from the key alone
  with no metadata, and `resource` may be nil
- **THEN** the rebuilt request's `Content-Type` is the one read back from the job's stored destination
  header — not `application/octet-stream` — so the object is stored with the type it was uploaded under

#### Scenario: First failure retries with a rebuilt URL
- **WHEN** a job is returned in the `.retry` set
- **THEN** the extension reports `UploadFailed`, obtains a `Retry` with a locally rebuilt edge
  destination (byte-identical to the original — no expiry), calls `retryWithDestination(:)`, and
  reports `UploadStarted` so the ledger holds `REQUESTED`

#### Scenario: A v2-route retry reaches its system job
- **WHEN** a job is returned in the `.retry` set whose destination is the v2 byte route, so its last path
  segment is the resource's role
- **THEN** the system job whose destination path resolves to that key is the one `retryWithDestination(:)`
  is applied to, and no "no live retry job" line is logged for it

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension records that row `DISCOVERED`, acknowledges the job, and returns it from the drain so
  the cycle creates a fresh job from the live resource

#### Scenario: A failure handed back for a completed key re-uploads nothing
- **WHEN** a retry-spent failure is returned from the drain for a key whose row is already `COMPLETED`
- **THEN** the cycle skips it as settled, writes nothing, and creates no job

#### Scenario: Every presented job is acknowledged
- **WHEN** a returned job's row cannot be recovered, or its guarded write applies to nothing, or its
  re-create hits the cap, or its resource is unavailable
- **THEN** the job is still acknowledged, so the system never reports error 50008

#### Scenario: Already-terminal re-handed job is a no-op
- **WHEN** a returned job maps to a row that is no longer `REQUESTED`
- **THEN** the guarded write applies to nothing, the job is acknowledged, and nothing is written or
  re-created

#### Scenario: A pruned-row completion derives assetId from the key
- **WHEN** a succeeded job is recorded but its ledger row was already pruned (no entry)
- **THEN** the guarded write applies to nothing, no phantom `assetId=""` row is created, and any resource
  reconstructed for a re-create carries the `assetId` parsed from the recovered key by `assetIdFromUploadKey`

### Requirement: Cap-aware creation and tri-state processing result

When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle and surface a **processing** result so the system re-invokes it
promptly. It SHALL NOT stop anything else: the walk's facts are already recorded (see "In-extension
discovery by full enumeration"), the un-created remainder is already recorded `DISCOVERED`,
and the cycle SHALL still publish its device manifest, its enumeration audit line, and its completion
notify. On the next wake, the producer resumes exactly the un-created remainder from the ledger — with
no duplicate jobs, no persisted residue list, and no re-derivation.

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
- **THEN** the extension stops creating jobs and the cycle surfaces a processing result (raw value 1,
  constructed as `.processing`)

#### Scenario: A cap-truncated cycle still publishes
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** that cycle still writes its device manifest, emits its enumeration audit line, and fires
  its completion notify if the projection changed

#### Scenario: Pending in-flight work requests re-invocation
- **WHEN** a cycle drains and creates with no cap, but the ledger still has pending (in-flight) rows
- **THEN** the cycle surfaces a processing result so the system re-invokes the extension to record
  their completions, rather than resting until the next library change

#### Scenario: Fully backed up reports completion
- **WHEN** a cycle ends with no pending rows in the ledger
- **THEN** the cycle surfaces the completed raw value and the system rests

#### Scenario: Re-entry resumes the remainder from the ledger
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the un-created remainder is enqueued from its `DISCOVERED` rows, with no duplicate jobs and
  without re-deriving the same change set

#### Scenario: An unconstructible raw value surfaces as failure
- **WHEN** the raw value forwarded to `init?(rawValue:)` is one the SDK enum does not carry
- **THEN** the shell reports `.failure`, so the system retries and the defect stays visible, rather
  than reporting a successful cycle that cannot be trusted

### Requirement: Re-provision resets sync state

On a **valid event-link (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and driving the upload arm through the tier-neutral lifecycle
(`upload-lifecycle`). The mechanism below is **this tier's** (iOS ≥26.1) and SHALL NOT be applied on
the app-driven tier, which has no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

A **switch** (a re-provision into a different event) is a leave followed by a join (capabilities
`upload-lifecycle`, `join-event`). On this tier it SHALL run in the app, in this order: the leave transition
**deregisters** the extension (the disable alone — see "Re-registering the extension demotes orphaned REQUESTED rows"),
the provision's **join-time load** re-baselines the ledger — it fetches the
per-device file listing (capability `api-endpoints`) and **`resetTo`s** (atomic clear-and-seed) the ledger
to one already-uploaded row per stored file, or `clear()`s it when the fetch fails — through the app's
`LedgerStore`, with no `LedgerWriter` (see `ios-app-shell`, "The app resets the upload ledger at membership
transitions on every tier"), the new config is then saved, and only then does the join transition
re-register the extension (the
disable→enable toggle, forced). A first join takes the same load before the first registration. The extension
therefore never re-baselines the ledger itself: it constructs no join marker and runs no in-cycle
reconciliation, and its first cycle after the re-register already reads the loaded ledger.

The device-global listing seeds the stored files as already-uploaded, so **nothing already stored
re-uploads**, while the clear drops every row from before the provision and the cycle's walk, a full
enumeration like every walk, finds genuinely-unstored work. The extension's next cycle **re-projects**
the re-baselined ledger to the **new** event's `device.json` path. Rows seeded from the listing are
**bare** (a filename carries no capture date) and are therefore not listed until the walk backfills their
manifest detail; a bare row is always re-read by the walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know"). The app decodes the event link only to gate this on a
valid payload; the authoritative decode/validate/persist still happens in the shared container intent.

A re-provision of the **already-joined** event SHALL NOT reset the ledger: only a provision that changes
the membership loads it. The re-register's repair demotes rows instead (see "Re-registering the extension
demotes orphaned REQUESTED rows").

#### Scenario: Valid re-scan re-baselines and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the app disables the extension, `resetTo`s the ledger from the per-device file listing, saves
  the new config, and re-registers the extension (disable→enable), and the extension's next
  cycle re-projects `device.json` from that ledger to the new event path

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `api-endpoints`)
- **THEN** the join-time load's clear-and-seed seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger and the config are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the join transition arms the app-driven engine instead

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL record that an asset has left the library by **deleting** its ledger rows, and only on
the evidence capability `sync-ledger` defines ("Deletion is a presence diff over an authoritative walk"): an
authoritative walk that did not return the asset, for rows inside the membership's capture window that no
live job owns; or, for a single row that still needs a job, its key resolving to nothing. The ledger writes
preserve the single-writer invariant. No remote object is deleted: nothing on the device deletes an
uploaded object, and reclamation belongs entirely to the nightly sweep (capability `scheduled-cleanup`).
The one-way model is unchanged.

Deleting keeps the ledger honest about what still exists on device and, critically, removes a row left
non-`COMPLETED` by an asset deleted mid-upload. That row would otherwise keep `pending > 0` forever and hold
the extension in the perpetual `processing` re-invocation loop (see "Cap-aware creation and tri-state
processing result"). Because the device manifest is projected from those same rows, one deletion also makes
the next projected `device.json` stop listing the departed asset: there is no second structure to keep in
step.

The walk is narrowed by the membership's own selection policy, so "not returned" means gone from the
library **only inside the policy's capture window**. That is why the deletion is judged per row by the
policy's row admission and never by the admitted candidate set. The retired reconcile backstop was supplied
the policy-**admitted** set, so raising a capture cutoff discarded the `COMPLETED` rows of photos that were
still present. Judged by presence and row admission, a raised cutoff moves those rows out of the window
instead, and they survive. A membership whose direction excludes upload never reaches the walk at all.

Deletion is now exhaustive for a full grant: a deletion is observed by the first authoritative walk after
it, whenever that is, with no token to expire. A re-added asset (for example, recovered from "Recently
Deleted") SHALL be discovered as new work and re-uploaded under its same keys. Its rows are gone, so nothing
suppresses the upload. The backend re-stores the role idempotently and wakes nobody (capability
`api-endpoints`). No `DELETED` state is introduced and the upload decision is unchanged.

#### Scenario: A departed asset's rows are deleted by the next walk
- **WHEN** asset `L` has in-window `COMPLETED` rows and an authoritative walk does not return it
- **THEN** the extension deletes those rows before recording the walk's discoveries, so `L` contributes to
  neither `pending` nor `completed` and the next projected `device.json` omits it

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a `DISCOVERED` row
- **THEN** its key resolves to nothing at enqueue and that row is deleted, the ledger reaches no pending
  rows, and `process()` can return `completed` instead of looping on `processing`

#### Scenario: A walk deletes nothing outside its window
- **WHEN** an authoritative walk completes and the ledger holds rows, outside the membership's capture
  window, for an asset the walk did not return
- **THEN** those rows are kept: the walk is policy-narrowed, so an asset's absence from it is not evidence
  that the asset left the library

#### Scenario: A narrowed scope costs no ledger rows
- **WHEN** the membership's capture cutoff is raised past an already-uploaded asset still in the library,
  and a full enumeration runs
- **THEN** that asset's `COMPLETED` rows survive, so lowering the cutoff again re-lists it with no byte
  re-uploaded

#### Scenario: Re-added asset re-uploads
- **WHEN** an asset whose rows a walk deleted reappears in the library (for example, recovered from
  "Recently Deleted")
- **THEN** the next walk records its resources `DISCOVERED`, their upload is re-created under the same
  keys, and the next projection lists it again

### Requirement: Re-registering the extension demotes orphaned REQUESTED rows

The app SHALL recover the in-flight jobs a disable wipes. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**, and no API surfaces a vanished job. Without a recovery the
rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never re-issues it, and no
cycle re-baselines the ledger — so the photos that were mid-upload are permanently abandoned.

The recovery SHALL run in the **registration** — the disable→enable re-register — **between** the
disable and the enable, and SHALL be the ledger's reset-family `demoteRequested()` (`sync-ledger`): every
`REQUESTED` row becomes `DISCOVERED`. Every one of them is unsettleable at that moment: the disable has just wiped
this tier's jobs, and wherever the app-driven engine also exists, the transition that registers disarms it
first (`upload-lifecycle`, "Membership transitions reconcile the upload mechanisms in one tested place"), so
no app-driven transfer is carrying a row either.

A `DISCOVERED` row needs a job, so the ledger's work read returns it on the next cycle without any walk
re-deriving it. The former recovery *deleted* the rows, which only a walk that re-read the asset's
resources could re-surface; a demoted row needs no such walk.

**Deregistration** SHALL be the disable alone and SHALL repair nothing — on a leave, a download-only join, and
a pin away from this mechanism alike. On a leave nothing uploads until a mechanism is brought up again, and
the leave's own ledger clear — `LeaveEvent`'s, after the deregistration, never its (capability
`leave-event`) — leaves no row to repair; where the app-driven engine is armed instead, its restart rule
repairs (`ios-url-session-upload`, "Stranded reconciliation: scoped each cycle, complete at a start"). There
SHALL therefore be no narrower teardown verb for a hand-off.

The demote SHALL be **awaited off the main thread and completed before the enable**. The write SHALL run on
`Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`), never on the `Dispatchers.Main` scope — it is a
synchronous SQLite write that on the main thread is a hang risk under cross-process WAL contention — and SHALL
use a small bounded retry around the write. `setUploadJobExtensionEnabled(true)` SHALL NOT be called until the
demote has completed, so a `REQUESTED` row the re-enabled extension records can never be demoted by a
still-running repair. The demote SHALL NOT be fire-and-forget. The bounded-retry, off-main helper is pure logic
and SHALL live in a tested `:domain` helper (`feature/upload`), not in the untested app shell.

The app SHALL use the `LedgerStore` directly (constructing no `LedgerWriter`): on this tier the extension is
the one recording process, and `demoteRequested` is a reset-family operation that a non-writer may perform.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs and `demoteRequested()` marks the rows `DISCOVERED`, so the next
  cycle's work read re-creates the not-yet-stored jobs (bytes resume landing), with no permanently-stuck
  `REQUESTED` and no re-read of the assets' resources

#### Scenario: The re-enable does not race the repair

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `demoteRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is demoted by the repair

#### Scenario: The repair runs off the main thread

- **WHEN** a re-register triggers `demoteRequested()`
- **THEN** the SQLite write executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: A deregistration repairs nothing

- **WHEN** the extension is deregistered — a leave, or a download-only join — while `REQUESTED` rows exist
- **THEN** the deregistration itself changes no ledger row, and the rows are demoted by the next bring-up
  (or removed by a leave's subsequent ledger clear)

#### Scenario: Completed rows survive the repair

- **WHEN** a re-register triggers `demoteRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are unchanged, so a subsequent discovery does not re-upload
  already-stored bytes

### Requirement: Discovery suppresses downloaded assets

The upload cycle's discovery SHALL consult the download store's suppression projection (the set of
`createdLocalId`s of foreign assets this device downloaded and imported) and SHALL drop every
discovered resource whose `assetId` — **normalized `'/'→'_'` to match the stored `createdLocalId`
form** — is in that set **before** engine fan-out (no upload job created). This prevents the
download→import→re-upload echo: an imported foreign asset gets a fresh local `localIdentifier` that
discovery would otherwise treat as a new local asset and upload back. The normalization SHALL be the
**same** transform the shared gallery enumeration applies when deriving the upload key, so the two sides
meet byte-for-byte. The suppression read SHALL be read-only and cross-process (the extension reads the
app-written store over WAL). The filter SHALL live in the platform-free upload-cycle core (an injected
suppression port), not in untested platform wiring, so it is exercised in `commonTest`.

Echo suppression is an id set supplied per cycle, so it is one of the rules the **manifest projection**
re-applies (capability `device-manifest`). A stale row for an asset that has since become an echo is
therefore kept out of the manifest without being removed from the ledger — the row is still a true
statement that those bytes are on the backend.

#### Scenario: A downloaded-then-imported asset is never re-uploaded

- **WHEN** discovery encounters a resource whose `assetId` (normalized `'/'→'_'`) is in the
  suppression set
- **THEN** no upload job is created for it

#### Scenario: A suppressed asset's stale row is not listed

- **WHEN** the ledger holds a `COMPLETED` row for an asset that is now in the suppression set
- **THEN** the row is retained and the manifest projection excludes it, so the asset is neither
  re-uploaded nor offered to other members

#### Scenario: Suppression is consulted before fan-out

- **WHEN** a discovery cycle runs
- **THEN** suppressed assets are removed from the discovered set before the engine is asked to create
  any upload job

### Requirement: The extension root contains only what is tier-specific

`process()` SHALL contain only the two concerns that cannot be shared with another upload tier:

- **The synchronous OS contract** — the cycle is driven to completion and its result returned, because the
  OS invokes `process()` synchronously and the process does not outlive it.
- **The pending→processing requeue** — because the OS invokes this tier lazily, on library changes rather
  than on upload completion, this tier alone must ask to be re-invoked while jobs are still in flight.
  The requeue *decision* SHALL be the pure, tested `requeueWhilePending` rule (`:domain` `ports/`,
  beside the raw-value mapping — drained out of the root at the migration finale); the root supplies
  only the ledger read and the diagnostic line, leaving `process()` straight-line.

(The cross-process liveness notification this list used to carry is deleted — migration step 12:
the app's foreground-gated `aggregates()` poll replaced it; see `sync-status`.)

Everything else the root does today — the membership read's decision, the
engine and cycle assembly, the manifest and notify hooks, the cutoff and contribution derivation — SHALL
move to the shared cycle (capability `upload-lifecycle`). What remains SHALL be translation: mapping this
platform's storage and bundle into the shared decision function's arguments, with no branch a second tier
could answer differently.

The root is `iosMain`-only and untested by project rule (capability `testing-architecture`: `:app:ios` and
the extension's composition root are wiring-only and declare no test source set). That rule is a constraint on what may live there, not a licence: a decision placed in an
untested root reaches whichever tiers its author enumerated, which is how the reconciliation, the direction
gate, and the membership read each shipped on one tier and not the other.

#### Scenario: The skip decision is not made in the root
- **WHEN** the extension is invoked and its membership is unreadable
- **THEN** the skip is decided by the shared cycle, and the root neither branches on the read nor
  touches the ledger

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
`:adapter:ios:ext-safe`'s file-backed store over the App-Group config file (capability
`event-link`) — the stable
per-install `deviceId` read from the **shared Keychain** (per `device-identity`); and the
compile-time edge **host** read from the extension bundle's `Deployment.plist` `uploadBase` (`NSBundle`
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
  `uploadBase`, `eventId` from the persisted config, and `deviceId` from the shared
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

### Requirement: The registration cannot be changed under a partial grant

The OS-driven tier SHALL be treated as **unavailable** while the containing app holds a partial
(`.limited`) photo grant, because a partially-granted process **cannot change its upload-job registration
in either direction**.

Forcing proof: `setUploadJobExtensionEnabled` is refused with `PHPhotosErrorAccessUserDenied` (3311) for
both `false` and `true` — measured on device (SE2 / iOS 26.6, 2026-08-24 and 2026-08-25; decision record
`changes/archive/2026-08-25-collapse-upload-tier-seam`, D11 and D11b). The **enable** was reached only by
pinning the OS-driven mechanism under a partial grant through a development mechanism override, which no
shipped build can supply; in production an enable is never attempted there, because resolution never
yields this mechanism under a partial grant.

An earlier probe (SE2 / iOS 26.5, 2026-07-20;
`changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`) measured that with real
pending work and the extension re-registered twice under `.limited`, the OS issued **zero** `process()`
invocations over 22 minutes, then invoked the extension **within seconds** of the grant returning to full.
That observation stands. The mechanism it was read as — *"registration succeeds and lies, with no error
and no callback"* — is **contradicted by measurement**: the call site discarded its `Boolean` and
`NSError` at the time, so "succeeds" described a return value nobody read and "no error" meant none was
looked for. A registration that could not be created explains those 22 minutes at least as economically.
Because that probe is not re-runnable, this SHALL be stated as the asserted mechanism being contradicted,
never as a claim about what that probe observed.

Evidence limits, stated so a reader can tell what would falsify this: one device, one OS point release,
and an enable reached through a development pin rather than a path a user can take. Expiry trigger:
re-evaluate at the iOS 27 GM re-assessment (~Sept 2026, the existing
`PHBackgroundResourceUploadJobExtension` trigger) — the constraint MUST be re-measured against the async
protocol before assuming it persists.

Consequently, under `LIMITED` resolution SHALL NOT yield this tier — the app-driven engine runs instead —
and no registration write SHALL be attempted there by a compared reconcile (capability `upload-lifecycle`);
only the forced writes of a join or a leave reach the platform, and their refusal is reported, not fatal. A `LIMITED` membership relying on this tier
would be a silent no-op: the screen would sit at "Synchronization pending…" indefinitely, which is exactly
the failure mode this requirement exists to prevent.

A registration that **survives** a downgrade to a partial grant SHALL be made **inert by the extension's own
gate**. The OS does NOT leave it alone — measured on the SE2, iOS 26.6, 2026-09-21: with a surviving record
under `LIMITED`, `process()` ran four seconds after a new photo joined the selection, and the gate withheld
it. That supersedes this requirement's earlier reading that such a record is inert because the OS does not
invoke it. The extension reads the grant in its own process and
withholds under anything but `GRANTED` ("The extension withholds its cycle without a full grant"). A return to
a full grant is a permission change whose compared reconcile re-registers through the disable→enable ritual
if the record is gone. There is therefore no state in which a surviving registration and a running
app-driven mechanism produce two ledger writers. Deregistration
remains both possible and required under a **full** grant, which is where a development mechanism override
places the app-driven mechanism (`upload-lifecycle`).

#### Scenario: A limited grant never waits on the extension
- **WHEN** photo access is `LIMITED` and an upload-inclusive membership has pending work
- **THEN** no upload waits on a `process()` invocation — the work runs on the app-driven mechanism

#### Scenario: A downgrade to a partial grant cannot deregister
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while the extension is registered
- **THEN** no deregistration is attempted (it would be refused with `PHPhotosErrorAccessUserDenied`), the
  configuration record survives, and the app-driven engine is armed regardless

#### Scenario: The surviving registration causes no second writer
- **WHEN** a registration survives a downgrade to a partial grant and the app-driven mechanism is running
- **THEN** an extension invocation — which the OS does make (measured, iOS 26.6) — withholds at its gate,
  so exactly one process writes ledger records

#### Scenario: An enable under a partial grant is refused too
- **WHEN** the OS-driven mechanism is pinned by a development override under a `LIMITED` grant and its
  registration ritual calls `setUploadJobExtensionEnabled(true)`
- **THEN** the call is refused with `PHPhotosErrorAccessUserDenied` and no configuration record is created

### Requirement: A failed extension-registration change is reported, not discarded

`PHPhotoLibrary.setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`. Both SHALL be
captured. A registration change that fails SHALL be reported with the error's domain and code, not
discarded.

This matters because the failure is otherwise **invisible and terminal**: if enabling fails, the extension is
never registered, the OS never launches it, no upload cycle ever runs, and the screen sits at
"Synchronization pending…" indefinitely with no error anywhere — in the log, on the screen, or in crash
reporting. The mechanism's failure mode is silence, which is precisely the case "Absence is never silent"
(spec `module-architecture`) exists to refuse.

A failing **enable**, and any failure whose error is not one of the **expected cases enumerated below**,
SHALL be logged at `Error` severity, so `crash-reporting` carries it as field telemetry rather than leaving
it knowable only by attaching to a device. The enumeration is **closed and measured**: a code is expected
only once a device measurement shows it arising on an ordinary path, and widening it is a change to this
requirement.

The **leading disable** of the disable→enable ritual SHALL NOT be treated as a failure when it reports
`PHPhotosError` **3201** ("Unable to find the configuration"). On any clean device there is no configuration
record to remove, so that outcome is the expected result of a first registration — measured twice on an SE2
(iOS 26.6). Raising on it would place a reporting event on every first join of every fresh install, burying
the signal this requirement exists to surface in noise the requirement itself created.

A **disable** that reports `PHPhotosErrorAccessUserDenied` (**3311**) SHALL likewise not be treated as a
failure. Under a partial photo grant the platform refuses the change outright ("The registration cannot be
changed under a partial grant"), so this is the expected outcome of an ordinary, supported user action —
switching Photos to Limited Access — and it recurs on every membership-lifecycle action taken while that
grant is held. It SHALL be reported **below `Error`**, so no reporting event is raised, and at a severity
that still reaches the device log and the diagnostic dump, because the app's model of the registration is
knowingly wrong afterwards even though the surviving record is inert. This is what `crash-reporting`
requires of any condition that is routine, expected, and self-healing.

An **enable** that reports **3311** SHALL remain at `Error`, and SHALL be reported as its own outcome
naming the cause rather than collapsing into the generic failure. The two directions have opposite
consequences: a refused disable leaves an inert record and costs nothing, while a refused enable means no
registration exists, the OS never launches the extension, and nothing else reports it. Reporting them
identically would hide the terminal case behind the routine one.

The disable's own return SHALL be used as evidence rather than only as an error check: a disable that
**finds** a record returns `true` with no error, so the write distinguishes "there was a registration" from
"there was not" as a side effect of doing its job — a distinction the read-back cannot reliably make.

Both call sites SHALL go through one helper. `setUploadJobExtensionEnabled` serves both `start()` and
`stop()`, and checking one call but not the other would be a deliberate blind spot. The classification
SHALL remain a decision of the tested `:domain` `model/` classifier, which carries the severity as a
property of the outcome, so the call site renders without branching (`module-architecture`, "Shells are
wiring only").

#### Scenario: Enabling the extension fails
- **WHEN** `setUploadJobExtensionEnabled(true)` returns `false`
- **THEN** the failure is logged at `Error` severity with the error's domain and code, and reaches crash
  reporting as an event

#### Scenario: The fresh-install disable is not a failure
- **WHEN** the leading disable of the ritual runs on a device with no configuration record and returns
  `false` with `PHPhotosError` 3201
- **THEN** the outcome is logged at debug severity and raises no reporting event

#### Scenario: A refused disable under a partial grant is not a failure
- **WHEN** a disable returns `false` with `PHPhotosError` 3311 because the app holds a partial photo grant
- **THEN** the outcome is logged below `Error` severity, raises no reporting event, and still appears in
  the device log and any diagnostic dump

#### Scenario: A refused enable under a partial grant stays an error
- **WHEN** an enable returns `false` with `PHPhotosError` 3311
- **THEN** the outcome is logged at `Error` severity as a distinct outcome whose message names the partial
  grant as the cause, and reaches crash reporting as an event

#### Scenario: A disable that finds a record says so
- **WHEN** the leading disable runs on a device that already holds a configuration record
- **THEN** it returns `true` with no error, and that outcome is recorded as evidence that a registration
  existed

#### Scenario: Both halves go through the same check
- **WHEN** either `start()` or `stop()` changes the registration
- **THEN** the same helper captures the return and the error for both

### Requirement: The OS's own view of the registration is reported as what it reports

Where a diagnostic surface reports whether the upload-job extension is registered, it SHALL report the OS's
answer (`PHPhotoLibrary.isUploadJobExtensionEnabled()`) as **what the OS reports**, and SHALL NOT present it
as what the OS holds.

The read SHALL be **three-valued**, never a bare boolean. `isUploadJobExtensionEnabled` is a 26.1 selector
while the app deploys to a minimum of iOS 18, so an unconditional call traps as an unrecognized selector. It
SHALL be reached only through a path that exists on the OS-driven tier — the same confinement that makes
every other upload-job call safe — and SHALL report a distinct **not-applicable** answer on an OS that has no
such selector, rather than `false`. Reporting `false` there would state "not registered" about an OS on which
registration could never occur.

A `false` answer SHALL carry the qualifier that makes it readable, because the read is **grant-dependent**:
measured on an SE2 (iOS 26.6), the OS reported `false` under `NOT_DETERMINED` photo access for a record that
was live in that same install and had survived a delete-and-reinstall, and `true` for that same record once
access was granted — one install, one variable, minutes apart. So `false` collapses "there is no record" with
"I am not permitted to see one", and a surface reporting it SHALL make that distinguishable.

⏰ Two cells remain unmeasured: `LIMITED` photo access, and a record left by a differently-signed build.

#### Scenario: The read is not attempted below 26.1
- **WHEN** the diagnostic surface is read on a device running iOS 18–26.0
- **THEN** it reports the not-applicable answer, and `isUploadJobExtensionEnabled` is never called

#### Scenario: A false answer without a grant is qualified
- **WHEN** the read returns `false` while photo access is `NOT_DETERMINED`
- **THEN** the surface reports the answer together with the access state, so "no record" and "not permitted
  to see one" are distinguishable rather than collapsed

#### Scenario: The answer is labelled as reported, not held
- **WHEN** the surface presents the OS's answer
- **THEN** it is labelled as what the OS reports, and no consumer treats it as proof that no configuration
  record exists

### Requirement: The registration reports exactly what the platform returned

A registration change SHALL be reported by its classified outcome and by nothing else. No line SHALL claim
that a registration was applied unless that claim is derived from the value the platform returned for that
change.

This exists because the opposite shipped. `start()` logged `background-upload extension re-registered
(disable→enable, cleared REQUESTED)` at `Info` **unconditionally**, milliseconds after the same method's
outcome classification may have reported the enable as failed at `Error` — so a `debug.log` from a device
whose registration had just failed terminally also carried a plain statement that it had succeeded, in the
one capability whose stated failure mode is that *"nothing else will report it"*. Both halves of that line
were already reported by the code that performed them: the enable by its own outcome, the `REQUESTED` clear
by the clear itself.

The remedy SHALL be to remove the unearned claim rather than to make it conditional. The call site is in
`:app:ios`, which the shell gate holds at `CyclomaticComplexMethod` threshold 2, so a branch on the outcome
is a decision it may not hold; the outcome type already carries its own severity and message precisely so
that the shell renders without deciding. A shell that asserts is a shell that decided.

#### Scenario: A failed enable is not followed by a success claim
- **WHEN** the enable half of the ritual reports a failure outcome
- **THEN** the log carries that failure and no statement that the extension was registered

#### Scenario: A successful enable is reported once
- **WHEN** the enable half of the ritual succeeds
- **THEN** the success is stated by the outcome alone, not restated by a second unconditional line

### Requirement: The upload-job subsystem binding is fixed by the compilation target

The **OS upload-job subsystem** SHALL be reached through seams whose implementation is chosen by
**compilation target**, never by a runtime check. That subsystem is the registration record
(`setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`), the job sets (`fetchJobsWithAction`), and
job creation, retry and acknowledgement. `iosArm64` — every shipped binary — SHALL bind the PhotoKit
implementations. A device binary SHALL contain no route to any other binding.

No other PhotoKit surface is covered by this requirement. Asset and resource fetches, the persistent
change-token walk, the selection policy's reads, and album creation and membership SHALL remain the real
platform APIs on every target.

**Forcing proof.** On `iosSimulatorArm64` the subsystem is not merely unscheduled, it is fatal.
Measured 2026-08-26 on iOS 26.5 under a full grant on a clean device, with the extension embedded and
signed: `setUploadJobExtensionEnabled(true)` returns `false` with `PHPhotosErrorDomain:-1` — a code distinct
from `3201`, `3202` and `3311` — and `isUploadJobExtensionEnabled()` then answers `false`. With no
configuration record, `creationRequestForJobWithDestination` raises `NSInvalidArgumentException` from inside
`-[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]` and **terminates the process**; it does
not return an error. Decision record: `PROBE-FINDINGS.md` in this change. A runtime check that could be taken
wrongly would therefore kill the process rather than degrade, which is why the choice is a compilation
target. Because a simulator refuses every provisionable entitlement, ad-hoc signing with the App Group alone
is the only buildable configuration for that target, so the measurement is co-extensive with the target.
**Expiry:** re-measure at the next iOS major, alongside the other PhotoKit platform facts.

The extension's composition root SHALL obtain its `BackgroundTransfer` from the target-bound seam rather
than constructing a named implementation, and SHALL be otherwise identical on every target. No caller SHALL
duplicate the root's port bundle in order to substitute one port: a second assembly of that bundle is a
second composition, and the host that most needs the real one is the host that would be running the copy.
The seam SHALL take the ledger only as a `TransferRecord` (`sync-ledger`).

Resource discovery is not part of the subsystem, and SHALL NOT be reached through either binding: the root
binds the real PhotoKit discovery (`UploadDiscovery`) once, beside the target-bound job queue, identically on
every target. A substituted queue that must recover the live resource the OS would have handed back on a job
object MAY fetch it by identifier; that stand-in for a job field is not discovery, and SHALL NOT be routed
through `UploadDiscovery`.

This does not widen the closed and measured expected-code enumeration in "A failed extension-registration
change is reported, not discarded". A `PHPhotosErrorDomain:-1` reaching a device build remains an
unexpected, terminal failure reported at `Error`.

#### Scenario: A device binary contains no substitute
- **WHEN** the `iosArm64` binary is built
- **THEN** it binds the PhotoKit registration and job-queue implementations, and contains no source for any
  other binding

#### Scenario: A simulator build never reaches job creation
- **WHEN** the upload cycle runs on `iosSimulatorArm64` and the engine issues an upload
- **THEN** job creation is answered by that target's binding, and
  `creationRequestForJobWithDestination` is not called

#### Scenario: Discovery is unaffected by the substitution
- **WHEN** the upload cycle discovers resources on a target whose job queue is substituted
- **THEN** it reads the root-bound PhotoKit change-token walk and the real selection policy, not the
  substituted queue, and the candidates it yields are the platform's own

#### Scenario: One composition serves every target
- **WHEN** the extension root assembles its upload cycle on any target
- **THEN** it builds one port bundle, whose `BackgroundTransfer` is whatever that target's seam yields and
  whose `UploadDiscovery` is the same PhotoKit discovery on every target, and no second assembly of that
  bundle exists anywhere

### Requirement: In-extension discovery by full enumeration

On each `process()` invocation, the extension SHALL discover work itself (the system does not enumerate)
by **enumerating the library** through the shared `UploadDiscovery` binding (`IosDiscovery`):
`PHAsset.fetchAssets` narrowed by the membership's selection policy (capability `photo-selection-policy`).
There SHALL be **no** persisted discovery cursor: no change token is archived, stored, loaded or cleared,
and no `fetchPersistentChanges(since:)` walk is made. Every cycle's walk is complete in itself, so a
short-lived wake needs nothing from the previous one.

The cursor was an efficiency optimization only, and its own contract said so: a cold start with no stored
token re-enumerated the whole library, which the ledger made harmless. It cost a durable App-Group key, a
port with its iOS store and fake, a clear effect threaded through every re-baselining caller, and an
absence protocol that existed only because a change feed reports a deletion once, as an event. A full
enumeration reports what **is**, so presence is recomputed every cycle (capability `sync-ledger`,
"Deletion is a presence diff over an authoritative walk").

A walk yields exactly two kinds of fact that exist nowhere else, both recorded in the ledger before any
upload job is created:

- every admitted resource the engine judged to be new work, recorded `DISCOVERED` in one batch write
  (capability `sync-ledger`);
- the manifest detail of every already-recorded row still lacking it, backfilled.

It also yields a **deletion**, when it is authoritative: the in-window rows of assets it did not return are
deleted before those facts are recorded. The walk reads resources only for assets the ledger does not fully
know (capability `sync-ledger`, "A walk re-reads only the assets the ledger does not fully know"), so its
cost follows the photos that are new or unenriched, not the size of the member's in-window library.

The discovery SHALL report `fullEnumeration` when the library was readable, and SHALL NOT report it when
the read reported the library not readable; in that case it SHALL return no candidates. A cycle over an
unreadable library then costs an idle pass: nothing is recorded and nothing is deleted.

#### Scenario: Every cycle enumerates the in-scope library
- **WHEN** `process()` runs
- **THEN** the extension enumerates the library narrowed by the membership's policy, and reads no persisted
  change token

#### Scenario: A restart needs no stored state from the previous walk
- **WHEN** the extension process is torn down after a cycle and later re-invoked
- **THEN** it enumerates the in-scope library again, and the ledger answers "already uploaded" for keys
  already recorded, so no duplicate job is created

#### Scenario: A cap-truncated cycle loses no discovered work
- **WHEN** a cycle records `DISCOVERED` rows for every admitted new-work resource and then stops creating
  jobs because `creationRequestForJob` raised `PHPhotosErrorLimitExceeded`
- **THEN** the next wake resumes the un-created remainder from the ledger

#### Scenario: An unreadable library costs an idle pass
- **WHEN** the library read reports the library not readable
- **THEN** the discovery returns no candidates and does not report `fullEnumeration`, so the cycle records
  nothing and deletes nothing

### Requirement: The extension withholds its cycle without a full grant

The extension SHALL read the photo grant **in its own process** and pass it to its cycle's entry gate as the
admission answer (`upload-lifecycle`, "The upload cycle owns its entry decision"): it SHALL admit exactly
under `GRANTED` and **withhold** under `LIMITED`, `DENIED` and `NOT_DETERMINED`.

A withheld cycle SHALL acknowledge the terminal jobs the OS presented and record their outcomes — the
acknowledgement obligation whose omission makes the system report `50008`, discard the outstanding jobs and
defer the extension — but SHALL re-create no retry, create no job, run no stranded pass, walk nothing, and
publish **no** device manifest. It SHALL report `SKIPPED` at routine severity.

It SHALL NOT reuse the direction gate's decline, which publishes an **empty** manifest: that is honest for a
direction that permanently excludes upload, and a grant is temporary. Publishing empty on a revoked or
undetermined grant would remove this device's photos from every member's view the moment the grant flipped.

The decision SHALL be made before the membership's selection policy is built, so a `NOT_DETERMINED` grant never
reaches the denylisted-album read that would present the permission dialog.

The grant read SHALL be the same `PHAuthorizationStatus` → `PermissionStatus` mapping the app process uses, held
once in `:adapter:ios:ext-safe` (whose `Photos` import the extension-safety gate allows) and delegated to by the
app-only permission adapter — one mapping, not a copy.

#### Scenario: A downgraded grant withholds the extension's cycle
- **WHEN** the OS invokes the extension while photo access is `LIMITED`
- **THEN** it acknowledges the presented jobs, creates no job, walks nothing, writes no manifest, and reports
  `SKIPPED`

#### Scenario: A withheld cycle does not blank the event union
- **WHEN** the extension withholds on a device whose manifest lists uploaded photos
- **THEN** that manifest is left as it was, so the photos stay visible to every member

#### Scenario: An undetermined grant raises no dialog in the extension
- **WHEN** the OS invokes the extension while photo access is `NOT_DETERMINED`
- **THEN** the cycle withholds before any album structure is read, and no permission prompt is issued

