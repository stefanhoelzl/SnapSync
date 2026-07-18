# architecture-guards Specification

## Purpose

Executable enforcement for the structural invariants the Kotlin compiler cannot express and the module
graph cannot withhold — the rules that hold across modules, source sets, and build files, and that no
type signature can state.

It exists because such an invariant, held only by convention and review, silently shipped a crash. Two
properties govern whether SnapSync's background tiers work at all on a **locked** device — the state
they run against is normally locked, since the OS wakes them while the phone is idle:

- **Every Keychain item the app persists must be readable by background work on a locked device**
  (capability `device-identity`, capability `event-link`). Keychain accessibility is chosen
  per-item at the call site, so this is a property of *every* call site — provable only if there is
  exactly one module that may contain them.
- **The App-Group default data-protection class must never be raised.** The background tier's SQL
  ledger, download store, discovery cursor, and event-album map are readable while locked only because
  they inherit the iOS default. Raising it in an entitlements file is a one-line edit that reads as a
  security improvement and disables background sync entirely — silently, and only on locked devices.

Neither is expressible in a type, and neither failed loudly: the first shipped as a lock-time crash on
a real device, the second is a latent trap. This capability turns their structural half into ordinary
tests in a test-only module (`:test:architecture`) that gate `./gradlew build`, so a violation fails the
build for everyone instead of resting on a reviewer noticing. The guards are the containment half of
each invariant; the behavioral half (that the confined module's every query carries the required
accessibility class) is pinned by that module's own tests. Both halves together are what make the
property true of the whole app.

Decision record: `changes/archive/2026-07-14-fix-locked-device-keychain-access`. Runtime-identity
pins and the pending zone gates: `changes/archive/2026-07-17-pin-runtime-identity-and-zone-gates`.
## Requirements
### Requirement: Architecture guards are executable and gate the build

The project SHALL enforce, mechanically, structural invariants that the compiler cannot express. The
guards SHALL live in a test-only module (`:test:architecture`) and SHALL run as part of the canonical
check (`./gradlew build`), so a violation fails the build locally and in CI rather than relying on
review.

A guard SHALL fail loudly rather than vacuously: a guard that analyses source SHALL assert that the
source it scanned is non-empty, so that a parser or configuration regression cannot cause the guard to
pass by inspecting nothing.

#### Scenario: A violation fails the build
- **WHEN** the canonical check runs against a tree that violates a guarded invariant
- **THEN** the build fails and names the violating file

#### Scenario: A guard that scans nothing fails
- **WHEN** a source-scanning guard resolves an empty scope (e.g. its parser rejected the sources)
- **THEN** the guard fails rather than reporting success

### Requirement: Keychain access is confined to one module

All Keychain access SHALL be confined to a single module (`:adapter:ios:ext-safe` — the
extension-safe adapter module, where the migration seated the Keychain impls; before migration
step 4 the owning module was `:domain:keychain`, which retains only the platform-free
`ProtectedData` seam). No other module SHALL reference the Keychain API (`SecItem*`, `kSecClass*`,
`kSecAttr*`), whether by import **or** by fully-qualified reference.

This containment is what makes the *"every Keychain item is readable by background work on a locked
device"* invariant provable rather than merely intended: it is the containment plus that module's own
test — that every query it builds carries the required accessibility class — which together establish
the property for every item in the app. Neither half is sufficient alone. Containment cannot be enforced
by the dependency graph the way Material 3 containment is (capability `design-system`), because the
Kotlin/Native platform libraries are ambient in every `iosMain` source set and there is no dependency to
withhold.

#### Scenario: A Keychain call outside the owning module fails the build
- **WHEN** any module other than `:adapter:ios:ext-safe` references `SecItemAdd`, `SecItemCopyMatching`,
  `SecItemUpdate`, `SecItemDelete`, or a `kSecClass`/`kSecAttr` constant
- **THEN** the guard fails the build

#### Scenario: A fully-qualified call is caught
- **WHEN** a module calls the Keychain API by fully-qualified reference and therefore imports nothing
- **THEN** the guard still fails the build

#### Scenario: The owning module is exempt
- **WHEN** `:adapter:ios:ext-safe` itself uses the Keychain API
- **THEN** the guard passes

