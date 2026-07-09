## Context

SnapSync pivoted (2026-06-22) from a personal one-way library backup to an event photo-sharing client.
`docs/design.md` was amended in place through ~25 subsequent changes and is now 988 lines of stacked
"supersedes" blocks, a dead narrative explicitly *"retained for context"*, and mutually contradictory
sections.

The rot is not neglect — it is **structural**. `docs/design.md` restates contracts that
`openspec/specs/` owns, and eight specs delegate their rationale back to it:

```
bunny-list-endpoint/spec.md:17   →  "Authoritative design: docs/design.md §3.1 (keys), §4 (storage/auth)."
docs/design.md §4                →  "`bunny-list-endpoint` is the sole authority on the URL format."
bunny-list-endpoint/spec.md:80   →  "This capability is the sole authority on the download-URL format."
```

Nobody owns the key layout, so `§3.1` drifted three reversals behind (`flatten-event-namespace` →
`dedup-files-device-manifests` → `device-namespace-reorg` → `restructure-storage-url-layout`) and no
review caught it.

A survey of the 49 live inbound references shows what the codebase actually wants from the file:
**§2.2 (9 refs), §2.4 (6), §2.3 (4)** — the platform seam, the status projection, the
snapshots-not-events argument. The contract sections everyone assumes are the payload — §3.1 keys (2
refs), §4 storage (1) — are barely cited. **The code cites `design.md` for the reasoning, not the
contracts.**

Two constraints landed during the design interview and reshaped the change:

1. **The specs cannot yet carry the weight.** 19 of 44 live specs have
   `Purpose: TBD - created by archiving change X. Update Purpose after archive.` — a string minted by the
   openspec CLI. `openspec-archive-change/SKILL.md` never mentions Purpose, so no archive ever filled one
   in. Eight more punt Purpose to `docs/design.md`. The sets are disjoint: **27 specs need a real Purpose.**
2. **OpenSpec has no durable home for rationale, deliberately.** Its own artifacts say so:
   `proposal.md` = what & why · `changes/<id>/design.md` = how (`## Context` / `## Goals / Non-Goals` /
   `## Decisions D1…Dn`) · `specs/<cap>/spec.md` = `## Purpose` + `## Requirements`. Rationale is a
   decision record attached to the change that made it, and it is **already there** —
   `archive/2026-06-12-sync-engine-ledger/design.md` holds the token-expiry / ~150 GB argument.

## Goals / Non-Goals

**Goals:**
- Make `openspec/specs/` the sole contract of record and `openspec/changes/archive/<id>/design.md` the
  sole decision record.
- Leave zero dangling references to the deleted file.
- Make the "specs are the source of truth" claim *true* by writing the 27 missing Purposes.
- Stop the placeholder-Purpose generator so this class of rot cannot recur.
- Retire the "backup" vocabulary from product-facing prose.

**Non-Goals:**
- Renaming `SyncEngine` / `SyncStatus` / `SyncProgress`, or the `sync-*` spec directories.
- Renaming the app (`app.snapsync`, `snapsync://`, `snapsync.stho.net`, `SnapSyncKit`).
- A root `README.md`, a `docs/invariants.md`, or any other durable narrative doc.
- Preserving `design.md` §1's non-goals or §8's open-questions list.
- Any change to an **existing** `SHALL` / `WHEN` / `THEN`. The change's only new requirements belong to
  the new `openspec-archive-command` capability (D6); the 27 edited specs are touched in `## Purpose` only.

## Decisions

### D1: Delete `docs/design.md` outright rather than amputate it to its unique ~40%

Considered keeping the path and gutting the body to delegation stubs — that would have broken zero
references and required no Kotlin edits. Rejected because an audit showed the "unique 40%" is smaller
than it looks: the **no-deletion-during-an-active-event invariant is already in `sync-status/spec.md`**
(lines 96–100, with its justification), **single-writer is already codified in `sync-ledger/spec.md`**,
and §8's device-verification list is already mirrored in `bunny-upload-endpoint`'s non-normative
`## Assumptions (unverified on device)` section — which is where it belongs, next to the contract it
qualifies. (Correcting an overstatement made while proposing this change: the endpoint's OPTIONS *response*
is a shipped Requirement, but whether **iOS** falls back to a plain `PUT` remains genuinely unverified. §8 was
partly stale, not wholly — and the live part already had a better home.) A slim `design.md` would be a
narrative doc with no owner and no forcing function — the same failure, one third the size. What is
genuinely unique (the "why not events", "why ledgered" arguments) lives in the archive by construction.

