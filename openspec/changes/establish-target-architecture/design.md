# Design — establish-target-architecture

## Context

The target architecture was produced by a structured interview (2026-07-16/17) working from the
codebase's own evidence: churn analysis (the two composition roots are the most-edited files;
`fix(ios)` the largest fix category), two code audits (readGate written three times with a
byte-identical string; four copies of one HTTP PUT; ~300 lines of testable logic in the untested
shell), literature research (Cockburn/Martin/Graça on port ownership — interfaces in the core is
essentially undisputed; Evans's thin application layer; Seemann on composition roots and shared
factory functions; Bernhardt's functional core / imperative shell), and KMP practice research
(JetBrains/Google/Touchlab module minimalism; NiA's `:core:model`; production apps from
Confetti's one shared module to Tivi's regretted 71). The draft was then hardened by four
independent adversarial reviews (~25 findings; four enforcement claims refuted and replaced) and
a 40-claim necessity audit (three "platform necessities" dissolved; two repo-doc falsehoods
caught). The full audit trail lives in this change's decision list below; the design document of
record during the process was the interview's v3 target file.

Current state: 31 modules whose names encode no law; `:domain:status → :capability:membership`
declared and never imported; the documented spine inverted in two places; `:app:ios` at ~2,400
untested lines reaching nine external systems; an abandoned first attempt at writing the
architecture down (branch `arch-abandoned-v1`) whose measured tooling results this design reuses.

## Goals / Non-Goals

**Goals:**
- Make the target architecture the contract of record (specs in this change), with every law
  carrying its enforcement mechanism and every necessity claim its forcing proof.
- Land the enforcement and observability infrastructure that is independent of code movement:
  the beacon module, the new gates, the diagram generators and freshness gate, the CI job.
- Record the decisions (including three user decisions with their evidence) so follow-up
  migration changes execute against settled questions.

**Non-Goals:**
- No production code moves in this change. Placement deltas ride the follow-up migration
  changes, each behavior-preserving and independently shippable.
- The audit-decided behavior changes (liveness poll, reinstall-= -left/config-file re-backing,
  device-identity annotation) are recorded here but shipped by their own changes with their own
  spec deltas to `sync-status`, `event-rejoin-reconciliation`, `device-identity`.
- No migration sequencing here (deliberate; separate proposal).

## Decisions

### D1 — One `:domain` module; packages + text gates carry the internal lines
JetBrains/Google/Touchlab consensus plus the repo's own withhold-rule; the two platform-forced
lines (platform-freedom, extension-safety linkage) stay compile errors. *Rejected:* per-feature
modules (compile-enforced blindness) — every heavily-modularized reference point warns against
it, iOS sees one umbrella anyway, and the platform-reality review measured the build-loop cost
as wash-to-win for one module. *Survived adversarial review.*

### D2 — Ports: interfaces in the core, named for the need; adapters by technology and linkage
Cockburn/Martin/Graça consensus (the only accepted variant is a neutral abstractions module;
interfaces-with-the-implementer is universally wrong). Need-naming test: survives a second
platform. Backend split by need so a god `BackendApi` never forms. *Rejected:* interface+impls
in one module per port (creates a `:model→:ports→:model` cycle); grouping ports by backing
system (re-imports Apple's taxonomy).

### D3 — SyncEngine and all domain services live in `model/`
Domain services beside entities and port interfaces is textbook (Cockburn: "only 2 layers —
inside and outside"). *Rejected:* hiding SyncEngine behind the ledger port (a tested decision
buried in an adapter — the same category error the Keychain module once made).

### D4 — flow/ is commands-only; reads are feature-owned projections
Two independent reviews converged against reads-through-flow (views have no home in
one-file-per-trigger; a fourth reduction hop per screen; `combine` breaks the transcriber). User
decision 2026-07-17 reversing the earlier reads-through-flow choice. Flow instances are an
injected surface built in `compose/` (the log decorator and the iOS forge silently depended on
this). Three driver kinds; adapter callbacks are compose-built single-command lambdas.

### D5 — Composition: shared functions + pure sealed selection + shell thunks
Seemann's sanctioned shape (shared factory, per-binary adapter choice); selection is
`resolveComposition(directives, osFacts) → CompositionMode` (sealed — data-class fields provide
no compiler fail-closed; the sealed `when` does), unit-tested precedence (the shipped
forge×link interaction bug becomes a resolver test); `composeRoot` invokes only the selected
tier's thunk, preserving only-selected-adapter-is-constructed structurally. Wiring graph is
smoke-tested, never unit-tested.

### D6 — Manual DI, chosen not inherited
Dagger cannot target Native (API contract); Koin's runtime resolution is an unacceptable failure
mode in an OS-scheduled appex; kotlin-inject buys nothing at one-screen-graph scale and breaks
the PSI-transcribability the diagrams and gates ride on. Revisit triggers: real scoping needs,
an Android target, compose outgrowing a screen, multi-team assembly.

### D7 — Gates: fail closed on novelty; text over imports; proven tools over invented ones
Scopes derived at runtime; the only lists are loud-when-stale. Text matching because
fully-qualified references import nothing (measured in-repo). Shell gate = detekt complexity
threshold (measured in the abandoned v1: found exactly the shell's 43 decisions) — *rejected:*
a "Konsist PSI visitor" (Konsist exposes no public AST; a compiler-embeddable dependency pins a
second Kotlin version). `buildHealth` scoped to jvm/common (no upstream iOS support).

### D8 — Beacon posture: red until done, non-required, with named tooling exemptions (REVISED)
Original decision (2026-07-17): exit-0 informational beacon. REVISED same day at apply time (user
decision): the `verify` job FAILS while migration distance is nonzero and goes green exactly at
completion — the red→green artifact restored. The mechanical blockers a red non-required check
trips are handled by the two named, loud-when-stale exemptions this design previously rejected
and then improved past: both tools now judge REQUIRED checks only, DERIVED from branch
protection at run time — no name list at all, per the fail-closed-on-novelty rule. Deliberate
side effect, user-accepted: ios-deliver/ios-promote (non-required) no longer block a release.
Unchanged: not a required check; nothing gates new violations during migration (review is
measured absent); the burn-down numbers are the signal. Alongside it, the `diagrams` job of the
same workflow IS a required check (main.json): regenerate-on-clean-checkout + porcelain, blocking
stale diagrams on every PR. *Rejected:* count-ratchet (recommended by two reviews; declined
thrice); exit-0 (superseded — the color now carries the signal it previously refused to).

### D9 — Diagrams derived, committed, freshness-gated in build + a required CI check (no self-heal)
Derived-only because hand-drawn diagrams rot (the CLAUDE.md graph "was never true"). Freshness
as an ordinary test, not `git diff --exit-code` (fails open on untracked files; lives outside
the canonical build; assumes a clean tree). Byte-determinism checklist from the screenshots
lesson. Transcriber grammar is a closed whitelist including sealed-result `when`; all current
triggers paper-transcribed before the gate arms.

### D10 — Necessity-audit outcomes (user decisions, evidence attached)
- **Reinstall = left the event.** Config re-backs from Keychain to an App-Group file (same
  protection window — the repo's own Decision 8 argument; Keychain uninstall-survival is
  explicitly non-contractual per Apple, and for config it was an unchosen product behavior).
  `SecureStore` narrows to device-id + attest token.
- **Liveness is a latency bound, not an event mechanism.** Foreground-gated poll replaces the
  Darwin ding; `ProcessSignal` and `ProtectedData` ports are deleted (Apple/DTS: suspended apps
  never resume on unlock — the defer-queue fired into a void; lock-state comes from three-state
  store reads in both processes). No C-callback interop remains in the target.
- **Swift is a transcriber**; the single pinned irreducible (extension result construction) is
  expected to dissolve pending an SDK visibility check.

### D11 — CLAUDE.md carries a gated laws digest, not just a pointer
Agents write most code with CLAUDE.md in context; under the beacon-only posture (D8), in-context
law knowledge is the only migration-time drift defense. So CLAUDE.md gets a one-line-per-law
digest (successor to the "Hard rules" section) while authority stays in the spec — and a
consistency guard asserts the digest names exactly the spec's requirement set, so the copy is
loud-when-stale instead of quietly rotting the way the old module graph did. *Rejected:* pointer
only (weakens D8's sole mitigation); duplicating full law text (two authorities).

## Risks / Trade-offs

- [Unguarded drift during migration (D8)] → accepted by decision, on record; mitigations are the
  visible burn-down and the follow-up option to add the ratchet later without redesign.
- [Text gates false-positive on strings/comments] → match on reference-shaped patterns, keep
  non-vacuity twins, and tune against the current tree before arming.
- [Transcriber grammar too tight for a real flow] → all existing triggers are paper-transcribed
  in the diagram task before the gate arms; the grammar is widened consciously, never per-file.
- [Konsist 0.17.3 is 19 months stale] → used for text+scope only (no AST dependence); the detekt
  and compiler tiers carry the structural load.
- [No self-healing on main (dropped at apply time, user decision)] → a semantically stale merge
  of two individually-green structural PRs turns the required `diagrams` check red on the merge
  commit; visible immediately, fixed by a one-command regeneration PR. Accepted over an
  auto-pushing bot on the default branch.
- [Keychain→file config re-backing changes reinstall semantics] → that is the point (chosen);
  the rejoin-reconciliation capability already covers re-scan; the delta ships as its own change
  with on-device verification.

## Migration Plan

This change lands docs + specs + test/CI infrastructure only; rollback is deleting the beacon
module, the generators, and the CI job. The code migration is a separate proposal (follow-up
changes, each behavior-preserving: dead-weight deletion; core extraction; adapter extraction;
UI re-homing; harness collapse; config re-backing; liveness poll), each carrying only the
placement/behavior deltas for the specs that name what it moves.

## Open Questions

Settle on next mac/device session (tracked in tasks): extension result type ObjC visibility
(expected: the last Swift pin dissolves) · `PHBackgroundResourceUploadExtension` deprecation
annotation in the Xcode 26 SDK · `BackgroundUploadURLBase` runtime-destination rules ·
defer-queue dead-code count in `debug.log` (expected zero — confirms the `ProtectedData`
deletion) · `backup2` App-Group extraction attempt · CUFUA-file pre-first-unlock error shape.
Time bomb: re-evaluate the 26.1 upload-extension protocol at iOS 27 GM (~Sept 2026).
