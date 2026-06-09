## Context

Greenfield repo (only `docs/design.md` + tooling). The target architecture (design.md §2) is a single Compose Multiplatform codebase with a layered module graph rendering an iOS app and a JVM desktop test harness. This change builds the *development foundation* that everything else rides on — build, CI, ship flow, branch protection — deliberately stopping short of any domain behavior.

The dominant greenfield risk is the bleeding-edge toolchain. It was retired up front with a throwaway spike (since deleted) that ran the exact stack end-to-end on this machine: it compiled and opened a Compose Desktop window, and surfaced two concrete gotchas now encoded as requirements. So the versions and the build shape below are verified, not assumed.

Environment: Ubuntu 24.04, JDK 21 installed (Gradle launcher), no system Gradle, Wayland display present (the window renders here). GitHub remote `stefanhoelzl/SnapSync`, default branch `master`. The repo is developed inside a codehydra workspace (so `mcp__codehydra__workspace_delete` is available to `/ship`).

## Goals / Non-Goals

**Goals:**
- A Gradle build that compiles green and runs a Compose Desktop window.
- CI that builds on every push.
- A one-command `/ship` flow (PR + auto-merge + client-side queue + cleanup) reused from the sibling repos.
- Branch protection as committed code, applied without a CI secret.
- Durable conventions (base package `app.snapsync`, `:app:desktop` path, version catalog) so later modules slot in cleanly.

**Non-Goals:**
- No domain/sync logic, status screen, design-system `App*`, MVI/Orbit wiring, tests.
- No other modules (`:domain:*`, `:capability:*`, `:app:ios`); no native packaging; no iOS target.
- No pull_request CI trigger (push-only), no changelog file, no ruleset sync-workflow/PAT.

## Decisions

- **Stack pinned to a spike-verified set: Gradle 9.5.1 · JDK 25 (Temurin, toolchain) · Kotlin 2.4.0 · Compose MP 1.11.1.** Latest of each as of 2026-06. The Kotlin/CMP pair is one day apart (CMP 1.11.1 shipped 2026-06-02, Kotlin 2.4.0 on 2026-06-03) so it was "unverified" — the spike confirmed it compiles and runs. _Fallback:_ Kotlin **2.3.21** (same CMP) if the pair ever misbehaves — a one-line catalog change.

- **JDK 25 via Gradle toolchain auto-provisioning (`foojay-resolver-convention` 1.0.0), not a manual install.** Gradle runs on the installed JDK 21; the toolchain downloads/uses Temurin 25. Bumping the JDK later is a one-line catalog change. _Spike-confirmed:_ Gradle 9.5.1 auto-provisioned Temurin 25.0.3 and compiled with it.

- **`gradle-wrapper.jar` is not committed.** `gradlew` bootstraps it on demand from the `gradle/gradle` release tag (verified byte-identical to the locally generated jar) and checks a pinned SHA-256 before use; the jar is `.gitignore`d. No `gradlew.bat` (no Windows development). Keeps binary blobs out of git at the cost of one extra download on fresh clones.

- **`:app:desktop` is `kotlin("jvm")` + `org.jetbrains.compose`, not Kotlin Multiplatform, and is the only module now.** The desktop app is JVM-only by design; multiplatform-ness belongs in the shared modules added later. Erecting the full empty module graph would be speculative; splitting a module holding only `main()` is trivial. _Alternatives rejected:_ single-jvm-target multiplatform module (needless ceremony); full empty graph (boilerplate before consumers).

- **Spike gotcha #1 — Compose run task JDK.** `kotlin { jvmToolchain(25) }` governs *compilation* only; the Compose Desktop `run` task launches on the Gradle JVM (21) → `UnsupportedClassVersionError`. Setting `javaLauncher` fails ("executable vs javaLauncher mismatch"). The working fix is `compose.desktop.application { javaHome = <JDK-25 toolchain installationPath> }`, derived from `javaToolchains.launcherFor { … }`.

