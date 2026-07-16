## 1. Pin the time bomb before defusing it

The claim this change rests on is measurable, and the measurement is destructive in-place — so take it in
a copy, not in the worktree. `update --force` rewrites the skill; running it here before the relocation
lands would delete the gate this repo currently depends on.

- [x] 1.1 In a throwaway copy of `openspec/` + `.claude/`, run `openspec update --force` and record the
      result in this change directory as archive evidence: exactly one file modified
      (`.claude/skills/openspec-archive-change/SKILL.md`), its gate count `4 → 0`, `openspec/config.yaml`
      untouched. This is the whole argument for D1 — without it the change reads as a preference.
      *Measured 2026-07-16 on a copy of the tree at `894ce9e`:*
      ```
      before : SKILL.md gate=4   command gate=0
      $ openspec update --force .
      after  : SKILL.md gate=0
      git status --porcelain  →  M .claude/skills/openspec-archive-change/SKILL.md   (ONE file)
      openspec/config.yaml    →  SnapSync context intact
      ```
      *The skill being the **only** modified file is the finding: it is the sole hand-edit in the generated
      set, which is exactly why the command lacks the gate. `config.yaml` is not an instruction file, so
      `update` has no opinion about it.*
- [x] 1.2 Record the companion fact: `grep -c 'TBD - created by archiving'` is **4** in the skill and **0**
      in `.claude/commands/opsx/archive.md`, both generated from the same `core` profile with
      `delivery: both`. The divergence is the patch's signature, and is why finding #5 exists.
      *Confirmed 2026-07-16: 4 / 0. Two files, one generator, one profile — the only way they differ is that
      somebody edited one of them (`73f6143`) and the tool does not know.*

## 2. Relocate the gates into `config.yaml`

Order matters: the gates must be in force in their new home **before** the skill is reverted, or there is
a window in which neither copy exists.

- [x] 2.1 Add the placeholder gate to `openspec/config.yaml`'s `context:` block. Carry the scoping subtlety
      verbatim from the spec — the check is scoped to `## Purpose`, **not** whole-file, because
      `openspec-archive-command`'s own Requirements quote the placeholder string and a whole-file match
      would make that spec permanently unarchivable. Keep it failing, not warning: "a warning is what let
      nineteen of them accumulate."
- [x] 2.2 Add the delta-completeness gate to the same block: enumerate the modules the diff touched,
      resolve each to its capability via CLAUDE.md's module list, name the delta **or** record why none is
      needed, fail on anything unaccounted. Phrase it as a question the archiver must answer, not a rule a
      script enforces (D3).
- [x] 2.3 Add the dead-type gate to the same block: for every type declaration the diff removes that no
      longer exists tree-wide, grep `openspec/specs/` and account for any spec still naming it. State the
      extraction shape explicitly — removed `class|object|interface|fun` with a **leading capital and ≥5
      characters** — because both looser and tighter forms provably fail (D6): `val`/lowercase `fun` flags
      50 of 50 specs, and `class|object|interface` alone misses `ListingSyncStatusSource`, which is a
      fake-constructor `fun`.
- [x] 2.4 Confirm the block still reads as instructions to an agent rather than a second contract —
      `config.yaml` already says "Project rules live in CLAUDE.md. Read them rather than restating them
      here." Do not restate the spec; point at it.
      *Each gate states WHAT to run and points at `openspec-archive-command` for WHY. The one thing carried
      verbatim rather than referenced is each gate's failure mode, because each is a trap an agent falls
      into by writing the obvious command: a whole-file placeholder grep bricks the archive-command spec
      forever; a `val`/lowercase-`fun` dead-type match flags all 50 specs; a `class|object|interface`-only
      match misses the fake-constructor idiom. A pointer cannot stop you writing the wrong grep — only the
      right grep can.*
      *Also carried: the MODIFIED-delta warning. It is not this capability's contract, but it is the
      failure that produced most of what the gates catch, and it recurred three times in one day while
      being documented.*

