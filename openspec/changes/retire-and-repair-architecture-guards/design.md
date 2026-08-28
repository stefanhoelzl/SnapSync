## Context

`:test:architecture` holds 44 guards, 6,273 LOC — 19% the size of `:domain`. They run in 8 s and have
needed little maintenance (28 of 44 written once and never touched). Cost was never the problem. The
question was whether they *work* and whether each still earns its place.

Every claim below was measured against the working tree on 2026-08-28, with the tree restored clean after
each experiment.

**The method mattered more than the verdicts.** Guards were first reviewed by reading. Of the first six,
three had a gap between claim and behaviour, and **only one was findable by reading** —
`ConstructorBlockingTest` looked immaculate (forcing proof, expiry trigger, reasoned grandfather list,
depth-bounded transitive follower with a documented rationale) and was dead. The review therefore switched
to a **mutation sweep**: for each guard, introduce the violation it claims to catch, run it in isolation
with `--rerun-tasks`, confirm red, restore. 43 guards across four batches.

Two facts made the sweep cheap: `:test:architecture:test` compiles only `:domain` (JVM), so mutations in
adapter `iosMain`, Swift, plists and entitlements carry no compile risk; and every guard reads source text
or build files, so a mutation is a one-line append.

**40 guards fired correctly.** Every green result was traced before being reported — in eleven cases the
invalid mutation was the reviewer's, not a guard defect (a comment where code was needed, a
`replace(…, 1)` that hit a comment occurrence first, a probe named `__sweep` against a regex requiring a
letter-initial identifier).

## Goals / Non-Goals

**Goals:**

- Remove guards that cannot fire, or whose failure is not the kind this capability exists to prevent.
- Replace text gates with structure wherever a compiler, module boundary or visibility modifier can carry
  the same invariant — the module set's own law prefers a compile error to a remembered rule.
- Convert *remembered* detectors into *derived* ones, or invert them so they fail closed on novelty.
- Keep every spec requirement gated. An ungated SHALL contradicts this capability's first requirement.
- State the exposures accepted, rather than letting deletions imply the risk went away.

**Non-Goals:**

- Replacing the guard mechanism itself. Guards remain ordinary tests in `:test:architecture` gating
  `./gradlew build`.
- The tooling migration (Konsist → Konture, semgrep for Swift). The specs name no tooling, so that is
  mechanical work outside this change.
- Retaining the mutation-sweep harnesses. They were a one-off measurement, deliberately discarded.
- Re-litigating the 21 guards whose contract is unchanged.

## Decisions

### D1 — The bar: a three-part conjunction, with 1b added mid-review

A guard earns its place only if **all three** hold:

1. The failure it prevents is **silent**, and either **(1a)** it is a field defect — green build, no crash,
   wrong or missing behaviour on a user's phone — or **(1b)** its recovery cost is a **migration rather
   than an edit**.
2. **No compiler, module boundary or type construction can express it.**
3. **It cannot be satisfied by editing the guard itself** — the population is derived, not remembered.

1b was added after two guards were kept that plainly failed 1a. Formalising the exception was better than
letting the bar quietly stop gating; it then applied uniformly to the rest.

*Alternative considered:* field defects only, strictly. Rejected because it deletes `ModuleSetTest`,
`DetektTierCoverageTest` and `ZoneFeatureBlindnessTest`, whose violations compound silently and whose
recovery is another migration — the exact cost this architecture was built to avoid paying twice.

### D2 — Split `:domain` into per-zone modules rather than gate zones with text

The zone gates match one regex, `app\.snapsync(\.[A-Za-z0-9_]+)+`, so they must enumerate the forms a
violation can take. A module boundary enumerates nothing: the symbol is not on the classpath, so import,
aliased import, star import, fully-qualified reference and typealias are all unresolvable.

Three further properties settle it. The gates **cannot see generated source** (`zoneFiles` skips `build`
dirs; the task inputs exclude `**/build/**`), while `:domain` has a `generateLinkOrigin` task. The gates
**can fail open**: `zoneFiles` returns `null` when a zone directory is absent, `pendingOrEmpty` prints
`PENDING` and returns `true`, and the test passes green — renaming `feature/` silently disarms it. And a
compile error appears in the IDE on the line, not in another module's test task.

