## ADDED Requirements

### Requirement: A KDoc block is never silently dropped

A `:test:architecture` guard SHALL fail the build when two KDoc blocks appear consecutively with only
blank lines between them and a declaration already appears earlier in the file.

The defect this pins is **invisible in review and invisible at runtime**. Kotlin binds only the *last*
KDoc block preceding a declaration; an earlier one is neither an error nor a warning, and the text
simply stops being that declaration's documentation. It arises the same way every time: someone adds a
revised rationale or an "Absence:" note as a *second* block rather than merging into the existing one,
and the block they meant to keep is the one that disappears. Eleven sites accumulated this way, and what
was dropped was load-bearing — the one-line summaries of what `AttestStore.token()` and `keyId()`
return, and the only statement of why the upload lifecycle lives in tested `:domain` rather than the
untested iOS composition root.

The **file-header convention is exempt by construction**, not by an exception list. A file-level KDoc
documenting the file as a whole can only be the first block in the file, so requiring that a declaration
already appear earlier excludes every such header without naming one. A list of permitted sites would
itself be a duplicate that goes stale, which is the failure this capability exists to prevent.

The guard SHALL scan **every** `.kt` source in the repository, test sources included: a dropped block
costs the next reader the same either way, and scoping to production would be a boundary to maintain
for no gain.

The guard SHALL carry **non-vacuity assertions** in the manner of `LawsDigestTest` — a change that
empties its extraction SHALL fail here rather than pass silently, because a guard that scans nothing
reports the same green as a guard that finds nothing.

This guard pins a **documentation** invariant rather than a structural one, which is deliberate and
narrow: it is admissible because the rule is mechanical and total — the compiler's own binding rule,
not a style preference — and because the failure is silent. It SHALL NOT be widened into a general
prose or content check; whether documentation is *correct* remains unguarded, and no green build is
evidence that it is.

#### Scenario: A second KDoc block silently drops the first

- **WHEN** a declaration is preceded by two consecutive KDoc blocks, so Kotlin binds only the last
- **THEN** the guard fails the build, naming the file and the line the dropped block opens on

#### Scenario: A file-level header above a documented declaration passes

- **WHEN** a file opens with a KDoc block documenting the file, immediately followed by the KDoc of the
  file's first declaration
- **THEN** the guard passes, because no declaration precedes the header

#### Scenario: A broken extraction fails rather than passing empty

- **WHEN** a change makes the guard's source scan match nothing
- **THEN** the guard fails, rather than reporting success over an empty set
