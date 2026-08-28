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

Agreement is **constructed rather than asserted**: each copy is generated from the one resolution, so a
copy cannot drift. The guarantee reaches copies no hand-written pin ever inspected — the compile-time
upload host and the site's canonical URLs were both unpinned before the resolver landed.

A test-only JVM guard SHALL remain, and SHALL assert the properties that generation does **not** already
make true:

- no artifact the app or backend reads declares the domain as a **hand-written host literal** rather than
  deriving it from the resolved deployment;
- the app entitlement references the build setting rather than hard-coding a host;
- the extension claims no associated domain, and the retired custom URL scheme stays retired;
- the guard fails **loudly rather than vacuously** — if a file it inspects has moved, been renamed, or no
  longer contains the marker it expects, it SHALL fail rather than silently scanning nothing.

The guard SHALL NOT assert that a generated artifact matches the deployment it derives from. That
assertion cannot fail: the build runs the deployment resolver before the guard reads its output, so the
artifact is regenerated from the same resolution moments earlier and staleness is eliminated before the
check. A check that cannot fail is not a guard, and stating it as one overstates what the build proves.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

#### Scenario: A hand-written host literal reappears
- **WHEN** any inspected artifact declares the domain as a literal rather than deriving it from the
  resolved deployment
- **THEN** the guard fails, naming the artifact and the literal

#### Scenario: Every copy is constructed, not restated
- **WHEN** the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's domain, the
  compile-time upload host, and the site's canonical URLs are inspected
- **THEN** each is derived from the resolved deployment, and none is a hand-written host literal

#### Scenario: The guard is not vacuous
- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing artifacts pass
- **WHEN** no inspected artifact carries a host literal and every marker is present
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

Every architecture gate SHALL derive its **scope** — what it scans — from the repository's structure
at test runtime: directory listings for feature enumeration, package patterns for zones, "everything
not allowlisted" for purity. A scope SHALL NOT come from a hand-maintained inclusion list. The only
permitted scope list is loud-when-stale: the per-zone library allowlists.

A gate MAY pin an **expected value** — the answer its scope must produce — as a literal table, and
several must: the OS-held literals of `RuntimeIdentityTest`, the Apple-declared enum sets of
`PlatformVocabularyPinTest`, the shell suppression inventory of `KotlinShellGuardTest`. A pin is what
a guard asserts, not where it looks, and is therefore not an inclusion list. Every pin SHALL carry
the reason its value is fixed and what would change it.

The module set is **no longer** a permitted scope list. Its expected value is derived from
`module-architecture`'s own enumeration at test runtime, so the spec is its single home; a gate
holding a second copy tethers the build to itself and leaves the spec unwatched.

Every gate SHALL keep a non-vacuity twin proving it scanned a non-empty scope. Where a gate derives
several groups from one source, it SHALL keep a twin **per group**: a reword that empties one group
leaves the others making the gate look alive. Zone gates SHALL match source text (fully-qualified
references import nothing), not import lists.

#### Scenario: New code is born in scope
- **WHEN** a new feature package, flow file, port, or adapter is added
- **THEN** every applicable gate covers it with zero gate edits

#### Scenario: A gate's scope silently empties
- **WHEN** a rename or restructure removes everything a gate scans
- **THEN** the gate's non-vacuity twin fails rather than the gate passing forever

#### Scenario: One derived group of several empties
- **WHEN** a heading or label a gate parses is reworded so that one of its derived groups resolves
  to nothing, while the other groups still resolve
- **THEN** that group's own non-vacuity twin fails, rather than the gate passing on the strength of
  the groups that still parse

#### Scenario: A pinned expected value is mistaken for a scope list
- **WHEN** a guard holds a literal table of the values its scan must produce
- **THEN** it is a pin, not an inclusion list, and is permitted provided it states why the value is
  fixed and what would change it

### Requirement: The zone gates

The zone boundaries inside the core SHALL be enforced by the **module graph** wherever a module can
withhold them, and by a derived text gate only where it cannot.

