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

The event link's domain SHALL be **single-sourced from one resolved deployment** (capability
`deployment-configuration`) in every place it appears: the app's `applinks:` associated-domains
entitlement, the app's `LINK_ORIGIN` constant, the Apple App Site Association document the backend serves,
the compile-time device-facing upload host, and the browser-facing site's canonical URLs.

This supersedes the previous position that the backend's copy **cannot** be single-sourced. That reasoning
held only while `api/` was reachable by nothing but a code-only deploy pipeline: with the domain resolved
from a deployment that every toolchain reads, generating each copy no longer couples two pipelines — it
gives them one shared input. The guard's own purpose said as much, that single-sourcing is preferable and
the guard existed only for the seam it could not close.

Two consequences follow. Agreement is no longer *asserted* across hand-written literals but *constructed*,
so a copy cannot drift. And the guarantee now reaches copies the previous guard never inspected — the
compile-time upload host and the site's canonical URLs were both unpinned.

A test-only JVM guard SHALL remain, reduced to a **staleness check**: it SHALL assert that each generated
artifact matches the deployment it derives from, and SHALL fail loudly rather than vacuously — if a file it
inspects has moved, been renamed, or no longer contains the marker it expects, it SHALL fail rather than
silently scanning nothing.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe` (the guard shrinks to a
staleness check once every copy is generated).

#### Scenario: A stale generated artifact fails the build

- **WHEN** a generated artifact carrying the domain no longer matches the deployment it derives from
- **THEN** the guard test fails, naming the artifact and the disagreeing values

#### Scenario: Every copy is constructed, not restated

- **WHEN** the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's domain, the
  compile-time upload host, and the site's canonical URLs are inspected
- **THEN** each is derived from the resolved deployment, and none is a hand-written host literal

#### Scenario: The guard is not vacuous

- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing artifacts pass

- **WHEN** every generated artifact matches the resolved deployment
- **THEN** the guard passes

### Requirement: The Swift shell keeps the event link's delivery seam

A test-only JVM guard SHALL assert that the iOS Swift shell still carries **every** event-link delivery
path and forwards each to the Kotlin entry point (capability `ios-app-shell`). There are two, fed by
different machinery, and neither is sufficient alone — so the guard SHALL pin both. Specifically it
SHALL assert that the shell:

0. declares SwiftUI's **`.onOpenURL`** on the `WindowGroup`, forwarding the URL to Kotlin — the path
   that carries a link opened while the app is already RUNNING on iOS 18.7.9, where the scene
   delegate's continuation never fires. It is the likeliest of all of these to be deleted as cruft,
   because this file's own history argued for years that it never fires for a universal link;

1. installs a scene delegate via the app delegate's `application(_:configurationForConnecting:options:)`
   (setting `delegateClass`) — without this the delegate is inert;
2. implements `scene(_:willConnectTo:options:)` — the **cold** half, reading the launching link from the
   connection options — and forwards the **count** of delivered activities to Kotlin **before** iterating
   them, so a scene that connects carrying none is still recorded;
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
SwiftUI's `onOpenURL`, which did not fire for a universal link in that configuration, so **every
invite silently did nothing**
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
- **With a custom scene delegate installed, the scene delegate's continuation does not fire on iOS
  18.7.9 while the app is already RUNNING** — measured on an iPhone XS, builds 681/683:
  `scene(_:willContinueUserActivityWithType:)` announces a continuation and then neither
  `scene(_:continue:)` nor `scene(_:didFailToContinueUserActivityWithType:error:)` follows, from Notes,
  WhatsApp and Safari's smart banner alike; a Camera QR scan and any cold launch deliver normally. Restoring
  SwiftUI's `.onOpenURL` delivered the link on that same OS build (687). **Why is unexplained**, and
  SHALL NOT be asserted: the 2026-07-16 matrix measured that modifier as failing cold and warm with
  SwiftUI's own scene delegate in place, and it fires now with a custom one installed — so "our delegate
  starves SwiftUI's" predicts the reverse of what was observed. A previous revision of this bullet said
  "iOS 18.7.9 does NOT call `scene(_:continue:)`", a claim about the PLATFORM; it was disproved within
  hours on that same OS build. Scope such claims to the build and configuration measured, and prefer
  recording an outcome to explaining it. The same signature is reported independently in Apple Developer
  Forums 758864 and 746362.
- **`.onOpenURL` is not reliable alone either** — it fired for 2 of 4 deliveries on an SE2 (iOS 26.6,
  build 687). Both paths are therefore pinned, and delivery is made idempotent in tested code
  (capability `event-link`) rather than by choosing between them.
- A **simulator cannot substitute** for any of this: the associated-domains entitlement makes the app
  un-launchable there, so no link entry point fires at all (measured 2026-08-25), and `simctl openurl`
  is accepted while delivering nothing.
- Expiry: re-measure at the next iOS major, and whenever a delivery hook is added or removed. Evidence
  is one device per OS major.

#### Scenario: Removing the scene delegate fails the build

- **WHEN** the Swift shell no longer installs a scene delegate, or no longer implements
  `scene(_:willConnectTo:options:)` or `scene(_:continue:)`, or no longer forwards the activity to
  `onUserActivity`
- **THEN** the guard test fails, naming what is missing and why it matters

#### Scenario: A cold connection with no activity is still recorded

- **WHEN** the cold half no longer forwards the delivered-activity count before iterating, so its only
  Kotlin call sits inside the loop and an empty `userActivities` records nothing
- **THEN** the guard test fails. The forwarding rule below cannot catch this on its own — the call is
  lexically present and merely never runs — and the consequence is measured: on `SNAPSYNC-25` a
  delegate that was installed and handed nothing was indistinguishable from a delegate that was never
  installed, and that ambiguity was the whole investigation

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

#### Scenario: Removing the SwiftUI delivery path fails the build

- **WHEN** the Swift shell no longer declares `.onOpenURL` on the `WindowGroup`, or no longer forwards
  its URL to Kotlin
- **THEN** the guard test fails, naming what is missing and carrying the evidence that this path is the
  one that delivers a link opened while the app is already running on iOS 18.7.9 — because the deletion
  this guards against is a reader trusting the older, falsified claim that the modifier never fires

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

- **App-Group id** `group.app.snapsync` — once in Kotlin, plus once in each of the **three**
  entitlements files (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`,
  `simulator.entitlements`). The third is not a signing surface for any shipped build: it is the
  App-Group-only plist an ad-hoc signature carries so a simulator build has a container at all. It
  is pinned for the same reason as the other two and one more — it is the surface most likely to be
  forgotten, because no shipped build fails when it is wrong. Re-value the App Group without it and
  the simulator host silently loses its container, which reads as the app being broken rather than
  as a rename being incomplete.
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
  declared in each of the **two signing** entitlements files together with `TEAM_ID` from
  `Config.xcconfig`: the guard SHALL assert the Kotlin literal equals `<TEAM_ID>.` followed by the
  entitlements' declared group, and that both signing entitlements files declare the same group.
  `simulator.entitlements` SHALL be excluded from this cross-check and SHALL declare **no**
  `keychain-access-groups` key at all — its omission is load-bearing rather than incidental: adding
  that entitlement to an ad-hoc-signed simulator build makes the app un-launchable, which is why
  `device-identity` carries a planted-identity path for hosts where the addressed group is
  unreachable. The guard SHALL assert the absence, so that "add the missing key" cannot be applied
  as a fix to a mystery it would cause. Drift in the signing files does not fail loudly — the item
  is written to a *different real group*, both processes still read successfully, and each simply
  reads a different item. That is the split-identity fault, which is invisible to every existing
  gate and unrecoverable once written.
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

