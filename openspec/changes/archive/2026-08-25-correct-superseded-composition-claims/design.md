## Context

Two supersessions left prose behind. `2026-08-24-retire-launch-env-triggers` extracted the forge into
its own binary target, deleting `ForgeShell` and `CompositionMode`. `2026-08-25-collapse-upload-tier-seam`
replaced `resolveComposition`/`UploadTier` with `resolveUploadMechanism`, moving from "compose both
producers and pick one per transition" to "resolve one kind and hold one reference".

Both changes synced the specs they knew they touched. `collapse-upload-tier-seam` even shipped an
`ios-app-shell` delta — but `## ADDED Requirements` only. It added *"OS entry points delegate upload
triggers to the resolved mechanism"*, which states **"no entry point SHALL re-check a tier"**, and left
untouched a requirement ~420 lines above it stating the root **SHALL switch on the resolved
`UploadTier`**. The contract of record now contradicts itself inside one file, and contradicts
`upload-lifecycle`, which blesses the very runtime override `ios-app-shell` forbids.

`openspec validate --specs --strict` passes on all of it. It asks whether a spec is well-formed and has
never opened a `.kt` file. This is the documented limitation of that gate, not a surprise.

Six of the surviving false statements sit in stacked KDoc blocks — a shape where Kotlin binds only the
last block and drops the earlier one with no error, no warning, and no visible symptom.

## Goals / Non-Goals

**Goals:**

- Make `ios-app-shell` true, and make it stop carrying a second copy of another spec's normative rule.
- Correct the ten forge/composition-mode statements in production prose, including two KDoc links that
  resolve to nothing.
- Close the one mechanically-detectable class of drift found here, at the eleven existing sites, with a
  gating guard so it cannot silently recur.

**Non-Goals:**

- **No behavior change.** No Kotlin declaration is added, removed, or renamed. The guard is the only new
  executable code and it runs at build time.
- **Not a general documentation-truth guard.** Whether prose is *correct* stays unguarded; only the
  silent-drop shape is mechanical enough to pin.
- **Not a refactor of `SnapSyncRoot`.** See D2.
- Not a sweep of every stale comment in the repo. The scope is the two named supersessions plus the
  eleven stacked-KDoc sites; anything else is left, deliberately.

## Decisions

### D1 — `ios-app-shell` drops the duplicate instead of re-synchronising it

The stale paragraph restated mechanism resolution, which `upload-lifecycle` already owns normatively
("The upload mechanism is resolved, never selected"). Re-synchronising it would recreate a duplicate of
evolving normative text with nothing holding the two copies together — which is exactly how it rotted.
So the requirement now states what the **shell** owes (supply the mechanisms, the OS-presence fact and
the override source; construct the OS-driven mechanism only where its selector exists; delegate every
entry point re-resolving nothing) and cites `upload-lifecycle` for the rule.

Deferring to another **spec** is not the forbidden kind of deferral. The rule that a spec never defers
its contract to a doc *outside* `openspec/` is untouched — cross-citation between specs is already the
house pattern, and the ADDED requirement from `collapse-upload-tier-seam` does exactly this.

*Alternative rejected:* restate resolution accurately in place. Self-contained for a reader of one file,
but it rebuilds the failure mode in the same paragraph that just demonstrated it.

*Alternative rejected:* delete the requirement entirely. It carries much that is not stale — the
host-assembly-only grant subscriptions, the Kotlin-side lifecycle observers replacing `scenePhase`, and
`MainViewController`'s rendering contract.

### D1b — one sentence is made mechanism-precise, and it is not a behavior change

The requirement said the graph constructs **no `LedgerWriter`** "on the OS-driven tier". Since the tier
became a per-transition resolution, the precise statement is *while the OS-driven mechanism is the
resolved one*. Verified rather than assumed: `UrlSessionUploadController` is `by lazy` and is reached
only when resolution yields `URL_SESSION`, so on iOS ≥26.1 under a **full** grant no writer is
constructed; under a **partial** grant it is, and the extension is not registered there (measured —
`ios-photokit-upload`), so `sync-ledger`'s single-writer invariant holds in both cases. The invariant is
carried by which mechanism is resolved, not by which OS this is. Nothing in the code changes.

### D2 — the `Shell` seam is kept and re-documented, not collapsed

