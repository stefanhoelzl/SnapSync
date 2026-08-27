## MODIFIED Requirements

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
task per scope.

Each scope's task SHALL layer a **shared baseline configuration** beneath its own. The baseline SHALL
carry only **readings of a rule** — interpretations of what the rule means, which apply everywhere —
and SHALL carry **no ceiling**, because a ceiling describes one scope's measurement and putting one in
the baseline would make that measurement every scope's contract. A reading, by contrast, cannot
sensibly differ per scope: if a named constant satisfies a rule that exists to force naming, it does so
in every module, and restating that argument in each scope's file would invite the copies to drift.

A scope's **own** configuration file SHALL be OPTIONAL, and its absence SHALL mean that the scope sits
at the baseline. The set of scope configuration files is therefore the list of scopes still carrying
debt, and creating one is the visible act of admitting that a scope has left the baseline.

#### Scenario: A function exceeds its scope's ceiling

- **WHEN** a change makes any function more complex than its scope's stated ceiling
- **THEN** the canonical build fails, naming the file, the function, the measured value, and the
  ceiling it exceeded

#### Scenario: Test sources and build scripts are measured

- **WHEN** a test source set or a Gradle build script exceeds its scope's ceiling
- **THEN** the build fails, on the same terms as production source

#### Scenario: A scope reaches the baseline

- **WHEN** a scope no longer deviates from the baseline on any rule and carries no exclusion
- **THEN** its configuration file is deleted, and the scope is measured by the baseline alone

#### Scenario: A ceiling is proposed for the baseline

- **WHEN** a number describing one scope's measurement is added to the shared baseline
- **THEN** it is rejected: the baseline carries readings, and a ceiling belongs to the scope it
  measures

### Requirement: A ceiling may only fall

Every ceiling SHALL be seeded at the value the tree measured when the scope came under the gate, so
the gate lands green with no code changes, and SHALL thereafter be treated as a value that may only
decrease.

Each scope's configuration file SHALL open with that contract stated in prose, in the file that
carries the numbers it governs — not in a shared document referenced from it, because a reader
editing a number must encounter the rule without opening anything else. The contract SHALL state
that raising a ceiling requires a stated forcing proof in the change that raises it. A scope that
sits at the baseline has no configuration file and therefore carries no such contract, which is
consistent rather than an omission: it has no ceiling to protect, and the act of creating a file for
it is itself the loud signal the contract exists to produce.

Rules that yield a per-site verdict rather than a measured value SHALL ratchet by their **scope
exclusion list** instead: the list names the scopes not yet clean, is the change's debt register,
and may only shrink. Removing an exclusion is the same event as lowering a ceiling.

A scope SHALL be described as sitting at the baseline only when it both matches every baseline value
**and** carries an empty exclusion register. Matching the numbers alone is not sufficient, and the
distinction is not academic: a scope whose every threshold equals the baseline's while two rules are
switched off still fails the moment its configuration file is removed.

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

#### Scenario: A scope matches every baseline number but switches a rule off

- **WHEN** such a scope's configuration file is removed
- **THEN** the scope fails, because the register was not empty — so it was never at the baseline

### Requirement: Coverage is derived, never remembered

Each scope's source SHALL be derived from the live Gradle project model rather than a committed list
of paths, and a guard in `:test:architecture` SHALL assert that **every** subproject in the build
resolves to **exactly one** scope. A subproject belonging to no scope, or to more than one, SHALL
fail the build naming that subproject.

Each scope's task SHALL additionally assert that it scanned a non-zero number of files, so a
configuration or filter regression fails rather than passing while inspecting nothing.

Because a scope's own configuration file is optional, the guard SHALL assert that **every
configuration file belongs to a scope** rather than that every scope has a configuration file. A file
naming no scope is dead weight that measures nothing while appearing to; a scope with no file is at
the baseline, which is the intended state.

This inversion is what makes the absence of a file safe to read as meaning. Without the subproject
guard above, "this scope has no configuration file" and "this scope is in no tier and is measured by
nothing" would be indistinguishable, and the quieter of the two is the dangerous one.

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

#### Scenario: A configuration file outlives its scope

- **WHEN** a configuration file exists that no scope names
- **THEN** the coverage guard fails, so a file cannot sit in the tree appearing to govern something
  while governing nothing

#### Scenario: A scope at the baseline has no configuration file

- **WHEN** a scope carries no configuration file while resolving to exactly one tier
- **THEN** the guard passes, and the scope is measured against the shared baseline
