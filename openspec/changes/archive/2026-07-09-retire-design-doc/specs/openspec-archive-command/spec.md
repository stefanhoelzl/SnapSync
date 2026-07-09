## ADDED Requirements

### Requirement: Archived specs carry a real Purpose

When the archive step creates or updates a spec under `openspec/specs/`, it SHALL ensure that spec's
`## Purpose` section describes the capability in its own terms. The openspec CLI mints the placeholder
`TBD - created by archiving change <name>. Update Purpose after archive.` whenever it creates a spec file;
the archive step SHALL replace that placeholder before completing, deriving the Purpose from the change's
`proposal.md` and the delta spec's requirements.

A Purpose SHALL be self-contained: it SHALL NOT defer the capability's meaning to a document outside
`openspec/` (for example `Authoritative design: docs/design.md §N`). Where a decision record is worth
naming, the Purpose SHALL cite it as `Decision record: changes/archive/<id>`, which points inside the
archive rather than outside it.

#### Scenario: A newly created spec has its placeholder Purpose replaced
- **WHEN** the archive step creates `openspec/specs/<cap>/spec.md` and the CLI writes the `TBD - created by archiving change <name>` placeholder
- **THEN** the archive step replaces that placeholder with a Purpose derived from the change's `proposal.md` and the delta spec's requirements, before the archive completes

#### Scenario: A Purpose that only paraphrases its own requirements is rejected
- **WHEN** a drafted Purpose restates the spec's `SHALL` statements without saying what the capability is for
- **THEN** the archive step rewrites it to state the capability's role and its motivation, drawn from the change's `proposal.md`

#### Scenario: A Purpose may not defer to a document outside openspec
- **WHEN** a drafted Purpose points at a file outside `openspec/` for the capability's authoritative meaning
- **THEN** the archive step rewrites the Purpose to be self-contained, optionally citing `Decision record: changes/archive/<id>`

### Requirement: Archive fails on a surviving placeholder

The archive step SHALL verify, before reporting success, that no spec under `openspec/specs/` has the minted
placeholder string `TBD - created by archiving` **within its `## Purpose` section**. If any spec still
carries it there, the archive step SHALL fail and name the offending spec files rather than completing
silently.

The check SHALL be scoped to the `## Purpose` section, not to the whole file. A spec may legitimately quote
the placeholder string inside its `## Requirements` — this specification does — and a whole-file match would
make that spec permanently unarchivable.

This check covers every spec in the tree, not only those the current change touched — so a placeholder
left behind by an earlier archive is surfaced at the next one.

#### Scenario: A surviving placeholder aborts the archive
- **WHEN** the archive step is about to report success and a spec's `## Purpose` section still contains `TBD - created by archiving`
- **THEN** the archive step fails, listing each spec file that still carries the placeholder

#### Scenario: A placeholder inherited from an earlier archive is surfaced
- **WHEN** the current change touched no spec carrying a placeholder, but another spec's `## Purpose` in the tree still does
- **THEN** the archive step fails and names that spec, because the check spans the whole `openspec/specs/` tree

#### Scenario: Quoting the placeholder outside Purpose does not block the archive
- **WHEN** a spec quotes the literal string `TBD - created by archiving` inside its `## Requirements` while its `## Purpose` is a real Purpose
- **THEN** the archive step does not fail on that spec

#### Scenario: A clean tree archives successfully
- **WHEN** every spec under `openspec/specs/` has a `## Purpose` free of the placeholder string
- **THEN** the archive step completes and reports success