- **WHEN** the Kotlin access-group literal, the group declared in the signing entitlements files, or
  `TEAM_ID` are edited so they no longer compose to the same string, or the two signing entitlements
  files declare different groups
- **THEN** the pin guard fails, naming the Kotlin value and the composed entitlements value

#### Scenario: The App Group is renamed without the simulator plist

- **WHEN** the App-Group id is re-valued in Kotlin and both signing entitlements files, and
  `simulator.entitlements` keeps the old value
- **THEN** the pin guard fails, naming the third file — rather than leaving a simulator host that
  launches and then reports its container as unavailable

#### Scenario: The simulator plist gains a keychain group

- **WHEN** `simulator.entitlements` declares a `keychain-access-groups` key
- **THEN** the guard fails, because that entitlement makes an ad-hoc-signed simulator build
  un-launchable and its absence is a deliberate decision rather than an omission to repair

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

A `:test:architecture` guard SHALL pin the invariants of `upload-lifecycle` that the compiler cannot,
at the two places the risk lives once exclusion is structural again:

- **The resolver's cells.** The guard SHALL drive the pure mechanism resolution over **every**
  combination of OS facts, permission, and override, asserting that no combination yields a mechanism the
  OS cannot run. This is the sharper risk: a wrong cell yields the OS-driven kind below iOS 26.1, where
  its registration selector does not exist, and the process aborts.
- **The orchestrator's transitions.** The guard SHALL drive the orchestrator over fake producers through
  every transition row of the lifecycle table — provision under each permission, the `GRANTED` ↔
  `LIMITED` flips in both directions, grant-with-no-membership, and leave — asserting after each step
  that its held producer is the one resolution yielded, that every change of kind observed
  stop-before-start, and that no transition leaves a producer started.

The guard SHALL NOT be retired on the grounds that "both started" became a compile error again.
Exclusion moving back to the compiler removed one failure mode and introduced two others: an unrunnable
resolved kind, and sequence bugs in an orchestrator that now holds mutable state where it previously held
none. The guard follows the risk rather than the original wording.

#### Scenario: No transition sequence leaves the wrong producer held or started
- **WHEN** the guard drives the orchestrator through every lifecycle transition row, in sequence and in
  permission-flip combinations
- **THEN** the held producer always matches the resolved kind, no transition leaves a producer started
  that should not be, and the build fails if any sequence violates this

