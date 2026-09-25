## MODIFIED Requirements

### Requirement: The zone gates

The zone boundaries inside the core SHALL be enforced by the **module graph** wherever a module can
withhold them, and by a derived text gate only where it cannot.

`:domain` SHALL be split into per-zone modules — `:domain:model` ← `:domain:ports` ← `:domain:feature` ←
`:domain:flow` ← `:domain:compose`, with `:domain:presentation` (over `model` and `feature`) and the host
`:domain:host` (over `model`, `ports`, `feature`, `compose` and `presentation`) — each declaring only the
zone dependencies its law permits, so a reference across a forbidden edge does not resolve. The permitted
edges are the module-set gate's expected value (`ModuleSetTest`, "the core declares only permitted zone
edges"). Compilation therefore enforces: `model/` references nothing project-internal outside `model/`;
`ports/` references only `model/`; `flow/` references only `model/` and `feature/`; presentation
references no zone but `model/` and `feature/`; and the host references neither `flow/` nor any module
outside the core. A property that a module boundary can hold SHALL NOT also be asserted by a text gate:
a module boundary is unresolvable rather than merely forbidden, and unlike a text scan it covers
generated source and typealias re-exports. The read-model rule below is not such a property, because it
draws its line inside the one `feature/` module.

Zone modules SHALL depend on one another with `implementation()` rather than `api()`, so a zone cannot
leak transitively to a downstream consumer.

Three properties remain outside what the module graph can express at acceptable cost, and SHALL remain
derived text gates:

- **features are mutually blind** — a feature references only `model/` and `ports/`, never a sibling
  feature (pairwise, features enumerated from the directory listing). Nine features cannot be nine
  modules;
- **`flow/` declares no `CoroutineScope` and accepts no non-suspend effect lambda** (law *A trigger flow
  never outlives its own run* — both doors, because removing the scope alone leaves the lambda one open);
- **outside `feature/`, a consumer references only read-models** (`ReadModelImportsTest`): the production
  and test sources of the presentation zone, the host, every `:ui:*` module and `:test:control` SHALL
  reference `feature/` declarations only inside a `feature/<feature>/readmodel/` package. `:app:desktop`
  is exempt until its harness is rewired onto the entry surface, and the exemption SHALL be named in the
  gate. A non-vacuity twin SHALL fail when the scan sees no read-model reference at all, so a renamed
  package or a moved scope cannot pass the gate by leaving it nothing to check. This replaces the
  presentation-imports gate that this requirement once described as compile-enforced. No test ever
  implemented it, and the presentation module's `api()` edge onto the whole of `feature/` made the claim
  false.

A text gate SHALL NOT pass when its scope is absent: a missing or renamed zone directory SHALL fail the
build, never report itself pending. A gate that reports "pending" when its subject has moved is a gate
that fails open.

The text gates' zone vocabulary (`zoneTokens`) and their whole-core scan (`domainMainFiles()`) SHALL name
every zone, the presentation zone and the host included, so a gate that scans "the core" scans all of it.

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

#### Scenario: A consumer names a feature's command
- **WHEN** a file in the presentation zone, the host, a `:ui:*` module or `:test:control` references a
  `feature/` declaration outside a `readmodel` package
- **THEN** the read-model import gate fails, naming the file and the reference

#### Scenario: The read-model gate is left nothing to check
- **WHEN** the read-model import gate's scan finds no `readmodel` reference in any of its scopes
- **THEN** its non-vacuity twin fails, rather than the gate passing on an empty scan

#### Scenario: A zone directory is renamed
- **WHEN** a scanned zone directory no longer exists under the path a text gate scans
- **THEN** the gate fails naming the absent scope, and does not report itself pending

### Requirement: The shell gates

The build SHALL enforce zero conditionals in the iOS app shells' Kotlin via a detekt complexity gate
(threshold: no function above cyclomatic complexity 1 beyond pinned wiring forms), **gating**
(`ignoreFailures = false`, wired into `check`) over **every** shell source root, asserted by a test
with a non-vacuity floor (`KotlinShellGuardTest`: the scanned source roots exist and are non-empty —
a stale source list after a module rename must fail, never pass vacuously).

The gate's scope SHALL be **named rather than implied**. It covers the iOS app shell, the iOS
upload-extension shell, the iOS forge shell, the shared host composition (the host zone,
`domain/host/src`: a core zone by its dependency edges, but wiring that every root calls and that
holds no decision, so it is proved decision-free like the shells), and source contributed into a
shell's source set under a build property. It does **not** cover `:app:desktop`: that module is test equipment hosting two
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
