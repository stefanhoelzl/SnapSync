## ADDED Requirements

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

## MODIFIED Requirements

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