Measured cost: **18 `internal` declarations** across all of `:domain`, none in `ports/`, `flow/` or
`compose/`.

*Trade-off accepted:* four more KMP modules (each compiling jvm + iosArm64 + iosSimulatorArm64), and 18
declarations widened from `internal` to `public`, which weakens a different boundary. A module boundary is
also only as strong as `api()` vs `implementation()` discipline — one `api(project(":domain:ports"))` and
everything downstream inherits ports transitively.

*Not closed by either mechanism:* feature A parks something in `model/` and feature B reads it. No
reference exists on either side; the coupling does.

*Why nine features are not nine modules:* `ZoneFeatureBlindnessTest` survives as a text gate because nine
more KMP modules is not a trade worth making.

### D3 — Prefer inversion to a longer denylist

Four guards share one disease: a **remembered detector**. `ExtensionSafetyTest` denies 2 frameworks of
~200; `SwiftShellGuardTest` counts 4 keywords and misses a ternary; `ConstructorBlockingTest`'s list
carried the typo that killed it; `PlatformIdentifierTest` lists 9 token forms.

Where the permitted set is small and bounded, **invert**. `ExtensionSafetyTest` becomes an allowlist of the
~8 `platform.*` frameworks the extension actually uses — covering all ~200 and failing closed on novelty,
which is the posture this capability already states.

*Forcing proof that enumeration is impossible:* `NS_EXTENSION_UNAVAILABLE` does not survive cinterop.
`klib dump-metadata` of `platform.UIKit` is 94,179 lines with **zero** extension-unavailable markers;
`UIApplication.sharedApplication` carries only `@kotlinx/cinterop/ObjCMethod`. The attribute cannot be read
from any klib, so inversion is the only total answer. *Expiry trigger:* Kotlin/Native gaining
extension-availability modelling.

*Implementation hazard:* `platform` is a local variable name in this codebase (`platform.retryJob`,
`platform.fetch`), so the rule must match imports and fully-qualified references, never raw text.

### D4 — Let the compiler and the module system absorb what they can

- **`PlatformIdentifierTest` goes.** Measured: `platform.Foundation.NSError` in `commonMain` fails
  `compileKotlinJvm` with `Unresolved reference 'platform'`. Only Apple knowledge encoded as *string
  literals* survives compilation — verified, `domain == "NSCocoaErrorDomain" && code == 4L` compiles clean
  — and that residual shape is narrow enough to leave to review. Its `NS|PH|UI|AV` type-prefix forms were
  redundant with the compiler all along.
- **`FakeHonestyTest` goes.** Making the fakes `internal` behind port-typed factories puts honesty beyond
  expression: `internal` is module-scoped, so `:test:world` cannot reach extra surface at all. The guard
  had already missed a real lever — `val files: MutableSet<String>` in `InMemoryStagedBytes` — because its
  regex matches `var`, not a `val` of a mutable type.
- **`LawsDigestTest` goes, with its duplicate.** Guarding a copy is worse than not having one; the laws
  move to `openspec/config.yaml`'s `context:` block, which is injected into every agent and is not
  rewritten by `openspec update`.

*Consequence recorded:* `:domain`'s `jvm()` target is now load-bearing for platform purity, and nothing
guards it.

### D5 — Where a second copy is unavoidable, derive it; where it is avoidable, delete it

`ModuleSetTest` is the exemplar and was rebuilt for exactly this reason: it once held an 18-name table,
said "this is a spec delta" twice, and **got a table edit both times**. It now derives the expected set
from the spec and cannot be edited to pass.

Two guards still hold copies and are corrected here:

- `RuntimeIdentityTest` holds its pin inventory locally while prose claims the spec is authoritative. The
  two agree today — verified, and the two apparent deltas (`app.snapsync.config`/`eventconfig`,
  `accumulator.json`) are deliberate exclusions the spec explains. The drift is **asymmetric**: a wrong
  value fails loudly against production Kotlin, but a *missing* pin drifts silently, which is the
  `:tools:diagrams` failure mode. Derive it from the spec.