`:domain` SHALL be split into per-zone modules — `:domain:model` ← `:domain:ports` ← `:domain:feature` ←
`:domain:flow` ← `:domain:compose` — each declaring only the zone dependencies its law permits, so a
reference across a forbidden edge does not resolve. Compilation therefore enforces: `model/` references
nothing project-internal outside `model/`; `ports/` references only `model/`; `flow/` references only
`model/` and `feature/`; and `:ui:presentation` references only `model/`, feature read-model types, and
the injected flow command bundle. These four properties SHALL NOT also be asserted by a text gate: a
module boundary is unresolvable rather than merely forbidden, and unlike a text scan it covers generated
source and typealias re-exports.

Zone modules SHALL depend on one another with `implementation()` rather than `api()`, so a zone cannot
leak transitively to a downstream consumer.

Two properties remain outside what the module graph can express at acceptable cost, and SHALL remain
derived text gates:

- **features are mutually blind** — a feature references only `model/` and `ports/`, never a sibling
  feature (pairwise, features enumerated from the directory listing). Nine features cannot be nine
  modules;
- **`flow/` declares no `CoroutineScope` and accepts no non-suspend effect lambda** (law *A trigger flow
  never outlives its own run* — both doors, because removing the scope alone leaves the lambda one open).

A text gate SHALL NOT pass when its scope is absent: a missing or renamed zone directory SHALL fail the
build, never report itself pending. A gate that reports "pending" when its subject has moved is a gate
that fails open.

The `:domain` tree SHALL have no `iosMain` source directory, and `:domain` and `:ui` zones SHALL import
only their per-zone allowlisted libraries.

#### Scenario: A forbidden zone reference does not compile
- **WHEN** a file in a zone module references a declaration from a zone its module does not depend on,
  by import, fully-qualified name, or typealias
- **THEN** the reference does not resolve and the build fails at compilation, in that module

#### Scenario: A feature reaches a sibling
- **WHEN** a file under `feature/<a>/` references `feature/<b>/`
- **THEN** the feature-blindness gate fails, naming the file and both features

#### Scenario: A flow reacquires a way to detach
- **WHEN** a `flow/` class gains a `CoroutineScope` parameter or a non-suspend effect lambda
- **THEN** the gate fails, naming the file, before any device build

#### Scenario: A zone directory is renamed
- **WHEN** a scanned zone directory no longer exists under the path a text gate scans
- **THEN** the gate fails naming the absent scope, and does not report itself pending

### Requirement: The extension-safety text gate

The build SHALL fail when extension-linked Kotlin references any `platform.*` framework outside an
**allowlist of permitted frameworks**. The gate SHALL be expressed as that allowlist rather than as a
denylist of forbidden frameworks, and the allowlist SHALL name each permitted framework and SHALL be
small enough to read.

Inversion is required because `NS_EXTENSION_UNAVAILABLE` cannot be enumerated: cinterop drops the
attribute entirely, so it is absent from the platform klibs and from every artifact available to the
build. A denylist therefore covers only the frameworks someone remembered, while an allowlist covers
every framework — including ones Apple has not shipped yet — and fails closed on novelty.

The gate SHALL match **imports and fully-qualified references**, never raw text: `platform` is also a
local variable name in this codebase, so a text match yields false positives on ordinary member access.

The scanned scope SHALL be **derived from the extension binary's project-dependency closure**, not from a
maintained list of roots, so a module newly linked into the extension is covered without a gate edit. The
gate SHALL fail if its derived scope is empty.

Expiry trigger: Kotlin/Native gaining extension-availability modelling, at which point the compiler
supersedes this gate.

#### Scenario: App-only API inside extension-linked code
- **WHEN** extension-linked source references a `platform.*` framework outside the allowlist
- **THEN** the gate fails before any device build, naming the file and the framework

#### Scenario: A framework Apple adds later
- **WHEN** extension-linked source references a platform framework nobody anticipated
- **THEN** the gate fails, because the framework is not on the allowlist, without any gate edit having
  been required to anticipate it

#### Scenario: A module joins the extension's link set
- **WHEN** the extension binary gains a dependency on a module the gate has never scanned
- **THEN** that module's source is in scope automatically, because the scope is derived from the
  dependency closure