#### Scenario: A resolver cell that cannot run on its OS fails the build
- **WHEN** any combination of OS facts, permission and override resolves to a mechanism whose platform
  API does not exist on that OS
- **THEN** the guard fails the build

#### Scenario: A switch that starts before stopping fails the build
- **WHEN** an orchestrator change makes a resolution change start the incoming producer before the
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
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. Four
have since been paid off (below), leaving a baseline of 1. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. the upload **mechanism kind**'s
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload mechanisms the pure resolver
  yields, not platform APIs the core calls, and a third mechanism is a new member rather than a new
  coupling. (These members were `UploadTier`'s until mechanism resolution absorbed `resolveComposition`;
  the pin follows them to the kind — the judgement is unchanged, only the type carrying it.)
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today there are **none**. The list being empty is a state to hold, not a gap to
  fill: a deferred pin is a receipt with an expiry, and it stops being one once the expiry is
  fiction.

The discharged entries went by three different routes, and recording which is the point of the split:

- `ports/ConfigPorts.kt` was discharged **incidentally** — the Stage-2 change deleted
  `configReadFrom`, the file's only `KeychainRead`-typed function, with the legacy fallback it
  served (capability `event-rejoin-reconciliation`), well before the family's reshape.
- `ports/Keychain.kt` and `feature/album/AlbumMapMigration.kt` were discharged **by the expiry
  trigger they were filed under**: the port was renamed for its need (`SecureStore`), its `OSStatus`
  and accessibility-class vocabulary moved into the iOS adapter, and the feature took the neutral
  read type.

- `ports/OsReceipt.kt`'s `ReceiptDeadlines.URL_SESSION_EVENTS` was discharged because its expiry
  trigger was **invalidated rather than reached**. It was filed to expire "with the iOS 18–26.0
  app-driven tier"; giving the download session the same handler budget put the constant in service of
  a session that exists on every iOS version, so the debt would have outlived the tier it was charged
  against. It was renamed for its need (`BACKGROUND_EVENTS`) instead of re-filed under a weaker expiry.

A deferred pin may therefore be discharged by whatever removes the code; the expiry trigger is a
floor, not a schedule. A pin whose expiry has become false SHALL be repaid or re-argued, never
silently re-filed — an expiry that cannot arrive makes the pin permanent while still reading as debt.

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

### Requirement: Every runbook pointer resolves to a skill that exists

A test-only JVM guard SHALL assert that every skill named in `CLAUDE.md`'s runbook pointer block
resolves to an existing `.claude/skills/<name>/SKILL.md`, and that every such skill file carries a
`name:` field in its frontmatter equal to the directory it lives in.

The pointer block is what remains after the operator runbooks move out of `CLAUDE.md`: one
imperative line per skill, naming the intent that should trigger a load. Its integrity cannot be
held by any compiler, and its failure is silent in the worst way — an agent reads "load the
`ios-device` skill", finds nothing under that name, and proceeds **without** it, executing the very
procedure the skill exists to make safe. That is the "absence is never silent" law (spec
`module-architecture`) applied to the one seam between the always-loaded file and the on-demand
ones: a pointer that reaches nothing must be distinguishable from a pointer that was never written.

The guard SHALL derive the pointer population from `CLAUDE.md` at test runtime rather than compare
against a maintained list, per "Gates fail closed on novelty": a sixth skill added to the block is
covered with zero guard edits.

The guard SHALL fail loudly rather than vacuously: if `CLAUDE.md` is absent, the pointer block's
marker is missing, or the derivation yields zero pointers, it SHALL fail rather than pass while
scanning nothing.

The guard constrains only the direction that can mislead an agent — a pointer naming a skill that
does not exist. A skill that exists with no pointer is permitted and SHALL NOT fail: the generated
`openspec-*` skills and `bugsink` are reachable by their own descriptions and by name, and a guard
that demanded a pointer for each would make `openspec update`'s regenerated output fail the build.

#### Scenario: A pointer names a skill that does not exist

- **WHEN** `CLAUDE.md`'s runbook block names a skill with no `.claude/skills/<name>/SKILL.md`
- **THEN** the guard fails, naming the pointer and the path it expected

#### Scenario: A skill is renamed without its pointer

- **WHEN** a skill directory is renamed and the pointer in `CLAUDE.md` still names the old name
- **THEN** the guard fails, rather than leaving an agent to look for a skill that is not there

#### Scenario: A skill's frontmatter name disagrees with its directory

- **WHEN** `.claude/skills/<dir>/SKILL.md` declares a `name:` other than `<dir>`
- **THEN** the guard fails, because the invoked name and the resolved path must be the same string

#### Scenario: A skill without a pointer passes

- **WHEN** a skill exists that no `CLAUDE.md` pointer names — including the generated
  `openspec-*` skills and `bugsink`
- **THEN** the guard passes, because only the dangling direction can mislead an agent

#### Scenario: The guard is not vacuous

- **WHEN** `CLAUDE.md` is absent or renamed, its pointer-block marker is missing, or the derivation
  yields zero pointers
- **THEN** the guard fails rather than passing while inspecting nothing

### Requirement: The platform-vocabulary pin

