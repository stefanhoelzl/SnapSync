## 1. Wire Kover into the build

- [x] 1.1 Add the Kover plugin to `gradle/libs.versions.toml` (version pinned like every other
      dependency) and declare it `apply false` in the root `build.gradle.kts` plugins block.
- [x] 1.2 Apply Kover in the instrumented modules only — `:domain`, `:adapter:generic:app`,
      `:adapter:generic:fake`, `:ui:presentation`, `:ui:screens`, `:ui:components` — by adding the
      plugin to each module's own `build.gradle.kts` rather than to a `subprojects {}` block, so the
      set is readable per module and a new module is not silently instrumented.
- [x] 1.3 In `:test:integration`, `:test:world`, `:test:architecture` and `:tools:diagrams`, apply
      Kover with `kover { currentProject { instrumentation { disabledForAll = true } } }` so their
      test tasks produce no coverage data and are not triggered by report generation. Note the
      property is `disabledForAll`, not `disableForAll`.
- [x] 1.4 Add the crediting edges so a module's bound counts the unit tests that exercise it:
      `:domain` declares `kover(project(":adapter:generic:fake"))`, and `:ui:components` declares
      `kover(project(":ui:screens"))`. Verify each module's report then covers only its own classes
      (`reports.filters.includes.projects`), so a crediting edge adds coverage without adding classes.
- [x] 1.5 Confirm `:app:desktop` and `:test:harness-driver` are neither instrumented nor bounded, and
      that no module without a JVM target has the plugin applied.

## 2. Seed the bounds

- [x] 2.1 Run `./gradlew koverXmlReport` across the instrumented set and record per-module
      `INSTRUCTION` and `BRANCH` totals plus each module's worst-package `INSTRUCTION` value. These
      are the seeds; do not reuse the numbers in `design.md` without re-measuring, since any test
      merged since will have moved them.
- [x] 2.2 Exclude `:domain`'s `compose/` zone from the bounded set by report filter, keyed on the
      zone path so the other eleven packages stay measured (spec: "A composition root is outside the
      measurable set").
- [x] 2.3 Exclude the generated SQLDelight packages (`app.snapsync.engine.db*`,
      `app.snapsync.downloadstore.db*`) from `:adapter:generic:app`'s report — generated code should
      not be ratcheted.
- [x] 2.4 Write the module-aggregate rules: `bound { minValue = <seed>; coverageUnits =
      CoverageUnit.INSTRUCTION }` and the same for `BRANCH`, one pair per bounded module, each seeded
      at `floor(measured)`.
- [x] 2.5 Write the package-floor rules: one rule per bounded module with
      `groupBy = GroupingEntityType.PACKAGE` bounding `INSTRUCTION` only, seeded at
      `floor(worst package)`. Do not add a per-package `BRANCH` rule (spec: denominators too small).
- [x] 2.6 State the "may only rise" contract in prose at the head of each file carrying bounds,
      including that raising is ordinary work, that lowering needs a stated forcing proof, that the
      destination is full coverage, and that nothing enforces this but review.
- [x] 2.7 Name the coverage engine where the bounds live, and record that switching it invalidates
      every seed.

## 3. Gate it

- [x] 3.1 Attach coverage verification to `check` so `./gradlew build` runs it, matching how
      `detektAppShell` and the tier tasks attach (capability `complexity-budgets`).
- [x] 3.2 Verify the gate lands green on an unmodified tree: `./gradlew build` passes with no source
      change.
- [x] 3.3 Verify the gate actually fails. Temporarily delete a test from a bounded module, confirm
      the build fails naming the scope, then restore it. A gate never observed failing is a gate that
      may be inspecting nothing — the same anti-vacuity check `complexity-budgets` requires of its
      tier tasks.
- [x] 3.4 Verify the package floor fails independently of the aggregate. Adding an uncovered class
      cannot isolate the two here — the aggregate's slack is 0.35% (77 instructions), so any
      perturbation large enough to break a floor of 57 also breaks the aggregate. Proven instead by
      temporarily raising the floor to 58: only `':domain package floor'` fired, naming
      `app.snapsync.flow` at 57.3379%, while the aggregate rule stayed silent.
- [x] 3.5 Confirm `./gradlew compileIosMainKotlinMetadata` still passes and that no iOS-targeted
      module gained a dependency.

## 4. Record the contract

- [x] 4.1 Confirm the delta spec matches what was built — in particular that
      the module lists under "Coverage is measured over unit tests only" name exactly the modules the
      build configures, and correct either side if they diverged during implementation.
- [x] 4.2 Record the debt the first measurement exposes where a reader will meet it: the
      `:adapter:generic:app` package floor of 0 and why (`HttpAttestClient` has no test), and
      `:domain`'s floor naming `flow/`.
- [x] 4.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and confirm it passes,
      remembering it checks structure and not truth.
- [ ] 4.4 Apply the `internal` changelog label to the PR — no customer-visible behaviour changes
      (capability `changelog-labels`).

## 5. Keep the derived diagrams true

Unplanned, and forced by two guards firing on the crediting edges: a `kover(project(...))` edge is
indistinguishable from an architectural one in the Gradle project model.

- [x] 5.1 Declare every crediting edge in the ROOT build script, not in the consuming module.
      `ModuleSetTest` forbids `:domain` naming any module in its own build file, and `Zones.kt` reads
      every script under `adapter/`, `domain/` and `ui/` as text — so a local declaration renders
      backwards in `architecture/zones.md`.
- [x] 5.2 Exclude `kover*` configurations from the module-graph model extraction, and state the
      exclusion in BOTH renderers' header text — the root build script's and the byte-identical twin
      in `:tools:diagrams` — since the freshness test fails if they disagree.
- [x] 5.3 Regenerate `architecture/` and confirm the module graph gains no edge; only the header text
      changes, and `zones.md` is untouched.
- [x] 5.4 Add the `architecture-diagrams` delta spec and list it under the proposal's Modified
      Capabilities.