### Requirement: The shell gates

The build SHALL enforce zero conditionals in the iOS app shells' Kotlin via a detekt complexity gate
(threshold: no function above cyclomatic complexity 1 beyond pinned wiring forms), **gating**
(`ignoreFailures = false`, wired into `check`) over **every** shell source root, asserted by a test
with a non-vacuity floor (`KotlinShellGuardTest`: the scanned source roots exist and are non-empty —
a stale source list after a module rename must fail, never pass vacuously).

The gate's scope SHALL be **named rather than implied**. It covers the iOS app shell, the iOS
upload-extension shell, the iOS forge shell, and source contributed into a shell's source set under a
build property. It does **not** cover `:app:desktop`: that module is test equipment hosting two
harness applications, it has never been scanned by this gate, and it is measured as harness under
capability `complexity-budgets`. The requirement previously read "all production `:app:*` source
sets" — a claim wider than the implementation, in the direction that reads as reassurance, and one
whose gap was real: an `:app:*` iOS shell module was absent from both the build file's source list
and the guard's mirror of it, so the shells' decision-free guarantee held only of the part someone
had remembered.

The shell gate is a **structural proof and not a complexity budget**, and SHALL remain distinct from
the per-scope ceilings that now surround it (capability `complexity-budgets`). Its value comes
precisely from its threshold being the decision-free one: raising it to a number the wider tree
passes would destroy the claim. The two gates answer different questions and SHALL NOT share a
number, a configuration, or a task.

Because detekt honors `@Suppress`, the suppression IS the Kotlin pin mechanism, and the same guard
SHALL pin the suppression inventory exactly, in both directions (per file, by count): a new
`@Suppress("CyclomaticComplexMethod")` fails until it is argued into the table with a forcing proof
at the suppression site, and a removed one fails until the table shrinks. The Swift shells SHALL be
guarded by a pinned-structure text check: decision keywords (`if`, `guard`, `switch`, `??`) may
appear only at the explicitly pinned occurrences, each pin carrying its forcing proof in the failure
message.

The Swift guard SHALL additionally assert that **every function in a Swift shell forwards to
Kotlin**: a shell function either calls the composition root or does not exist. A Swift function
that handles a platform callback without reaching Kotlin is invisible by construction — the shells
are wiring-only and untested by project rule, and platform logging redacts interpolated messages —
so a callback that only writes a Swift-side log line, or deliberately does nothing, records
nothing anywhere. Two such holes existed when this rule was written: the extension's termination
callback (the OS announcing it is killing the upload cycle) and the push-registration failure
handler.

#### Scenario: A decision creeps into a shell
- **WHEN** a branch is added to a shell's Kotlin or an unpinned decision keyword to a Swift shell
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

#### Scenario: Every shell module is scanned
- **WHEN** the gate runs
- **THEN** every iOS shell module registered in the build — the app shell, the extension shell, and
  the forge shell — is among the scanned roots, and so is source contributed into a shell's source
  set under a build property

#### Scenario: The shell gate is not a complexity budget
- **WHEN** a scope outside the shells needs a complexity ceiling
- **THEN** it is given one under capability `complexity-budgets`, and the shell gate's threshold is
  left at the decision-free value rather than raised to accommodate it

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

### Requirement: The migration's laws are permanent gates

Every law the migration beacon measured SHALL be enforced permanently in `:test:architecture`
under `./gradlew build`. (The module-architecture migration is complete; its beacon — the
detached burn-down module and the non-required `verify` job — measured zero on every law at the
finale and was deleted, per its own contract.) The promoted gates:

- **Module-set equality**: the `settings.gradle.kts` include set SHALL equal the union of the
  groups `module-architecture` enumerates, **derived from that spec's text at test runtime** — the
  gate SHALL NOT hold its own copy of the set. Adding or deleting a module fails until the spec is
  consciously amended with the group the module joins and the argument for that group. The failure
  SHALL name all three groups and what each requires, because "must withhold a dependency" is the
  right instruction for only one of them. The gate SHALL keep a non-vacuity twin per group.
