# Tasks — establish-target-architecture

## 1. Contract of record

- [x] 1.1 Land the `module-architecture` spec (new capability) and the `architecture-diagrams`
      spec (new capability); apply the `architecture-guards` delta.
- [x] 1.2 Rewrite CLAUDE.md's module section: point at `module-architecture` as the contract of
      record, mark the current module list as the state being replaced, and fold in the two
      factual corrections (APNs re-delivery comment in `SnapSyncRoot.kt`; the two-frameworks
      rationale in `app/ios/CLAUDE.md` re-derived to extension-safety + appex footprint).
- [x] 1.2a Bake a compact laws digest into CLAUDE.md (replacing the "Hard rules" section): one
      line per law, no rationale — the in-context copy agents code against, which is the only
      drift defense the beacon-only posture leaves during migration. Authority stays in the
      spec; the digest says so.
- [x] 1.2b Digest consistency guard in `:test:architecture`: the CLAUDE.md digest SHALL name
      exactly the requirement set of the `module-architecture` spec (loud-when-stale — editing
      either side without the other fails the build), with a non-vacuity twin.
- [x] 1.3 Record the settle-on-next-mac/device list and the iOS 27 GM time bomb where the dev
      loop will see them (CLAUDE.md on-device section).

## 2. The beacon module (`:test:architecture:migration`)

- [x] 2.1 Create the module, detached from `check` (v1's `setDependsOn(emptyList())` pattern,
      including the custom-Test-task launcher pitfall noted there); register in
      `settings.gradle.kts`.
- [x] 2.2 Implement the per-law distance measurements with derived scopes over source text:
      capability↔capability references, shell decisions (via the detekt report), files mixing
      model+port+impl, deletion-ledger items still present, module-set delta vs the target list.
- [x] 2.3 Emit the burn-down table to stdout and `$GITHUB_STEP_SUMMARY`, then FAIL while total
      distance is nonzero (D8 revised: the `verify` job is red by design, green at completion).
- [ ] 2.4 One `architecture` workflow, two jobs: `verify` (red-by-design, non-required —
      `ios-release.yml`'s guard and `/ship`'s watcher judge required checks only, derived from
      branch protection) and `diagrams` (required, in `main.json`: regenerate + porcelain,
      blocks stale diagrams). Live-verify both on the first PR.

## 3. New gates (in `:test:architecture`, gating from day one where their scope already exists)

- [x] 3.1 Extension-safety text gate: no `platform.UIKit` / `platform.BackgroundTasks` reference
      in `:app:ios:photokit-extension` today (scope widens to `:adapter:ios:ext-safe` when it
      exists); non-vacuity twin.
- [x] 3.2 Swift pin guard: decision keywords in `iosApp/**.swift` restricted to the pinned list
      with forcing proofs in the failure message; make the extension's result `switch`
      exhaustive (delete the `default:` arm) as part of pinning.
- [x] 3.3 detekt shell gate: port v1's `detektAppShell` config (complexity threshold, explicit
      iosMain source-dir task); wire as a measurement feeding the beacon now, a hard gate when
      the shell reaches zero.
- [x] 3.4 Fake-honesty gate: applies to `:adapter:fake` when created; land the gate now with a
      scope-empty-is-pending twin so it arms itself on the module's first file.
- [x] 3.5 buildHealth: apply dependency-analysis warn-only with the `kotlin-metadata-jvm` force;
      surface its report in the beacon job summary.

## 4. Derived diagrams

- [x] 4.1 Module-graph generator as a Gradle task emitting Mermaid from the project model
      (deterministic: fixed code-point sort, `"\n"`, UTF-8, no timestamps); commit
      `architecture/modules.md`.
- [x] 4.2 Zone/feature graph + port × adapter matrix + feature cards generators over source
      scans (current-state edition now; shapes track the migration automatically because scopes
      are derived).
- [x] 4.3 Flow-transcriber: implement the closed grammar (straight-line feature calls · `par`
      fan-out · `when` over feature-returned sealed results · single leading guard clause);
      paper-transcribe every current trigger body and record the gaps as burn-down items —
      generation failure becomes a hard gate only when flows exist.
- [x] 4.4 DI wiring + binary × port matrix generators over `compose/` and shell call sites
      (current edition reads the three existing roots and RENDERS their divergence — the
      anti-drift instrument working before the migration starts).
- [x] 4.5 Freshness test (in `:tools:diagrams`, with the generators — `:test:architecture` keeps
      its no-project-deps rule): regenerate + compare; declared inputs include committed
      `architecture/**`, `settings.gradle.kts`, all `build.gradle.kts`. Verified: a hand-edit
      fails, a clean regen passes, cached.
- [x] 4.6 ~~Self-healing workflow on main~~ DROPPED at apply time (user decision, D8/D9
      revision): no bot pushes to main; a semantically-stale merge turns the required `diagrams`
      check red on the merge commit, fixed by a regeneration PR. The heal workflow was deleted.

## 5. Verification

- [x] 5.1 `./gradlew build` green (all new tests + twins); `compileIosMainKotlinMetadata` green.
- [x] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green.
- [ ] 5.3 One full CI round on a PR: `verify` RED with the burn-down in its summary (and the
      PR still mergeable); `diagrams` green and required; existing required checks unchanged.
