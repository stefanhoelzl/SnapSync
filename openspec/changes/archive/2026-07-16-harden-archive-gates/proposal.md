## Why

The archive step's placeholder gate works. It caught real rot in `a47bc84` — a Purpose the CLI had
overwritten with a placeholder and left in the contract of record since `006fb2b`. That save is the whole
justification for this capability, and its own Purpose records what the tree looked like without it:
**19 of 44 specs** carrying `TBD - created by archiving`.

The gate is also a hand-edit to a generated file, and it is scheduled for silent deletion.

`.claude/skills/openspec-archive-change/SKILL.md` is generated output. CLAUDE.md instructs the reader to
regenerate it (`openspec config profile core` → `openspec update`) and "commit the output verbatim —
hand-edits are overwritten on the next update". Measured, in a throwaway copy of this tree:

```
openspec update --force  →  M .claude/skills/openspec-archive-change/SKILL.md
                            gate occurrences: 4 → 0        (destroyed)
                            openspec/config.yaml            (untouched)
```

The skill is the *only* file that update rewrites here — it is the sole hand-edit in the generated set.
The gate has survived so far only because nobody has regenerated since `73f6143` added it.

The same fact explains why `/opsx:archive` — the tab-completable slash command, generated from the same
`core` profile with `delivery: both` — carries **0** occurrences of the check while the skill carries 4.
The divergence is not an oversight; it is the signature of an unofficial patch. Archiving via the command
today reports success with a placeholder Purpose in the tree, which is precisely what
`openspec-archive-command` exists to forbid.

Nothing else covers this, and there is now a controlled experiment proving it. `openspec validate --specs
--strict` — the `build.yml` gate — passed **50/50 on a tree with 28 confirmed drifts**, including four
specs that contradicted themselves within one file (`SHALL NOT require any token` sitting beside `requires
a device token`). Those drifts have since been swept (`2026-07-16`, ~20 corrections across 19 spec files).
`validate` passes **50/50** now too. **The number did not move**: the gate reported the same thing with the
lies in and with them out. It validates structure — Purpose present, Requirements present, SHALL/WHEN/THEN,
at least one scenario — and it has never opened a `.kt` file. `doctor`'s `references` federate spec repos;
they do not link a capability to its code. OpenSpec is AI-native by design: the model is the mechanism, and
the mechanism is prose. So the prose must live somewhere regeneration cannot reach.