### Requirement: The data-protection entitlement never raises the default protection class

Neither target's entitlements (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`) SHALL
set `com.apple.developer.default-data-protection` to `NSFileProtectionComplete`.

The background tier depends on the iOS default protection class for App-Group container files,
`NSFileProtectionCompleteUntilFirstUserAuthentication` — the guarantee that makes the SQL ledger, the
download store, the discovery cursor, and the event-album map readable while the device is locked.
Raising the default to `NSFileProtectionComplete` would make **every** file in both containers unreadable
while locked, disabling background upload and download entirely, silently, and only on locked devices —
while presenting as a security improvement.

#### Scenario: Raising the default protection class fails the build
- **WHEN** either entitlements file sets `com.apple.developer.default-data-protection` to
  `NSFileProtectionComplete`
- **THEN** the guard fails the build

#### Scenario: The current entitlements pass
- **WHEN** neither entitlements file sets the key at all, so both containers inherit the iOS default
- **THEN** the guard passes

### Requirement: The event-link domain agrees across the app and the backend

A test-only JVM guard SHALL assert that the event link's domain agrees across every place it appears:
the app's `applinks:` associated-domains entitlement, the app's `LINK_ORIGIN` constant, the Apple App
Site Association document the backend serves, and the backend's own domain constant (capability
`event-link`). No compiler and no module boundary can hold those four together.

Two of the four SHALL be **single-sourced** rather than merely guarded: `LINK_ORIGIN` SHALL be generated
from one Gradle property, and the entitlement's value SHALL be supplied from `Config.xcconfig`. The
backend's copy **cannot** be: `backend/` is a Deno tree deployed by a separate, path-scoped workflow that
ships code only and never config (capability `backend-deployment`), so nothing in the Gradle build can
reach it, and generating it would couple two deliberately independent pipelines. The guard therefore
exists to hold exactly the seam that single-sourcing cannot close.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

The guard SHALL fail loudly rather than vacuously: if a file it inspects has moved or been renamed, it
SHALL fail rather than silently scanning nothing.

#### Scenario: A drifted domain fails the build

- **WHEN** any one of the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's
  domain, or the backend's domain constant names a different host than the others
- **THEN** the guard test fails, naming the disagreeing values

#### Scenario: The guard is not vacuous

- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing domains pass

- **WHEN** all four locations name the same host
- **THEN** the guard passes

### Requirement: The Swift shell keeps the event link's delivery seam

A test-only JVM guard SHALL assert that the iOS Swift shell still installs a **scene delegate** that
handles **both** halves of universal-link delivery and forwards to `SnapSyncRoot.onOpenUrl(_:)`
(capability `ios-app-shell`). Specifically it SHALL assert that the shell:

1. installs a scene delegate via the app delegate's `application(_:configurationForConnecting:options:)`
   (setting `delegateClass`) — without this the delegate is inert;
2. implements `scene(_:willConnectTo:options:)` — the **cold** half, reading the launching link from the
   connection options;
3. implements `scene(_:continue:)` — the **warm** half; and
4. forwards to `SnapSyncRoot.onOpenUrl` from that delegate.

The guard SHALL fail loudly rather than vacuously: if the file it inspects has moved or no longer
contains the markers it expects, it SHALL fail rather than pass while scanning nothing. The guarded Swift
sources SHALL be declared as inputs of the guard's test task, or the guard silently stops re-running when
its subject changes — a guard that goes stale is a guard that fails open.

This is the first guard over Swift. `:app:ios` and the Swift shell are wiring-only and **untested** by the
project's hard rule, and on 2026-07-16 that rule's blind spot shipped: the app received event links via
SwiftUI's `onOpenURL`, which never fires for a universal link, so **every invite silently did nothing**
while every automated check stayed green. The guard does not test behavior — the seam remains
device-verified — it pins the **structure** that behavior depends on, which is exactly what this
capability exists for.

The guard is a **regression guard, not a discovery guard**, and SHALL be understood as such: it could not
have caught the original defect, because nobody knew `scene(_:willConnectTo:options:)` was the answer
until it was measured on a device. What it catches is the realistic future: a reader sees a UIKit scene
delegate in a SwiftUI app, concludes it is legacy cruft that `.onOpenURL` supersedes, deletes it — and
every event link dies silently, with CI green. That is the same species as *the data-protection
entitlement never raises the default protection class*: a small edit that reads as an improvement and
disables a whole feature invisibly.

