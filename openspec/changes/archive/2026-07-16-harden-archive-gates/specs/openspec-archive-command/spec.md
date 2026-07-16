## ADDED Requirements

### Requirement: The gates live where regeneration cannot reach them

The archive step's gates SHALL be stated in `openspec/config.yaml`'s `context:` block, not in any file
under `.claude/`.

Everything the openspec CLI writes under `.claude/` — the `opsx` commands and the `openspec-*` skills
alike — is generated output for the machine-global profile, and `openspec update --force` rewrites it
verbatim. A gate hand-added there is deleted by the next regeneration, silently, with a green run: this
was measured on a copy of this tree, where `update --force` reduced `SKILL.md`'s gate from four
occurrences to zero while leaving `openspec/config.yaml` untouched. `config.yaml` is hand-authored, is not
an instruction file, and is injected into every agent working in this root — so a gate stated there reaches
the skill path, the `/opsx:archive` command path, and a bare agent equally.

The existing requirements of this capability say "**the archive step** SHALL verify", which names no
implementation. Stating the gates in `config.yaml` satisfies them on every path; hand-patching a generated
file satisfies them until the next `update`.

Any `.claude/` file under this capability's influence SHALL therefore be byte-identical to what
`openspec update --force` produces, so that regenerating it is a no-op rather than a regression.

#### Scenario: Regenerating the instruction files does not weaken the archive step
- **WHEN** `openspec config profile core` and `openspec update --force` are run and the output is committed verbatim
- **THEN** every gate of this capability is still in force, because none of them lived in the regenerated files

#### Scenario: Archiving via the slash command is gated identically to the skill
- **WHEN** a change is archived through `/opsx:archive` rather than the `openspec-archive-change` skill
- **THEN** the placeholder gate and the delta-completeness gate both apply, because both are stated in `config.yaml` rather than in either entry point

#### Scenario: A gate proposed for a generated file is rejected
- **WHEN** a new archive-step obligation is drafted as an edit to `.claude/skills/openspec-archive-change/SKILL.md` or `.claude/commands/opsx/archive.md`
- **THEN** it is stated in `openspec/config.yaml`'s `context:` block instead, and the generated file is left as the CLI produces it

### Requirement: Archive accounts for every module the change touched

The archive step SHALL, before reporting success, enumerate the modules the change's diff touched, resolve
each to its owning capability using CLAUDE.md's module list, and for each either name that capability's
delta under the change's `specs/` directory or record why no delta is needed (for example, the change is
behavior-preserving for that module). If any touched module is left unaccounted for, the archive step SHALL
fail and name it.

Recording *why* is sufficient; a delta is not required for every touched module. This is an accountability
gate, not a mechanical one — its purpose is to force the question, because the failure it addresses is a
change whose scope list was decided before implementation and never revisited.

`add-device-attestation` is the motivating case. Its `design.md` D11 promised "no new screen, no new `App*`
component", so `sync-status-screen` and `design-system` never joined its scope list. Implementation then
discovered D11 was wrong — a background token stall needed a visible state — added `SyncHealth.Unattested`
and `AppSyncStatus.CannotVerifyDevice`, and recorded the reasoning in `tasks.md` 4.5, an implementation log
rather than a contract. The change archived with nine deltas, all backend, while its diff touched
`domain/presentation/` and `domain/ui/components/`. The behavior shipped owning no spec; `sync-status-screen`
still asserts three precedence rungs against the code's four. Nothing structural could have caught it, and
no reviewer was prompted to ask.

#### Scenario: A touched module with no delta and no justification fails the archive
- **WHEN** the change's diff touches `domain/presentation/` and the change's `specs/` directory contains no delta for any capability owning that module, and no reason is recorded
- **THEN** the archive step fails and names the unaccounted module

#### Scenario: A touched module may be accounted for without a delta
- **WHEN** the change's diff touches a module only in a behavior-preserving way and records that
- **THEN** the archive step accepts it and completes, because the obligation is to account for the module, not to write a delta for it

#### Scenario: The scope list is re-derived from the diff, not from the proposal
- **WHEN** the change's `proposal.md` declares one set of affected capabilities but the diff touches modules outside it
- **THEN** the archive step accounts for the modules the diff actually touched, because a scope list written before implementation is exactly what this gate exists to catch

### Requirement: Archive accounts for the specs its deletions invalidate

The archive step SHALL, before reporting success, identify every type declaration the change's diff removes
that no longer exists anywhere in the source tree, and grep `openspec/specs/` for each. Any spec still
naming a removed type SHALL be corrected or accounted for; an unaccounted spec SHALL fail the archive, and
the step SHALL name both the dead type and the spec that carries it.

Kotlin declares types as `class`, `object`, `interface`, **and** as CamelCase fake-constructor functions —
`ListingSyncStatusSource` was a `fun` returning an anonymous `object`. The check SHALL treat a CamelCase
`fun` as a type declaration; restricting it to `class`/`object`/`interface` misses that idiom, and
restricting it no further — to any removed `val` or `fun` — matches common words like `config` and `url`
and flags every spec in the tree.

This is the only gate that reaches a spec the change never touched, and it is the only mechanical one. It
scopes itself: a type the change deleted is by construction a type this project declared, so no allowlist
of external names (`NSSecureCoding`, `PHCloudIdentifier`, plist keys) is needed or maintained.

`611b51e fix(stale synchronization status)` is the motivating case. It deleted `ListingSyncStatusSource`
and `OwnDeviceCompletedAssetsSource`; `full-stack-harness` and `harness-world-model` both named them; its
delta covered six other specs and neither of those. Both specs went on naming both dead types for eleven
days, until a hand sweep removed them — surfaced by an audit, not by any check. The module gate could not
have reached it: `611b51e` touched `:domain:status`, whose capability **was** in its delta, while the
offended specs belong to `:app:desktop`. Only asking *which specs name what this change deleted* reaches
that.

#### Scenario: A deleted type still named by a spec fails the archive
- **WHEN** the change's diff removes a type declaration that exists nowhere else in the source tree, and a spec under `openspec/specs/` still names it, and no delta corrects that spec
- **THEN** the archive step fails, naming the dead type and the spec that carries it

#### Scenario: The gate reaches specs the change never touched
- **WHEN** the deleted type is named only by specs whose owning modules the diff never touched
- **THEN** the archive step still fails on them, because the question is which specs name the dead type — not which modules the change touched

#### Scenario: A type removed from one file but surviving elsewhere does not fire
- **WHEN** the diff removes a type declaration and the same type is still declared somewhere in the source tree (a move or a re-home)
- **THEN** the archive step does not fail on it, because no spec has been invalidated

#### Scenario: A CamelCase factory function counts as a type
- **WHEN** the diff removes a CamelCase `fun` that returned an anonymous object, and a spec names it
- **THEN** the archive step fails on it exactly as it would for a removed `class`

#### Scenario: A deleted type no spec mentions is silent
- **WHEN** the diff removes a type declaration — a test class, an internal helper — that no spec under `openspec/specs/` names
- **THEN** the archive step does not fail, and reports nothing about it
