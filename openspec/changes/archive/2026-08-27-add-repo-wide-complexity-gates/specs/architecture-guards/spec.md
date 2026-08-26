## MODIFIED Requirements

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
