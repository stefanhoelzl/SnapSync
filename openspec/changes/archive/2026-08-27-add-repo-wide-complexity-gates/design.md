## Context

`detektAppShell` is the repo's only complexity measurement. It reads three source roots
(`app/ios/src`, `app/ios/extension/src`, `test/rig/src/hook`) with `CyclomaticComplexMethod` at
threshold 2 — one branch fails — and every other detekt ruleset switched off. Its purpose is not a
complexity budget: it is the executable half of *"the app shell holds no decisions"*, and its
`@Suppress` inventory is pinned exactly, both directions, by `KotlinShellGuardTest`. Read as a
complexity gate it is unrecognisable; read as a structural proof it is exactly right.

Everything else in the repo — 484 Kotlin files, 69k lines, plus 21 TypeScript files in `api/` — is
measured by nothing.

Measurements taken against the tree at proposal time, with detekt 1.23.8 and a prototype Deno lint
plugin. Production Kotlin at detekt's own default complexity thresholds: **107 findings** —
`LongParameterList` 46, `LongMethod` 19, `TooManyFunctions` 18, `CyclomaticComplexMethod` 16,
`ComplexCondition` 5, `NestedBlockDepth` 3. The cyclomatic distribution across production is
`cc≥15: 16 · ≥12: 27 · ≥10: 48 · ≥8: 73 · ≥5: 140`. The extremes:

| function | cc | where |
|---|---|---|
| `miniEdgeClient` | 50 | `:test:world` |
| `stripComments` | 37 | `:tools:diagrams` |
| `performWipe` | 36 | `:test:rig` |
| `JoiningEventScreen` | 35 | `:ui:screens` |
| `StatusScreen` | 30 | `:ui:screens` |
| `WorldInspector` | 20 | `:app:desktop` |
| `UploadCycle.run` | 18 | `:domain` — the only `:domain` violation at 15 |
| `attest.ts:210`, `app.ts:279`, `app.ts:588` | 19 | `api/` |

Two constraints on the shape of any answer, both established by inspecting detekt's own artifacts
rather than by reasoning:

