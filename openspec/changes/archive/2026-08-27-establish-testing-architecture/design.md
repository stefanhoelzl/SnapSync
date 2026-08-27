## Context

Three standing testing rules have governed this codebase since before the module migration. They
live in CLAUDE.md's "Testing strategy" section and — abbreviated to two sentences — in
`openspec/config.yaml`'s injected `context:` block. No spec states any of them.

They are nevertheless treated as law. An audit of `openspec/specs/` found:

- **24 citations across 10 specs** of the "wiring-only and untested" rule, every one phrased as
  *by law* / *by rule* / *by project rule*;
- **4 citations by bare ordinal** — `per testing rule 1` (`harness-world-model:18`, `:42`;
  `ios-photokit-upload:34`) and `testing-rule-3` (`harness-world-model:528`) — pointing at a
  numbered authority that exists nowhere in `openspec/`;
- **16 further citations** of `commonTest` placement, of which five were pinned to real test files
  and found true.

Meanwhile the prose itself has drifted from the tree. Six findings, all verified against the
current checkout:

| # | claim | reality |
|---|---|---|
| ① | "`jvmTest`/`iosTest` hold only driver/cinterop wiring behind a shared contract" | `ui/components/src/jvmTest` holds 5 Compose behaviour tests; `:test:architecture` and `:tools:diagrams` are JVM-only `src/test` |
| ② | "integration tests assert `UiState` **and** world outcomes" | 9 of 15 `:test:integration` tests reference `UiState` zero times |
| ③ | the `:adapter:generic:fake` test enumeration ("RawAssetMapping, status sources, download trio, DeviceAttestation") | 11 files; three fit none of the four names |
| ④ | `:test:world` described as infra + contracts only | its `commonTest` holds 13 feature tests over the real stack — an undocumented tier |
| ⑤ | CLAUDE.md's module list | omits `:app:ios:forge` and `:tools:diagrams`, both named by `ModuleSetTest` |
| ⑥ | `ios.yml`'s `ios-test` step comment | cites `:domain:*`, `:capability:*`, `:domain:keychain`, `:domain:ui` — all pre-migration names |

Two structural facts constrain any statement of the rules:

1. **`:domain`'s `commonTest` cannot reach `:adapter:generic:fake`** — the fake module depends on
   `:domain`, so the reverse test edge is a project cycle. `harness-world-model:45` states the
   general form ("a test source set cannot be depended on across modules"), which is also why the
   storage contracts live in `:test:world`'s `commonMain`. Fake-driven feature tests therefore live
   in the fake module's own `commonTest`.
2. **`module-architecture:120` limits rule 1.** A gated scenario rejects hoisting a
   platform-to-neutral translation into `model/` *"so it can be exercised in `commonTest`"*. Rule 1
   and that law pull against each other, and the new spec must state the resolution or contradict a
   law with a guard behind it.

## Goals / Non-Goals

**Goals:**

- Give the 24 citations an address inside `openspec/`, so a later change is a delta against a
  stated contract rather than against prose.
- State the rules **as they are today**, including every exception the current tree actually has,
  and the residual risk each rule leaves uncovered.
- Correct the five specs that state something already false about testing.
- Remove the ordinal addressing scheme.

**Non-Goals:**

- Any behaviour, code, build-file, test, or CI change. `./gradlew build` is untouched.
- Writing the spec to anticipate a later reversal of the "shells are untested" rule. It describes
  today; the reversal is a delta.
- New guards or generators (see D4).
- Absorbing `architecture-guards` (what the gates check), `ci-build`/`ios-ci` (which jobs run),
  or `harness-world-model` / `full-stack-harness` / `desktop-test-harness` (what the harnesses
  are). These are cited, never restated.
- Fixing findings ⑤ and ⑥ — they belong to `module-architecture` and `ios-ci`'s workflow comment
  respectively, and are recorded below for a follow-up.

## Decisions

### D1 — A spec, not more prose

The project's own "WHAT EARNS A SPEC" test asks whether the contract is spread across artifacts with
invisible drift between them. It is: CLAUDE.md, `openspec/config.yaml`, ~12 `build.gradle.kts`
comments, `ios.yml`'s step comment, and 24 spec citations. Six drifts accumulated and none was
caught by anything. No single committed artifact is the contract, so a spec is not a second copy.

### D2 — No CLAUDE.md digest, and no digest guard

`module-architecture` is mirrored by a one-line-per-law digest in CLAUDE.md, tethered by
`LawsDigestTest`. The obvious move was a second instance of that mechanism. Rejected, for three
reasons found while examining it:

1. **It guards the wrong thing.** `LawsDigestTest` compares requirement **names** only — content
   comparison is explicitly rejected in its own KDoc ("two authorities would be worse than one").
   All six drifts above are truth-drift, not name-drift. A name tether would have caught **zero of
   six**.
