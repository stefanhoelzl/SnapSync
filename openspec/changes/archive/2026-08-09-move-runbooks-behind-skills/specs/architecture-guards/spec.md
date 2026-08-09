## ADDED Requirements

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

### Requirement: The launch-trigger index agrees with production source

A test-only JVM guard SHALL assert that the set of `SNAPSYNC_*` launch-trigger names documented in
`.claude/skills/ios-device/SKILL.md` equals, exactly and in both directions, the set of
`"SNAPSYNC_*"` string literals in production Kotlin source (main source sets; test sources and
`build/` excluded).

The device-driving skill carries a compressed operator index of the launch triggers — for each one,
its name, its value shape, and the `dvt launch` invocation that applies it. That index is a
**duplicate** of what production source declares, and this repo forbids a duplicate unless it is
loud-when-stale (the rule `LawsDigestTest` was built on, and whose rationale records that "the
previous CLAUDE.md module graph rotted silently for months precisely because nothing held it to
anything"). This guard is that loudness: add, rename, or delete a launch trigger without touching
the skill — or document one that no longer exists — and the build fails naming the delta.

The comparison SHALL be by **name only**. The index's one-line effects are deliberately not the
spec's normative text, and are not compared: two authorities for a trigger's semantics would be
worse than one. `ios-app-shell` remains the contract of record for every trigger it specifies, and
the skill SHALL point there rather than restate it.

The guard SHALL compare against **source** rather than against `ios-app-shell`'s requirements,
because four triggers — `SNAPSYNC_SEED_PHOTOS`, `SNAPSYNC_SEED_POLICY`, `SNAPSYNC_WIPE_GALLERY`
and `SNAPSYNC_POLICY_PROBE` — ship in production Kotlin and appear in no spec. A spec-keyed guard
would silently cover a subset, which is the failure mode this capability exists to refuse.
That those four are unspecified is a **stated gap**, named here rather than left to be discovered;
this guard holds their documentation, not their contract.

The guard SHALL fail loudly rather than vacuously: if the skill file is absent, or the source scan
resolves zero `SNAPSYNC_*` literals, it SHALL fail rather than pass while scanning nothing.

#### Scenario: A new launch trigger is added without documenting it

- **WHEN** production Kotlin gains a `"SNAPSYNC_*"` literal that the `ios-device` skill's index
  does not name
- **THEN** the guard fails, naming the undocumented trigger

#### Scenario: A documented trigger no longer exists

- **WHEN** a `"SNAPSYNC_*"` literal is removed from production Kotlin while the skill's index still
  names it
- **THEN** the guard fails, so the index cannot outlive the triggers it describes

#### Scenario: A trigger is renamed

- **WHEN** a launch trigger's literal is re-valued in production Kotlin
- **THEN** the guard fails on both sides of the delta — the new name undocumented and the old name
  documented but absent

#### Scenario: Semantics are not compared

- **WHEN** the skill's one-line description of a trigger's effect differs in wording from
  `ios-app-shell`'s requirement for it
- **THEN** the guard passes, because only names are compared and the spec remains the single
  authority for meaning

#### Scenario: The guard is not vacuous

- **WHEN** `.claude/skills/ios-device/SKILL.md` is absent or renamed, or the production-source scan
  resolves zero `SNAPSYNC_*` literals
- **THEN** the guard fails rather than passing while inspecting nothing