## 3. Return `.claude/` to generated output

- [x] 3.1 Run `openspec config profile core` then `openspec update --force` in the worktree, and commit the
      output verbatim. Expect exactly one modified file — the skill — reverting to its generated form. Any
      other file changing means the generated set has drifted further than this change assumed; stop and
      re-scope.
      *Exactly one file changed: `.claude/skills/openspec-archive-change/SKILL.md`. The profile was already
      `core`, so the whole diff is the hand-edit unwinding. `config.yaml` is untouched by regeneration, as
      D1 measured.*
- [x] 3.2 Verify the gate is gone from **both** entry points (`grep -c` → 0 in the skill and the command)
      and that this is now correct rather than a regression, because `config.yaml` carries it.
      *skill 0 / command 0 / config.yaml 2. And the property this change exists to buy, tested directly:
      running `update --force` a **second** time leaves `SKILL.md` byte-identical (sha `5d214ad3…` both
      times) — regeneration is now a **no-op**. CLAUDE.md's "commit the output verbatim" is finally safe to
      follow, and the gate cannot be deleted by following it.*

## 4. Make the docs stop lying

- [x] 4.1 CLAUDE.md: record that the archive step's gates live in `openspec/config.yaml`'s `context:`
      block, and that `.claude/opsx` skills/commands are regenerable verbatim — which the existing
      "hand-edits are overwritten on the next update" warning already promises but which was false in fact
      until this change.
- [x] 4.2 CLAUDE.md: note that `openspec validate --specs --strict` checks structure only. It was green
      50/50 on a tree with 28 known drifts and is green 50/50 on the swept tree — the same answer with the
      lies in and with them out. A reader who assumes it gates truth will trust it wrongly.

## 5. Prove both gates fire on the path that was unguarded

The skill path is not the interesting one — it had the gate all along. Every check below runs through
`/opsx:archive`, which had none.

- [x] 5.1 Plant a `TBD - created by archiving` in some spec's `## Purpose`, archive a scratch change via
      `/opsx:archive`, and confirm it **fails and names that spec**. Before this change it reported
      success.
- [x] 5.2 Confirm the inverse: a spec quoting the placeholder inside `## Requirements` with a real Purpose
      does **not** block the archive — i.e. `openspec-archive-command` itself remains archivable.
- [x] 5.3 Plant a scratch change whose diff touches a module no delta covers, archive via `/opsx:archive`,
      confirm it fails and names the module. Then add a recorded reason and confirm it completes — the gate
      accepts accounting, not only deltas (D3).
- [x] 5.4 Replay the dead-type gate against real history as archive evidence — it is the one gate whose
      accuracy is measurable rather than argued. Confirm it fires on `611b51e` naming **both**
      `full-stack-harness` and `harness-world-model`, and is silent on `8345bae`, `cb8b9fe`, `569a52f`,
      `1f85ce6`, and `40a6ee2`. A version that also fires on those five is over-broad; one that misses
      either spec on `611b51e` has lost the fake-constructor idiom.
- [x] 5.5 Prove the gate on a live deletion: delete a type some spec names, archive a scratch change via
      `/opsx:archive`, confirm it fails naming both the type and the spec. Then confirm the move case is
      silent — re-home a type to another file and confirm no failure, since no spec was invalidated.
- [x] 5.6 Re-run `openspec validate --specs --strict` (green, 50/50 — it cannot see any of this, which is
      the point) and `openspec doctor`.

## 6. Hand off what this change deliberately does not do

- [x] 6.1 Record the deferred CI ratchet (D4) where the next person will find it: the placeholder gate is
      greppable and belongs in `build.yml` failing **closed**, under `ci-build`. This change leaves it
      failing open, knowingly, because splitting the gates across two mechanisms would leave neither
      well-argued. The dead-type gate is the strongest candidate to follow it (D6).
