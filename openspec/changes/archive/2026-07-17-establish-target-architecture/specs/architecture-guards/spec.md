# architecture-guards (delta)

## ADDED Requirements

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

### Requirement: The migration beacon is red until the migration completes
Migration distance SHALL be measured by a dedicated module detached from `check` and reported by
the NON-required `verify` job of the `architecture` workflow: the job SHALL fail while any per-law
burn-down count is nonzero (writing the per-law table to the job summary before failing) and SHALL
pass exactly when every count is zero. The check SHALL NOT be required and SHALL NOT gate any
merge; the release guard in `ios-release.yml` and `/ship`'s watcher SHALL judge REQUIRED checks
only, with the required set derived from branch protection at run time — never a name list — so
this and any future informational check is tolerated automatically, and the filter degrades in
the strict direction (unresolvable required set ⇒ every check counts). At completion each gate
moves into the gating module and the beacon module is deleted.
Accepted risk unchanged, on record: during the migration nothing GATES new violations — the
beacon makes them visible (red, with numbers), not blocked.

#### Scenario: A release during the migration
- **WHEN** `ios-release.yml` is dispatched while the beacon is red
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