`SnapSyncRoot.Shell` now has exactly one implementation and its stated reason ("implemented once per
composition mode") is gone with the forge. Keeping it is the smaller correct move: it is `private`, so
it costs nothing outside the module; it enumerates the OS entry-point surface in one place; and no law
compels its removal — "the module set withholds" governs modules, not internal interfaces.

Collapsing it is a ~100-line refactor of `:app:ios`, which project rule leaves **untested**; the only
safety nets are the compiler and `detektAppShell`, and no guard names `Shell`, so nothing catches a
mistake structurally. Paying that inside a change that already carries a contract correction and a new
build gate would make the diff unreviewable for no behavioral gain.

*Consequence named:* an indirection whose original purpose is gone survives, now documented as what it
is. Recorded as an open question rather than silently kept.

### D3 — the guard's rule is "a declaration already appears earlier in the file"

Two consecutive KDoc blocks is *not* the rule, because the repo has a deliberate file-header
convention — a file-level KDoc above the first declaration's own KDoc — at eighteen sites. A header can
only be the first block in a file, so requiring a preceding declaration exempts every one of them
**by construction**. That matters more than convenience: an allow-list of permitted sites would be a
duplicate that goes stale, the failure this capability exists to prevent.

Measured on the current tree: the rule fires on exactly eleven sites, each inspected and each a genuine
drop.

*Alternative rejected:* forbid all consecutive KDoc and convert the eighteen headers to `//` comments.
A simpler rule bought by churning eighteen files that have nothing wrong with them, and by downgrading
useful file-level prose to a weaker form.

*Alternative rejected:* also require a blank line between a header and the following KDoc. Enforces a
whitespace convention that changes nothing semantically.

### D4 — the eleven sites are fixed by merging, not by deleting the dropped block

Recovering the lost text is the point; deleting it would complete the doc-loss rather than undo it. At
`AttestSeams.kt` the dropped blocks are the one-line summaries of what `token()` and `keyId()` return;
the already-corrected `UploadArm.kt` case dropped the only statement of why the upload lifecycle lives
in tested `:domain`. Cost accepted: eleven judgment calls and prose a reviewer must read rather than
scan.

### D5 — no dead-symbol guard over spec prose

Such a guard would have caught this drift, and it is still refused: it cannot separate a stale normative
claim from a correct historical one. `architecture-guards` names `UploadTier` and `app/ios/CLAUDE.md`
names `CompositionMode.Forge`, both correctly, in the past tense. It would need an exception list —
again the thing that goes stale. Recorded here so the idea is not silently lost a third time.

### D6 — one change, not three

The spec correction, the prose sweep and the guard have one cause and one review context: prose that
outlived the code it described. Sequencing matters more than separation here — prose written before the
spec settles would have to be rewritten after it, since the spec is what a future agent is told to
trust. The guard rides along because six of the sites are only reachable through the defect it pins.

## Risks / Trade-offs

- **A guard over documentation invites scope creep** ("if we guard KDoc shape, why not KDoc content?")
  → the requirement states the boundary explicitly: mechanical and total, pinning the compiler's own
  binding rule, and never widened to a content check. A green build is stated to be no evidence that
  documentation is correct.
- **The guard's file-header heuristic could misfire on an unusual file layout** → the rule keys on a
  preceding *declaration*, not on position or blank lines, and was validated against all twenty-nine
  consecutive-KDoc occurrences on the current tree — eighteen exempt, eleven flagged, each checked by
  hand.
- **The extraction could silently match nothing after a refactor** → non-vacuity twins, the pattern
  `LawsDigestTest` already uses.
- **A long MODIFIED requirement risks losing detail at archive time** → the entire requirement block is
  copied verbatim and edited in place, per the delta rules, rather than summarised.
- **`:app:ios` is untested, so nothing catches a bad comment edit** → the edits are comments only; the
  compiler and `detektAppShell` cover the file, and no behavior is reachable from a comment.
- **Two capabilities in one change** → they share a cause and neither delta depends on the other, so
  either can be reverted alone.

## Open Questions

- Should `SnapSyncRoot.Shell` eventually be collapsed, now that it has one implementation and no
  switch to serve? Deferred by D2 with its cost stated; a later change can decide with the prose
  already honest.
- `SnapSyncRoot.kt:615-638` carried the same stacked-KDoc defect *and* a live self-contradiction from
  the forge extraction. It is in scope here. Whether other pre-collapse supersessions left similar
  fields elsewhere in the repo is not investigated by this change.

### D7 — the prose fix exposed dead code, and the dead code goes

Correcting `SnapSyncRoot.kt:323`'s comment ("the tier thunks resolve through the one switch above; the
cast is what lets this read them") revealed that the line it documents does nothing. `val live = shell as
LiveShell` is never read, and because `shell` is an unconditional `LiveShell()` the cast cannot fail, so
it is not a runtime assertion either. It is a leftover from when the shell came out of a tier switch and
the graph needed tier-specific thunks off it. Removing both lines compiles clean.

This is the one place the change edits Kotlin rather than prose, and the reason is that the alternative is
worse: fixing only the comment means writing an accurate comment for a line that does nothing, which is
documenting dead code as intentional — the exact failure this change exists to remove. Leaving both
untouched would ship a false comment inside a change whose subject is false comments.

*Behaviour-preserving, and checked rather than assumed:* the value is unreferenced (verified by removing
it and compiling), and the cast's failure branch is unreachable because the field it casts is assigned
one concrete type at its declaration.

*Scope discipline:* this does not license a hunt for other dead code. It is removed because this change
was already correcting the comment that justified it, and no other site in scope has the same shape.
