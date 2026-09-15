## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), the library-read seam (`UploadDiscovery`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per capability `testing-architecture` ("Every test runs on every target its module declares"). The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, the PhotoKit discovery (`IosDiscovery`, bound once as the `UploadDiscovery`), and the generic HTTP adapters (`:adapter:generic:app`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery the roots bind (the `IosDiscovery` change-token walk + token archiver, implementing `UploadDiscovery`), the shared upload-request builder, and the `IosDiscoveryStore` cursor store, all shared with the `ios-url-session-upload` adapter, and the file-backed `ConfigSource`. The **compile-time host read** (`bakedUploadBase`, the `uploadBase` value read from the bundled `Deployment.plist`) SHALL live in `:adapter:ios:ext-safe` beside the build-version read the boot banner uses, for the same two reasons: **both processes** read it (each `NSBundle.mainBundle` being its own bundle), and its absent-key defaulting is a **decision**, which the zero-decision shell gate forbids a wiring-only root to hold — the same reasoning that seated `IosPhotoKitUploadPlatform` there at the migration finale. The composition root (`UploadExtensionRoot`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic:app`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension bundle SHALL carry the generated `Deployment.plist` (capability `deployment-configuration`), whose `uploadBase` is the compile-time edge host the app and the extension **read** when they build upload requests. The extension `Info.plist` SHALL **additionally** declare `BackgroundUploadURLBase`, carrying that same base URL: it is read not by this app but by **`assetsd`**, which validates the registration insert against the value in the bundle's own `Info.plist` and can see no resource the app bundles. With the key absent, `setUploadJobExtensionEnabled(true)` SHALL be expected to fail with a bare `PHPhotosErrorDomain -1` and empty `userInfo`, the OS never launches the extension, and nothing uploads on this tier. Because an `Info.plist` substitution can only read a build setting and `//` opens a comment anywhere on an xcconfig line with no escape, the value SHALL be **composed** in the `Info.plist` from build settings that cannot themselves contain `//` — a scheme enum and a bare host — rather than carried as one URL-valued build setting. The app bundle SHALL carry the key on the same terms: the registration call is made by the app process, and which bundle the daemon reads has not been established. What IS established is a device A/B (SE2, iOS 26.6, 2026-08-28, one variable): key absent → enable fails `-1`, disable fails `3201`; key present as `https://<domain>/api/v1` → both succeed and the read-back is `true`. The daemon's **matching rule** — whether it compares host, origin or prefix — is NOT established, and this spec SHALL NOT assert one. ⏰ Re-measure at the next iOS major, with the other PhotoKit platform facts. The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

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
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscovery`, `IosDiscoveryStore`) implement the upload seams — `IosDiscovery` shared with the app-driven tier from `:adapter:ios:ext-safe` and bound once as the `UploadDiscovery` — and the root supplies them as `UploadPorts` to `uploadCore`, which constructs the `:domain` `feature/upload` `UploadCycle`, with the download-store / rejoin / manifest edges answered in the ports bundle rather than inside the feature

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same shared `:domain` orchestration assembled by the same `uploadCore`

#### Scenario: The extension's cycle is the shared composition
- **WHEN** `UploadExtensionRoot` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no cycle, gate, reconciler, or
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
Version/attempt come from the
ledger; the `resource`, when still present, is reused
only to re-create a
retry-spent job. **Every presented job SHALL be acknowledged** — including one whose row is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `FAILED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED` at the incremented attempt). The system job `retryWithDestination(:)` is applied to SHALL be
  found by the **same route** the job's key was recovered by — its destination path against the recorded
  `destinationPath`, then the v1 last-segment fallback — and SHALL NOT be found by comparing the destination's
  last path segment to the ledger key, which under the v2 route is the resource's role and matches no key.
  A retry whose system job is no longer in the `.retry` set SHALL be logged and SHALL NOT be silent.
- **`fetchJobsWithAction(.acknowledge)` (terminal):** the adapter SHALL record the outcome into the ledger
  itself, through the guarded `markTerminal` of its `TransferRecord` (`sync-ledger`), and acknowledge the job
  **in place** — `state == Succeeded` → record `COMPLETED`, then acknowledge; a key already in a terminal
  state → acknowledge (the guard applies to nothing, an idempotent no-op); otherwise (a retry-spent
  `Failed`/`Cancelled` job) → record `FAILED`, then acknowledge. The job SHALL be acknowledged **regardless** of
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
  reports `UploadStarted` so the ledger holds `REQUESTED` at the incremented attempt

#### Scenario: A v2-route retry reaches its system job
- **WHEN** a job is returned in the `.retry` set whose destination is the v2 byte route, so its last path
  segment is the resource's role
- **THEN** the system job whose destination path resolves to that key is the one `retryWithDestination(:)`
  is applied to, and no "no live retry job" line is logged for it

#### Scenario: Retry-spent failure re-creates from the job's resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent) and its
  `resource` is still available
- **THEN** the extension records that row `FAILED`, acknowledges the job, and returns it from the drain so
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
