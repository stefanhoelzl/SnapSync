## Why

Complexity is measured in exactly one place today — `detektAppShell`, over three iOS shell source
roots — and that gate is not a complexity budget at all: it is a structural proof that `:app:*`
holds no decisions. Everywhere else, nothing measures anything. Measured against the current tree
with detekt 1.23.8: 107 findings at detekt's own default complexity thresholds across 282
production files, with cyclomatic complexity reaching 50 (`:test:world`), 37 (`:tools:diagrams`),
35 (`:ui:screens`) and 19 in `api/`. None of those is a defect today; the point is that nothing
would notice if the next one were 80.

Two smaller facts force the timing. `:app:ios:forge` — a real iOS shell, registered in
`settings.gradle.kts` — is covered by neither `detektAppShell` nor `KotlinShellGuardTest`, so the
shell gate's own guarantee is true only of the part someone remembered, which is the failure mode
the build file's comment warns about. And the `flow/` zone carries a written law — *flows
coordinate, never decide* — that no gate enforces, while sitting at cyclomatic 3 across four sites,
one step from being provable.

## What Changes

- **A new tiered complexity gate over every Kotlin source in the repo.** Eight detekt tasks, each
  with its own config, because detekt 1.x carries one threshold per rule per config. Every tier
  starts at the tree's current measured maximum for that scope, so the gate lands green with **zero
  code changes**.
- **Each tier's numbers are ceilings that may only fall.** Every config opens with that contract in
  prose, in the voice `config/detekt/app-shell.yml` already uses. No baseline file, no suppression
  inventory, no automated tightness check — the ratchet is carried by the written contract and the
  diff.
- **Tier membership is derived from the live Gradle project model**, not a hand-written path list,
  and a guard asserts every subproject lands in exactly one tier and every tier scanned a non-zero
  number of files. A new module is covered automatically or the build fails naming it.
- **Two zones get the decision-free structural claim** that `:app:*` already carries: `flow/` (at 3
  today, declared end state 2) and `compose/` (at 8). This makes an existing law executable rather
  than inventing a rule.
- **`:app:ios:forge` joins the shell gate's source roots** and `KotlinShellGuardTest`'s mirror. It
  measures below the shell threshold today, so this closes the gap at no cost.
- **The shell gate's stated scope is corrected** from "all production `:app:*` source sets" to the
  named iOS shells. `:app:desktop` is an `:app:*` module the requirement's wording claims but the
  implementation never covered — it is test equipment, and belongs in the harness tier.
- **`api/` gets a cyclomatic-complexity rule** as a project-local `deno lint` plugin, running under
  the `deno lint` step `api-deploy.yml` already gates on. No ready-made rule exists: `deno lint`
  ships none, and none of the four published JSR lint plugins measures complexity.
- **Not in this change, and deliberately so**: the refactors that would let the numbers fall —
  design tokens in `:ui:components`, splitting `AppPorts` into cohesive sub-bundles, `UploadCycle.run`,
  the three oversized `StatusScreen` composables. Each lands afterwards as its own PR whose visible
  effect is lowering a ceiling.

## Capabilities

### New Capabilities
- `complexity-budgets`: per-scope complexity ceilings over every Kotlin source set and the backend's
  TypeScript, the ratchet discipline that governs them (a number may only fall), the tier-coverage
  guard that makes "every module is measured" provable rather than remembered, and the stated
  boundary of what the gate does not reach.

### Modified Capabilities
- `architecture-guards`: the shell gates' scope is corrected — it names the iOS shell roots it
  actually covers (adding `:app:ios:forge`) instead of claiming all `:app:*`, and states that the
  shell gate remains a structural proof, distinct from the complexity budgets that now surround it.
- `backend-deployment`: the `deno lint` gate now runs a project-local lint plugin, and the deploy
  workflow's check set is stated to include it.

## Impact

- **Build**: `build.gradle.kts` gains seven `Detekt` task registrations beside `detektAppShell`, all
  wired into `check`; the comment asserting exactly one detekt task becomes false and is rewritten.
  `config/detekt/` gains seven config files beside `app-shell.yml`.
- **Guards**: `:test:architecture` gains a tier-coverage guard; `KotlinShellGuardTest.shellSourceRoots`
  gains `app/ios/forge/src`.
- **Backend**: `api/` gains a lint plugin under a new source directory and a `deno.json` `lint.plugins`
  entry; `api-deploy.yml` needs no new step.
- **CI time**: negligible — the whole-repo detekt scan completes in seconds.
- **Dependencies**: none added. detekt stays at 1.23.8, the latest stable; its embedded Kotlin
  parser (2.0.21) lags this repo's Kotlin (2.4.0), which the change records as a stated constraint
  with a named expiry trigger rather than resolving by adopting detekt 2.0.0-alpha.
- **No production code changes.** No behavior changes. Every tier is green on the tree as it stands.