### D2: Rationale stays in `changes/archive/<id>/design.md`; do **not** add `## Why` to live specs

`## Why` was verified to pass `openspec validate --specs --strict`. Rejected anyway: it invents a
convention the tooling does not know, it would fragment cross-cutting arguments (*"why snapshots, not
events"* spans engine + status + presentation, so it would have to be duplicated into three specs —
re-creating the exact duplication-drift being undone here), and it contradicts OpenSpec's model in which
rationale is per-change. The idiomatic replacement for `Authoritative design: docs/design.md §2.2` is
`Decision record: changes/archive/2026-06-12-sync-engine-ledger`.

### D3: Write all 27 Purposes in this change, not just the 8 the deletion orphans

Deleting the knowledge-holder while 43% of the contract of record reads `TBD` would leave the repo
strictly worse than before. The 8-only variant ships the deletion faster but cements the lie. Each
Purpose is derived from the spec's own `## Requirements` plus its archived change's `proposal.md` —
mechanical, and it is the work that makes D1 defensible.

### D4: `share` is the product word; `sync` is the mechanism word; `SnapSync` is a proper noun

`backup` is wrong on all three axes it once asserted (one-way, personal, restore-oriented). The shipped
UI already resolved this — every user-visible string in `StatusScreen.kt` says *share* / *receive*, none
says *sync* or *backup*. So the drift is docs-only. `sync` is retained for the machinery because
`SyncEngine` genuinely synchronizes a device's photo set against event storage — and it now moves photos
in **both** directions (upload own, download + import foreign), making the word *more* accurate than in
v1. `SnapSync` stays: renaming implies a new bundle id, a new deeplink scheme (breaking every printed QR
and every joined device), a new host, and a new TestFlight app.

### D5: KDoc pointers are repointed to the owning spec, not deleted

`design.md §2.2` → `openspec/specs/sync-engine`. Deleting the pointers was cheaper but loses the trail
from code to contract; dual pointers (spec + archived decision record) were rejected as two long paths
per comment. Repointing is only viable *because* of D3 — the pointer now resolves to a spec with a real
Purpose.

### D6: Fix the generator, and give the fix a spec

`.claude/skills/openspec-archive-change/SKILL.md` gains a post-archive step: replace any minted
`TBD - created by archiving` Purpose with one derived from the change's proposal and delta specs, and
fail the archive if a `TBD` survives. Without this, the next archive mints a fresh placeholder and the
19-spec debt reaccumulates.

The patch is backed by a new **`openspec-archive-command`** capability spec. A bare skill edit was
considered and rejected: a behavior with no contract is exactly what rotted here, and this repo already
specs its process capabilities (`ship-command`, `branch-protection`, `ci-build`). Making the archive
step's Purpose obligation a `SHALL` gives it a test surface and a home. A CI grep guard was considered as
belt-and-braces and deferred — the skill patch plus its spec address the actual producer.

### D7: Drop the non-goals and the open-questions list rather than relocate them

Audited against shipped specs, `design.md` §1's non-goals are mostly **false**: *"never deletes
remotely"* and *"no TTL/purge — photos persist indefinitely"* are contradicted by `event-leave-endpoint`
(last-member leave reaps the event tree and GCs freed byte partitions); *"no content-dedup"* is stale
(`dedup-files-device-manifests` ships same-device, cross-event dedup — only cross-device is absent);
*"no album selection"* is superseded by `event-album`; *"no settings screen"* is nuanced (the join surface
carries date-cutoff and direction controls). Relocating a list that is majority-wrong would launder
staleness into a new file. Per-change `Goals / Non-Goals` in `proposal.md` is the openspec idiom; a
durable global list is not.

### D8: The 27 spec edits are editorial and carry no delta