- **Spike gotcha #2 — Skiko on JDK 25.** Skiko calls a restricted native method (`System::load`); JDK 25 warns and future JDKs will block it. Add `--enable-native-access=ALL-UNNAMED` to the app JVM args.

- **CI is push-only, single JDK 25.** `on: push`, `ubuntu-latest`, `actions/setup-java` temurin 25, `gradle/actions/setup-gradle`, `./gradlew build`. Gradle 9.5.1 runs on JDK 25 (supported since 9.1.0; max 26) — verified via the compatibility matrix — so a single JDK suffices in CI (this differs from local, which keeps Gradle on 21 + toolchain 25). The status check is named `build` (the job id), matched by the ruleset.

- **`/ship` reused from the siblings, adapted.** `ship.md` is project-specific prose (the two existing copies already diverge), so it is tailored: checks become `./gradlew build`; the openspec-un-archived precondition fits directly (SnapSync uses openspec); labels kept (`enhancement`/`bug` are GitHub defaults; `internal` created); workspace deletion via codehydra applies. `ship-wait.ts` is the **generic** workflow-engine version, reused so the script stays shared across repos.

- **Ruleset applied inside `ship-wait.ts`, when first in queue, generically.** Rather than a sync workflow + PAT (workflow-engine's approach) or a manual step, `ship-wait` gains an `applyRulesets(repo)` that applies every `.github/rulesets/*.json` (find-by-name → PUT/POST) at the first-in-queue moment, after the rebase, before CI — using the operator's local admin `gh` (no secret). Written generically (no-op when the dir is absent) so the shared script stays shared and workflow-engine could retire its own sync workflow. _Alternatives rejected:_ separate `sync-rulesets.yml` + `GH_UPLOAD_TOKEN` PAT (extra secret); `apply-ruleset.sh` called from `ship.md` (loses exact in-queue timing); a `--on-turn` hook indirection (unnecessary once the logic lives in TS).

- **`allow_auto_merge` is a one-time manual enable, verified-only by ship.** Rulesets don't cover the repo-level auto-merge toggle; ship reads it as a precondition and aborts with guidance if off, but never mutates it.

## Risks / Trade-offs

- **Bleeding-edge Kotlin/CMP pair** → spike-verified; documented 2.3.21 fallback if a future patch breaks it.
- **`ship-wait.ts` enhanced (no longer byte-identical to the siblings)** → kept generic and additive (no-op without rulesets), so it remains a single shared script the others can adopt rather than a fork.
- **Ruleset apply not at the exact theoretical in-queue instant for concurrent PRs** → applied after the first-in-queue rebase, which is correct for the solo case; idempotent, re-applied every ship, so drift self-heals.
- **First-ship bootstrap of the ruleset** → the very first `/ship` `POST`s the ruleset (requiring the `build` check, which is live on that same PR's commit) before the PR merges; auto-merge then gates on it. Acceptable; if it wedges, the operator can apply the ruleset by hand once.
- **Compose Desktop needs a display to *render*** → `build` (compile) is the headless CI gate; the visible window is verified locally (display present).

## Migration Plan

Greenfield — no rollback concerns. Order: (1) Gradle wrapper + `settings.gradle.kts` + `gradle/libs.versions.toml` + `.gitignore`; (2) `:app:desktop` `build.gradle.kts` (incl. `javaHome` + native-access arg) + `Main.kt`; (3) verify `./gradlew build` green and `:app:desktop:run` opens the window; (4) `.github/workflows/build.yml`; (5) `.github/rulesets/main.json`; (6) `.claude/commands/ship.md` + `ship-wait.ts` (with `applyRulesets`). Operator one-time setup (outside the diff): `allow_auto_merge` on; `internal` label (done).

## Open Questions

- Exact patch versions (Gradle 9.5.x, foojay, setup-java/setup-gradle action majors) — pin current-stable at implementation time.
- Whether workflow-engine/codehydra adopt the enhanced generic `ship-wait.ts` and retire `sync-rulesets.yml` — their call, out of scope here.
- A formatter/linter (ktlint/spotless → `./gradlew :check`) and its `/ship` integration — deferred until there's code worth linting.
