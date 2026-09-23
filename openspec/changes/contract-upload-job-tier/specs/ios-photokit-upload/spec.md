## MODIFIED Requirements

### Requirement: Completion and retry adjudication

The extension SHALL adjudicate the system's returned upload jobs each cycle, **before** discovering
new work (so completed/failed slots are freed first). It SHALL recover a returned
`PHAssetResourceUploadJob`'s ledger row by matching the job's **destination URL path** against the
`destinationPath` recorded for that row when the job was created (capability `sync-ledger`), read through the
`TransferRecord` the adapter is given. The destination is the only field reliably present for every job
state, since `resource` is **nil for succeeded jobs** (the system releases it after upload) — but under the v2
byte route its last path segment is the resource's **role**, not the ledger key, so the key SHALL NOT be read
from it.

The recorded destination path SHALL be the **only** route from a job to its row. There SHALL be no fallback
that reads a key out of the destination: the v1 last-segment recovery, which served only jobs created before
the v2 byte route shipped, is retired (decision record `changes/retire-legacy-key-fallback`). A row written
before `destinationPath` existed therefore cannot be resolved from a job.

A job whose destination has a recognised byte-route shape but whose **row is gone** SHALL be treated as
**pruned**, not unrecoverable. The walk deletes a departed or de-selected asset's rows whatever their state
(capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk"), so a late job for one
is expected. A pruned job SHALL be acknowledged in place in whichever set presented it (`.retry` or
`.acknowledge`), SHALL write nothing, SHALL NOT be emitted to the cycle, and SHALL be logged at `Info`
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). Reporting a
pruned job at `Error` would raise a crash-reporting event for every photo deleted or de-selected mid-upload.
The recognised byte-route shape SHALL be the v2 shape `/files/devices/<deviceId>/<assetId>/<role>` only.

A job whose destination cannot be mapped to any known shape SHALL be **counted and reported at `Error`
severity**, naming how many such jobs a cycle saw. It SHALL NOT be silently drained: an unmappable job means
an upload whose outcome is being discarded for a reason this build does not understand. A job with a **v1**
destination (`/files/devices/<deviceId>/<key>`) SHALL be treated as unmappable, not pruned: its row may still
exist and be `REQUESTED`, which a quiet prune would hide.

It SHALL likewise recover the job's **content type** from that same destination's `Content-Type` header
(matched case-insensitively, a blank value treated as absent), falling back to the `resource`'s uniform
type identifier and then to `application/octet-stream`. Deriving the content type from `resource` alone is
silently wrong for the same reason the key is not taken from it: a succeeded job has none, so a retried
upload rebuilt its request as `application/octet-stream` and every object that had ever failed once was
stored with that type. That the destination's headers survive the system's job store — not merely its URL —
is measured on device (SE2 / iOS 26.6), on both the `.retry` and `.acknowledge` sets; re-measure if the tier
moves to the iOS 27 `PHBackgroundResourceUploadJobExtension`.
A retry-spent job's live resource is used
only to re-create it. The OS answers **no** `resource` for a retry-spent job (measured inside the extension, SE2,
iOS 26.6.2, 2026-09-23), so the adapter SHALL fetch the live resource by identifier — the photo whose `assetId` the
key names, its resource of the key's role — and SHALL treat a photo that has left the library as no live resource. **Every presented job SHALL be acknowledged** — including one whose row is
unrecoverable — or the system reports `appex failed to acknowledge jobs for processing state`
(error 50008). The two phases:

- **`fetchJobsWithAction(.retry)` (first failures):** map `job.error` → `UploadError`, report
  `UploadFailed` (engine records `DISCOVERED`, answers `Retry` with a rebuilt edge URL — stable, no
  expiry, nothing to re-mint), call `retryWithDestination(:)`, then report `UploadStarted` (records
  `REQUESTED`). The system job `retryWithDestination(:)` is applied to SHALL be
  found by the **same route** the job's key was recovered by — its destination path against the recorded
  `destinationPath` — and SHALL NOT be found by comparing the destination's
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

Only **retry-spent failures whose photo is still in the library and whose row exists** SHALL be returned
from the drain, so the cycle can re-create them in the same cycle from a live resource. No succeeded job, no
terminal fact and no pruned job SHALL cross the port.

