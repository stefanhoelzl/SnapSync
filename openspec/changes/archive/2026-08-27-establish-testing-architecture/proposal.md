## Why

The project's testing strategy is cited as binding law in **24 places across 10 specs** — "by law",
"by project rule", and four times by bare ordinal ("per testing rule 1") — while the rules themselves
live only in CLAUDE.md prose. `ls openspec/specs/` has no testing spec, so every one of those
citations resolves to nothing inside `openspec/`, which `openspec/config.yaml` forbids outright:
*"A spec's Purpose never defers its meaning outside openspec/."*

Nothing holds the prose to the tree, and an audit of the current tree found it **false in six
places** — among them "`jvmTest`/`iosTest` hold only driver/cinterop wiring" (`:ui:components`'s
`jvmTest` holds five Compose behaviour tests) and "integration tests assert `UiState` **and** world
outcomes" (9 of 15 `:test:integration` tests reference `UiState` zero times). The same audit found
five specs stating something already false about testing, including one that contradicts itself
within a single file.

This capability earns a spec by the project's own test: the contract is spread across CLAUDE.md,
`openspec/config.yaml`, a dozen `build.gradle.kts` comments, `ios.yml`'s step comment, and 24 spec
citations — and drift between them was invisible until this audit went looking. No single committed
artifact is the contract.

## What Changes

- **A new `testing-architecture` spec** stating the testing contract as it is **today**: the three
  standing rules, where each kind of test lives and which targets it runs on, why the `:test:*`
  modules exist, and what is deliberately not tested at all. Written to describe the current tree —
  including its exceptions and its residual risks — not the intended tree.
- **Five specs corrected** where they state something already false about testing (details in
  `design.md`): an adapter labelled an untested shell, two references to the retired `:capability:`
  namespace, "untest**able** by project rule", `UiState` asserted as universal, and a CI job
  described as running only `commonTest` when 22% of what it runs is not.
- **The four by-ordinal citations retired.** `per testing rule 1` / `testing-rule-3` become
  citations by requirement name. Ordinals as an addressing scheme mean inserting a requirement
  silently re-points four other specs, and no guard covers that.
- **CLAUDE.md's "Testing strategy" prose replaced by a pointer** to the spec. The elaboration under
  each rule is where four of the six falsehoods lived; a pointer has no elaboration to rot. No
  digest and no digest guard — see `design.md` for why that mechanism was considered and rejected.
- **No new guards, no new tooling.** Deliberately. Rationale in `design.md`.

Not breaking: no behaviour changes, no code changes, no test changes.

## Capabilities

### New Capabilities
- `testing-architecture`: the testing contract of record — what is tested, where each kind of test
  lives, which targets it runs on, why the test-only modules exist, and what is measured on hardware
  rather than asserted.

### Modified Capabilities
- `event-album`: the requirement calls `AlbumManager` "`iosMain`, wiring-only and untested"; it is
  an `:adapter:ios:ext-safe` adapter, and adapters are tested (24 test files in that module).
- `ios-photokit-upload`: two references to the retired `:capability:` namespace (one contradicting
  this same file's own line 34); "untest**able** by project rule" where the rule makes code
  untest**ed**; and the cursor store's `NSUserDefaults` archiving called "untested iosMain wiring"
  when `IosDiscoveryStore` is an `:adapter:ios:ext-safe` adapter, not shell wiring. Also carries one
  of the by-ordinal citations.
- `harness-world-model`: "Integration tests assert UiState **and** world outcomes" is false for 9 of
  15 tests in `:test:integration`. Also carries three of the four by-ordinal citations.
- `ios-ci`: the `ios-test` job is described as executing "the shared modules' `commonTest` suites";
  it executes 140 test classes of which 31 are `iosTest`/`iosSimulatorArm64Test`, including the
  entire 28-class iOS adapter suite.

`upload-lifecycle` is **not** listed above and carries no delta file. Its one false statement — the
Purpose calling the iOS composition root "a module" when it is a file in `:app:ios` — is in a
`## Purpose`, not a requirement, and no requirement of that capability changes. A Purpose-only delta
is rejected by `openspec validate` and has no precedent in 206 archived changes, so the correction is
made as a direct Purpose edit (task 2.3) and recorded here so the archive's delta-completeness gate
is pre-answered: **capability touched, no requirement changed, no delta needed.**

The remaining 20 citations ("wiring-only and untested by project rule") are **true today** and are
left untouched (enumerated in `design.md`). A citation improvement is not a wrongness, and a `MODIFIED` delta restates a whole
requirement — one of these sites sits inside a single ~1,800-word requirement.

## Impact

- `openspec/specs/testing-architecture/spec.md` — new.
- Five existing specs — corrections plus the four by-ordinal citations.
- `CLAUDE.md` — the "Testing strategy" section becomes a pointer. No guard parses that section
  (`LawsDigestTest` reads `## The laws (digest)`, `RunbookSkillsTest` reads the runbook block), so
  nothing breaks.
- `openspec/config.yaml` — its two-sentence testing statement is true and stays; it gains a
  capability citation.
- **No code, no build files, no tests, no CI.** Nothing in `./gradlew build` changes.

Adjacent drift surfaced by the audit and deliberately **not** fixed here, because it belongs to
other capabilities and would widen this change: `:app:ios:forge` and `:tools:diagrams` are missing
from CLAUDE.md's module list and from `module-architecture`'s module-set requirement while
`ModuleSetTest` names both; and `ios.yml`'s `ios-test` step comment cites pre-migration module
names. Both are recorded in `design.md` for a follow-up.
