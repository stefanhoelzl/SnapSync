## Context

The capability is cited in 45 live files and 151 archived ones. The archived citations must not change —
they are decision records of what was decided at the time — so the rename deliberately produces a split:
searching the old name finds history, searching the new one finds the contract.

That split is the main design problem. Everything else is a substitution.

## Goals / Non-Goals

**Goals:**

- The capability's name describes the operation, so it survives a trigger being added.
- No requirement's normative content changes; the diff is reviewable as one substitution plus a Purpose
  rewrite.
- A reader who finds the old name in an archive can tell what happened without asking anyone.

**Non-Goals:**

- No behaviour change of any kind.
- No rewriting of archived changes.
- No renaming of `UploadReconciler`, the `JoinedEventMarker` port, or `joinedEventId` — the marker really
  is about which event was last joined, and that name is accurate.
- Not re-litigating any requirement while the file is open. A rename PR that also edits behaviour is
  unreviewable.

## Decisions

### D1 — Name it for the operation: `upload-state-reconciliation`

The operation is "make the device's record of what it has uploaded agree with the backend". Candidates
considered:

| candidate | why not |
|---|---|
| `upload-state-reconciliation` | **chosen** — names the operation and the subject; survives any trigger |
| `upload-reconciliation` | ambiguous: reconciling *uploads* could mean retrying them |
| `repair-upload-state` | a verb phrase; every other capability is a noun phrase |
| `device-listing-reconciliation` | names the mechanism, which is the same mistake one layer over |
| keep `event-rejoin-reconciliation` | the status quo, and wrong the moment a second trigger lands |

### D2 — The archives keep the old name, and the new spec says so

A pointer line in the renamed capability's Purpose — that it was `event-rejoin-reconciliation` until this
change, and that decision records under `changes/archive/` cite the former name — costs one sentence and
answers the only question the split creates. The capability already carries `Decision record:` lines
pointing into the archive, so this fits the established shape.

**Alternative considered — rewrite the archived citations too.** Rejected. An archived change is what was
proposed and decided *then*; editing it to use a name that did not exist yet makes the record false, and
the repo's whole convention is that archives are immutable records rather than living documents.

### D3 — Rename exactly one requirement, and leave the rest alone

*Join reconciliation seeds already-stored photos as completed* is reached on any `joinedEventId` marker
mismatch — a fresh provision, an event switch, or a delete-and-reinstall. Calling all of those "join" is
the same compression that produced the drifted Purpose.

Everything else stays. *Event switch versus re-join* genuinely contrasts two triggers and is accurate.
*Reinstall means the device left the event* is about config absence and is accurate. *Reconciliation gate
before enabling uploads* and *Upload tier defers uploads until the seed succeeds* are already
trigger-neutral. Renaming accurate names to look consistent would inflate the diff and lose the
distinction the accurate ones are drawing.

### D4 — Sequence it against the change that makes it necessary

The name is wrong *today* and becomes conspicuously wrong when `detect-lost-upload-records` lands. Two
orders work and one does not:

```
  rename → detect        ✔ the new consumer is written against the right name from the start
  detect → rename        ✔ but the capability is briefly named for a trigger it demonstrably
                           no longer has, in the same tree
  detect WITH rename     ✔ best, if the reviewer is willing to read a substitution and a
                           feature in one PR — one label, no interim state
```

Deferring the rename indefinitely is the option to avoid: the citation count only grows, and this
capability has already demonstrated that a stale name outlives the person who noticed it.

## Risks / Trade-offs

- **A 45-file substitution buries a real edit** → keep the PR pure. If it is folded into
  `detect-lost-upload-records`, the rename should be its own commit within that branch so it can be read
  separately.
- **Searching the old name still returns 151 hits** → intended, and D2's pointer is what makes it legible.
  Anyone grepping for orientation lands in the archive and is told where the contract went.
- **`:test:architecture` may pin capability names** → `RunbookSkillsTest` and the laws digest check
  documentation/tree agreement; a rename must run the full build rather than assume no guard notices.
- **Churn for no behaviour** → real, and the reason the proposal offers folding it into another change as
  the preferred route rather than shipping ceremony on its own.

## Open Questions

- Is `upload-state-reconciliation` the name, or does it read as too close to `sync-status`? The two are
  adjacent — one reconciles the record, the other displays it — and if that adjacency is confusing, the
  time to say so is before 45 files move.