- `KotlinShellGuardTest` duplicates `appShellSources` from the root build file — a duplication that
  **already failed**: `app/ios/forge/src` was absent from *both* copies until the complexity-budgets change
  measured the tree. Parse it from `build.gradle.kts`, as `DetektTierCoverageTest` already does with
  `detektTierOf` in that same file.

`CompositionSeamTest` keeps its 20 pins unchanged: verified exact in **both** directions — an injected
phantom pin describing a field that does not exist fails the build, so the inventory cannot drift from
reality. `PlatformVocabularyPinTest` likewise stays: falsifying one pinned constant (`5L` → `99L`) caught
it, so the comparison is genuinely live against the SDK klib.

### D6 — Retire requirements, not just tests

This capability's first requirement is that guards are executable and gate the build, and there is no
non-gating grace period. Deleting a guard while leaving its SHALL standing would create exactly the
ungated requirement the capability forbids. Every retirement therefore removes or narrows its requirement
in the same change — including `diagnostic-logging`'s "Uniform platform-invocation logging", which loses
its gate with `PlatformEntryLoggingTest`.

## Risks / Trade-offs

- **Constructor blocking becomes unguarded** → Accepted deliberately. The evidence is that the risk was
  never actually managed: the guard passed with its grandfather list emptied. Re-introducing a working
  guard is a separate change with its own forcing proof.
- **The SNAPSYNC-3 class loses all mechanical coverage** (both `AbsenceIsNamedTest` and
  `PlatformEntryLoggingTest` go) → Accepted; diagnosability is a cost paid by developers. An entry point
  that decides and returns silently will again be undiagnosable from a dump.
- **The zone split contradicts the withholding law as written** → Not a refactor. The law and the module
  enumeration are amended in this change, with the new modules placed in a group and justified.
- **`api()` leakage silently weakens the new module boundaries** → Use `implementation()` on every
  intra-`:domain` edge; a leak is invisible once made.
- **18 `internal` → `public` widenings enlarge the published API surface** → Small, bounded, and reviewed
  as part of the split.
- **Konture routes guard input through a generated `layout_v2.json`**, reversing the principle that
  `:test:architecture` depends on no project modules → Verify a stale layout fails **loudly** before
  adopting; silent staleness is the failure mode these guards exist to prevent.
- **Three tools instead of one** (Konture, semgrep, detekt) → Accepted: Konture cannot read Swift at all,
  and detekt has no type resolution for Kotlin/Native source sets.
- **`ZoneFeatureBlindnessTest` remains a text gate** → No affordable structural alternative at nine
  features; its `PENDING` fail-open is removed as part of the port.

## Migration Plan

1. Amend the specs first — `architecture-guards`, `module-architecture`, `diagnostic-logging`,
   `complexity-budgets` — because `ModuleSetTest` derives from the module enumeration and will fail until
   it describes the new modules.
2. Retire the guards that need no structural precondition.
3. Split `:domain` into per-zone modules; delete the four zone gates and `MixedPortImplTest` **in the same
   commit that creates `:domain:ports`**, so there is never a window with neither the boundary nor the gate.
4. Make the fakes `internal` behind factories; delete `FakeHonestyTest`.
5. Move the laws into `openspec/config.yaml`; delete the digest and `LawsDigestTest`. Keep `CLAUDE.md` as a
   declared build input — `RunbookSkillsTest` still reads it.
6. Strengthen the 10 surviving guards.
7. Confirm `./gradlew build` is green and `architectureDiagrams` is fresh.

Rollback is per-step: each retirement is an independent revert, and the module split is the only step that
cannot be partially reverted.

## Open Questions

- Does a stale Konture `layout_v2.json` fail loudly or silently? This blocks the tooling migration and is
  untested.
- Should `:domain`'s `jvm()` target — now load-bearing for platform purity — be pinned by a guard, given
  this change removes the only other check on that property?
