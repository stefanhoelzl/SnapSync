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
step 4 the owning module was `:domain:keychain`, whose residual platform-free `ProtectedData` seam
died at step 12 with the module itself). No other module SHALL reference the Keychain API
(`SecItem*`, `kSecClass*`,
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
backend's copy **cannot** be: `api/` is a Deno tree deployed by a separate, path-scoped workflow that
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
handles **both** halves of universal-link delivery and forwards to the Kotlin entry point
(capability `ios-app-shell`). Specifically it SHALL assert that the shell:

1. installs a scene delegate via the app delegate's `application(_:configurationForConnecting:options:)`
   (setting `delegateClass`) — without this the delegate is inert;
2. implements `scene(_:willConnectTo:options:)` — the **cold** half, reading the launching link from the
   connection options;
3. implements `scene(_:continue:)` — the **warm** half; and
4. forwards the delivered `NSUserActivity` **whole** from that delegate, with each hook forwarding
   under its **own** Kotlin entry-point name (cold and warm are distinguishable in a device log, which
   is what lets a dump say which hook the platform actually invoked) (migration step 12, the transcriber law: the browsing-web filter and the raw
   `absoluteString` read — fragment included — are the tested `model/` codec's, routed on to
   `onOpenUrl` in Kotlin; a Swift-side field extraction would be an unpinned decision under the
   shell gates).

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

Because the failure is invisible, the guard's **failure message** SHALL carry the evidence — it is the
only thing standing between the next reader and re-introducing the bug. The evidence:

- `.onOpenURL` and `application(_:continue:restorationHandler:)` never fire for a universal link at all.
- SwiftUI's continuation modifier never fires **cold**, and **cannot be added as a second warm path
  while this delegate exists**: a scene has exactly one delegate, this app installs its own, so
  SwiftUI's — which feeds that modifier — is never created. Measured on device 2026-08-04: 8 warm
  deliveries, 8 hits on `scene(_:continue:)`, **zero** on the modifier. The 2026-07-16 matrix measured
  it in the opposite configuration; those rows are mutually exclusive setups, not composable features.
- Whether iOS 18 calls `scene(_:continue:)` at all is **unmeasured** and not measurable without an iOS
  18 device: a simulator does not route universal links (on an iOS 26.5 simulator, where a device shows
  8/8, the app received zero).

#### Scenario: Removing the scene delegate fails the build

- **WHEN** the Swift shell no longer installs a scene delegate, or no longer implements
  `scene(_:willConnectTo:options:)` or `scene(_:continue:)`, or no longer forwards the activity to
  `onUserActivity`
- **THEN** the guard test fails, naming what is missing and why it matters

#### Scenario: The guard is not vacuous

- **WHEN** the Swift file the guard inspects is absent, renamed, or no longer contains the markers it
  expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when the Swift shell changes

- **WHEN** only the iOS Swift shell is edited and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

#### Scenario: An intact shell passes

- **WHEN** the shell installs the scene delegate and implements both halves, forwarding the
  delivered activity whole to `onUserActivity`
- **THEN** the guard passes

### Requirement: Gates fail closed on novelty
Every architecture gate SHALL derive its scope from the repository's structure at test runtime —
directory listings for feature enumeration, package patterns for zones, "everything not
allowlisted" for purity — never from a hand-maintained inclusion list. The only permitted lists
are loud-when-stale: the end-state module list (compared against the build's own include set)
and the per-zone library allowlists. Every gate SHALL keep a non-vacuity twin proving it
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
directory); `flow/` references only `model/` and `feature/`; `flow/` declares no `CoroutineScope`
and accepts no non-suspend effect lambda (law *A trigger flow never outlives its own run* — both
doors, because removing the scope alone leaves the lambda one open); `:ui:presentation` references
only the injected flow command bundle, feature read-model types, and `model/`; `:domain` and `:ui`
zones import only their per-zone allowlisted libraries; `:domain` has no `iosMain` source
directory and declares no `project()` dependency.

#### Scenario: A fully-qualified sidestep
- **WHEN** a file references a forbidden declaration by fully-qualified name without an import
- **THEN** the text gate fails exactly as it would for an import

#### Scenario: A flow reacquires a way to detach
- **WHEN** a `flow/` class gains a `CoroutineScope` parameter or a non-suspend effect lambda
- **THEN** the gate fails, naming the file, before any device build

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
(threshold: no function above cyclomatic complexity 1 beyond pinned wiring forms), **gating**
(`ignoreFailures = false`, wired into `check`) over all production `:app:*` source sets including
`iosMain`, asserted by a test with a non-vacuity floor (`KotlinShellGuardTest`: the scanned source
roots exist and are non-empty — a stale source list after a module rename must fail, never pass
vacuously). Because detekt honors `@Suppress`, the suppression IS the Kotlin pin mechanism, and
the same guard SHALL pin the suppression inventory exactly, in both directions (per file, by
count): a new `@Suppress("CyclomaticComplexMethod")` fails until it is argued into the table with
a forcing proof at the suppression site, and a removed one fails until the table shrinks. The
Swift shells SHALL be guarded by a pinned-structure text check: decision keywords (`if`, `guard`,
`switch`, `??`) may appear only at the explicitly pinned occurrences, each pin carrying its
forcing proof in the failure message.

The Swift guard SHALL additionally assert that **every function in a Swift shell forwards to
Kotlin**: a shell function either calls the composition root or does not exist. A Swift function
that handles a platform callback without reaching Kotlin is invisible by construction — the shells
are wiring-only and untested by project rule, and platform logging redacts interpolated messages —
so a callback that only writes a Swift-side log line, or deliberately does nothing, records
nothing anywhere. Two such holes existed when this rule was written: the extension's termination
callback (the OS announcing it is killing the upload cycle) and the push-registration failure
handler.

#### Scenario: A decision creeps into a shell
- **WHEN** a branch is added to `:app:*` Kotlin or an unpinned decision keyword to a Swift shell
- **THEN** the canonical build fails (the detekt gate or the Swift pin check) and the message
  names the tested zone the decision belongs in

#### Scenario: A suppression sidesteps the Kotlin gate
- **WHEN** a new `@Suppress("CyclomaticComplexMethod")` appears in the shells without a pin row
- **THEN** the pin-inventory guard fails — a suppression is exactly as loud as a branch

#### Scenario: A Swift callback handles a platform event without reaching Kotlin
- **WHEN** a function in a Swift shell does not call the composition root — whether it is empty, or
  logs only on the Swift side
- **THEN** the Swift guard fails, naming that a shell function which forwards nothing records
  nothing anywhere

### Requirement: The fake-honesty gate
Every public type in `:adapter:generic:fake` SHALL expose only members of the port interfaces it
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
  string survives: (`app.snapsync.deviceid`, `deviceid`),
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`). Each pair SHALL match exactly once in production Kotlin.
  The config pair (`app.snapsync.config`, `eventconfig`) was **retired from this inventory** by the
  Stage-2 change that deleted the read-only legacy-Keychain fallback (capability
  `event-rejoin-reconciliation`): its one seat was that fallback, and with it gone the pair appears
  in production Kotlin **nowhere**, which an exactly-once pin cannot express. The config's runtime
  identity is now carried entirely by the `eventconfig.json` pin below.
- **Keychain access group** — the shared group the device-id item is addressed with (capability
  `device-identity`). Pinned once in production Kotlin, and cross-checked against the **suffix**
  declared in each of the two entitlements files together with `TEAM_ID` from `Config.xcconfig`:
  the guard SHALL assert the Kotlin literal equals `<TEAM_ID>.` followed by the entitlements'
  declared group, and that both entitlements files declare the same group. Drift here does not
  fail loudly — the item is written to a *different real group*, both processes still read
  successfully, and each simply reads a different item. That is the split-identity fault, which
  is invisible to every existing gate and unrecoverable once written.
- **The unscoped-Keychain inventory** — the guard SHALL pin, as an exact set, which Keychain seats
  search **without** naming an access group. That set SHALL be
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`); and the device-id seat
  SHALL NOT be in it. Unscoped search is bounded, not forbidden: the attest pair and album map
  remain unscoped deliberately — the attest token is demonstrably read cross-process today, and the
  album map is a self-healing cache. What the pin forbids is a **new** unscoped seat appearing by
  default, which is how implicit placement spread. Adding, removing, or re-scoping an entry is a
  spec delta to this requirement. The config seat left this set with the Stage-2 fallback deletion,
  and because the set is exact in both directions, a *reconstructed* unscoped config seat SHALL fail
  the build. **Stated blind spot:** a config seat reconstructed **scoped** would not — scoped sites
  are checked only for the device-id seat's presence, not pinned as a set. That gap is narrow by
  construction (a scoped read cannot find the unscoped items pre-11a builds wrote, which is the only
  thing such a seat could be after) and is named here rather than left to be discovered.
