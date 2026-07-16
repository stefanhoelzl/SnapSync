## Context

An audit compared all 48 capability specs against the code and found **28 confirmed divergences** — 5 code
defects, 22 stale specs, 1 behavior owning no spec at all. Roughly 27 of 50 specs carried at least one
finding. `openspec validate --specs --strict`, the `build.yml` gate, was **green 50/50** on that tree,
including four specs that contradicted themselves within one file.

Those drifts were then swept by hand (`2026-07-16`). `validate` is **green 50/50** on the corrected tree
too. The number never moved — the same gate, the same answer, with the lies in and with them out. That is
not a bug in `validate`; it is the shape of it.

That is not a bug in `validate`. OpenSpec has no concept of the code:

| Surface | What it checks | Sees drift? |
|---|---|---|
| `validate --specs --strict` | structure: Purpose, Requirements, SHALL/WHEN/THEN, ≥1 scenario | no |
| `doctor` / `references` | relationships between spec **repos** (stores, federation) | no |
| `schema` / `templates` | the artifact pipeline `proposal → specs → design → tasks` | no |

Nothing in the tool has ever opened a `.kt` file, and no artifact links a capability to its modules.
OpenSpec is AI-native: the model is the mechanism, and the mechanism is prose. So the only question is
*where the prose lives* — and the answer is constrained by regeneration.

Sorting the drift by cause shows where it is manufactured, and where it is not:

```
  born at archive time                    inflicted from a distance
  ─────────────────────────               ─────────────────────────
  placeholder Purpose   (gate exists)     fossils: a LATER change kills a
  scope-list blindness  (this change)     type an OLDER spec names.
  append-not-amend      (semantic)        full-stack-harness was CORRECT on
  stale-prose Purpose   (blind)           07-03. No archive-time gate can see it.
```

The archive step is the choke point for the left column and structurally blind to the right one.

## Goals / Non-Goals

**Goals**
- The gates survive `openspec update --force`, which CLAUDE.md instructs the reader to run.
- One statement of each gate governs every archive path (skill, `/opsx:archive`, bare agent).
- Force the scope question that `add-device-attestation` was never asked.
- `.claude/` returns to being regenerable verbatim, as CLAUDE.md already claims it is.

**Non-Goals**
- **Fixing the 28.** A separate behavior-preserving sweep, shipped as `docs(openspec):` per `a47bc84`.
  These gates are prospective; they cannot see drift already in the tree.
- **A module→capability data artifact.** CLAUDE.md's module list already names capabilities inline
  (`:capability:album … (event-album)`); see D3 for why its rot is tolerable rather than absent.
- **Semantic gates.** "Does this new requirement contradict a surviving one?" would have caught the four
  token-gate contradictions. It needs the model to read a whole spec at archive time; not attempted here.
- **Drift under a stable name.** `event-link` declares `EventConfig { minPhotoDate: String? }` with no
  `startsAt`; the code requires `minPhotoDate` and has `startsAt`. The identifier never died, so the
  dead-type gate is silent by construction. Shape drift needs a model, not a gate.

An earlier draft of this change listed **retroactive drift** as a Non-Goal, on the grounds that a fossil is
invalidated from a distance and only a sweep can see it. That was wrong, and D6 records the measurement
that disproves it. It is a Goal now.

## Decisions

### D1. The gates live in `openspec/config.yaml`, not in `.claude/`

Measured on a throwaway copy of this tree, `openspec update --force` modified exactly one file:

```
M .claude/skills/openspec-archive-change/SKILL.md      gate: 4 occurrences → 0
  openspec/config.yaml                                 untouched
```

The skill being the *only* modified file confirms it is the sole hand-edit in the generated set — and
explains the `/opsx:archive` gap directly. Both entry points are generated from the same `core` profile
with `delivery: both`; the skill has the gate (4 occurrences) and the command does not (0) because someone
patched one of them in `73f6143`. The divergence is the signature of the patch, and the patch dies on the
next regeneration with a green run.

`config.yaml` is hand-authored (its `context:` opens "SnapSync: an iOS app for sharing photos…"), is
injected into every agent working in this root, and is not an instruction file. The existing requirements
say "**the archive step** SHALL verify" — implementation-agnostic — so relocating satisfies them
everywhere at once.

### D2. A forked schema cannot host these gates

`openspec schema fork spec-driven` is the other regeneration-proof surface, and it is the wrong shape.
Schema templates govern the four artifacts (`proposal`, `specs`, `design`, `tasks`); **there is no archive
artifact**. A gate written into the `tasks` template would be per-change, reach only changes created after
the fork, and be copyable-away by the next author. `config.yaml`'s `context:` is global and retroactive.

### D3. Delta-completeness is an accountability gate, not a mechanical one

The mechanical version — "every touched module must have a delta" — is wrong. CLAUDE.md permits
behavior-preserving work with no OpenSpec flow at all, so a refactor touching `:domain:ui` would fail a
mechanical gate for doing nothing wrong. Demanding a *reason* instead of a *delta* keeps the gate honest
without manufacturing false alarms.

This weakens it to self-certification: an agent can write "behavior-preserving" and pass. Accepted. The
failure being addressed is not dishonesty — it is that nobody was prompted to look. `add-device-attestation`
would have failed this gate loudly: its diff touched `domain/presentation/UiState.kt`,
`StatusContainerHost.kt`, `AttestedSource.kt`, and `domain/ui/components/AppStatusLine.kt` while every one
of its nine deltas was backend, and its own `tasks.md` 4.5 records — in prose, in the change — that D11's
"no new `App*` component" had been falsified during implementation.

