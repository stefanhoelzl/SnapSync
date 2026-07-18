# Tasks: rehome-ui-modules

## 1. Re-homing

- [x] 1.1 `git mv` the three modules under `ui/`; update `settings.gradle.kts` + every
  `project()` reference; sweep comment-level path mentions
- [x] 1.2 Extend `tools/diagrams` `Scan.kt`/`Zones.kt` walk lists with `ui`; regenerate
  `architecture/` (diagrams see the three `:ui:*` modules)
- [x] 1.3 Extend the beacon's deletion-ledger scan roots with `ui` (`targetModules` untouched)

## 2. The split

- [x] 2.1 `UserCommands` `flow/`→`model/` (+`requestAccess`/`openSettings`); `AppPorts` gains
  `photoAccessRequester`; `AppCore.userCommands` binds the two new commands
- [x] 2.2 `StatusContainerHost`: StateFlow read-model params, dead `store` deleted, `requester`
  dissolved, formatter required; `JoinLoad`→`model/`, `toJoinLoad`→`feature/membership`
- [x] 2.3 `CutoffFormatter` pure (+`ports/Time.kt`, `:adapter:generic` `SystemTime.kt`); thread
  through SnapSyncRoot, MainViewController, StatusPane, forge factory, all tests
- [x] 2.4 Arrow/ArrowLevel unify on `model/`'s `Arrow`; components api-dep on `:domain`;
  `toLevel()` deleted; `ArrowIcon` rename
- [x] 2.5 Convert every construction site (SnapSyncRoot · ForgeStatusHost · StatusPane ·
  StatusContainerHostTest · ForgeStatusHostTest · JoinGate/FullStack integration tests)

## 3. Gates & verification

- [x] 3.1 Presentation gate armed: deliberate-red proof (planted `ports/` import → named
  violation → removed → green); non-vacuous scan
- [x] 3.2 `./gradlew build` + `compileIosMainKotlinMetadata` + `:test:architecture:test` +
  `:test:world:jvmTest` + `:test:integration:jvmTest` + `:ui:presentation:jvmTest` +
  `:ui:screens:jvmTest` green
- [x] 3.3 Beacon before/after (fresh detekt, `--rerun-tasks`): 36 → 29 (module 16→10, ledger
  2→1, shells 18 unchanged, no law increased)
- [x] 3.4 Dispatch `screenshots.yml` from this branch (sole exerciser of `forgeStatusHost`);
  run id recorded in the implementation report
- [x] 3.5 CLAUDE.md: module rows, `:ui:screens:jvmTest` runbook path, forge-factory seat