When the extension reconstructs a resource for a returned job, it SHALL derive the resource `assetId` from
the recovered key via the **shared** `assetIdFromUploadKey` parser (the exact inverse of `uploadKey`; see
`gallery-status`) — never a placeholder such as an empty string. It SHALL record a terminal state only for a
job whose row exists, and SHALL NOT write any row for a pruned job, phantom or otherwise.

#### Scenario: A returned job is matched by its destination path
- **WHEN** a job in the `.acknowledge` set carries a destination whose path equals the `destinationPath`
  recorded for a ledger row
- **THEN** that row is the job's row, whatever the destination's last path segment happens to be

#### Scenario: The role token is never mistaken for the key
- **WHEN** a job's destination is the v2 byte route, whose last path segment is the resource's role
- **THEN** the extension does not treat that segment as a ledger key, and no row keyed `primary` or `live`
  is ever written

#### Scenario: A v1-shaped job is unmappable, not resolved
- **WHEN** a job in either set carries a v1 destination `/files/devices/<deviceId>/<key>`, whether or not a row
  keyed by its last path segment exists
- **THEN** no key is read from its path, nothing is written, it is not returned to the cycle, it is
  acknowledged, and it is counted in the cycle's `Error`-severity unmappable report

#### Scenario: An unmappable job is reported, not drained silently
- **WHEN** a cycle presents one or more jobs whose destinations match no known byte-route shape
- **THEN** the count is reported at `Error` severity, and every such job is still acknowledged

#### Scenario: A job for a pruned row is acknowledged quietly
- **WHEN** a job in either set carries a v2 destination whose row the walk deleted
- **THEN** it is acknowledged in place, nothing is written, it is not returned to the cycle, it is logged at
  `Info`, and no `Error` is reported

#### Scenario: A first failure for a pruned row is not retried
- **WHEN** a job in the `.retry` set belongs to a row the walk deleted
- **THEN** it is acknowledged, not handed to the cycle, and `retryWithDestination(:)` is not called

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

#### Scenario: Retry-spent failure re-creates from the photo's live resource
- **WHEN** a `Failed` job appears in the `.acknowledge` set (its one system retry is spent), with no `resource`
  as the OS answers it, and its photo is still in the library
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

#### Scenario: A pruned-row completion writes nothing
- **WHEN** a succeeded or retry-spent job is presented but its ledger row was already pruned (no entry)
- **THEN** no row is written, phantom or otherwise, the job is acknowledged, and nothing is returned for
  re-creation

#### Scenario: A reconstructed resource derives assetId from the key
- **WHEN** a returned job's resource is reconstructed for a re-create
- **THEN** it carries the `assetId` parsed from the recovered key by `assetIdFromUploadKey`

#### Scenario: A retry-spent failure whose photo left is not re-created