- **Deletion ledger**: the migration's retired dead weight SHALL stay dead — the zxing and
  kotlincrypto catalog entries, the `capability/` tree, the device-manifest accumulator, the
  Arrow/ArrowLevel duplicate enum, and any second `*Enrollment` uploader. Resurrection is not
  forbidden forever; it is forbidden **silently** — bringing an item back means deleting its guard row
  in the same commit, with the argument in the PR. The guard SHALL assemble its patterns so its own
  source never matches them (the beacon's self-match lesson).

  The ledger SHALL NOT carry rows that retire a declaration for being **single-implementation
  interface ceremony**. That judgement was overturned when `LeaveNotifier` was brought back: a
  **port** is not an interface justified by a second implementation, it is the declared boundary
  where the core stops and an external system begins, and with the interface gone the composition
  carried the crossing as an opaque closure instead — invisible to every gate that reads types. Rows
  resting on the overturned judgement (`LedgerReader`, `LoggingPushReceiver`, `EventMetadataSource`)
  are retired from the ledger, because a ledger row that would block a correct change is worse than
  no row.
- **Shells** and **zones** are gated by their own standing requirements (the shell gates; the zone
  gates), now all armed and gating.

The mixed port/impl file rule is retired as a standing gate: with `ports/` a module that withholds Ktor
and SQLDelight, a port interface declared beside a technology import does not compile, and an interface
declared inside an adapter beside its own implementation is ordinary Kotlin rather than a defect.

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

#### Scenario: A port interface is written beside its technology implementation
- **WHEN** an interface is declared in `:domain:ports` alongside a Ktor or SQLDelight import
- **THEN** the import does not resolve, because the module withholds those dependencies

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
and it is aimed squarely at a blindness no lexical scan can cover: a decoder over another system's
values carries no import and no distinctive token, so scanning source cannot see one and SHALL NOT be
assumed to catch it. (That blindness was previously stated by "The platform-identifier gate", retired in
this change once the JVM target was measured to reject Apple type references outright.)

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

### Requirement: The token-rejection route into the trust feature is pinned

A guard SHALL assert that the iOS root still routes a rejected credential from the shared HTTP client into
the attestation feature — that the client is constructed with a rejection hook, and that the hook reaches
the feature's rejection entry point and triggers a refresh.

This one wiring cannot be moved into the shared composition, and the reason is a construction cycle, not an
oversight: the composed core's ports are built over the HTTP client, and the client reads its credential —
and reports its rejection — from the core. Two lazy bindings break the cycle, and `:domain` is platform-free
so it cannot build the platform client itself. Something outside the composition must hand the core's
callback to the client, and that something is the shell by definition.

The guard therefore covers what testing cannot reach here. Both sides of the route are tested — the client's
interceptor in its adapter module, the feature's rejection handling in its own suite — but the join lives in
shell source, which is wiring-only and untested by law and invisible to the world harness. Without the pin,
deleting a lambda whose purpose is not legible at its call site leaves every test green while the app loses
its only recovery from a rejected credential.

The guard SHALL assert presence, not behaviour: it establishes that the route is still connected, which is
the failure mode that would otherwise rot silently. It SHALL fail closed if the source it scans cannot be
found, so it cannot pass by scanning nothing.

#### Scenario: Removing the wiring fails the build

- **WHEN** the root no longer passes the rejection hook into the shared client, or the hook no longer
  reaches the attestation feature
- **THEN** the guard fails

#### Scenario: The guard cannot pass vacuously

- **WHEN** the shell source the guard scans is absent or renamed out from under it
- **THEN** the guard fails rather than reporting success over nothing

### Requirement: The upload-job subsystem binding gate


`:test:architecture` SHALL pin, by source text, which implementation each iOS target binds for the **OS
upload-job subsystem** — the registration record and the job queue (`ios-photokit-upload`, "The upload-job
subsystem binding is fixed by the compilation target"). The gate SHALL assert, **exactly in both
directions**:

- the `iosArm64` actuals name the PhotoKit APIs — `setUploadJobExtensionEnabled` and
  `creationRequestForJobWithDestination`; and
- the `iosSimulatorArm64` actuals name **neither**.

A source-text gate is the mechanism for the same reason the transport-binding gate uses one: this repo's
iOS tests run on `iosSimulatorArm64` and nothing else, so the **device** actual is never executed by
anything in CI. A swap of the two actuals would ship a binary whose uploads are inert to real users and
would pass the build, `codesign`, and the whole `iosSimulatorArm64Test` suite.

The stakes are higher here than for the transport binding, and in the opposite direction. Reaching the
PhotoKit job creation on a simulator does not degrade — it raises an uncaught `NSInvalidArgumentException`
from inside PhotoKit and terminates the process. So a mis-bound simulator actual destroys the host it was
meant to serve, with a crash whose stack names Apple's frames rather than ours.

The gate SHALL fail on a missing actual as well as on a wrong one, so deleting a target's actual is not a
way past it. Adding a third iOS target SHALL require extending this pin rather than silently escaping it,
by the rule in "Gates fail closed on novelty".

The gate SHALL NOT assert anything about the *runtime behaviour* of either binding — that the PhotoKit
subsystem accepts a registration on a device, or that it refuses one on a simulator. Those are platform
facts with their own forcing proofs and expiry triggers in `ios-photokit-upload`, and a text gate that
claimed them would be asserting what it cannot observe.

#### Scenario: The device actual loses its PhotoKit call
- **WHEN** the `iosArm64` binding stops naming `setUploadJobExtensionEnabled` or
  `creationRequestForJobWithDestination`
- **THEN** the gate fails, because a shipped binary would register nothing and create no upload job

#### Scenario: The simulator actual gains a PhotoKit call
- **WHEN** the `iosSimulatorArm64` binding names either PhotoKit API
- **THEN** the gate fails, because reaching that call on a simulator terminates the process

#### Scenario: A target's actual is deleted
- **WHEN** either target's actual is removed
- **THEN** the gate fails on the missing actual rather than passing vacuously

#### Scenario: A third iOS target is added
- **WHEN** a new iOS compilation target is introduced
- **THEN** the gate fails until the pin names that target's binding explicitly

### Requirement: The screen-state gate

`:ui:screens` SHALL hold no Compose-remembered state outside a named allowlist, and every allowlist
entry SHALL state why its state is HOW the screen draws rather than WHAT it shows (capability
`sync-status-screen`). The gate SHALL also fail on an allowlist entry whose file no longer exists or no
longer holds state, so a spent permission cannot read as a standing one.

This is the mechanical half of "what the screen SHOWS is `UiState`". Without it the rule is a comment:
the drift it exists to catch — a value the screen renders that no state carries — produced a banner one
host could not render at all, and neither the build nor any test said so.

#### Scenario: New screen-held state fails the build
- **WHEN** Compose-remembered mutable state is declared in `:ui:screens` outside the allowlist
- **THEN** the gate fails, naming the declaration

#### Scenario: An allowlist entry that stopped being used fails too
- **WHEN** an allowlisted file no longer holds state, or no longer exists
- **THEN** the gate fails rather than leaving a permission nobody is using

### Requirement: The event-name limit gate

The client's event-name cap SHALL equal the backend's, asserted from source: `model/`'s
`EVENT_NAME_MAX_LENGTH` against `api/src/validators.ts`'s `MAX_EVENT_NAME_LENGTH` (capability
`event-creation`). No screen SHALL state a name cap as a literal.

The backend owns the rule and the client mirrors it so an over-long name is unreachable rather than
rejected on a round trip — which makes the mirror useful only while it agrees. Nothing else can notice a
disagreement: a raised backend limit makes the client refuse names the server would take, a lowered one
makes it offer names the server will reject, and the only symptom of either is a `400` the member cannot
act on while naming their event.

#### Scenario: The two limits diverge
- **WHEN** either constant changes without the other
- **THEN** the gate fails, naming both values

#### Scenario: A screen restates the cap as a literal
- **WHEN** a name field's `maxLength` is written as a number instead of the shared constant
- **THEN** the gate fails, naming the file and line