Every edit to an **existing** spec is editorial — `## Purpose` prose, removal of `Authoritative design:`
pointers, and one typo (`device-manifest`'s Purpose says `/events/<eventId>/device/<deviceId>.json` where
its Requirements say `devices/`). No existing `## Requirements` block is touched, so there is nothing to
express as an `ADDED` / `MODIFIED` / `REMOVED` delta against them. The change's sole delta is the new
`openspec-archive-command` capability (D6). `openspec validate --specs --strict` is the gate.

## Risks / Trade-offs

- **[Rationale becomes harder to find]** → Recovering *"why is the engine ledgered"* now means reading
  `changes/archive/2026-06-12-sync-engine-ledger/design.md` rather than one `design.md` section. Mitigation:
  each affected spec's Purpose names its decision record by path, so the hop is one grep, not an
  archaeology exercise over 73 archived changes.
- **[A Purpose written from Requirements can restate rather than illuminate]** → 27 Purposes authored in
  one pass risks mechanical, low-value prose. Mitigation: each is written from the spec's Requirements
  **plus** its archived `proposal.md`'s "Why", which carries the motivation; a Purpose that only
  paraphrases its own requirements is a failed Purpose.
- **[Deleting non-goals makes settled questions re-litigable]** → *"no multi-event"* loses its written
  home. Accepted (D7): the falsified majority made the list a liability, and each survivor is implied by
  the absence of a spec. Revisit if someone actually re-proposes one.
- **[16 Kotlin files touched for comments only]** → A source diff in a docs change invites reviewer
  fatigue and merge conflicts. Mitigation: comment-only, no identifiers, no behavior; `./gradlew build`
  gates it. The alternative (dangling refs) is worse.
- **[Losing the end-to-end narrative]** → No single file will describe the system top to bottom for a
  newcomer. Accepted deliberately: no root README (D-scope). The 44 specs plus `CLAUDE.md`'s module map
  are the entry point.

## Migration Plan

1. Write the 27 spec Purposes and strip the `docs/design.md` pointers. Gate:
   `openspec validate --specs --strict`.
2. Repoint the 16 KDoc + 3 `build.gradle.kts` refs onto the now-real specs. Gate: `./gradlew build`.
3. Update `CLAUDE.md`, `app/ios/CLAUDE.md`, `backend/README.md`.
4. Delete `docs/design.md` and `docs/sync-refactor.md`. Gate: zero matches for `design\.md` outside
   `openspec/changes/archive/` and `.claude/`.
5. Patch `openspec-archive-change/SKILL.md`.

Ordering matters: the deletion is **last**, so every step before it can still consult the file it is
retiring. Rollback is `git revert` — nothing here is stateful and no runtime behavior changes.

## Open Questions

None blocking. All branches were resolved in the 2026-07-09 design interview; the outcomes are D1–D8.

Two findings surfaced **during apply** and were deliberately deferred rather than folded in:

1. **"backup" survives inside 12 `## Requirements` bodies** (`ios-photokit-upload` ×3, `sync-status-screen`
   ×4, `sync-status` ×1, `photo-date-cutoff` ×1, `event-creation-ui` ×2, and one scenario name). Rewording
   them would need `MODIFIED` deltas and would break D8, so this change touches only the 3 occurrences in
   `## Purpose`. Note that **two of the twelve are correct as written** — `event-creation-ui`'s *"Sharing
   framing in create and status copy"* and `sync-status-screen`'s *"Copy avoids backup framing"* are
   requirements that **mandate** avoiding the word. A follow-up change should reword the other ten, not
   these.

2. **`sync-status-screen` is stale against the code.** It specifies `UiState.Joining` and
   `UiState.JoinFailed` rendering the copy *"Checking what's already backed up …"*; the shipped
   `UiState` has `JoiningEvent(eventId, phase: JoinPhase)` and no such string exists anywhere in
   `:domain:ui`. This is a spec↔code divergence at the behavior level, not a vocabulary problem — an
   editorial change must not silently rewrite it. It needs a read of the real screen and the join flow to
   decide whether the spec or the screen drifted. **Deferred to its own change.**
