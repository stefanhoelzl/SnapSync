# complexity-budgets Specification

## Purpose

A **ceiling on complexity for every source file in the repository**, expressed per scope, seeded at
what the tree already measures, and permitted to move in one direction only: down.

It exists because complexity was measured in exactly one place — `detektAppShell`, over three iOS
shell source roots — and that gate is not a complexity budget at all. Threshold 2 there asserts *the
app shell holds no decisions*: a structural proof, whose value comes precisely from the number being
2 (capability `architecture-guards`, "The shell gates"). Everywhere else, 484 Kotlin files and 21
TypeScript files, nothing measured anything. At detekt's own default thresholds the production tree
carried 107 findings and cyclomatic complexity reached 50; none of those was a defect, and that is
the point — nothing would have noticed if the next one were 80.

This capability is therefore deliberately a **different kind of thing** from the guards beside it. An
architecture guard proves an invariant: it is true or the build is red. A complexity budget is a
ceiling that starts where the code happens to be and is lowered by people who improve the code. It
is a **ratchet carried by a written contract, not a proof** — nothing mechanically prevents a
ceiling from being raised in the same change that would have violated it, and the specification says
so rather than implying a guarantee it does not deliver. What *is* mechanical is coverage: that
every module is measured, and that no scope escapes by being forgotten.

Two zones are the exception, and they are why this capability is worth having beyond regression
prevention. `flow/` carries a written law — *flows coordinate, never decide* (capability
`module-architecture`) — that no gate enforced; it sits one step from being provable. `compose/`
carries a weaker version of the same claim. Their budgets are the mechanism by which a prose law
becomes an executable one.

Decision record: `changes/archive/2026-08-27-add-repo-wide-complexity-gates`.

## Requirements

### Requirement: Every Kotlin source in the repository sits under a complexity ceiling

The build SHALL enforce complexity ceilings over **every** Kotlin source in the repository —
production source sets, test source sets, and Gradle build scripts — as part of the canonical check
(`./gradlew build`), so a function that exceeds its scope's ceiling fails the build locally and in
CI.

Ceilings SHALL be expressed **per scope** rather than repository-wide. A single repository-wide
ceiling would have to carry the global maximum, under which the scopes with the strictest real
ceilings would be governed by nothing.

Because a detekt rule carries exactly one threshold value per configuration — path filters vary the
*scope* a rule applies to, never its number — per-scope ceilings SHALL be realised as one detekt
task with its own configuration file per scope.

#### Scenario: A function exceeds its scope's ceiling

- **WHEN** a change makes any function more complex than its scope's stated ceiling
- **THEN** the canonical build fails, naming the file, the function, the measured value, and the
  ceiling it exceeded

#### Scenario: Test sources and build scripts are measured

- **WHEN** a test source set or a Gradle build script exceeds its scope's ceiling
- **THEN** the build fails, on the same terms as production source

### Requirement: A ceiling may only fall

Every ceiling SHALL be seeded at the value the tree measured when the scope came under the gate, so
the gate lands green with no code changes, and SHALL thereafter be treated as a value that may only
decrease.

Each scope's configuration file SHALL open with that contract stated in prose, in the file that
carries the numbers it governs — not in a shared document referenced from it, because a reader
editing a number must encounter the rule without opening anything else. The contract SHALL state
that raising a ceiling requires a stated forcing proof in the change that raises it.

Rules that yield a per-site verdict rather than a measured value SHALL ratchet by their **scope
exclusion list** instead: the list names the scopes not yet clean, is the change's debt register,
and may only shrink. Removing an exclusion is the same event as lowering a ceiling.

This requirement is enforced by the written contract and by review, **not** by a check. The
specification states this limitation explicitly: a raised ceiling and a removed exclusion are
visible in a diff, and nothing beyond that prevents either. A mechanical tightness check was
considered and rejected because its failure mode is inverted — improving the code would fail the
build until someone edited a number, which teaches people to route around the gate.

#### Scenario: The ratchet's limitation is stated, not implied

- **WHEN** a reader asks whether this gate proves anything about complexity over time
- **THEN** the capability and each configuration file answer that it does not: it is a ratchet
  carried by a written contract, and the guarantee it offers is regression detection against the
  ceiling as it currently stands

#### Scenario: A scope becomes clean under a per-site rule

- **WHEN** a scope on a rule's exclusion list is brought into compliance
- **THEN** the exclusion is removed, and the register shrinks

### Requirement: Coverage is derived, never remembered

Each scope's source SHALL be derived from the live Gradle project model rather than a committed list
of paths, and a guard in `:test:architecture` SHALL assert that **every** subproject in the build
resolves to **exactly one** scope. A subproject belonging to no scope, or to more than one, SHALL
fail the build naming that subproject.