For every Apple enumeration an adapter decodes with a **fallback arm**, `:test:architecture` SHALL
pin the complete set of constants that enumeration declares, with their exact values, and SHALL fail
the build on any delta — a constant added, removed, renamed, or re-valued.

The source of truth SHALL be the **Kotlin/Native platform klib** the build resolves, not a vendor
header, a documentation page, or a device observation. That klib is the compiler's own input, so it
states exactly what our source sees; reading it needs no Mac and no Xcode, and the pin therefore runs
on Linux inside `./gradlew build` rather than on macOS CI. Because the platform klibs ship prebuilt
inside the Kotlin/Native distribution, the declared set changes when the **Kotlin/Native version**
changes — so the pin fails on the version-bump pull request that introduces the new vocabulary, which
is the earliest moment the change is visible to anyone.

This is the inward mirror of "Runtime identity is pinned": that requirement pins literals **we** hold
which the OS also holds, so we cannot strand devices in the field; this one pins literals **Apple**
holds which we encode, so Apple cannot widen a vocabulary we decode without saying so. It is also the
first guard whose input is the toolchain's platform metadata rather than this repository's own source,
and it is aimed squarely at the blindness "The platform-identifier gate" already declares: that a
lexical scan cannot see a decoder over another system's values, and SHALL NOT be assumed to catch one.

A fallback arm is unavoidable in the decoders themselves — cinterop renders `NS_ENUM` as a type alias
over `NSInteger` plus loose constants, never a Kotlin `enum class`, so a `when` over one can never be
compiler-exhaustive. The pin is what supplies the exhaustiveness the language cannot.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing an entry
is a spec change to this requirement, deliberately):

- **`PHAssetResourceUploadJobState`** — `Registered` = 1, `Pending` = 2, `Failed` = 3,
  `Succeeded` = 4, `Cancelled` = 5. Decoded by the PhotoKit upload adapter's job-state table
  (capability `ios-photokit-upload`). An untaught state reaching the terminal-job drain is adjudicated
  as a retry-spent failure, which is safe but wrong.
- **`PHAssetResourceType`** — decoded by `photoKitResourceRole` (capability `gallery-status`), whose
  fallback **drops** the resource. An untaught original resource type is therefore a photo that never
  uploads, with no error anywhere — the silent-failure class this project treats as the worst outcome.

**What it does not cover, stated so a green run is not over-read:** the pin describes what the SDK
*declares*, not what the OS *returns*. A device may hand back a value no header carries, and the klib
reflects the SDK the Kotlin/Native distribution was built against rather than the iOS version on the
device. A green pin is therefore not a promise that a decoder's fallback arm is unreachable, and the
fallback arms SHALL remain load-bearing and SHALL keep handling an unrecognised value safely. Only a
device measurement settles what the runtime actually produces.

Decision record: `changes/archive/2026-08-09-extract-upload-platform-mappings`.

#### Scenario: A toolchain bump widens a pinned enumeration

- **WHEN** a Kotlin/Native version bump ships a platform klib in which a pinned enumeration declares a
  constant the inventory does not carry
- **THEN** `./gradlew build` fails on that pull request, naming the enumeration and the new constant
  with its value, so the decoder is taught before the bump merges

#### Scenario: A pinned constant changes value or disappears

- **WHEN** a pinned constant is removed, renamed, or bound to a different value in the resolved
  platform klib
- **THEN** the build fails naming the affected entry, rather than leaving a decoder silently mapping a
  value that no longer means what it meant

#### Scenario: The pin runs without a Mac

- **WHEN** the guard executes on Linux, where no Xcode and no Apple SDK is present
- **THEN** it resolves the platform klib from the Kotlin/Native distribution the build already
  provisions and completes normally, so the pin gates the required build rather than macOS CI alone

#### Scenario: An undeclared runtime value is out of scope

- **WHEN** a device returns a value for a pinned enumeration that appears in no SDK declaration
- **THEN** the pin is silent by construction, and the decoder's fallback arm handles the value safely —
  the guard's green result is never read as evidence that such a value cannot occur

### Requirement: Source contributed into a shell's source set is shell source for the gates
The shell gates SHALL scan every source directory that is compiled into a `:app:*` module, including
directories contributed from another module by the build script. The scanned-root list of the detekt gate
and the mirrored list in its non-vacuity guard SHALL name such directories explicitly, and SHALL move
together.

The gates select their input **by path**, not by Gradle source-set membership, so a directory that
compiles into a shell but lives outside the shell's own tree is invisible to them by default — and the
shell's decision-free guarantee would then be true only of the part of the shell someone remembered to
list. A documented exemption is not an acceptable substitute: a rule that a reader must remember is the
failure mode these gates exist to remove.

#### Scenario: A contributed directory is scanned like any other shell source
- **WHEN** a build script adds a source directory from another module to a `:app:*` module's source set
- **THEN** that directory appears in the shell gate's scanned roots, and a conditional placed in it fails
  the canonical build exactly as one in the shell's own tree would

#### Scenario: A contributed directory is not exempted by comment
- **WHEN** a contributed directory is left out of the scanned roots and its exclusion is recorded only as
  a comment
