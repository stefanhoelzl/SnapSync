## Purpose

The **OS-driven upload tier** for iOS ≥26.1: a PhotoKit background-upload app extension that the system
invokes on its own cadence, discovers newly-qualifying photos, drives the shared upload cycle, and lets the
OS perform the uploads — power- and network-aware, across suspension and lock. It exists because photos
must reach the event without the user ever opening the app, and only the OS can schedule that.

The extension is the **sole `LedgerWriter`** on this tier; the app reads the ledger read-only. The
platform-agnostic orchestration deliberately lives in `:domain`'s `feature/upload` zone (which declares a `jvm()` target
so the upload cycle is harness- and JVM-tested); what this capability covers is the iOS side of that seam —
the PhotoKit adapter, the thin Swift pass-through shell, discovery via the persistent change token, job
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


## MODIFIED Requirements

### Requirement: Background upload extension target


On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per capability `testing-architecture` ("Every test runs on every target its module declares"). The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, and the generic HTTP adapters (`:adapter:generic:app`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery it delegates to (the `IosDiscovery` change-token walk + request builder + token archiver and the `IosDiscoveryStore` cursor store, shared with the `ios-url-session-upload` adapter) and the file-backed `ConfigSource`. The **compile-time host read** (`bakedUploadBase`, the `uploadBase` value read from the bundled `Deployment.plist`) SHALL live in `:adapter:ios:ext-safe` beside the build-version read the boot banner uses, for the same two reasons: **both processes** read it (each `NSBundle.mainBundle` being its own bundle), and its absent-key defaulting is a **decision**, which the zero-decision shell gate forbids a wiring-only root to hold — the same reasoning that seated `IosPhotoKitUploadPlatform` there at the migration finale. The composition root (`UploadExtensionRoot`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic:app`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension bundle SHALL carry the generated `Deployment.plist` (capability `deployment-configuration`), whose `uploadBase` is the compile-time edge host the system permits; the extension `Info.plist` SHALL declare no deployment value of its own. The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, its bundle carries `Deployment.plist` with a non-empty `uploadBase`, it links the `:app:ios:extension` framework (which composes `:domain` and the adapter modules), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

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
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic:app`'s
  `HttpEnrollment`


### Requirement: Disabling the extension clears orphaned REQUESTED rows


The app SHALL recover the in-flight jobs wiped by a disable. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension **and this tier runs again afterwards**, it SHALL, immediately after the
disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to the disable half of the
`disable→enable` re-register, and to the leave use-case's extension-disable.

The repair SHALL NOT run when the disable is a **switch to the app-driven tier**. That tier reconciles
stranded `REQUESTED` rows precisely from `getAllTasks` and, by its own contract, "SHALL NOT depend on
`clearRequested`" (`ios-url-session-upload`, "Precise in-flight reconciliation replaces blanket clear"),
so the blanket clear is redundant there **and blunter than the reconciliation that immediately follows
it**: `clearRequested()` is ledger-wide and the discovery cursor is shared, so running it would delete
in-flight rows belonging to the tier about to start and force it into a full re-enumeration it does not
need. The repair belongs to **re-registering** this tier — where no API can enumerate the vanished jobs —
not to every disable.

The disable-and-clear SHALL be **awaited off the main thread and completed before any re-enable**. The
`clearRequested()` write SHALL run on `Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`),
never on the `Dispatchers.Main` scope — it is a synchronous SQLite `DELETE` that on the main thread is
a hang risk under cross-process WAL contention — and SHALL use a small bounded retry around the write.
The `disable→enable` re-register SHALL NOT call `setUploadJobExtensionEnabled(true)` until the clear
has completed, so the re-enabled extension's freshly recorded `REQUESTED` rows can never be deleted by
a still-running clear. The clear SHALL NOT be fire-and-forget. The bounded-retry, off-main clear is
pure logic and SHALL live in a tested `:domain` helper (`feature/upload`) injected into both disable paths,
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

#### Scenario: A switch to the app-driven tier does not run the blanket repair

- **WHEN** the extension is disabled as part of a switch to the app-driven tier (a mechanism override, or
  a downgrade to a limited grant) while `REQUESTED` rows exist
- **THEN** the extension is deregistered, `clearRequested()` and the cursor reset do **not** run, and the
  app-driven tier's own `getAllTasks` reconciliation surfaces each stranded row as terminal `FAILED` so it
  is recreated — leaving rows whose transfers are still live untouched

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes


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

Everything else the root does today — the membership read's decision, the leave-side reconciliation, the
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
  reconciles

#### Scenario: A drained cycle with pending jobs still asks for re-invocation
- **WHEN** the cycle would otherwise report completed and the ledger still holds pending rows
- **THEN** the extension surfaces processing instead, unchanged


### Requirement: Persisted change-token cursor


The discovery cursor SHALL be persisted in the shared App-Group store written by the extension. The
extension SHALL archive the `PHPersistentChangeToken` (via its `NSSecureCoding` support) to `Data`
and store it in App-Group `NSUserDefaults` (suite `group.app.snapsync`), reading it back at cycle
start. The cursor's load/advance orchestration SHALL be platform-free (a `commonMain` port over
opaque token bytes) so it is exercised on the simulator with a fake; the `NSUserDefaults` archiving
is a platform detail of the `:adapter:ios:ext-safe` cursor store (`IosDiscoveryStore`), not shell
wiring. Persistence is an efficiency optimization only: a cold start with no
stored token re-enumerates the whole library, which the ledger makes harmless.

#### Scenario: Token round-trips through the App-Group store
- **WHEN** the extension advances the cursor at the end of a fully-drained cycle
- **THEN** the archived token bytes are written to App-Group `NSUserDefaults` and a subsequent
  process reads them back and resumes `fetchPersistentChanges(since:)` from that token

#### Scenario: Missing token falls back to full enumeration
- **WHEN** `process()` runs with no token in the App-Group store
- **THEN** the extension enumerates the whole library and the ledger skips already-recorded keys