- **App-Group `NSUserDefaults` keys** `discovery.changeToken`, `rejoin.joinedEventId`,
  `app.snapsync.album.map`.
- **Database filenames** `ledger.db`, `downloads.db`.
- **Config filename** `eventconfig.json` — the App-Group config file of record (capability
  `event-link`; the only config storage). Re-valuing it reads every joined device's file as
  absent: a **false leave on every joined device**.
- **Device-manifest App-Group layout**: directory `device-manifest`, file `last-uploaded.json` —
  the skip-if-unchanged record of the manifest this device last published successfully. The manifest
  itself is a projection of the upload ledger's `COMPLETED` rows (capability `sync-ledger`), so this
  is the layout's only file: re-valuing either name reads the previously-written state as absent and
  abandons it in the container. A device-global accumulator file is deliberately **not** in this
  inventory — there is none; pinning one would fail the exactly-once assertion forever, and a stale
  `accumulator.json` an older build left in the container is inert.
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

#### Scenario: The access group disagrees with the entitlements

- **WHEN** the Kotlin access-group literal, the group declared in the entitlements files, or
  `TEAM_ID` are edited so they no longer compose to the same string, or the two entitlements
  files declare different groups
- **THEN** the pin guard fails, naming the Kotlin value and the composed entitlements value

