## Why

SnapSync v1 (see `docs/design.md`) is greenfield — the repo holds only docs and tooling, no build. Before any feature work, the project needs its development foundation in place: a Compose Multiplatform build that compiles and runs, CI on every push, a one-command ship flow, and enforced branch protection. The riskiest part (a bleeding-edge Compose/Kotlin/JDK toolchain) was de-risked with a throwaway spike that compiled and opened a window end-to-end, so the stack below is verified, not speculative. This change establishes that foundation and nothing more — it carries no domain/sync behavior.

## What Changes

**Build + blank window**
- Greenfield Gradle build: root `settings.gradle.kts`, `gradle/libs.versions.toml`, Gradle **wrapper 9.5.1**, `foojay-resolver-convention` 1.0.0 to auto-provision the JDK toolchain.
- Verified stack: **Gradle 9.5.1 · JDK 25 (Temurin, toolchain) · Kotlin 2.4.0 · Compose Multiplatform 1.11.1**.
- One module **`:app:desktop`** — plain `kotlin("jvm")` + `org.jetbrains.compose` (Compose Desktop), **not** a multiplatform module (shared `:domain:*`/`:capability:*` modules come in later changes).
- Entry point `app.snapsync.desktop.MainKt` → `application { Window(onCloseRequest = ::exitApplication, title = "SnapSync") { /* empty */ } }`.
- Spike-derived build requirements: `compose.desktop.application { javaHome = <JDK-25 toolchain installationPath> }` (Compose's run task otherwise launches on the Gradle JVM 21 → `UnsupportedClassVersionError`); add `--enable-native-access=ALL-UNNAMED` to the app JVM args (silences Skiko's JDK-25 restricted-native-access warning).
- Dependencies limited to Kotlin + Compose Desktop. `.gitignore` for `build/`, `.gradle/`, `local.properties`.

**CI**
- `.github/workflows/build.yml`: trigger **`on: push`** (push-only), `runs-on: ubuntu-latest`, a single job `build` on **JDK 25** (`actions/setup-java` temurin 25) + `gradle/actions/setup-gradle`, running `./gradlew build`. `concurrency: cancel-in-progress` on the ref. Future-proof: `build` covers modules/tests as they are added.

**Ship command** (dev tooling — Claude Code slash command)
- `.claude/commands/ship.md` (SnapSync-adapted): preconditions (clean tree · not on `master` · no un-archived openspec changes · `allow_auto_merge` is ON) → rebase onto `master` → `./gradlew build` → idempotency (existing-PR handling) → push `--force-with-lease` → create PR (conventional title + categorize → label `enhancement`/`bug`/`internal` + optional `--resolves`) → `gh pr merge --auto --rebase --delete-branch` → `ship-wait` → delete codehydra workspace (`--keep-workspace` opt-out).
- `.claude/commands/ship-wait.ts`: the generic client-side merge queue from workflow-engine, reused; **enhanced generically** with `applyRulesets(repo)` that runs when the PR is **first in queue** (after rebase, before CI) and applies every `.github/rulesets/*.json` (no-op if none) — so the shared script also subsumes ruleset sync for repos that have one.

**Branch protection (ruleset-as-code)**
- `.github/rulesets/main.json` targeting the default branch: required status check `build`, rebase-only merges, required linear history, no branch deletion, no force-push, PR required (0 approvals). Applied by `ship-wait` when first in queue (using the operator's local admin `gh` — no PAT/CI secret, no sync workflow).

**Out of scope / deferred:** all domain/sync logic, status screen, design-system `App*`, other modules (`:domain:*`, `:capability:*`, `:app:ios`), tests, native packaging, iOS target.

## Capabilities

### New Capabilities
- `desktop-app-shell`: the launchable JVM/Compose Desktop application — build target + entry point that opens the (empty) application window; future host of the UI.
- `ci-build`: continuous integration that builds the project on every push and reports a `build` status check.
- `ship-command`: the `/ship` slash command that ships a branch via PR + auto-merge + a client-side merge queue, with cleanup.
- `branch-protection`: the default branch protected by a committed ruleset (required `build` check, rebase-only, linear history, PR-gated), applied during ship.

### Modified Capabilities
<!-- None. First change in a greenfield repo; no existing specs. -->

## Impact

- **Build**: `settings.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper, root `.gitignore`. New module `app/desktop/` (`:app:desktop`) with `build.gradle.kts` + `src/main/kotlin/app/snapsync/desktop/Main.kt`.
- **Dependencies**: Kotlin 2.4.0 + Compose Multiplatform 1.11.1 (desktop) only.
- **CI/tooling**: `.github/workflows/build.yml`, `.github/rulesets/main.json`, `.claude/commands/ship.md`, `.claude/commands/ship-wait.ts`.
- **Repo settings** (operator, outside the diff): the `internal` label exists (created); `allow_auto_merge` enabled once manually (verified by ship, never mutated by it).
- **No** domain/runtime code, networking, persistence, or platform integrations.
- **Acceptance**: `./gradlew :app:desktop:run` opens a blank window titled "SnapSync"; `./gradlew build` is green; a push produces a green `build` check; `/ship` merges a branch and applies the ruleset when first in queue.
