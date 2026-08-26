## 1. Close the shell gate's coverage gap

- [x] 1.1 Add `app/ios/forge/src` to `appShellSources` in `build.gradle.kts`, extending the existing
      comment to say why the forge shell is a shell.
- [x] 1.2 Add the same path to `KotlinShellGuardTest.shellSourceRoots`, keeping the two lists mirrored.
- [x] 1.3 Run `./gradlew detektAppShell :test:architecture:test` and confirm both pass with no new
      suppression — `ForgeViewController.kt` measures below the decision-free threshold today, so this
      must be free.

## 2. Seed the tier configurations

- [x] 2.1 Write the shared prose contract header used by every tier config: each number is a ceiling
      that may only fall, raising one requires a stated forcing proof in the PR, and this gate is a
      ratchet carried by that contract rather than a proof. Voice matches `config/detekt/app-shell.yml`.
- [x] 2.2 Create `config/detekt/flow.yml` — `CyclomaticComplexMethod` at the measured `flow/` maximum,
      header naming the decision-free value as the declared target and the merge into the shell tier
      that follows reaching it.
- [x] 2.3 Create `config/detekt/compose.yml` — same shape, seeded from measurement, with the header
      stating that no target is claimed because a composition resolving a mechanism per transition
      genuinely decides.
- [x] 2.4 Create `config/detekt/core.yml` — full complexity ruleset plus `potential-bugs`,
      `exceptions`, `coroutines`, `style`, `naming` and `MagicNumber`, each threshold seeded from the
      measured `core` maximum.
- [x] 2.5 Create `config/detekt/ui.yml` — same rule set, seeded from the measured `ui` maximum;
      `FunctionNaming` carries `ignoreAnnotated: ['Composable']` with a header note that this states
      the Compose dialect rather than waiving the rule.
- [x] 2.6 Create `config/detekt/harness.yml` — same rule set, seeded from the measured `harness`
      maximum, header noting these ceilings exist for regression detection and are not expected to
      converge.
- [x] 2.7 Create `config/detekt/tests.yml` — seeded from the measured test-source maxima; note that
      test code measured structurally simpler and much wider than production, which is why it is its
      own tier.
- [x] 2.8 Create `config/detekt/buildscripts.yml` — seeded from the measured build-script maxima, with
      `UnusedPrivateProperty` disabled and the reason stated (the Kotlin MPP Gradle DSL's
      `val jvmTest by getting` reads as an unused private property).
- [x] 2.9 Find `NestedBlockDepth` and `LargeClass` thresholds per tier by binary search — their
      findings carry no measured value — and record in each config that those two numbers are search
      results rather than reported maxima.
- [x] 2.10 Record the per-rule scope-exclusion register for the per-site rules (`MagicNumber` in
      `:ui:components` foremost), with the header stating the list may only shrink.

## 3. Wire the tier tasks

- [x] 3.1 Add a tier→subproject mapping to `build.gradle.kts` and derive each tier task's sources from
      the live Gradle project model rather than a path list.
- [x] 3.2 Register one `io.gitlab.arturbosch.detekt.Detekt` task per tier, `buildUponDefaultConfig`
      set so each config is a complete statement, `ignoreFailures = false`, XML report only — matching
      `detektAppShell`'s shape.
- [x] 3.3 Make each tier task fail when it scanned zero files.
- [x] 3.4 Wire every tier task into `check`.
- [x] 3.5 Rewrite the comment above `tasks.named("detekt") { enabled = false }` — "exactly one detekt
      task in this build" is no longer true; the root task stays disabled for the same reason, and the
      comment must say what the real set now is.
- [x] 3.6 Run `./gradlew build` and confirm it is green with zero production code changes.

## 4. Guard the coverage

- [x] 4.1 Add a tier-coverage guard to `:test:architecture` asserting every subproject in the build
      resolves to exactly one tier, failing with the subproject's name when it resolves to none or to
      several.
- [x] 4.2 Assert the guard is non-vacuous — it fails if the tier map or the project list resolves
      empty.
- [x] 4.3 Verify the guard fails as intended by temporarily removing a module from the map, then
      restore.

## 5. Record the analyser constraint

- [x] 5.1 Record detekt 1.23.8's embedded `kotlin-compiler-embeddable` version against this repo's
      Kotlin version, with the expiry trigger (detekt 2.0 stable, or the first Kotlin 2.1+ syntax that
      fails to parse), in the shared config header.
- [x] 5.2 State in the same place why detekt 2.0.0-alpha.6 was not adopted despite erasing the lag.

## 6. The backend complexity rule

- [x] 6.1 Write the cyclomatic-complexity `deno lint` plugin under `api/src/lint/`, enumerating in its
      source exactly which syntax it counts as a decision so the definition is comparable by reading
      against detekt's.
- [x] 6.2 Make the ceiling configurable in the plugin and seed it at the measured `api/` maximum,
      carrying the same only-falls contract in a header comment.
- [x] 6.3 Add tests for the plugin against fixtures covering each counted form and each non-counted
      one.
- [x] 6.4 Declare the plugin under `lint.plugins` in `api/deno.json` so CI and a local `deno lint` run
      the same rule set.
- [x] 6.5 Confirm `deno lint`, `deno task check` and `deno task test` are green in `api/`, and that
      `api-deploy.yml` needs no new step.

## 7. Verify and document

- [x] 7.1 Confirm the full canonical check is green: `./gradlew build` plus the `api/` check set.
- [x] 7.2 Confirm no production Kotlin or TypeScript source file changed in this PR.
- [x] 7.3 Update `CLAUDE.md`'s Build & test section to name the tier gates and where their ceilings
      live.
- [x] 7.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and confirm it passes.