Each scope's task SHALL additionally assert that it scanned a non-zero number of files, so a
configuration or filter regression fails rather than passing while inspecting nothing.

Deriving coverage rather than listing it is a direct consequence of what a hand-maintained list did
here: the existing shell gate's roots are listed by hand in two places, and an `:app:*` shell module
was missing from both (capability `architecture-guards`). A mechanism whose known failure has
already occurred is not the one to reuse at greater scale.

#### Scenario: A new module is added to the build

- **WHEN** a subproject is added and assigned to no scope
- **THEN** the coverage guard fails, naming the subproject and requiring a scope choice — the module
  is never silently unmeasured

#### Scenario: A scope resolves no sources

- **WHEN** a scope's derived source set is empty
- **THEN** its task fails rather than reporting success

### Requirement: The zones with a written decision-free law carry their own ceilings

The `flow/` and `compose/` zones of `:domain` SHALL each be a scope of their own, carved out of the
surrounding scope, with ceilings low enough to express the law each already carries in prose.

`flow/` SHALL declare a target of **one decision-free ceiling identical to the shell's**, and on
reaching it SHALL merge into the shell scope, because at that point the two make the same claim.
Until then its ceiling is the measured value, falling as the zone is brought to the target.

`compose/`'s ceiling SHALL be seeded from measurement without claiming a target: a composition that
resolves a platform mechanism per transition genuinely decides something, so its honest end state is
not known to be the shell's.

#### Scenario: A decision creeps into a flow

- **WHEN** a `flow/` class gains a decision beyond its current ceiling
- **THEN** the build fails, naming that coordination belongs in the flow and rules belong in a
  feature

#### Scenario: The flow zone reaches the decision-free ceiling

- **WHEN** `flow/`'s ceiling reaches the shell's
- **THEN** the two scopes merge, and `flow/` is thereafter governed by the shell gate's proof rather
  than by a budget

### Requirement: The backend carries the same measure, and only that measure

`api/` SHALL enforce a cyclomatic-complexity ceiling on its TypeScript through a project-local
`deno lint` plugin, running under the `deno lint` invocation the deploy workflow already gates on
(capability `backend-deployment`), so no additional toolchain, package manager, or lockfile enters a
backend that deliberately runs Deno-only.

The plugin SHALL exist because no published rule does: `deno lint` ships no complexity rule, and no
published lint plugin measures complexity. The plugin SHALL state, in its own source, exactly which
syntax it counts as a decision, so its definition is comparable by reading against the Kotlin side's,
and SHALL be covered by its own tests.

Parity SHALL be **partial and stated**: cyclomatic complexity is the one measure both sides share,
so that "the complexity budget" names one thing across the repository. Method length, parameter
count, and nesting depth are **not** enforced on the TypeScript side, and the capability says so
rather than approximating them with hand-written rules whose semantics would drift.

#### Scenario: A backend function exceeds the ceiling

- **WHEN** a function in `api/src/` exceeds the stated cyclomatic ceiling
- **THEN** `deno lint` fails, and the deploy is blocked by the existing check gate

#### Scenario: The two definitions of complexity are comparable

- **WHEN** a reader asks whether the Kotlin and TypeScript ceilings measure the same thing
- **THEN** the plugin's source enumerates the syntax it counts, and the answer is readable rather
  than assumed

### Requirement: The gate's boundary is stated

The capability SHALL state what it does **not** reach, so that a claim of repository-wide coverage
cannot be read as covering artifacts no gate inspects. Swift sources are covered by the pinned
structure check under capability `architecture-guards` and carry no complexity ceiling; the
marketing site and its toolchain are out of scope entirely.

#### Scenario: A reader asks what is not measured

- **WHEN** the capability is read as a coverage claim
- **THEN** it names Swift and the marketing site as outside it, so the absence is a stated boundary
  rather than an oversight

### Requirement: The analyser's version lag is a stated constraint with an expiry

The Kotlin analyser's embedded compiler version SHALL be recorded as a constraint alongside the
repository's own Kotlin version, together with the trigger that retires it, because widening the
gate from a handful of files to the whole repository turns a narrow exposure into a repository-wide
one: source using language syntax newer than the analyser's parser fails to parse everywhere at
once.

The constraint SHALL cite the analyser's declared dependency — not the current code — and SHALL name
its expiry: the analyser's next major reaching stable, or the first newer-syntax parse failure,
whichever comes first.

#### Scenario: The repository adopts newer Kotlin syntax than the analyser parses

- **WHEN** source uses language syntax the analyser's embedded parser does not accept
- **THEN** the gate fails loudly with a parse error rather than passing while inspecting nothing,
  and the recorded expiry trigger names the resolution
