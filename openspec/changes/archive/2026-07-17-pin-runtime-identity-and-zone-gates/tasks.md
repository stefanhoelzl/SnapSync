# Tasks — pin-runtime-identity-and-zone-gates

## 1. Consolidation (make exactly-once true)

- [x] 1.1 `domain/download-store/build.gradle.kts`: add `implementation(project(":domain:engine"))`
      to `iosMain.dependencies`; in `IosDownloadStore.kt` delete the private `DOWNLOAD_APP_GROUP`
      const and import `app.snapsync.engine.LEDGER_APP_GROUP` (D2)
- [x] 1.2 `domain/engine/src/iosMain/…/IosLedgerBackend.kt`: consolidate the two `"ledger.db"`
      occurrences into one private const (used by both the driver `name` and the
      protection-attributes path)
- [x] 1.3 Verify no other production Kotlin duplicate of any pinned literal remains:
      grep each inventory value across `src/*Main` (expect exactly one hit each, pairs one line
      each) — fix any straggler before writing the guard

## 2. Runtime identity pin guard

- [x] 2.1 Create `test/architecture/src/test/kotlin/app/snapsync/architecture/RuntimeIdentityTest.kt`:
      repo-root discovery (FakeHonestyTest pattern), a production-Kotlin walk
      (`src/*Main`, excluding `test/` and `build/`), and a table-driven pin list matching the
      spec inventory verbatim
- [x] 2.2 Implement the three pin kinds: bare-literal exactly-once (Kotlin), (service, account)
      pair regex exactly-once (Kotlin), and surface-pinned occurrences (entitlements ×2 for the
      app-group id; `build.gradle.kts` for the two baseNames)
- [x] 2.3 Implement the BGTask cross-surface assertion: each id exactly once in Kotlin, exactly
      once in `iosApp/iosApp/Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, values equal
- [x] 2.4 Non-vacuity: assert every scanned surface resolved non-empty (Kotlin file set,
      each entitlements file, Info.plist, the build-file set); failure messages name the literal,
      the expected count, and every found location
- [x] 2.5 Red-test the guard once by hand: temporarily change one literal and one pair, watch
      both fail with useful messages, revert (do not commit the red)

## 3. Zone gates (pending, self-arming)

- [x] 3.1 Create `ZoneModelPurityTest`: scope `domain/src/*/kotlin/**/model/`; law: no
      project-internal reference outside `model/` (source-text, catches fully-qualified);
      PENDING print while `domain/src` is absent; non-vacuity once present (D6 assumption named
      in the comment)
- [x] 3.2 Create `ZonePortsTest`: scope `…/ports/`; law: references only `model/` (and
      non-project libs)
- [x] 3.3 Create `ZoneFeatureBlindnessTest`: scope `…/feature/`; features enumerated from
      directory listing; law: no reference to a sibling feature package, pairwise
- [x] 3.4 Create `ZoneFlowTest`: scope `…/flow/`; law: references only `model/` and `feature/`,
      never `ports/`
- [x] 3.5 Create `ZonePresentationImportsTest`: scope `ui/presentation/src/**`; law
      (import-level approximation, D7): never references `ports/` or `flow/` packages
- [x] 3.6 Confirm all five print their PENDING line today (`./gradlew :test:architecture:test`)
      and that creating a scratch file under a scope flips that gate live (then delete the
      scratch file)

## 4. Plan amendment + landing

- [x] 4.1 Amend `test/architecture/migration/PLAN.md` step-0 section: consolidate-then-guard
      scope (D1), extended inventory (D5), and tick row 0 status
- [x] 4.2 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` — both green
- [x] 4.3 Run `./gradlew architectureDiagrams`; commit regenerated `architecture/` if the new
      download-store→engine edge renders (required `diagrams` check)
- [x] 4.4 Validate the change: `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