2. **The falsehoods live in the elaboration, and a digest has none.** ①–④ are all in the
   explanatory sentences *under* a rule heading. Converting the section to one line per rule
   doesn't *catch* them — it deletes the surface they lived on. That outcome is available without
   any guard.
3. **The colocated copies were true where the central copy was false.** `ui/screens`,
   `ui/components`, and `adapter/generic/fake`'s build files each state the testing rule accurately,
   at the point of decision, with nothing holding them there. Distance is what rots a claim, not the
   absence of a tether.

A derived digest (generated from the spec, freshness-gated like `architecture/`) was also
considered. It is strictly better than a hand-written one on the repo's own principle — *"derived
diagrams can only be stale, and staleness is mechanically checkable"* — and it dissolves the
two-authorities objection. It was rejected here only on proportion: a generator plus a freshness
gate plus a spec-prose convention is a lot of machinery for three rules, and (2) already removes the
observed defect. It remains the right answer if a digest is ever wanted.

### D3 — A pointer, not a copy

CLAUDE.md's "Testing strategy" section becomes one pointer line at the spec.

This repo has answered "how does the agent reach this knowledge" twice, oppositely: operator
runbooks moved **out** of CLAUDE.md to pointers (gated for resolution by `RunbookSkillsTest`), while
architectural laws were duplicated **into** it. The distinction that justifies the split:

> Pointers serve known-unknowns; injection serves unknown-unknowns.

Nothing announces "you are about to place a class wrongly" — that is ambient, and needs injection.
But *"I am about to create a test file"* announces itself. The testing rules are triggered
knowledge, closer in kind to a runbook than to a placement law, so a pointer is the fitting
mechanism rather than a weaker one.

`openspec/config.yaml`'s two-sentence version is terse and true; it stays, gaining only a capability
citation. It is the highest-value in-context surface and is not where the rot occurred.

### D4 — No new guards in this change

Two guards were designed and deferred:

- **Gate A** (rule 2): assert no `app/**/src/*Test/` directory exists. Six lines, and today nothing
  enforces this — the shell gates forbid *decisions*, which is a different claim from *no tests*.
- **Gate B** (rule 1): a loud-when-stale allowlist over test source sets, in `ModuleSetTest`'s
  permitted shape. Building the allowlist is what surfaced findings ① and ②, so the guard would be
  the audit made repeatable. It needs only three rows if it derives the two non-exceptional kinds
  (source sets covering every target the module declares; contract-paired jvm/native splits).

Deferred because a guard is worth adding when rot recurs, and after this change there is a spec to
gate *against*, which there is not today. Gate A additionally has a short expected lifetime if the
shells-untested rule is later reversed.

### D5 — Fix what is false; leave what is true

Every citation was screened for truth **today**, producing three buckets:

- **A — false today (8 sites, 5 specs).** Fixed here. Precedent for reaching into specs this change
  does not otherwise touch: `config.yaml`'s dead-types gate is explicitly *"the only gate that
  reaches a spec your change never touched"*, and exists for exactly this.
- **B — unresolvable citation.** The 4 ordinals are fixed (D6); the 20 "by project rule" phrasings
  are left.
- **C — true today, will become false if the shells rule is reversed.** Untouched. Rewriting a true
  statement to survive an unproposed change is writing to anticipate.
  `architecture-guards:188/343/1540` are worth leaving intact for a second reason: they are the
  documented evidence base for that reversal (188 records the `onOpenURL` invite failure; 1540
  records that a shell join is "invisible to the world harness").

### D6 — Sweep ordinals only, and only where the text is already being edited

All four by-ordinal citations sit in `harness-world-model` and `ios-photokit-upload` — both already
Bucket A specs. So retiring the ordinal scheme costs no additional spec delta.

The 20 "by project rule" citations are left. House style would prefer an inline
`(capability testing-architecture)`, but a citation improvement is not a wrongness, and a `MODIFIED`
delta restates a **whole** requirement — `ios-photokit-upload:34` is a single ~1,800-word
requirement, and the risk of silently dropping unscrolled content exceeds the value of the added
provenance. Once the spec exists, a reader searching for "the project rule" finds it.

### D7 — State rule 2's residual risk explicitly

The requirement records that the shell gates (`detektAppShell`, `KotlinShellGuardTest`,
`SwiftShellGuardTest`) forbid **decisions**, and that this does not cover **mis-transcription**: a
zero-conditional forwarding naming the wrong collaborator passes every gate and compiles.

This is a true statement about today, not an anticipation. It is also what makes a later reversal a
clean delta — the reversal reads as "the stated residual risk was realised", rather than as a
contradiction of a rule that claimed more than it delivered.

### D8 — `commonTest` is where a test goes, never a reason to move code