- [x] 6.2 Record the classes these gates cannot reach, so the next reader does not mistake three gates for
      coverage: **shape drift** under a stable name (`event-link`'s `EventConfig` — `minPhotoDate` went
      non-null and `startsAt` appeared; the identifier never died, so nothing fires), **purpose rot**
      (stale prose that is not a placeholder — the `bunny-*` key layouts), and **append-not-amend** (the
      four token-gate contradictions, where a spec asserts a route is both gated and ungated). All three
      need a model reading the spec against the code. That is the standing audit, not a gate.
- [x] 6.3 Record that the `docs(openspec):` sweep **has happened** (`2026-07-16`, ~20 corrections across 19
      spec files) so this change inherits a clean tree. That is the argument for landing the gates now
      rather than the argument against: the tree is clean only because a human paid to clean it, and nothing
      stops the next `611b51e` re-dirtying it identically. These gates are prospective by construction — the
      sweep was the retrospective half, and it should not need doing twice.
- [x] 6.4 Record the evidence gathered while fixing the drift, because it is the strongest case the gates
      have and it is all self-inflicted. Across three changes on `2026-07-16` the *same* failure recurred,
      by an author who had just finished diagnosing it:
      *(a)* `fix-download-integrity`'s delta modified `harness-world-model`'s download-seams requirement and
      left a sibling requirement in the same spec asserting `PhotoDownloadJobs` is the fake — caught three
      commits later, in the sweep.
      *(b)* `fix-stop-prohibition-scope`'s first deltas would have **deleted** `upload-lifecycle`'s
      transition table, its "No membership, no arm" paragraph (itself a fix for a shipped bug), and
      `event-rejoin-reconciliation`'s entire `resetTo` mechanics — because an in-place `MODIFIED` restates a
      whole requirement, and restating from the parts you have read drops the parts you have not. Caught by
      the sync, not by the author.
      *(c)* `specify-unattested-state`'s deltas were built programmatically from main and diffed — and still
      left a **scenario outside every requirement they named** contradicting the requirement above it.
      None of this required carelessness. It is what a requirement-shaped delta does to a prose-shaped
      contract, and it is why the gates ask questions rather than trusting the author to have asked them.

### Gate evidence — replayed against real history, 2026-07-16

*Gate 1 (placeholder), planted and run:* a `TBD - created by archiving` inserted into
`marketing-site`'s `## Purpose` is **flagged**; `openspec-archive-command`, which quotes that exact string
**5×** in its Requirements, is **not** — the discrimination the whole-file grep would destroy, bricking that
spec permanently.

*Gate 2 (delta completeness), replayed on `1f85ce6`:* its diff touched `domain/presentation` and
`domain/ui`; its nine deltas were `backend-deployment`, `bunny-list-endpoint`, `bunny-upload-endpoint`,
`device-attestation`, `device-config-endpoint`, `edge-upload-provider`, `event-creation`,
`event-leave-endpoint`, `event-notify-endpoint` — **every one backend**, none owning either touched module.
**FIRES** on exactly the change that shipped `SyncHealth.Unattested` with no owning spec.

*Gate 3 (dead types), replayed on six real changes:*

```
611b51e (killed the fossil)      FIRES  ⚠ ListingSyncStatusSource        → full-stack-harness, harness-world-model
                                        ⚠ OwnDeviceCompletedAssetsSource → harness-world-model
8345bae (config gate)            silent
cb8b9fe (scene delegate)         silent
1f85ce6 (add-device-attestation) silent   ← correct: it DELETED no type, it ADDED a state (gate 2's case)
71507e5 (my download fix)        silent
6ca9c02 (my cursor scope)        silent
```

One true positive naming both offended specs; five silent. `1f85ce6` being silent here and firing on gate 2
is the clearest demonstration that the two axes are complementary rather than redundant — neither would have
caught the other's case.
