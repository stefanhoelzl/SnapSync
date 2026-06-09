## 1. Build skeleton

- [x] 1.1 Bootstrap the Gradle wrapper for 9.5.1 (`gradlew` + `gradle/wrapper/gradle-wrapper.properties`; the jar is NOT committed — `gradlew` downloads it on demand with a pinned SHA-256; no `gradlew.bat`); verify `./gradlew --version` reports 9.5.1
- [x] 1.2 Create `gradle/libs.versions.toml`: versions for kotlin `2.4.0`, compose `1.11.1`, foojay `1.0.0`; plugin aliases for `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.compose`
- [x] 1.3 Create root `settings.gradle.kts`: `rootProject.name = "snapsync"`, `pluginManagement` repos (gradlePluginPortal, mavenCentral, google), `id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"`, `dependencyResolutionManagement` repos (mavenCentral, google), `include(":app:desktop")`
- [x] 1.4 Create root `.gitignore` (`.gradle/`, `build/`, `local.properties`, `*.hprof`, IDE files)

## 2. :app:desktop module + blank window

- [x] 2.1 Create `app/desktop/build.gradle.kts`: apply `kotlin("jvm")` + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.compose`; repos mavenCentral+google; `kotlin { jvmToolchain(25) }`; `dependencies { implementation(compose.desktop.currentOs) }`
- [x] 2.2 In `app/desktop/build.gradle.kts`, configure `compose.desktop.application`: `mainClass = "app.snapsync.desktop.MainKt"`, `javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().metadata.installationPath.asFile.absolutePath`, and add `--enable-native-access=ALL-UNNAMED` to the run/jvm args
- [x] 2.3 Create `app/desktop/src/main/kotlin/app/snapsync/desktop/Main.kt`: `fun main() = application { Window(onCloseRequest = ::exitApplication, title = "SnapSync") { } }`
- [x] 2.4 Verify `./gradlew build` completes green
- [x] 2.5 Verify `./gradlew :app:desktop:run` opens a blank window titled "SnapSync" (running on JDK 25, no `UnsupportedClassVersionError`)

## 3. CI

- [x] 3.1 Create `.github/workflows/build.yml`: `on: push`; `permissions: { contents: read }`; `concurrency: { group: ${{ github.ref }}, cancel-in-progress: true }`; job `build` on `ubuntu-latest` → `actions/checkout@v4`, `actions/setup-java@v4` (temurin 25), `gradle/actions/setup-gradle@v4`, `run: ./gradlew build`
- [ ] 3.2 Verify a push produces a green `build` status check on the commit

## 4. Branch-protection ruleset

- [x] 4.1 Create `.github/rulesets/main.json`: `target: branch`, `enforcement: active`, `conditions.ref_name.include: ["~DEFAULT_BRANCH"]`, rules `deletion`, `non_fast_forward`, `required_linear_history`, `pull_request` (0 approvals, `allowed_merge_methods: ["rebase"]`), `required_status_checks` (strict, `[{ "context": "build", "integration_id": 15368 }]`)

## 5. Ship command

- [x] 5.1 Copy the **generic** `ship-wait.ts` from workflow-engine into `.claude/commands/ship-wait.ts` (args `<repo> <pr-number> <default-branch>`)
- [x] 5.2 Add `applyRulesets(repo)` to `ship-wait.ts`: after `rebaseAndPush` and before `waitForCi`, for each `.github/rulesets/*.json` look up the ruleset by name (`gh api repos/<repo>/rulesets`) and `PUT` if it exists else `POST` (`--input <file>`); no-op when the directory is absent
- [x] 5.3 Create `.claude/commands/ship.md` (SnapSync-adapted): frontmatter `allowed-tools` for git/gh/npx + `mcp__codehydra__workspace_delete`; preconditions (clean tree · not on `master` · no un-archived openspec changes · `allow_auto_merge` ON, read-only); rebase `master`; checks = `./gradlew build`; idempotent existing-PR handling; push `--force-with-lease`; create PR (conventional title + label `enhancement`/`bug`/`internal` + optional `--resolves`); `gh pr merge --auto --rebase --delete-branch`; run `ship-wait.ts`; delete codehydra workspace unless `--keep-workspace`

## 6. Operator one-time setup (outside the diff)

- [x] 6.1 Enable `allow_auto_merge` on `stefanhoelzl/SnapSync` (one-time, manual: `gh api -X PATCH repos/stefanhoelzl/SnapSync -F allow_auto_merge=true` or settings UI)
- [x] 6.2 Confirm the `internal` label exists on the repo (already created; `enhancement`/`bug` are GitHub defaults)

## 7. End-to-end verification

- [ ] 7.1 Archive this change, then run `/ship`: confirm the PR is created and labeled, the ruleset is applied (POST on first run) when first in queue, the `build` check gates the merge, the rebase-merge succeeds, and workspace cleanup behaves per `--keep-workspace`