`openspec/config.yaml` is that place. It is hand-authored (its `context:` block opens "SnapSync: an iOS app
for sharing photos…"), it is injected into every agent working in this root, it is not an instruction file,
and the measurement above confirms `update --force` leaves it alone.

A second gate belongs there too. `add-device-attestation` shipped nine deltas — every one backend — while
its diff touched `domain/presentation/UiState.kt`, `StatusContainerHost.kt`, `AttestedSource.kt`, and
`domain/ui/components/AppStatusLine.kt`. `SyncHealth.Unattested` therefore shipped with **no owning spec at
all**, and `sync-status-screen` still asserts a three-rung precedence against the four rungs in the code.
Its `design.md` D11 had promised "no new screen, no new `App*` component"; implementation discovered
otherwise and recorded the correction in `tasks.md` 4.5 — an implementation log, not a contract. Because
D11 said "no UI change", those capabilities were never on the change's scope list, so nothing prompted a
delta when the code touched them anyway. No structural check can see that; a question at archive time can.

A third gate covers the class the first two are blind to: a change that invalidates a spec it never
touches. `611b51e fix(stale synchronization status)` deleted `ListingSyncStatusSource` and
`OwnDeviceCompletedAssetsSource`. At that commit `full-stack-harness` and `harness-world-model` both named
them; its delta covered six other specs and neither of those two. Both specs went on naming both types for
**eleven days**, while neither type existed anywhere in the source. Nothing surfaced it: not `validate`, not
the six later changes that edited those same two specs. It took a human-ordered audit, and then a hand
sweep, to remove them (`2026-07-16`). This is not caught by the module gate: `611b51e` touched
`:domain:status`, whose capability `sync-status` **was** in the delta, while the offended specs belong to
`:app:desktop`. It is caught by asking a different question — *which specs name what you just deleted?* —
and that question is `git diff | grep | git grep`. Measured over six real changes, it fires once (on
`611b51e`, naming both specs correctly) and is silent on the other five, including `1f85ce6`, which
deleted no types. The two axes are complementary in fact, not merely in theory.

## What Changes

- **Relocate both gates into `openspec/config.yaml`'s `context:` block**, the one surface `openspec update
  --force` provably does not rewrite. The `openspec-archive-command` spec already says "**the archive
  step** SHALL verify" — it is implementation-agnostic, so this satisfies the existing requirement on
  *every* path (skill, `/opsx:archive`, or an agent working the tree directly) instead of patching the
  command into a second time bomb.
- **Stop hand-editing the generated skill.** After the relocation, `.claude/skills/openspec-archive-change/
  SKILL.md` and `.claude/commands/opsx/archive.md` are regenerable verbatim, as CLAUDE.md already claims
  they are. This closes the `/opsx:archive` gap as a side effect rather than as a separate patch.
- **Add a delta-completeness gate** (new requirement): before reporting success, the archive step accounts
  for every module the change's diff touched — resolving each to its owning capability via CLAUDE.md's
  module list, and either naming that capability's delta or recording why none is needed. Unaccounted
  modules fail the archive. This is an accountability gate, not a mechanical one: it forces the question
  that `add-device-attestation` never got asked.
- **Add a dead-type gate** (new requirement): for every type declaration the diff removes that no longer
  exists tree-wide, the archive step greps `openspec/specs/` and accounts for any spec still naming it.
  Unlike the other two this one is fully mechanical, and it is the only gate that reaches a spec the change
  never touched.
- **No change to `openspec/specs/` content beyond the `openspec-archive-command` delta.** The 28 drifts
  found by the audit are a separate, behavior-preserving sweep (`docs(openspec):`, per the `a47bc84`
  precedent); this change is only about the step that manufactures them.

## Impact

- **Affected capability**: `openspec-archive-command` (three new requirements; the existing two unchanged
  in substance).
- **Affected files**: `openspec/config.yaml` (the `context:` block), `.claude/skills/openspec-archive-change/
  SKILL.md` (reverts to generated output), `CLAUDE.md` (record that the gates live in `config.yaml` and that
  the `.claude/opsx` set is regenerable verbatim).
- **The sweep has happened; these gates are what stop it recurring.** The ~20 drifts are corrected and
  merged (`2026-07-16`), so this change inherits a clean tree rather than a backlog. That is the whole
  argument for doing it now: the tree is only clean at the moment a human paid to clean it, and nothing
  currently prevents the next `611b51e` from re-dirtying it the same way. With the dead-type gate live, a
  fossil is caught by the change that manufactures it instead of eleven days later by an audit.
- **Not covered — drift that changes no type's existence.** The gates see deletions and touched modules.
  They are blind to a type whose *shape* drifts under a stable name: `event-link` declares an `EventConfig`
  with a nullable `minPhotoDate` and no `startsAt`, while the code's `minPhotoDate` is required and
  `startsAt` exists. The identifier survives, so nothing fires. Purpose rot (stale prose that is not a
  placeholder) and append-not-amend (a new requirement contradicting a surviving one — the four token-gate
  contradictions) are likewise out of reach. Those classes need a model reading the spec against the code,
  not a gate.
- **Fails open** for the first two gates. A prose gate is advisory — an agent in a hurry skips it and
  nothing goes red, unlike the Konsist guards in `:test:architecture`. That is the accepted cost of the
  only home that survives regeneration; see design. The dead-type gate is the exception: it is mechanical,
  and D6 records why it is nonetheless stated as prose here.
