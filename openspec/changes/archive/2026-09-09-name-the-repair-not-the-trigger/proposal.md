## Why

`event-rejoin-reconciliation` names a **trigger**, not what the capability does. The operation is: ask the
backend which resources it holds for this device, and make the local record agree. A rejoin is one
occasion for that — historically the only one — but the name has already outlived its accuracy once and is
about to do so again.

It outlived it the first time when the reconcile stopped being the extension's. The class was called
`ExtensionReconciler` for the same reason: it was accurate when only the extension had one, and stayed
after `2026-07-12-fix-app-driven-upload-lifecycle` generalized it to both tiers. That rename has just
happened, one level down. The capability name is the same mistake at the level above it, and the spec's
own Purpose had drifted with it — it opened "The **extension-side** gate … pulls **the event's**
stored-file listing" while its requirements said "upload tier" and "per-**device** listing" throughout.

It is about to outlive it again. `detect-lost-upload-records` adds a foreground-triggered, read-only
consumer of the same seam that has nothing to do with joining, and any repair that follows will be
triggered by ordinary use rather than by a membership transition. At that point the capability's name
describes the minority of its own behaviour.

Names that encode a trigger are also how the drift propagates: a reader looking for "what keeps my upload
record honest" does not search for "rejoin", and a reader who finds the capability assumes its contents
only apply at a join. Both happened here.

## What Changes

- **The capability is renamed** to describe the operation rather than its occasion — the working name is
  `upload-state-reconciliation`. `openspec/specs/event-rejoin-reconciliation/` moves, and every live
  citation follows.

- **The Purpose is rewritten** to lead with the operation and list the occasions, rather than defining the
  capability as a join-time gate that happens to run elsewhere too.

- **One requirement is renamed** — *Join reconciliation seeds already-stored photos as completed* — for
  the same reason: it is reached by a marker mismatch, which covers a fresh provision, an event switch and
  a delete-and-reinstall, and describing all of those as "join" is what made the Purpose drift.

- **No requirement changes meaning.** Every SHALL keeps its force, its scenarios and its wording. The
  marker gate, the seed, the `resetTo`, the deferral behaviour and the timeout are all untouched.

- **Archived decision records keep the old name.** They record what was decided when it was decided; a
  rename that rewrites history makes the archive lie. That leaves 151 archived files citing the old name
  and 45 live files citing the new one, which is correct but must be *stated* somewhere, or the next
  reader concludes the rename was botched.

## Capabilities

### New Capabilities

None. This is a rename of an existing capability, not a new one.

### Modified Capabilities

- `event-rejoin-reconciliation`: renamed; one requirement renamed with it. No requirement's normative
  content changes.

## Impact

- `openspec/specs/event-rejoin-reconciliation/` → the new name; **17 live spec files** cite it and follow.
- **28 further live files** — `domain/feature` (9), `adapter/ios` (6), `domain/model` (3), `app/ios` (3),
  `test/world` (2), `domain/ports` (2), `test/architecture` (1), `CLAUDE.md`, and one skill under
  `.claude/skills/`. Almost all are KDoc citations of the form `capability \`event-rejoin-reconciliation\``.
- `openspec/changes/archive/` — **151 files, deliberately untouched.**
- No code behaviour, no API, no schema, no migration.
- Changelog label: `internal`.

**Classification, stated honestly.** This is behaviour-preserving, and `CLAUDE.md` routes
behaviour-preserving refactors and docs straight to branch → PR → `/ship` without the OpenSpec flow. The
argument for running it through the flow anyway is that a capability name is part of the contract's index
— 17 specs cross-reference it — and a rename touches the contract of record rather than the code. The
argument against is that the spec delta here is genuinely thin: one renamed requirement and nothing else.

Two reasonable routes, and the choice is the user's:

1. **Fold it into `detect-lost-upload-records`.** That change already adds a non-join consumer, which is
   what makes the name wrong; renaming in the same PR means the capability is never briefly misnamed for a
   behaviour it already has. One PR, one label, no interim state.
2. **Do it standalone as a direct mechanical change**, skipping OpenSpec — a pure rename PR, easy to
   review because every hunk is the same substitution.

Doing it as a *separate OpenSpec change* is the least attractive of the three: it carries the full
ceremony for a delta that renames one requirement.