The rule-1 requirement carries this clause explicitly, to honour `module-architecture:120`. A
platform-to-neutral translation stays beside its inputs even though that costs the faster test loop;
`gallery-status:312` is the worked example (interpretation covered by `iosSimulatorArm64Test`, policy
logic by `commonTest` over neutral facts).

### D9 — `harness-world-model`'s requirement name is kept, not renamed

The requirement is named "Integration tests assert UiState and world outcomes" while its corrected
body says `UiState` is asserted only where the seam reaches presentation. The name is kept.

Nothing outside the immutable archives references it — a repository-wide search finds the name only
in `openspec/specs/harness-world-model/spec.md` itself and in two archived change records — so a
rename would be cheap in references. It is not cheap in tooling: OpenSpec matches a `MODIFIED` block
to its target by **exact header text**, and pairing a `RENAMED` with a `MODIFIED` for the same
requirement has no precedent in the archive and no defined ordering. Weighed against that, the name
reads as a topic label for the surface while the contract lives in the SHALL text, which is now
precise. If the name is ever changed, it should be its own small change.

### D10 — The shell gates' real scope, corrected during implementation

Verifying the spec against the build (task 1.3) showed `detektAppShell` scans exactly
`app/ios/src`, `app/ios/extension/src`, and `test/rig/src/hook` — **not** `:app:ios:forge` and not
`:app:desktop`. The requirement's first draft implied the gates covered all four `:app:*` modules.

Corrected: the gates' scope is the two live-core shells, and the other two `:app:*` modules are
untested for reasons the requirement now states separately — `:app:ios:forge` links no live graph,
and `:app:desktop`'s harness panes are the named test-equipment zone. Conflating the three reasons
would have made the rule look more mechanically enforced than it is, which is the failure this whole
change exists to correct.

## Risks / Trade-offs

- **The elaboration relocates into the spec, where nothing checks it either.** → Accepted. The
  change buys *audited-once* and *addressable*; it does not buy *stays-true*. D4's Gate B is the
  known next step, and after this change there is something to gate against.
- **No in-context map during a 12-change sequence.** An agent gets the rules from the pointer or
  from neighbouring build files. → Accepted; revisit if it bites during the sequence.
- **`MODIFIED` deltas silently drop unscrolled content.** Five specs get one. → Build each delta
  from the current main spec and diff it; the removed lines must be only the intended ones
  (`config.yaml`'s standing instruction).
- **The pointer line is deletable with nothing to notice.** `RunbookSkillsTest` guards pointer
  *resolution*, not *existence*, and only for skills. → Accepted; extending it is cheap if wanted.
- **A statement of today's exceptions can itself go stale** as source sets are added. → This is
  precisely what Gate B would fix, and is the trigger for adopting it.

## Migration Plan

Documentation-only. No rollout, no rollback, no code path. Ordering: create the new spec first so
the five corrections can cite it in the same change.

## Bucket C — the citation sites this change deliberately leaves alone

Handed forward to whichever change reverses the shells-untested rule. All 20 are **true today**;
each reasons from untestedness to a placement decision, and each will need review — not merely
re-citation — if that rule changes.

```
architecture-guards      188, 343, 1319, 1540   ← also the evidence base for the reversal
upload-lifecycle         215, 268, 335, 479
ios-photokit-upload      643, 706
ios-app-shell            580, 709
photo-download           305, 691
event-album              161
event-creation-ui        271
ios-url-session-upload   281
module-architecture      9
photo-selection-policy   537
push-registration        87
```

`architecture-guards:188` records the `onOpenURL` invite failure — the shell defect that shipped —
and `:1540` records that a shell-placed join is "invisible to the world harness". Those two are the
documented cost of the residual risk that requirement 3 now states, and should be quoted by the
reversal rather than edited by it.

## Open Questions

- **Follow-up, `module-architecture`:** `:app:ios:forge` and `:tools:diagrams` are in
  `settings.gradle.kts` and in `ModuleSetTest`'s target list, but absent from the module-set
  requirement's enumeration and from CLAUDE.md's module list. `:app:ios:forge` is a `:app:*` module
  and therefore inside the shells rule's scope, so the new spec enumerates it; the module-set
  requirement should too.
- **Follow-up, `ios-ci`:** the `ios-test` step comment in `ios.yml` names pre-migration modules
  (finding ⑥). The spec text is corrected here; the workflow comment is not.
- **Unresolved:** `adapter/generic/app/iosSimulatorArm64Test` holds `NativeLedgerStoreTest`, while
  `adapter/ios/ext-safe/iosTest` holds both `IosLedgerStoreTest` and `IosDownloadStoreTest` — two
  modules with overlapping driver coverage. Whether that is deliberate (distinct drivers) or drift
  is unknown; a contract-pairing gate (D4) would force the question once.