1. **A detekt 1.x rule carries exactly one threshold per config.** Rules accept path
   `includes`/`excludes` filters, but the numeric value is single-valued (confirmed in
   `detekt-rules-complexity-1.23.8.jar` and `detekt-api`'s `Rule`/`PathFilters`). Per-scope
   thresholds are therefore not a configuration feature — they require one `Detekt` task with its
   own config file per scope. This is why `detektAppShell` is shaped as a bespoke task.
2. **`NamedArguments` carries `@RequiresTypeResolution`.** Enabled at threshold 3 across all
   production source it reported **zero** findings — not because the code complies, but because
   detekt silently skips type-resolution rules with no binding context, which Kotlin/Native source
   sets cannot supply. `KeychainContainmentTest` already records this limitation for
   `ForbiddenMethodCall`.

Two coverage gaps found while measuring. `:app:ios:forge` (`ForgeViewController.kt`, 124 lines,
`settings.gradle.kts:28`) is an `:app:*` iOS shell covered by neither the detekt task nor
`KotlinShellGuardTest`. And the `architecture-guards` spec's shell-gate requirement claims coverage
of "all production `:app:*` source sets", which has never included `:app:desktop` — a claim wider
than the implementation, in the direction that reads as reassurance.

## Goals / Non-Goals

**Goals:**

- Every Kotlin source in the repo, and `api/`'s TypeScript, sits under a complexity ceiling.
- The ceilings land **green on the tree as it stands** — this change contains no production code
  edits and no behavior change.
- Each ceiling is a **ratchet**: a number that may only fall. The end state is a set of ceilings
  low enough to be structural claims rather than budgets.
- The two zones with a written decision-free law — `flow/` and `compose/` — get gates that make
  that law executable.
- "Every module is measured" is **provable**, not remembered: a new module is covered automatically
  or the build fails naming it.
- The boundary of what this reaches is **stated**, so "all code" cannot be read as a promise the
  gate does not keep.

**Non-Goals:**

- **Refactoring.** The work that lowers the ceilings — design tokens in `:ui:components`, splitting
  `AppPorts`, `UploadCycle.run`, the oversized `StatusScreen` composables — is deliberately
  excluded. Each lands afterwards as its own PR whose visible effect is a ceiling falling.
- **Mechanically enforcing the ratchet.** No automated check asserts that a threshold is tight or
  that it never rose (D5).
- **Swift, `site/`, and the Astro toolchain.** Out of scope, stated as such.
- **Adopting detekt 2.0.** It is at alpha.6; this gate runs in the canonical build for everyone.

## Decisions

### D1 — Two gates, not one widened gate

`detektAppShell` is left exactly as it is (threshold 2, its suppression inventory pinned) and the
new tiers are built beside it. Widening the existing gate was the literal reading of the request
and is wrong: threshold 2 asserts *"this code makes no decisions"*, a claim that is true of wiring
and false of everything else. Raising its threshold to something the repo passes would destroy the
shell proof — the shells' guarantee comes precisely from the number being 2. The two gates answer
different questions and must not share a number.

### D2 — Per-scope thresholds, realised as one task + one config per tier

Chosen over two alternatives. A **single repo-wide config** would have to carry the global maximum,
so `:domain` would inherit `:test:world`'s cyclomatic 50 and be governed by nothing. **One config
per Gradle module** (≈17) is tightest, but each number would then be a single accident with no
group-level justification, and every new module would need a new config. Tiers group scopes whose
complexity has the *same cause* — Compose inflates cyclomatic complexity and function length
structurally; test equipment is written to be read once; tests are wide but shallow — so a tier's
number is defensible as a statement about that kind of code.

### D3 — Eight tiers, seeded at the measured maximum

| tier | scope | cyclo | cognitive | LongMethod | params | ComplexCond | TooManyFn | ReturnCount | maxLineLen | NestedDepth | LargeClass |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `shell` | `:app:ios`, `:app:ios:extension`, `:app:ios:forge` | (app-shell.yml) | 4 | 60 | 16 | 2 | 33 | 1 | 132 | 2 | 600 |
| `flow` | `domain/…/flow/` | 4 | 2 | 60 | 10 | 2 | 11 | 1 | 120 | 2 | 600 |
| `compose` | `domain/…/compose/` | 9 | 6 | 60 | 47 | 2 | 11 | 1 | 120 | 3 | 600 |
| `core` | `:domain` (minus `flow/`, `compose/`), `:adapter:*`, `:ui:presentation` | 19 | 37 | 83 | 15 | 5 | 26 | 12 | 146 | 4 | 600 |
| `ui` | `:ui:components`, `:ui:screens` | 36 | 46 | 169 | 30 | 4 | 17 | 2 | 133 | 4 | 600 |
| `harness` | `:app:desktop`, `:test:rig`, `:test:world`, `:test:harness-driver`, `:tools:diagrams` | 51 | 64 | 171 | 24 | 6 | 43 | 5 | 159 | 8 | 600 |
| `tests` | every `*Test` source set, `:test:architecture`, `:test:integration` | 24 | 40 | 60 | 12 | 4 | 90 | 5 | 216 | 5 | 1300 |
| `buildscripts` | `**/*.gradle.kts` | 15 | 15 | 60 | 6/7 | 4 | 11 | 2 | 120 | 4 | 600 |

`api/` carries one number, cyclomatic **19**, in `api/src/lint/complexity.ts`.

The `tests` tier is the surprise the measurement produced: test code is structurally *simpler* than
`harness` (max cyclomatic 23 against 50) while being far wider (89 functions in one class against
`core`'s 25). A
single budget covering both would have been loose in one dimension and meaningless in the other,
which is the case for giving tests their own tier rather than folding them into each module's.

`buildscripts` exists as its own tier for a reason beyond its numbers: `UnusedPrivateProperty`
false-positives on the Kotlin MPP Gradle DSL (`val jvmTest by getting` reads as an unused private
property), so that rule is off in this tier and nowhere else. Encoding that as a tier is legible;
encoding it as a path exclusion buried in a shared config is not.

`flow` and `compose` are carved out of `core` by path filter. `flow/` sits at cyclomatic 3 across
four sites (`Foreground:84`, `Provision:66`, `SilentPush:46`, `SilentPush:64`) with a law already
written — *flows coordinate, never decide* — so a gate there converts prose into enforcement rather
than inventing a rule. Its declared end state is 2, at which point it merges into `shell`.
`compose/` sits at 8 (`UploadCore:187`); its claim is genuinely weaker, because a composition that
resolves an upload mechanism per transition does decide something.

### D4 — Seed green, refactor afterwards

The gate lands at today's maxima with no code changes. Three alternatives were considered:

- **Refactor to green first** (all 107) — the largest possible first change, during which nothing
  prevents regression, and the gate's arrival is far away.
- **A detekt baseline file** — cheapest, and rejected: a generated list of tolerated violations with
  no per-site justification, regenerable away, and silent. "Absence is never silent" applies.
- **The shell gate's own mechanism, a pinned `@Suppress` inventory** — right at 2 suppressions,
  wrong at 107. Each suppression carries a forcing proof; 107 forcing proofs for functions whose
  only sin is being ordinary would devalue the mechanism precisely where it currently works.

Seeding green also makes the subsequent refactor PRs legible: each one's visible effect in the diff
is a number falling.

### D4a — A ceiling below detekt's own default is a claim, and claims are argued (amended during apply)

Seeding purely at the measured maximum produced ceilings that were tight by ACCIDENT rather than by
argument: `LargeClass` bottomed out at **20 source lines** in `ui`, **30** in `flow`, **≤10** in
`buildscripts` — not because those scopes are disciplined but because Compose is top-level functions,
flows are small, and build scripts have no classes. Pinning them would fail the build on the first
ordinary 40-line class, which is not regression detection.

So the seeding rule is: **ceiling = the tightest passing value, except where that falls below detekt's
own default — there the default is used** — with one exception, argued per scope rather than applied
by formula. Where tightness restates a WRITTEN law it is kept, because there the number is the claim.
That is the shells, `flow/` and `compose/`, and it is kept only on the DECISION measures (cyclomatic,
cognitive, `ComplexCondition`, `NestedBlockDepth`, `ReturnCount`) — never on the SIZE measures
(`LongMethod`, `LargeClass`, `TooManyFunctions`, `MaxLineLength`), because a long flow is not a
deciding flow. `SnapSyncRoot` grows by a statement every time a port is added; that is wiring.

Each tier's sub-default rules were settled individually rather than by rule, which is why `ui` takes
detekt's `NestedBlockDepth` default while `shell` and `flow` hold theirs tight.

### D5 — The ratchet is carried by a written contract, not a check

Each tier config opens with a prose contract in the voice `config/detekt/app-shell.yml` already
uses: **every number here is a ceiling that may only fall**; raising one requires a stated forcing
proof in the PR. The contract sits in the same file as the number it governs, and states the rule
in terms an agent editing that file will read before editing it.

The alternative considered and declined was a **zero-slack tightness guard**: a test asserting each
threshold is tight by re-running the rule at `threshold - 1` and requiring a failure. It needs no
stored history and closes the gap completely — a threshold raised in the same diff that violates it
would fail. It was declined because its failure mode is backwards: improving the code (deleting the
worst function in a tier) fails the build until someone edits a number. A gate that punishes
improvement teaches people to route around it.

The consequence is stated plainly rather than mitigated: **this gate is a ratchet by discipline, not
a proof.** That is the difference between it and the shell gate beside it, and the tier configs say
so in their own words.

A shared `config/detekt/README.md` carrying the rule once was also considered and rejected — it puts
the rule one indirection away from the number, and an agent editing the YAML may never open it.

### D5a — `buildUponDefaultConfig` is true for the tiers, false for the shell proof (amended during apply)

`app-shell.yml` sets it FALSE and is a complete statement of five rules, so that adding a rule there is
a deliberate act and never a side effect of a detekt upgrade. That property does not survive the scale
here: with `false`, every one of detekt's ~60 rules must be listed explicitly to be active, in each of
eight files.

The tiers therefore set it TRUE and each config states its thresholds and switches off the rulesets out
of scope. The cost is real and stated: a detekt upgrade that activates a new rule by default starts
firing here. That failure lands in the upgrade PR — which is where you want to see it, and the version
is pinned in `gradle/libs.versions.toml`, so an upgrade is always deliberate.

### D6 — Binary rules ratchet by a shrink-only exclusion register

Roughly half the enabled rules have no threshold to lower — `MagicNumber`, `FunctionNaming`,
`TooGenericExceptionCaught`, `EmptyFunctionBlock`, `MatchingDeclarationName` are per-site verdicts.
For those the ratchet is the rule's `excludes` list: it is the debt register, it names the scopes not
yet clean, and it may only shrink. An exclusion removed is the same event as a threshold lowered.

### D7 — Tier membership derives from the Gradle project model

Each tier task takes its sources from the live subproject list rather than a committed path list, and
a `:test:architecture` guard asserts every subproject lands in **exactly one** tier and every tier
scanned a non-zero number of files. This removes the staleness class rather than testing for it.

The alternative was the existing `detektAppShell` / `KotlinShellGuardTest.shellSourceRoots` pattern —
a hand list mirrored in a test. It is the repo's precedent, and it is precisely what failed here:
`:app:ios:forge` is missing from both copies of that list, which is how this change found it. A
mechanism whose known failure mode has already occurred is not the one to reuse at eight times the
scale. The shell gate keeps its hand list because its roots include a non-module directory
(`test/rig/src/hook`, contributed into `:app:ios` under a build property) that the project model does
not express.

### D8 — detekt stays at 1.23.8, with the parser lag named and given an expiry

1.23.8 (Feb 2025) is the latest **stable** release. It parses with `kotlin-compiler-embeddable`
**2.0.21**; this repo is on Kotlin **2.4.0**. detekt **2.0.0-alpha.6** (published 2026-08-04, group
id moved to `dev.detekt`) embeds Kotlin **2.4.10** and would erase the lag.

The lag is harmless today — all 504 `.kt`/`.kts` files parse cleanly under 1.23.8, verified. But this
change multiplies the exposure from three source roots to the whole repo: the first Kotlin 2.1+
syntax adopted anywhere (guard conditions in `when`, context parameters, nested type aliases) would
break the gate everywhere at once. Adopting an alpha into the canonical build for everyone is the
worse trade.

**Forcing proof and expiry trigger**, recorded per the necessity-claims law: the constraint is
detekt 1.23.8's declared dependency on `kotlin-compiler-embeddable:2.0.21`. Re-evaluate when detekt
2.0 reaches stable, or at the first Kotlin 2.1+ syntax that fails to parse — whichever comes first.

### D9 — `NamedArguments` is excluded, and the readability goal behind it is answered elsewhere

Listing it would be the vacuous pass these guards exist to prevent (see Context). The goal it stands
for is already met at the two call sites that matter: `SnapSyncRoot.kt:346` and `World.kt:380` both
pass all 46 `AppPorts` arguments named. What remains is the 46-parameter type, and the answer is
**cohesive sub-bundles** — `UploadPorts` already exists as one; `MembershipPorts`, `DownloadPorts`,
`DiagnosticsPorts` are the same shape — each a plain constructor, so the compiler keeps proving
every port is supplied. That refactor is a later PR whose effect is `compose`'s `LongParameterList`
ceiling falling from 47. (`AppPorts` lives in `compose/`, which is carved out of `core` — so it is
`compose`'s number that the split lowers, not `core`'s, whose parameter maximum is 14.)

A **DSL builder** (`appPorts { … }`) was rejected: its fields must be nullable or `lateinit`, so the
compiler stops proving completeness and a missing port becomes a runtime failure. That is the exact
trap `UploadCore`'s own comment on `albumExcludedAssetIds` records — *"the signature permitting
exactly what the prose forbade"*. **Kotlin context parameters** were rejected for inverting the
repo's thesis: ports would stop appearing at the seam.

### D10 — No exemptions; two cases that look alike are not

`FunctionNaming` reports 97 findings, 63 of them in `:ui:components`, because detekt's default
`functionPattern: '[a-z][a-zA-Z0-9]*'` encodes a pre-Compose convention while `@Composable` Unit
functions are PascalCase by Compose's documented convention. `ignoreAnnotated: ['Composable']` is a
**statement of dialect**, not a waiver — the alternative is renaming 63 composables away from the
convention every Compose reader and IDE expects.

`MagicNumber` reports 157, 109 of them in `:ui:components` — raw `dp`/`sp` literals in a design
system. This one has no such defence, and it gets **no exemption**: the fix is named design tokens,
landing as its own PR. Until then the scope sits on the shrink-only exclusion register (D6), which
names the debt rather than hiding it.

### D11 — `api/` gets a project-local `deno lint` plugin

No ready-made rule exists. `deno lint --rules` ships nothing matching complexity, depth, length or
parameter count, and none of the four published JSR lint plugins measures complexity:
`@nashaddams/lint-plugin` (`no-missing-await`, `no-missing-try-catch`),
`@devhaven/deno-lint-plugin` (`function-call-argument-newline`, `no-empty-function`),
`@yolk-oss/deno-lint-plugin` (`no-magic-numbers`, `no-underscore-dangle`, `require-yield`),
`@uki00a/deno-lint-plugin-extra-rules` (Deno built-in API / std rules).

So: a hand-written rule using Deno's lint plugin API (stable since 2.2; CI pins `v2.x`), running
under the `deno lint` step `api-deploy.yml` already gates on. A prototype was written and measured
the tree: max cyclomatic **19**, distribution `2:27 · 3:15 · 4:14 · 5:10 · 6:7 · 7:2 · 8:3 · 9:3 ·
10:4 · 11:1 · 12:1 · 13:1 · 15:1 · 19:3`.

**eslint** was rejected despite its `complexity` rule being core and battle-tested: it means npm,
`typescript-eslint`, a lockfile and a `node_modules` surface in a backend that deliberately runs
Deno-only, plus a second lint step in the deploy path.

Scope parity is deliberately partial: **cyclomatic complexity only** on the TypeScript side. It is
the one measure both sides share, so "the complexity budget" means one thing repo-wide; length,
parameter count and nesting stay unenforced there, stated as such rather than approximated by four
more hand-written rules whose semantics would drift from detekt's.

### D12 — The shell gate's scope is corrected while it is being touched

`:app:ios:forge` joins `appShellSources` and `KotlinShellGuardTest.shellSourceRoots`; it measures
below threshold 2 today, so this costs nothing and closes a real gap. Separately, the
`architecture-guards` requirement's phrase *"all production `:app:*` source sets"* is narrowed to
the iOS shell roots it actually covers, with `:app:desktop` stated as harness rather than shell. A
requirement claiming more coverage than exists is the failure mode the whole capability is against.

### D13 — Kotlin plus `api/` TypeScript is the boundary, and it is stated

Swift is covered by its own pinned-structure text gate under `architecture-guards` and is not given a
complexity budget; `site/` and its Astro toolchain are out of scope. The `complexity-budgets` spec
says so, so that "every source in the repo" cannot be read as covering artifacts it never touches.

## Risks / Trade-offs

- **[The ratchet can be loosened in the same PR that violates it]** → Accepted deliberately (D5).
  Mitigated only by the prose contract in each config and the visibility of a one-line number change
  in review. Stated in the spec so the gate is never mistaken for a proof.
- **[detekt's parser lags the repo's Kotlin by two minors]** → D8's expiry trigger. The failure is
  loud (a parse error), not silent, and it fails the build rather than passing vacuously.
- **[Parameter counts cannot be read off detekt's finding message]** — the message truncates, and its
  parameter list contains nested generic lambdas, so counting its commas UNDERCOUNTS. A first pass put
  `harness` at 17; `StatusPane` actually has 23. → Every `LongParameterList` ceiling was re-derived by
  binary search, and `harness.yml` records the correction beside the number so the next reader does not
  repeat it.
- **[`NestedBlockDepth` and `LargeClass` report no measured value]** — their messages are "nested too
  deeply" / "is too large" with no number → their per-tier thresholds can only be found by binary
  search, re-running detekt per candidate. This makes them the two rules whose seeds are most likely
  to be wrong, and the most tedious to re-tighten later. Mitigation: seed them by binary search once,
  and note in the config that their numbers are search results rather than reported maxima.
- **[The hand-written TypeScript rule's semantics drift from detekt's]** → The plugin states which
  node types it counts as decisions (`if`, ternary, the three `for` forms, `while`, `do`, `catch`,
  `case` with a test, and `&&`/`||`/`??`) so the two definitions are comparable by reading, and the
  rule is unit-tested against fixtures.
- **[The `ui` and `harness` tiers may never tighten]** — Compose inflates cyclomatic complexity and
  method length structurally, and test equipment is written to be read once, so both tiers' numbers
  have a floor well above the others'. → Accepted: their value is regression prevention, not
  convergence. The tiers whose numbers are expected to fall are `flow`, `compose` and `core`.
- **[Eight detekt tasks slow the canonical build]** → Measured negligible; the whole-repo scan
  completes in seconds, and the tasks are independently cacheable.
- **[The tier-coverage guard blocks a new module at an inconvenient moment]** → That is the intended
  behavior; the failure message names the module and the tier choice required.

## Migration Plan

1. **This change, one PR**: the eight tier tasks and configs, the tier-coverage guard, the shell-root
   correction, and the `api/` lint plugin. Green on the tree as it stands; no production code edits.
2. **Then, one PR each, every one lowering a stated ceiling**: design tokens in `:ui:components`
   (removes the `MagicNumber` exclusion) · `AppPorts` sub-bundles (`core` `LongParameterList`
   46 → ~12) · `UploadCycle.run` (`core` cyclomatic 18 →) · the three oversized `StatusScreen`
   composables (`ui` cyclomatic 35 →) · `flow/` 3 → 2, after which `flow` merges into `shell`.

Rollback is per-tier: a tier task can be detached from `check` without affecting the others, and the
shell gate is untouched throughout.

## Open Questions

- **`compose/`'s end state.** `flow/`'s is 2 and defensible. `compose/` sits at 8 because
  `UploadCore:187` resolves an upload mechanism, which is a decision the composition is entitled to
  make. Its honest end state may be above 2, and this change does not claim to know it.
- **Whether the `harness` tier should ever tighten**, or be documented as a permanent ceiling on test
  equipment that exists only to catch a regression.
