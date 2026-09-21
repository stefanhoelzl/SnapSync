## ADDED Requirements

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

## MODIFIED Requirements

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
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscovery`) implement the upload seams — `IosDiscovery` shared with the app-driven tier from `:adapter:ios:ext-safe` and bound once as the `UploadDiscovery` — and the root supplies them as `UploadPorts` to `uploadCore`, which constructs the `:domain` `feature/upload` `UploadCycle`, with the download-store / rejoin / manifest edges answered in the ports bundle rather than inside the feature

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

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

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
- **THEN** the rows are demoted to `FAILED` **before** the enable is attempted, so the repair cannot demote
  rows belonging to the registration it is about to re-create

#### Scenario: Stopping is the disable alone
- **WHEN** the OS-driven mechanism's `stop()` runs — on a leave, or to relinquish it to the app-driven one
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and no ledger
  row is touched; the next mechanism start repairs any row left `REQUESTED`

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

On this tier the re-provision's `start()` SHALL re-register the extension (the disable→enable toggle).
On its next cycle the extension reconciles against the per-device file listing (capability
`api-endpoints`, see `upload-state-reconciliation`): it **`resetTo`s** (atomic clear-and-seed)
the ledger to one already-uploaded row per stored file. The device-global listing re-seeds the same files
as already-uploaded, so **nothing already stored re-uploads**, while the clear drops stale/phantom rows and
the cycle's walk, a full enumeration like every walk, finds genuinely-unstored work. The re-baselined ledger
is then **re-projected** to the
**new** event's `device.json` path, and the joined-event marker is set. Rows seeded from the listing are
**bare** (a filename carries no capture date) and are therefore not listed until the walk backfills their
manifest detail; a bare row is always re-read by the walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know"). The app decodes the event link only to gate this on a
valid payload; the authoritative decode/validate/persist still happens in the shared container intent.

The re-provision itself SHALL NOT clear the **ledger** (`upload-lifecycle`): only the reconciliation's
`resetTo` re-baselines it, from the authoritative per-device listing. The re-register's repair demotes rows
instead (see "Re-registering the extension demotes orphaned REQUESTED rows").

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing and re-projects `device.json` from
  that ledger to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `api-endpoints`)
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger and the joined-event marker are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the app-driven producer's `start()` runs instead

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
- **WHEN** an asset deleted before its upload completed leaves a `DISCOVERED` or `FAILED` row
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
rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never re-issues it, and a
same-event cycle never reconciles — so the photos that were mid-upload are permanently abandoned.

The recovery SHALL run in this mechanism's **`start()`** — the disable→enable re-register — **between** the
disable and the enable, and SHALL be the ledger's reset-family `demoteRequested()` (`sync-ledger`): every
`REQUESTED` row becomes `FAILED`. Every one of them is unsettleable at that moment: the disable has just wiped
this tier's jobs, and wherever the app-driven mechanism also exists, starting this mechanism is preceded by the
app-driven mechanism's `stop()` (`upload-lifecycle`, "The upload mechanism is resolved, never selected"), so
no app-driven transfer is carrying a row either.

A `FAILED` row needs a job, so the ledger's work read returns it on the next cycle without any walk
re-deriving it. The former recovery *deleted* the rows, which only a walk that re-read the asset's
resources could re-surface; a demoted row needs no such walk.

This mechanism's **`stop()`** SHALL be the disable alone and SHALL repair nothing — on a leave and on a
relinquish to the app-driven mechanism alike. On a leave nothing uploads until a mechanism starts again, and
that start repairs; on a relinquish, the app-driven mechanism's own start repairs (`ios-url-session-upload`,
"Stranded reconciliation: scoped each cycle, complete at a start"). There SHALL therefore be no narrower
teardown verb for a hand-off.

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
- **THEN** the disable wipes the OS jobs and `demoteRequested()` marks the rows `FAILED`, so the next
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

#### Scenario: A stop repairs nothing

- **WHEN** the extension is disabled by this mechanism's `stop()` — a leave, or a relinquish to the app-driven
  mechanism — while `REQUESTED` rows exist
- **THEN** no ledger row changes, and the rows are demoted by the next mechanism start

#### Scenario: Completed rows survive the repair

- **WHEN** a re-register triggers `demoteRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are unchanged, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes

## REMOVED Requirements

### Requirement: In-extension discovery via persistent change token
**Reason**: The persisted change-token cursor is removed; every walk is a full enumeration, and deletion is a presence diff over it.

**Migration**: Replaced by "In-extension discovery by full enumeration" (this change) and capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk".

### Requirement: Persisted change-token cursor
**Reason**: There is no discovery cursor to persist: `DiscoveryStore`, `IosDiscoveryStore` and the App-Group `discovery.changeToken` key are removed.

**Migration**: None needed. A stale App-Group value is left unread; a build that still reads it resumes from it or falls back to a full enumeration, both harmless because dedup lives in the ledger.