Because the failure is invisible, the guard's **failure message** SHALL carry the evidence — that
`.onOpenURL` and `.onContinueUserActivity` and `application(_:continue:restorationHandler:)` were each
tried on device and are each insufficient — since that message is the only thing standing between the
next reader and re-introducing the bug.

#### Scenario: Removing the scene delegate fails the build

- **WHEN** the Swift shell no longer installs a scene delegate, or no longer implements
  `scene(_:willConnectTo:options:)` or `scene(_:continue:)`, or no longer forwards to `onOpenUrl`
- **THEN** the guard test fails, naming what is missing and why it matters

#### Scenario: The guard is not vacuous

- **WHEN** the Swift file the guard inspects is absent, renamed, or no longer contains the markers it
  expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when the Swift shell changes

- **WHEN** only the iOS Swift shell is edited and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

#### Scenario: An intact shell passes

- **WHEN** the shell installs the scene delegate and implements both halves, forwarding to `onOpenUrl`
- **THEN** the guard passes

### Requirement: Gates fail closed on novelty
Every architecture gate SHALL derive its scope from the repository's structure at test runtime —
directory listings for feature enumeration, package patterns for zones, "everything not
allowlisted" for purity — never from a hand-maintained inclusion list. The only permitted lists
are loud-when-stale: the end-state module list (compared against the module-graph generator's
output) and the per-zone library allowlists. Every gate SHALL keep a non-vacuity twin proving it
scanned a non-empty scope. Zone gates SHALL match source text (fully-qualified references import
nothing), not import lists.

#### Scenario: New code is born in scope
- **WHEN** a new feature package, flow file, port, or adapter is added
- **THEN** every applicable gate covers it with zero gate edits

#### Scenario: A gate's scope silently empties
- **WHEN** a rename or restructure removes everything a gate scans
- **THEN** the gate's non-vacuity twin fails rather than the gate passing forever

### Requirement: The zone gates
The build SHALL enforce, over source text with derived scopes: `model/` references nothing
project-internal outside `model/`; `ports/` references only `model/`; features reference only
`model/` and `ports/` and never a sibling feature (pairwise, features enumerated from the
directory); `flow/` references only `model/` and `feature/`; `:ui:presentation` references only
the injected flow command bundle, feature read-model types, and `model/`; `:domain` and `:ui`
zones import only their per-zone allowlisted libraries; `:domain` has no `iosMain` source
directory and declares no `project()` dependency.

#### Scenario: A fully-qualified sidestep
- **WHEN** a file references a forbidden declaration by fully-qualified name without an import
- **THEN** the text gate fails exactly as it would for an import

### Requirement: The extension-safety text gate
Because Kotlin/Native does not enforce `NS_EXTENSION_UNAVAILABLE`, the build SHALL fail when any
source under `:adapter:ios:ext-safe` or `:app:ios:extension` references `platform.UIKit` or
`platform.BackgroundTasks`. The module split prevents cross-module leaks; this gate covers
in-module ones.

#### Scenario: App-only API inside extension-linked code
- **WHEN** an ext-safe adapter gains a `platform.UIKit` reference
- **THEN** the gate fails before any device build, naming the file

### Requirement: The shell gates
The build SHALL enforce zero conditionals in `:app:*` Kotlin via a detekt complexity gate
(threshold: no function above cyclomatic complexity 1 beyond pinned wiring forms), asserted by a
test with a non-vacuity floor, over all `:app:*` source sets including `iosMain`. The Swift
shells SHALL be guarded by a pinned-structure text check: decision keywords (`if`, `guard`,
`switch`, `??`) may appear only at the explicitly pinned occurrences, each pin carrying its
forcing proof in the failure message.

#### Scenario: A decision creeps into a shell
- **WHEN** a branch is added to `:app:*` Kotlin or an unpinned decision keyword to a Swift shell
- **THEN** the respective gate fails and the message names the tested zone the decision belongs in

### Requirement: The fake-honesty gate
Every public type in `:adapter:fake` SHALL expose only members of the port interfaces it
implements plus a constructor taking initial state — no public mutable properties, no non-port
public functions. Operator rigging lives in `:test:world` wrappers, never in fakes.