- **THEN** that is a defect: the exclusion SHALL be removed by listing the directory, or the directory
  SHALL not be contributed into a shell at all

### Requirement: The control channel's trigger coverage is derived, never hand-enumerated
Where a dev/test control surface exposes platform entry points, the set it exposes SHALL be **derived**
from the same entry-point population the entry-point guard derives, and every member SHALL be either
wired to a trigger or named in an exclusion list carrying its reason. A guard SHALL assert that the
derived population equals the wired set plus the excluded set, exactly, in both directions.

A hand-picked trigger list rots invisibly: a new OS callback simply cannot be driven, and the only symptom
is a test nobody wrote. Deriving it means adding an entry point fails the build until its disposition is
stated, which is the same bargain the entry-point guard already imposes.

An exclusion SHALL name the consequence that makes it safe. Re-invoking an entry point that registers
process-lifetime observers, or one that reads a process environment fixed for the life of the process, is
a defect rather than an omission, and the reason distinguishes the two.

#### Scenario: A new entry point is added without a trigger disposition
- **WHEN** a new platform entry point is added to a composition root
- **THEN** the coverage guard fails until the entry point is either wired to a trigger or excluded with a
  stated reason

#### Scenario: An exclusion is recorded without a reason
- **WHEN** an entry point is listed as excluded with no reason
- **THEN** the guard fails, because an unreasoned exclusion is indistinguishable from an oversight

#### Scenario: A wired trigger is removed
- **WHEN** a trigger is deleted but its entry point still exists
- **THEN** the guard fails until the entry point moves to the exclusion list with its reason

### Requirement: A dev/test control channel binds the loopback address only
A control channel served from inside the app SHALL bind the loopback address and no other. A guard SHALL
assert that the channel's source names no bind address but the loopback constant.

The channel forces OS callbacks and exposes event state, and the device it runs on is a phone attached to
whatever network it happens to be on. Widening the bind address is a one-token edit that reads as fixing a
connectivity problem, and nothing about the change would look like a security decision to the person
making it.

#### Scenario: A widened bind address fails the build
- **WHEN** the channel's source names any bind address other than the loopback constant
- **THEN** the guard fails, naming that the channel is reachable only through a host-side port forward

#### Scenario: The channel cannot bind
- **WHEN** the channel's bind fails — for example because a previous instance of the app is still alive
  and holding the port
- **THEN** the app SHALL continue to run unaffected, and the failure SHALL be logged at `Error` severity
  naming the address, the port, and that the channel is not listening — because a refused connection is
  otherwise indistinguishable from an app that is not running or a port forward that was never set up

### Requirement: The OS-receipt expiry line is pinned
The diagnostic line emitted when an OS-handler receipt is released on its deadline SHALL be pinned by a
guard, in the same manner as other cross-boundary literals.

The line is emitted on deadline-expiry paths and on no others, which makes its presence the only
authoritative answer to whether the app released a handler because its work finished or because the bound
fired. Any consumer reading it therefore treats **absence** as "the work finished" — so rewording the line
turns every consumer green while hiding exactly the class of defect it was watching for. The failure is
silent and in the dangerous direction.

**The pin SHALL cover the SET of emitters, derived from the source and compared in both directions**, not
one named file. More than one receipt type may bound a hold — a receipt that bounds work in flight, and one
that bounds a wait for a signal that may never arrive — and each reports a genuine expiry. Pinning a single
file leaves every other emitter rewordable with the guard still green, which is the same silent failure one
level down. Each declared emitter SHALL state which expiry it reports, and SHALL emit the line exactly once.

#### Scenario: The expiry line is reworded
- **WHEN** the text of any declared emitter's deadline-expiry log line changes
- **THEN** the pin guard fails, naming the consumers that read it as ground truth

#### Scenario: An undeclared emitter appears
- **WHEN** production source emits the pinned line from a file the inventory does not name
- **THEN** the guard fails until that emitter is declared with the expiry it reports, because an emitter
  nobody pinned can be reworded without any guard noticing

#### Scenario: The expiry line is emitted on a non-expiry path
- **WHEN** a code path that is not a deadline expiry emits the same line
- **THEN** that is a defect: absence of the line must remain equivalent to "the handler was released
  because the work completed"

### Requirement: OS completion handlers are held in one type

Holding an OS-supplied completion handler SHALL be confined to the single `:domain` `ports/` type that
bounds the hold and releases every outstanding handler (`BackgroundEventsReceipts`, capability
`ios-app-shell`). No other production source SHALL declare a **mutable** property whose type is a
nullary `Unit`-returning function — `var x: (() -> Unit)?`, `var x: () -> Unit`, or the `lateinit`
form — whether by import or by fully-qualified reference.