- **WHEN** a retry-spent `Failed` job is presented and no photo in the library has the `assetId` its key names
- **THEN** the extension records the row `DISCOVERED`, acknowledges the job, and returns nothing for re-creation

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), the library-read seam (`UploadDiscovery`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per capability `testing-architecture` ("Every test runs on every target its module declares"). The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, the PhotoKit discovery (`IosDiscovery`, bound once as the `UploadDiscovery`), and the generic HTTP adapters (`:adapter:generic:app`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery the roots bind (the `IosDiscovery` full-enumeration walk, implementing `UploadDiscovery`) and the shared upload-request builder, both shared with the `ios-url-session-upload` adapter, and the file-backed `ConfigSource`. The **compile-time host read** (`bakedUploadBase`, the `uploadBase` value read from the bundled `Deployment.plist`) SHALL live in `:adapter:ios:ext-safe` beside the build-version read the boot banner uses, for the same two reasons: **both processes** read it (each `NSBundle.mainBundle` being its own bundle), and its absent-key defaulting is a **decision**, which the zero-decision shell gate forbids a wiring-only root to hold — the same reasoning that seated `IosPhotoKitUploadPlatform` there at the migration finale. The composition root (`UploadExtensionRoot`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic:app`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension bundle SHALL carry the generated `Deployment.plist` (capability `deployment-configuration`), whose `uploadBase` is the compile-time edge host the app and the extension **read** when they build upload requests. The extension `Info.plist` SHALL **additionally** declare `BackgroundUploadURLBase`, carrying that same base URL: it is read not by this app but by **`assetsd`**, which validates the registration insert against the value in the bundle's own `Info.plist` and can see no resource the app bundles. With the key absent, `setUploadJobExtensionEnabled(true)` SHALL be expected to fail with a bare `PHPhotosErrorDomain -1` and empty `userInfo`, the OS never launches the extension, and nothing uploads on this tier. Because an `Info.plist` substitution can only read a build setting and `//` opens a comment anywhere on an xcconfig line with no escape, the value SHALL be **composed** in the `Info.plist` from build settings that cannot themselves contain `//` — a scheme enum and a bare host — rather than carried as one URL-valued build setting. The app bundle SHALL carry the key on the same terms: the registration call is made by the app process, and which bundle the daemon reads has not been established. What IS established is a device A/B (SE2, iOS 26.6, 2026-08-28, one variable): key absent → enable fails `-1`, disable fails `3201`; key present as `https://<domain>/api/v1` → both succeed and the read-back is `true`. The daemon's **matching rule** — whether it compares host, origin or prefix — is NOT established, and this spec SHALL NOT assert one. ⏰ Re-measure at the next iOS major, with the other PhotoKit platform facts. The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and every upload host MUST be a valid HTTPS endpoint **except a loopback IP literal**, which default ATS exempts and the resolver already renders as `http` (capability `deployment-configuration`). Measured on device (SE2, iOS 26.6, 2026-09-23): a registration against `http://127.0.0.1:<port>/api/v2` succeeds, and assetsd delivers each job's bytes to that address in plaintext. Only a build selecting a loopback deployment — the local deployment and the rig build's contract runs — bakes one; supplying any other non-HTTPS host is a build/configuration error, and iOS blocks the plaintext request at the platform level.

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

#### Scenario: A loopback upload base

- **WHEN** a build bakes `http://127.0.0.1:<port>/api/v2` as its upload base
- **THEN** the extension registers against it and the operating system uploads to it without any ATS
  exception, and no non-loopback plaintext host is admitted

## ADDED Requirements

### Requirement: How the operating system invokes the extension is recorded as measured

The capability SHALL record, with its evidence and an expiry trigger (re-measure at the next iOS major), how
the operating system invokes the upload extension, because no clause can observe it — a clause runs only
inside a call — and every scheduling decision in this tier rests on it. Measured on an SE2, iOS 26.6,
2026-09-23:

- a `process()` call runs for about **60 s** and is then killed, **without** `notifyTermination`;
- `notifyTermination` arrives about 55 ms after every **normal** return, so it marks the end of a cycle, not a
  kill, and SHALL NOT be reported as one;
- after `process()` returns `PROCESSING`, the next call comes **5 min** later;
- after a killed call the system backs off (the next calls came about 6 and then 11 min later), and neither a
  library change nor a re-registration triggers a call meanwhile;
- outside a backoff, enabling the registration triggers a call within about 1 s and a new photo within about
  3 s; a job finishing triggers none;
- a job the extension creates inside `process()` is uploaded only after that call returns; when the call returns
  `PROCESSING` after creating jobs, the next call comes within about a second, with the uploads done in between
  (the five-minute cadence above was measured after calls that created nothing);
- a retry-spent job is presented with no `resource`;
- job states settle within 0.1–5 s of creation or retry; a failed job is presented in **both** the retry and
  the acknowledge set with no error, a retry-spent one in the acknowledge set only, and acknowledging a job
  removes it from both.

#### Scenario: A cycle returns normally

- **WHEN** `process()` returns and the system then calls `notifyTermination`
- **THEN** the extension records the end of a cycle at `Info`, and does not report a termination

#### Scenario: A cycle overruns the budget

- **WHEN** a `process()` call runs past about 60 s
- **THEN** the process is killed with no notice, and the next call comes only after a backoff, so work that
  must finish in one call is sized well inside the budget