#### Scenario: A lever lands in a fake
- **WHEN** a fake gains a public `var` or a public function outside its port contract
- **THEN** the gate fails; the lever moves to a world wrapper

### Requirement: Runtime identity is pinned

`:test:architecture` SHALL pin every runtime-identity literal — a string the OS or the installed
base holds on its side, so that changing it strands or corrupts state on devices already in the
field. Each pin SHALL assert the literal appears **exactly once** in production Kotlin source
(main source sets; test sources and `build/` excluded) with its exact value, plus its pinned
occurrences in the non-Kotlin surfaces named below. Any delta — a disappearance, a value change,
or a new occurrence — SHALL fail the build.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing a pin
is a spec change to this requirement, deliberately):

- **App-Group id** `group.app.snapsync` — once in Kotlin, plus once in each of the two
  entitlements files (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`).
- **Keychain entries**, pinned as (service, account) **pairs** — the pair is the unit of
  identity, so a cross-swap of accounts between services fails even though every individual
  string survives: (`app.snapsync.deviceid`, `deviceid`), (`app.snapsync.config`, `eventconfig`),
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`). Each pair SHALL match exactly once in production Kotlin.
- **App-Group `NSUserDefaults` keys** `discovery.changeToken`, `rejoin.joinedEventId`,
  `app.snapsync.album.map`.
- **Database filenames** `ledger.db`, `downloads.db`.
- **Config filename** `eventconfig.json` — the App-Group config file of record (migration step
  11a, capability `event-link`). Re-valuing it reads every joined device's file as absent: a
  Keychain-fallback resurrection while the write-through lasts, a **false leave on every joined
  device** after it ends.
- **Device-manifest App-Group layout**: directory `device-manifest`, files `accumulator.json`,
  `last-uploaded.json` — the manifest is the physical fact of membership; losing the accumulator
  shrinks the event union.
