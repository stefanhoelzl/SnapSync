# ios-photokit-upload — delta for complete-architecture-migration

## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, and the generic HTTP adapters (`:adapter:generic`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery it delegates to (the `IosDiscovery` change-token walk + request builder + token archiver and the `IosDiscoveryStore` cursor store, shared with the `ios-url-session-upload` adapter) and the file-backed `ConfigSource`. The composition root (`UploadExtensionRoot`) and the compile-time host read (`uploadHostFromBundle`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

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

### Requirement: Extension assembles config from the shared config store and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfig` (`eventId`) read through the shared three-state config store —
`:adapter:ios:ext-safe`'s file-backed store over the App-Group config file (capability
`event-link`) — the stable
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