#### Scenario: A new unscoped Keychain seat appears

- **WHEN** a production Keychain seat outside the pinned inventory is constructed without naming an
  access group, or a pinned unscoped seat is silently re-scoped
- **THEN** the guard fails, listing the expected and found inventories — implicit placement may only
  change deliberately

#### Scenario: The retired config seat is reconstructed unscoped

- **WHEN** production Kotlin again constructs an unscoped Keychain seat for
  (`app.snapsync.config`, `eventconfig`)
- **THEN** the unscoped-inventory pin fails, because the expected set no longer contains it — the
  retired legacy fallback cannot return silently

#### Scenario: The device id names its group

- **WHEN** the guard classifies the device-id seat
- **THEN** it is found among the seats that name an access group, never among the unscoped ones

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

### Requirement: The migration's laws are permanent gates
Every law the migration beacon measured SHALL be enforced permanently in `:test:architecture`
under `./gradlew build`. (The module-architecture migration is complete; its beacon — the
detached burn-down module and the non-required `verify` job — measured zero on every law at the
finale and was deleted, per its own contract.) The promoted gates:

- **Module-set equality**: the `settings.gradle.kts` include set SHALL equal the
  `module-architecture` target module list exactly (a loud-when-stale list — the one the "Gates
  fail closed on novelty" requirement permits); adding or deleting a module fails until the list
  is consciously amended with the withholding argument in a `module-architecture` spec delta.
- **Mixed port/impl files**: no file under `adapter/`, `domain/`, or `ui/` SHALL declare an
  `interface` beside a Ktor or SQLDelight import — a port and its technology impl cohabiting is
  the seed of the pre-migration shape.
- **Deletion ledger**: the migration's retired dead weight SHALL stay dead — the zxing and
  kotlincrypto catalog entries, the `capability/` tree, `LedgerReader`, `LoggingPushReceiver`,
  `EventMetadataSource`, the Arrow/ArrowLevel duplicate
  enum, and any second `*Enrollment` uploader. Resurrection is not forbidden forever; it is
  forbidden **silently** — bringing an item back means deleting its guard row in the same commit,
  with the argument in the PR. The guard SHALL assemble its patterns so its own source never
  matches them (the beacon's self-match lesson).
  The `LeaveNotifier` interface, retired as single-implementation ceremony ("the class is the
  seam"), has been brought back under exactly that clause and its row deleted: a **port** is not an
  interface justified by a second implementation, it is the declared boundary where the core stops
  and an external system begins, and with the interface gone the composition carried the crossing as
  an opaque closure instead — invisible to every gate that reads types.
- **Shells** and **zones** are gated by their own standing requirements (the shell gates; the zone
  gates), now all armed and gating.

The flow-transcriber generation failure (capability `architecture-diagrams`) SHALL likewise be a
hard gate: an untranscribable flow fails `architectureDiagrams` and the freshness test under the
canonical build.

#### Scenario: A module is added without amending the target list
- **WHEN** a new `include(...)` lands in `settings.gradle.kts` with no matching edit to the
  module-set gate's target list
- **THEN** the gate fails, naming the drift and the withholding bar a new module must clear

#### Scenario: Retired dead weight grows back
- **WHEN** a retired declaration (or catalog entry, or the `capability/` tree) reappears anywhere
  in the scanned roots
- **THEN** the deletion-ledger gate fails, naming the resurrected item and its rationale

#### Scenario: A retired name comes back as a port
- **WHEN** a declaration the ledger retired is reintroduced because the judgement that retired it is
  overturned
- **THEN** its ledger row is deleted in the same commit and the reversal is argued in the change's
  decision record, so the resurrection is loud rather than silent

### Requirement: Dead-edge analysis is scoped honestly
The build SHALL run dependency-analysis `buildHealth` warn-only for jvm/common declared-unused
edges (with the `kotlin-metadata-jvm` force it requires). iOS-only adapter edges are covered by
the text gates, not by `buildHealth` (no upstream iOS-target support).

#### Scenario: A declared-and-never-imported edge
- **WHEN** a jvm/common module declares a project dependency no source references
- **THEN** `buildHealth` reports it in the job summary

### Requirement: The upload producers are never both started

A `:test:architecture` guard SHALL pin the exactly-one-started invariant of `upload-lifecycle`: with
both upload producers composed (iOS ≥26.1), no path through the tier-neutral orchestrator starts both
producers, and every mechanism switch stops the outgoing producer before starting the incoming one.
The guard SHALL drive the orchestrator over fake producers through every transition row of the
lifecycle table — provision under each permission, the `GRANTED` ↔ `LIMITED` flips in both directions,
grant-with-no-membership, and leave — asserting after each step that at most one producer is in the
started state and that a switch observed stop-before-start.

This guard is the enforcement half of the structural→behavioral move recorded in `upload-lifecycle`
("Exactly one producer started per process"): construction-time exclusion was the previous guarantee,
runtime permission-dependence made it inexpressible, and a build-gating test is what replaces the
compile error.

#### Scenario: No transition sequence starts both producers
- **WHEN** the guard drives the orchestrator through every lifecycle transition row, in sequence and in
  permission-flip combinations
- **THEN** at no observed point are both producers started, and the build fails if any sequence
  violates this

#### Scenario: A switch that starts before stopping fails the build
- **WHEN** an orchestrator change makes a permission flip start the incoming producer before the
  outgoing producer's stop completes
- **THEN** the guard fails the build

### Requirement: Platform entry points are derived and logged before deciding

A test-only JVM guard SHALL assert that every platform entry point is instrumented before it
decides anything (capability `diagnostic-logging`; spec `module-architecture`, "Absence is never
silent").

The guard SHALL **derive** the entry-point population from the source rather than compare against a
maintained list, because hand-enumeration is the failure mode being fixed — an enumeration attempted
during this change's design was wrong in both directions, including a function the platform never
calls and misclassifying two that it does. The derivation rules are:

1. every member of a composition-root object invoked from outside that root's own file (covering
   both Swift→Kotlin doors: the app/scene delegate shell and the Compose entry the Swift view calls);
2. every overridden member of a class conforming to a platform callback protocol;
3. every observer body registered with a platform notification or change-observer centre.

For each derived entry point the guard SHALL assert that it carries the entry-point marker and that
its body opens with the instrumentation wrapper, so a decision cannot precede the enter line. A
declaration reached only from our own Kotlin SHALL NOT be treated as an entry point.

The guard SHALL fail loudly rather than vacuously: if the sources it derives from are missing,
renamed, or yield an empty population, it SHALL fail rather than pass while scanning nothing, and
the guarded sources SHALL be declared as inputs of its test task.

The rules do not describe every conceivable callback shape (a C function pointer, a KVO observation,
a dispatch-source handler). That residue SHALL be **named in the guard's failure message**, so the
next reader extends the derivation rules rather than adding a pinned exception — the pinned-list
outcome this requirement exists to avoid.

#### Scenario: A new entry point is added without instrumentation
- **WHEN** a new platform callback is added and its body does not open with the instrumentation wrapper
- **THEN** the guard fails, naming the entry point and why a decision must not precede its enter line

#### Scenario: A new entry point in a previously unscanned file
- **WHEN** a new class conforming to a platform callback protocol is added anywhere in the iOS sources
- **THEN** the derivation picks it up without any list being edited, and it is held to the same rule

#### Scenario: The guard is not vacuous
- **WHEN** the sources the guard derives from are absent, renamed, or produce an empty entry-point set
- **THEN** the guard fails rather than passing while scanning nothing

#### Scenario: A non-entry-point is not flagged
- **WHEN** a function on a composition root is reached only from our own Kotlin
- **THEN** the guard does not require it to be an entry point

### Requirement: Nullable port seams carry a stated consequence

A test-only JVM guard SHALL assert that every nullable-returning member of the `ports/` boundary has
a recorded verdict naming the consequence that makes its collapse safe, or is expressed as a
distinguishing result type instead (spec `module-architecture`, "Absence is never silent").

The **population SHALL be derived** from the `ports/` sources; only the **verdicts** are authored.
A new nullable port seam therefore fails the build until someone states its consequence — the guard
demands a reason, it does not maintain a list. A verdict that is present but wrong is a review
concern, not a mechanical one, and this requirement makes no claim to catch it.

This is the mechanically enforceable half of an otherwise-prose law: `ports/` is a small, bounded
directory, which is what makes derivation cheap and non-vacuous there while a tree-wide equivalent
would be neither.

#### Scenario: A new nullable port seam has no verdict
- **WHEN** a nullable-returning member is added to a port interface without a recorded consequence
- **THEN** the guard fails until the consequence is stated or the seam returns a distinguishing type

#### Scenario: A retired seam leaves a stale verdict
- **WHEN** a nullable port member is removed or made non-nullable while its verdict remains
- **THEN** the guard fails, so the verdict inventory cannot outlive the seams it describes

### Requirement: The main lane is contained to platform UI

A gate SHALL fail the build when a main-thread dispatcher is named outside an allowlist of platform-UI
adapters. The watched forms SHALL cover both languages, because either can put work back on the main
thread: in Kotlin `Dispatchers.Main`, `MainScope()`, `dispatch_get_main_queue`, and
`NSOperationQueue.mainQueue`; in Swift `DispatchQueue.main`.

The gate is lexical containment rather than an attempt to decide whether a call blocks — that is not
decidable — so it makes the main lane unreachable by default and reachable only by an allowlist edit a
reviewer sees. The allowlist SHALL name each entry's reason.

`runBlocking` SHALL NOT appear outside test source sets: it blocks whatever thread it is called on, which
defeats the lane it was called from, and the extension's single pinned use is its composition root's
documented execution model rather than a call inside the core.

#### Scenario: A new adapter reaches for the main thread
- **WHEN** a file outside the allowlist names any watched main-thread dispatcher form
- **THEN** the gate fails the build, naming the file and the form

#### Scenario: A UI adapter is added
- **WHEN** a new platform-UI adapter legitimately needs the main lane
- **THEN** it is added to the allowlist with its reason, and the addition is visible in review

#### Scenario: Blocking is reintroduced through runBlocking
- **WHEN** `runBlocking` appears in non-test source outside its pinned composition-root use
- **THEN** the gate fails

### Requirement: Every user command declares its dispatcher lane

A gate SHALL fail the build when any field of the user-command bundle is built without a lane-declaring
decorator. Two decorators SHALL exist — one for commands that present platform UI and must run on the main
lane, one for everything else — and neither SHALL be a default, so a command that declares no lane does
not compile.

This gate exists because the composition scope cannot cover this door: the presentation container launches
an intent on an unconfined dispatcher, so a command's synchronous prefix runs on the thread that fired it.
It also keeps the manually-verified surface small — the lane choice for every command is visible in one
file, which matters because the UI-lane commands cannot be exercised by any automated test available to
this project.

#### Scenario: A command is added without a lane
- **WHEN** a field is added to the user-command bundle and built without either decorator
- **THEN** the build fails

#### Scenario: A command's lane is reviewed
- **WHEN** a reviewer checks whether platform-UI commands stay on the main lane
- **THEN** every command's lane is readable in the single file where the bundle is built

### Requirement: Adapter constructors perform no blocking work

A gate SHALL fail the build when a blocking platform call appears in a property initialiser or `init`
block of an iOS adapter. Construction happens during graph assembly, which runs on whichever thread
touches the graph first — so constructor I/O is a race between the launch path and the first render, and a
race is why such a defect is never observed in testing.

The gate SHALL carry a named grandfather list rather than blocking on a redesign. The one existing
instance is the file-backed config store, whose constructor read exists because the status container's
first state is built from seams that hold their current truth synchronously; removing it requires either
a placeholder first frame or a launch/render reordering across the shell boundary. The entry SHALL record
that reason, so the exemption is a decision rather than an oversight.

#### Scenario: A new adapter reads in its constructor
- **WHEN** an adapter gains a property initialiser or `init` block that performs a blocking platform call
- **THEN** the gate fails, and the class of defect cannot grow

#### Scenario: The grandfathered instance is inspected
- **WHEN** a reader asks why the config store is exempt
- **THEN** the allowlist entry states the constraint that makes fixing it a separate change

### Requirement: The composition seam gate
The build SHALL fail when the function-typed field inventory of `AppPorts` or `UploadPorts` differs
from a pinned list held in `:test:architecture`, exact in **both** directions. A new function-typed
field fails until it is pinned with a stated reason it is not a port; a removed one fails until the
pin is deleted, so the inventory cannot outlive what it describes.

The gate pins an inventory rather than inspecting call targets, deliberately: whether a lambda
reaches out of the process is not decidable from its declared type — `downloadStagingRoot: () ->
String` and `deviceId: () -> String` are type-identical while one resolves a platform container and
the other returns a value the composition already holds. The pin records a human judgement once,
which is the same mechanism the shell gates use for complexity suppressions.

**What it does not cover, stated so a green run is not over-read:** it constrains what the
composition hands the core. It says nothing about what the OS hands the shell — registering an
`NSNotificationCenter` observer or submitting a `BGProcessingTaskRequest` is the shell being called
by the platform, not accessing it, and is out of scope.

#### Scenario: A function-typed field is added to a composition bundle
- **WHEN** `AppPorts` or `UploadPorts` gains a function-typed field that is not in the pinned
  inventory
- **THEN** the gate fails, naming the field, until it is given a port type or pinned with its reason

#### Scenario: A pinned seam is converted to a port
- **WHEN** a pinned function-typed field is replaced by a port type
- **THEN** the gate fails until its pin is removed, so the inventory shrinks with the code

#### Scenario: The gate is pointed at a bundle that no longer exists
- **WHEN** the scanned declarations resolve to zero fields
- **THEN** the gate fails as vacuous rather than passing, per "Gates fail closed on novelty"

#### Scenario: A third composition bundle appears
- **WHEN** a new `*Ports` bundle is declared in `compose/`
- **THEN** the gate fails until that bundle is listed with its file, because a bundle it does not
  know about is a third place the composition can hand the core a lambda unseen

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured when the gate was introduced, scanning those zones including comments
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. Three
have since been paid off (below), leaving a baseline of 2. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. `CompositionMode`'s tier
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  selects, not platform APIs the core calls, and a second tier is a new member rather than a new
  coupling.
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today there is exactly one: `ports/OsReceipt.kt`'s
  `ReceiptDeadlines.URL_SESSION_EVENTS`, a naming slip whose two sibling deadlines are already
  neutral; it expires with the iOS 18–26.0 app-driven tier.

Both `Keychain`-token entries in that list have now been discharged, by two different routes, and
recording which is the point of the split:

- `ports/ConfigPorts.kt` was discharged **incidentally** — the Stage-2 change deleted
  `configReadFrom`, the file's only `KeychainRead`-typed function, with the legacy fallback it
  served (capability `event-rejoin-reconciliation`), well before the family's reshape.
- `ports/Keychain.kt` and `feature/album/AlbumMapMigration.kt` were discharged **by the expiry
  trigger they were filed under**: the port was renamed for its need (`SecureStore`), its `OSStatus`
  and accessibility-class vocabulary moved into the iOS adapter, and the feature took the neutral
  read type.

A deferred pin may therefore be discharged by whatever removes the code; the expiry trigger is a
floor, not a schedule.

The scanned vocabulary SHALL keep the `Keychain` token even though no pin now names it. Its original
purpose is served — because the pin list is exact in both directions, retaining the token is what
made the reshape unable to land without deleting those pins, and what made the `ConfigPorts`
discharge visible the moment the code went. Its remaining purpose is ordinary: a port or feature that
reintroduces the token SHALL fail the gate rather than arrive unpinned.

**What it does not cover, stated so a green run is not over-read:** the gate is lexical. A decoder
over another system's values written in bare integers — a `when` over `0L`, `1L`, `2L` that is in
fact a `UIApplicationState` table — is indistinguishable from arithmetic and SHALL NOT be assumed
caught. The gate's hits are therefore not ranked by risk: it fires on named constants, which are the
safer kind, and is silent on unnamespaced integer tables, which are the kind that can return a wrong
answer to a second platform rather than a safe default. It is likewise blind to a platform encoding
carried in a neutral type — an `Int` that is really an `OSStatus`, or a `String` that is really an
accessibility class — which is how `ports/Keychain.kt`'s pin understated what that file actually
owed.

#### Scenario: An Apple constant is introduced into a platform-free zone
- **WHEN** an `NS*`, `PH*`, `kSec*`, `UI*`, `AV*` identifier or an Apple product name appears
  outside a comment in `model/`, `ports/` or `feature/`
- **THEN** the gate fails, naming the file and the token

#### Scenario: A documented binding note is written
- **WHEN** a KDoc records how a neutral type is bound on iOS (for example, that an opaque payload is
  a `PHAssetResource` there, or that a legacy item physically lived in the Keychain)
- **THEN** the gate does not fire, because comments are exempt by design

#### Scenario: A pinned exception is removed from the code
- **WHEN** a pinned Apple identifier is deleted or moved into an adapter
- **THEN** the gate fails until its pin is removed, so the pin list cannot describe absent code

#### Scenario: A deferred pin's code is deleted before its expiry trigger fires
- **WHEN** unrelated work removes the code a deferred pin describes — as the Stage-2 fallback
  deletion removed `ports/ConfigPorts.kt`'s `KeychainRead` use ahead of the port family's reshape
- **THEN** the gate fails on the stale pin, and the pin is deleted with that work rather than
  waiting for the trigger it was filed under

#### Scenario: A deferred pin's expiry trigger fires
- **WHEN** the reshape a deferred pin named as its expiry lands, and the token leaves the code
- **THEN** the gate fails on every pin that reshape cleared, and each is deleted in the same commit,
  so the receipt and the debt end together

#### Scenario: A retired token is reintroduced
- **WHEN** a platform token that no pin names any more reappears in the code of a scanned zone
- **THEN** the gate fails, because the vocabulary is not narrowed when a pin is discharged

#### Scenario: Deferred debt is filed as accepted
- **WHEN** a pin is added for an identifier the owner intends to remove later
- **THEN** it belongs in the deferred list with an expiry trigger, not in the accepted list, so the
  pin inventory never reads as if the law had no outstanding violations