The gate leans on CLAUDE.md's module list, and that list **rots — demonstrably, and then gets fixed by
luck**. `:capability:attest` shipped in `1f85ce6` (2026-07-14, *"feat(attest): gate every API route on App
Attest"*) and did not reach CLAUDE.md's module list until `0dc1e50` (2026-07-16) — an **unrelated docs
commit** (*"stop ios.yml and CLAUDE.md understating the test surface"*). The map was wrong for two days, and
the change that broke it never noticed; a passer-by did.

That is the same commit, `1f85ce6`, that shipped `SyncHealth.Unattested` with no owning spec. One change
drifted four specs, orphaned a UI state, and desynced the module map — and none of it was caught, because
nothing asked.

So the map is not the reliable artifact an earlier draft of this design claimed ("nothing new to rot"). The
gate survives the correction, and is in fact strengthened by it: an unmapped module is an *unaccounted*
module, so it fails the archive and forces the map to be repaired **by the change that touched it**, rather
than by whoever wanders past two days later. The map becomes self-healing under the gate instead of being
assumed correct — a better property than the draft asserted, but one that has to be argued rather than
taken on faith.

*(Verified against `main` at the time of writing: the map has no gaps in either direction. The evidence
here is historical, which is the point — nothing prevents the next gap.)*

### D4. Fail-open is accepted here, and it is the weakest part of this change

`:test:architecture` proves this repo can do better: `KeychainContainmentTest` fails **closed**, and no
amount of hurry gets a `SecItem*` call past it. A prose gate is advisory — an agent that skips it produces
a green run, which is exactly the failure mode that let 19 of 44 placeholders accumulate.

The rejected alternative is real and should be recorded plainly: **the placeholder gate is mechanically
checkable.** It is a grep over `## Purpose` sections — the spec even pins the scoping subtlety (whole-file
matching would make this very spec permanently unarchivable). A ~5-line `build.yml` step would fail closed
and never rot. It is strictly better than prose *for that one gate*.

It is not adopted here because it is a different capability (`ci-build`) and a different argument, and
because it covers only the placeholder half — delta-completeness needs judgment and cannot be greppped.
Splitting the two gates across two mechanisms in one change would leave neither well-argued. **Deferred,
not dismissed:** a follow-up should add the CI check and let `config.yaml` keep the half that needs a
model. That ordering also means the CI check, when it lands, is a ratchet under a gate that already exists
rather than a new rule.

### D6. The dead-type gate: diff-scoped, types-only, and it disproves the old Non-Goal

A fossil is only "retroactive" if you look at the wrong moment. The change that *kills* a symbol is the one
that should fix the specs naming it, and at that moment the question is mechanical. Measured over six real
changes:

```
611b51e  killed the fossil     ⚠ ListingSyncStatusSource        → full-stack-harness, harness-world-model
                               ⚠ OwnDeviceCompletedAssetsSource → harness-world-model
8345bae  config gate            none
cb8b9fe  scene delegate         none
569a52f  universal links        none      (deleted ConfigDeeplinkTest — no spec names it)
1f85ce6  add-device-attestation none
40a6ee2  fix-upload-lifecycle   none
```

One true positive — naming both specs that are still wrong today — and zero false positives. `1f85ce6`
returning nothing is the confirmation, not a miss: it deleted no types, it *added* `SyncHealth.Unattested`.
The identifier axis is silent there and the module axis (D3) catches it. Neither subsumes the other.

**Types only, including CamelCase `fun`s.** The first attempt matched every added-or-removed `class|object|
interface|fun|val|var` name and flagged **50 of 50 specs** on one change: `val config`, `val url`, `fun
start` are words every spec contains. Narrowing to removed `class|object|interface` went quiet but missed
`ListingSyncStatusSource` itself — it is a `fun` returning an anonymous object, Kotlin's fake-constructor
idiom. Removed `class|object|interface|fun` **requiring a leading capital and ≥5 characters** is the shape
that both fires and stays silent.

**Diff-scoped, not standing.** The standing variant — assert every backticked identifier in every spec is
declared somewhere — was probed too: 10 of 290 flagged, with real false positives (`NSSecureCoding`,
`PHCloudIdentifier`, `NSAppTransportSecurity`). Specs legitimately name Apple types and plist keys this
project never declares, so a standing check needs an allowlist: a new artifact, rot-prone, exactly what D3
just got burned by. The diff-scoped check has no such surface — a type the change deleted is by
construction one this project declared.

**Stated as prose despite being mechanical.** This gate could be a script, and unlike the placeholder gate
(D4) it needs no CI home to be useful, since it wants the diff. It lives with the other two so that all
three are read in one place and cannot diverge; the same D4 argument applies for promoting it to a
fail-closed check later, and it is the strongest candidate of the three.

### D5. Reverting the skill is part of the change, not cleanup

Leaving the hand-edit in place "as a belt and braces" would preserve the divergence between skill and
command, keep CLAUDE.md's "commit the output verbatim" a lie, and leave a second copy of the gate to drift
from the one in `config.yaml`. The skill goes back to generated output so that regenerating is a no-op —
which is the only state in which CLAUDE.md's instruction is safe to follow.