- **BGTask identifiers** `app.snapsync.upload.heartbeat`, `app.snapsync.download.backstop` —
  once in Kotlin AND once in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`; the guard
  SHALL assert the Kotlin value and the plist value agree, because drift between them silently
  kills that background tier (the OS rejects an unpermitted submit; nothing raises).
- **Background `URLSession` identifiers** `app.snapsync.upload.session`,
  `app.snapsync.download.bg` — the OS reattaches in-flight transfers by these across relaunch.
- **Framework `baseName`s** `SnapSyncKit`, `SnapSyncUploadKit` — once each, in
  `build.gradle.kts` files (the scan surface for this pin is build files, not Kotlin).

The guard SHALL match source text (a fully-qualified or string-template occurrence counts), and
per the existing non-vacuity requirement SHALL fail if any scanned surface resolves to zero
files.

#### Scenario: A moved literal drifts

- **WHEN** a migration step moves a file and the App-Group id (or any pinned literal) arrives
  with a changed value, or does not arrive at all
- **THEN** the pin guard fails the build, naming the literal and the expected count

#### Scenario: A literal is duplicated

- **WHEN** a second production Kotlin occurrence of a pinned literal appears (e.g. a private
  copy instead of an import of the consolidated const)
- **THEN** the pin guard fails — exactly-once is the invariant that keeps future drift
  single-sited

#### Scenario: A keychain cross-swap preserves every string

- **WHEN** an edit moves account `token` from service `app.snapsync.attest` to another service,
  leaving every individual string present somewhere
- **THEN** the pair pin fails, because the (service, account) pair no longer matches

#### Scenario: A BGTask id diverges between Kotlin and Info.plist

- **WHEN** the Kotlin constant and the `BGTaskSchedulerPermittedIdentifiers` entry for a BGTask
  id no longer agree
- **THEN** the pin guard fails, naming both values

### Requirement: The zone gates exist before their zones, pending and self-arming

The five zone gates SHALL exist in `:test:architecture` **before** the zones they guard exist —
the gates of requirement "The zone gates": model-purity, ports→model, feature-blindness,
flow-no-ports, presentation-imports — following the fake-honesty gate's self-arming pattern:

- While a gate's scope directory does not exist, the gate SHALL report itself pending — visibly
  (a printed PENDING line naming the scope), never vacuously green-by-silence.
- Once the scope directory exists, the gate SHALL fail if it scans zero sources (the non-vacuity
  twin), and SHALL enforce its import law with **zero gate edits** — migration steps arm gates by
  creating code, never by writing gates mid-move.
- The scan scopes are pinned now, as named assumptions in each gate:
  `domain/src/*/kotlin/**/model/`, `…/ports/`, `…/feature/`, `…/flow/`, `…/compose/` (the
  `:domain` module roots at `domain/`, its `src/` beside the legacy submodule directories until
  they empty), and `ui/presentation/src/**` for the presentation gate.
- The presentation gate SHALL enforce the import-level approximation of its law:
  `ui/presentation` sources never reference the `ports/` or `flow/` packages (imported or
  fully-qualified); the finer no-feature-command-invocation rule remains a review concern until
  it has a mechanical form. The gate's scope is **every** `.kt` under `ui/presentation/src` —
  test sources included, deliberately: presentation's tests are presentation sources, so a test
  that assembles a port-typed stub reintroduces exactly the coupling the gate exists to sever
  (honored at migration step 9, where the two tests assembling the real create use-case over a
  stubbed `EventCreation` port were re-seated as bundle-level choreography, their feature half
  owned by `CreateEventTest` and `:test:integration`).

As of migration step 9 all five pinned scopes exist (`model/`+`ports/` at 3a, `feature/` at 5–6,
`compose/` at 7, `flow/` at 8, `ui/presentation/src` at 9) and every zone gate is **armed** — the
pending state is historical; the self-arming contract stands for any future scope move.

#### Scenario: A gate whose zone does not exist yet

- **WHEN** the guards run while `domain/src` (or `ui/presentation/src`) does not exist
- **THEN** the gate prints a PENDING line naming its absent scope and passes, rather than
  failing or passing silently

#### Scenario: A zone is born and the gate arms itself

- **WHEN** a migration step creates the first file under a gate's pinned scope
- **THEN** the gate enforces its import law on that file with zero edits to the gate

#### Scenario: A scope exists but the scan is empty

- **WHEN** a gate's scope directory exists but the gate's file walk matches nothing
- **THEN** the gate fails — a layout drift must surface as red, not as a gate that passes
  forever

### Requirement: The migration beacon is red until the migration completes
Migration distance SHALL be measured by a dedicated module detached from `check` and reported by
the NON-required `verify` job of the `architecture` workflow: the job SHALL fail while any per-law
burn-down count is nonzero (writing the per-law table to the job summary before failing) and SHALL
pass exactly when every count is zero. The check SHALL NOT be required and SHALL NOT gate any
merge; the release guard in `ios-appstore-promote.yml` and `/ship`'s watcher SHALL judge REQUIRED checks
only, with the required set derived from branch protection at run time — never a name list — so
this and any future informational check is tolerated automatically, and the filter degrades in
the strict direction (unresolvable required set ⇒ every check counts). At completion each gate
moves into the gating module and the beacon module is deleted.
Accepted risk unchanged, on record: during the migration nothing GATES new violations — the
beacon makes them visible (red, with numbers), not blocked.

#### Scenario: A release during the migration
- **WHEN** `ios-appstore-promote.yml` is dispatched while the beacon is red
- **THEN** the release guard evaluates required check-runs only, ignoring the red beacon and
  any other non-required check

#### Scenario: A ship during the migration
- **WHEN** `/ship` watches a PR carrying the red beacon
- **THEN** the watcher's verdict comes from required checks only, and the queued-PR classifier
  does not skip PRs for it

#### Scenario: The migration completes
- **WHEN** every per-law burn-down count reaches zero
- **THEN** the `verify` job goes green, the gates move under `./gradlew build`, and the beacon
  module and the job are deleted

### Requirement: Dead-edge analysis is scoped honestly
The build SHALL run dependency-analysis `buildHealth` warn-only for jvm/common declared-unused
edges (with the `kotlin-metadata-jvm` force it requires). iOS-only adapter edges are covered by
the text gates, not by `buildHealth` (no upstream iOS-target support).

#### Scenario: A declared-and-never-imported edge
- **WHEN** a jvm/common module declares a project dependency no source references
- **THEN** `buildHealth` reports it in the job summary