The rule **confines rather than forbids**, because storing the handler is the platform's own documented
recipe (*"You should then store that completion handler before creating a background configuration
object"*). What is unsafe is not the storing but the shape a bare field forces: a single slot has no
deadline, and a second handover silently overwrites the first, which costs the app its future background
wakes. Naming one home makes both properties provable in one tested place, in the same shape Keychain
access is confined to one module. Any exempt declaration SHALL state, at the exemption, why it is
exempt.

The guard SHALL fail when it reads no files, so a moved directory or a regex that stops matching fails
loudly instead of passing empty. A guard of this kind has already failed silently once: a prior version
matched on field **names** containing `ompletion`/`nComplete`, which passed a field of the exact
forbidden type — `IosUrlSessionUploadPlatform.onBackgroundEventsFinished` — in a directory it was
scanning. Matching the **type** is what makes the rule mean anything, and it is why that adapter's
callback slot becomes a constructor `val`: an allowlist for a field that is not an OS handler at all
would invite the next one.

The rule SHALL cover **both languages of the shell**. Apple's recipe stores the handler on the
`UIApplicationDelegate`, so a Swift property holding a `(() -> Void)?` is the likeliest reintroduction, and
a Kotlin-only rule would never see it.

**The rule's residue SHALL be stated where the rule is written**, not implied away. It catches *storing*,
not *releasing early*: an entry point that invokes its raw handler inline stores nothing and passes. It
does not match non-nullary or non-`Unit` handler shapes, a handler held inside a collection, or one behind
a type alias. It reads raw source text, so it also matches the shape inside a comment — prose must
describe such a declaration rather than quote it. Where a missed case appears, the rule SHALL be widened
rather than an exception added.

#### Scenario: A stored handler outside the owning type fails the build

- **WHEN** any production source other than the owning `ports/` type declares
  `var handler: (() -> Unit)? = null` or an equivalent mutable nullary-`Unit` function property
- **THEN** the guard fails the build

#### Scenario: A non-null or lateinit store is caught too

- **WHEN** the declaration avoids nullability — `lateinit var handler: () -> Unit` — to hold the same
  value
- **THEN** the guard still fails the build

#### Scenario: The owning type is exempt, with its reason recorded

- **WHEN** the owning `ports/` type holds handlers itself
- **THEN** the guard passes, and its allowlist entry states why that type is the one permitted holder

#### Scenario: A constructor parameter is not a stored handler

- **WHEN** a type takes its release action as a `val` constructor parameter, as the OS receipt does
- **THEN** the guard passes, because an immutable parameter can be neither overwritten nor left unbounded

#### Scenario: A handler stored in the Swift shell is caught

- **WHEN** a Swift shell property holds the completion handler, as Apple's own sample does
- **THEN** the guard fails the build, in that language

#### Scenario: The guard fails when it scans nothing

- **WHEN** the scanned roots match no files
- **THEN** the guard fails rather than reporting no violations

### Requirement: Production Kotlin declares no launch triggers

A test-only JVM guard SHALL assert that production Kotlin source declares **no** `"SNAPSYNC_*"` string
literal at all.

Dev/test control of a device is the control channel's surface (`:test:rig`), contained at compile time and
absent from every production build. A `SNAPSYNC_*` literal in production Kotlin is therefore a regression to
a surface this repo removed deliberately: a remote-control affordance present in every shipped binary, inert
only because a SpringBoard launch supplies no process environment — which is a property of how the app is
*started*, not of what it *contains*.

The guard SHALL be an **exact inventory** whose permitted set is empty, and the failure SHALL name every
literal found together with its file. It SHALL NOT be expressed as a maximum count: a count invites being
raised, and the previous guard's floor is what this requirement replaces.

Two readers are deliberately **out of scope**, both for the same reason — the file reading them does not
exist in a production build, so their inertness is a property of the module graph rather than a runtime
check:

- `SNAPSYNC_RIG_PORT`, read in the source `:test:rig` contributes into the shell under its build property;
- the forge target's state selector, read in the forge module's own source.

The scan SHALL therefore cover the production main source sets under `domain/`, `app/`, `adapter/` and
`ui/`, excluding test sources, `build/`, and the build-property-gated trees.

The guard SHALL fail loudly rather than vacuously: an empty *result* over a non-empty *scan* is the passing
condition, and a scan that resolves zero Kotlin files SHALL fail rather than pass while inspecting nothing.

#### Scenario: A launch trigger is re-added to production Kotlin

- **WHEN** a production main source set gains a `"SNAPSYNC_*"` literal
- **THEN** the guard fails, naming the literal and its file, so the trigger must be argued rather than
  landing unnoticed

#### Scenario: A gated tree may read one

- **WHEN** the source `:test:rig` contributes into the shell reads `SNAPSYNC_RIG_PORT`, or the forge module
  reads its state selector
- **THEN** the guard passes, because neither file is on a production build's compile path

#### Scenario: The guard is not vacuous

- **WHEN** the scanned roots are absent, renamed, or resolve to zero Kotlin files
- **THEN** the guard fails rather than passing while inspecting nothing

### Requirement: A KDoc block is never silently dropped

A `:test:architecture` guard SHALL fail the build when two KDoc blocks appear consecutively with only
blank lines between them and a declaration already appears earlier in the file.

The defect this pins is **invisible in review and invisible at runtime**. Kotlin binds only the *last*
KDoc block preceding a declaration; an earlier one is neither an error nor a warning, and the text
simply stops being that declaration's documentation. It arises the same way every time: someone adds a
revised rationale or an "Absence:" note as a *second* block rather than merging into the existing one,
and the block they meant to keep is the one that disappears. Eleven sites accumulated this way, and what
was dropped was load-bearing — the one-line summaries of what `AttestStore.token()` and `keyId()`
return, and the only statement of why the upload lifecycle lives in tested `:domain` rather than the
untested iOS composition root.

The **file-header convention is exempt by construction**, not by an exception list. A file-level KDoc
documenting the file as a whole can only be the first block in the file, so requiring that a declaration
already appear earlier excludes every such header without naming one. A list of permitted sites would
itself be a duplicate that goes stale, which is the failure this capability exists to prevent.

The guard SHALL scan **every** `.kt` source in the repository, test sources included: a dropped block
costs the next reader the same either way, and scoping to production would be a boundary to maintain
for no gain.

The guard SHALL carry **non-vacuity assertions** in the manner of `LawsDigestTest` — a change that
empties its extraction SHALL fail here rather than pass silently, because a guard that scans nothing
reports the same green as a guard that finds nothing.

This guard pins a **documentation** invariant rather than a structural one, which is deliberate and
narrow: it is admissible because the rule is mechanical and total — the compiler's own binding rule,
not a style preference — and because the failure is silent. It SHALL NOT be widened into a general
prose or content check; whether documentation is *correct* remains unguarded, and no green build is
evidence that it is.

#### Scenario: A second KDoc block silently drops the first

- **WHEN** a declaration is preceded by two consecutive KDoc blocks, so Kotlin binds only the last
- **THEN** the guard fails the build, naming the file and the line the dropped block opens on

#### Scenario: A file-level header above a documented declaration passes

- **WHEN** a file opens with a KDoc block documenting the file, immediately followed by the KDoc of the
  file's first declaration
- **THEN** the guard passes, because no declaration precedes the header

#### Scenario: A broken extraction fails rather than passing empty

- **WHEN** a change makes the guard's source scan match nothing
- **THEN** the guard fails, rather than reporting success over an empty set

### Requirement: No reader is left behind on a moved deployment key

A guard SHALL assert that no file in the repository, other than the resolver itself, extracts from the
committed `Config.xcconfig` a build setting that `Config.xcconfig` does not itself assign. The guarded set
SHALL be derived from that file's own assignments rather than from any enumeration of keys that have
moved, so a key is covered whichever rendering it moves to — the build-settings fragment, the bundled
property list, or one not yet invented — and covered from the moment it moves.

The guard SHALL scan the repository's text surfaces, including `.claude/skills/`, because the reader that
motivated it was a skill rather than source code. It SHALL match a setting being **read out of** that
file, and SHALL NOT match a file merely naming both, so that `Config.xcconfig`'s own header comment
documenting what the generated renderings carry, and the design records discussing the split, remain
passable. An extraction SHALL be attributed to the file it actually reads, resolved from the extraction
site itself — the filename it names, or the variable it reads — and never from mere proximity to a
filename mentioned elsewhere.

The guard SHALL fail loudly rather than vacuously: it SHALL assert that the scanned file set is non-empty
and that the committed file's assignments parsed non-empty, so that a parse regression cannot make it pass
by finding nothing to guard.

A reader left behind does not raise anywhere. The file it reads still exists and is still readable; it
simply no longer assigns the setting, so the extraction yields the empty string and the reader proceeds.
Where that value composes a signing identity, the result is a validly signed binary claiming the wrong
identity — which every downstream validity check passes, and which the existing wildcard guard cannot see,
because absence of a wildcard is not presence of the right prefix.

#### Scenario: A read of a moved setting from the committed file fails the build

- **WHEN** any file other than the resolver extracts from `Config.xcconfig` a setting it does not assign
- **THEN** the guard fails the build, naming the file and the setting

#### Scenario: A newly moved key is covered without editing the guard

- **WHEN** a key moves out of `Config.xcconfig` into ANY rendering, and a reader still extracts it from
  `Config.xcconfig`
- **THEN** the guard fails, without that key having been named anywhere in the guard

#### Scenario: A setting the committed file still assigns is not a violation

- **WHEN** a reader extracts from `Config.xcconfig` a setting `Config.xcconfig` assigns
- **THEN** the guard passes, because that read resolves a real value

#### Scenario: Documenting the split is not a read

- **WHEN** a file names a moved key and `Config.xcconfig` without extracting the one from the other — as
  `Config.xcconfig`'s own header comment does
- **THEN** the guard passes

#### Scenario: An extraction from another file is not attributed by proximity

- **WHEN** a file extracts a setting from some other file, near an unrelated mention of `Config.xcconfig`
- **THEN** the guard passes, because provenance is resolved from the extraction site, not from nearness

#### Scenario: The guard is not vacuous

- **WHEN** the scanned file set is empty, or the committed file's assignments fail to parse
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when any scanned surface changes

- **WHEN** a file in any surface the guard scans is edited, and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

The scanned set and the task's declared inputs SHALL name the same surfaces. A guard whose subject is
wider than its declared inputs stops running silently — which is indistinguishable from a guard that
runs and finds nothing.

### Requirement: The transport-binding gate

`:test:architecture` SHALL pin, by source text, which `URLSession` configuration each iOS target's
transport-session seam yields (`ios-url-session-upload`, "The transport binding is fixed by the compilation
target"). The gate SHALL assert, **exactly in both directions**:

- the `iosArm64` actual — the one every shipped binary links — names
  `backgroundSessionConfigurationWithIdentifier`; and
- the `iosSimulatorArm64` actual does **not** name it.

A source-text gate is the mechanism here because no executable test can reach the artefact that matters.
This repo's iOS tests run on `iosSimulatorArm64` and nothing else, so the **device** actual is never
executed by anything in CI; a swap of the two actuals, or a "simplification" that gives both targets the
default configuration, would ship a foreground session to real users and pass every existing gate,
including `codesign`, the build, and the whole `iosSimulatorArm64Test` suite. This is the same reasoning
that makes "Keychain access is confined to one module" a text gate — it catches what no linter can see on
`iosMain`.

The gate SHALL fail on a missing actual as well as on a wrong one, so deleting a target's actual is not a
way past it. Adding a third iOS target SHALL require extending this pin rather than silently escaping it,
by the rule in "Gates fail closed on novelty".

The gate SHALL NOT assert anything about the *runtime behaviour* of either session — that a background
session transfers on a device, or that a default session does not survive suspension. Those are platform
facts with their own forcing proofs and expiry triggers in `ios-url-session-upload`, and a text gate
cannot evidence them. The stated residual gap: this pin shows the device actual **names** the background
factory, never that the resulting session behaves; only a device run shows that.

#### Scenario: Swapping the two actuals fails the build

- **WHEN** the `iosArm64` actual is changed to yield a default session configuration
- **THEN** `./gradlew build` fails on the transport-binding gate, naming the file and the expected literal

#### Scenario: A simulator actual that reaches for the background factory fails the build

- **WHEN** the `iosSimulatorArm64` actual is changed to name `backgroundSessionConfigurationWithIdentifier`
- **THEN** the gate fails, because the pin is exact in both directions

#### Scenario: A deleted actual fails the build

- **WHEN** either target's actual is removed
- **THEN** the gate fails rather than passing vacuously

### Requirement: The simulator transport binding is asserted where it can be executed

`:adapter:ios:app-only` SHALL carry an `iosSimulatorArm64` test asserting that the seam yields a
configuration with a **nil** session identifier on that target, and that the reported binding names the
default one. This is the executable half of the pin above, and it covers what the text gate cannot: that
the actual selected by the build for this target really produces a non-background configuration, rather
than merely being spelled that way.

The two halves SHALL NOT be collapsed into one. The text gate covers the target no test can run; this test
covers the behaviour text cannot show. Neither subsumes the other.

#### Scenario: The simulator seam yields a default configuration

- **WHEN** the seam is called on `iosSimulatorArm64`
- **THEN** the returned configuration's identifier is nil, and the reported binding names the default one

### Requirement: The scene-record completeness gate

A gate SHALL fail the build when the iOS shell's scene-mode resolution gains a second caller, or when the
scene generation it advances gains a second writer.

Decision record: `changes/archive/2026-08-26-stop-rebuilding-the-composed-scene`.

The shell answers the UI framework's rebuild signal from a counter it advances as it hands each scene out
(capability `ios-app-shell`). That counter describes what is actually installed **only because a single
function is the sole path by which a scene is obtained**. A second caller would either install a scene the
counter never saw or advance the counter without installing anything, and in both cases the signal would
answer for a scene other than the one on screen — which is the defect the rule exists to prevent, restored
by a different route.

The property is invisible to the compiler: the resolver is module-internal, so any call site inside the
iOS shell module is legal and silent. It is also invisible to review after the fact, because the damage
appears one activation later and on a device rather than at the call site.

The gate SHALL read source text rather than a resolved symbol model, because the shell's source set is not
on the JVM test classpath — the same constraint the Keychain-containment gate works under.

The gate SHALL state, at its failure, that a new call site is not to be allowlisted reflexively: the
question it forces is whether the new caller **installs** the scene it receives. A caller that installs
keeps the count complete and warrants widening the gate deliberately; a caller that merely inspects the
mode corrupts the count and should read it another way.

#### Scenario: The scene resolver gains a second caller
- **WHEN** any file in the iOS shell module other than the platform entry point calls the scene-mode
  resolver
- **THEN** the gate fails and names the offending callers

#### Scenario: The generation gains a second writer
- **WHEN** the scene generation is assigned anywhere other than where the scene mode is resolved
- **THEN** the gate fails, because a write with no scene handed out moves a signal the UI framework
  rebuilds on

#### Scenario: The gate is proven to fail
- **WHEN** the guard is introduced or changed
- **THEN** each of its assertions is shown to fail for the right reason against a deliberate violation, so
  a gate that can never go red is not mistaken for a property that always holds
