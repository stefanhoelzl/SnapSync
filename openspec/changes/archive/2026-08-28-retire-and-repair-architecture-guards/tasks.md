## 1. Amend the specs first

The module-set gate derives its expected value from `module-architecture` at test runtime, so the build
fails until the spec describes the new modules. Specs lead, code follows.

- [x] 1.1 Apply the `architecture-guards` delta: 4 modified requirements, 10 removed
- [x] 1.2 Apply the `module-architecture` delta: the zone modules join the withholding group with their justifying argument
- [x] 1.3 Apply the `diagnostic-logging` delta: the platform-invocation obligation becomes review-maintained, with the exposure stated
- [x] 1.5 Apply the `testing-architecture` delta (found during apply: it cited the retired fake-honesty gate) and repair `architecture-guards`' own citation of the retired platform-identifier gate
- [x] 1.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes (structure only — it proves nothing about truth)

## 2. Retire the guards that need no structural precondition

Each deletion removes a test file and leaves its requirement already gone from step 1.

- [x] 2.1 Delete `ConstructorBlockingTest.kt` (inert: `contentsOfFile` never matches `dataWithContentsOfFile`)
- [x] 2.2 Delete `AbsenceIsNamedTest.kt` (unanchored 40-line look-behind)
- [x] 2.3 Delete `StackedKDocTest.kt` (its only remaining consumer was 2.2)
- [x] 2.4 Delete `PlatformIdentifierTest.kt` (the JVM target already rejects Apple type references)
- [x] 2.5 Delete `PlatformEntryLoggingTest.kt` (guards diagnosability, not behaviour)
- [x] 2.6 Delete `RigControlChannelTest.kt` (the channel is contained at compile time and ships in no build)
- [x] 2.7 `./gradlew build` is green after each deletion

## 3. Split `:domain` into per-zone modules

Steps 3.4 and 3.5 land in the **same commit**, so there is never a window with neither the module boundary nor the gate.

- [x] 3.1 Create `:domain:model`, `:domain:ports`, `:domain:feature`, `:domain:flow`, `:domain:compose` with build files, and register them in `settings.gradle.kts`
- [x] 3.2 Move each zone's sources into its module; widen the 18 `internal` declarations that now cross a module boundary
- [x] 3.3 Declare zone edges with `implementation()`, never `api()`, so no zone leaks transitively; re-point every dependent module (`:ui:*`, `:adapter:*`, `:app:*`, `:test:*`) at the zones it actually uses
- [x] 3.4 Delete `ZoneModelPurityTest.kt`, `ZonePortsTest.kt`, `ZoneFlowTest.kt`, `ZonePresentationImportsTest.kt` and `MixedPortImplTest.kt` — in the same commit as 3.1–3.3
- [x] 3.5 Shrink `ZoneGateSupport.kt` — it SURVIVES: nine remaining guards use its shared `repoRoot`/`zoneFiles`/`stripComments`/`assertNoViolations` helpers. Remove only the pending/self-arming path, so a missing scope directory FAILS rather than printing `PENDING` and passing
- [x] 3.6 Add the five new modules to `detektTierOf` in the root `build.gradle.kts` (satisfies `complexity-budgets`' existing coverage requirement)
- [x] 3.7 Verify the move preserved behaviour: `./gradlew build` and `compileIosMainKotlinMetadata` are green, and `architectureDiagrams` is fresh
- [x] 3.8 Verify by mutation that a forbidden zone reference now fails to COMPILE (not merely a red test)

## 4. Replace the fake-honesty gate with structure

- [x] 4.1 Make every class in `:adapter:generic:fake` `internal`, exporting a factory that returns the port type
- [x] 4.2 Fix the lever the old gate missed: `InMemoryStagedBytes.files` (`val files: MutableSet<String>`) must not be reachable from outside the module
- [x] 4.3 Re-point `:test:world`, `:test:integration` and the harnesses at the factories
- [x] 4.4 Delete `FakeHonestyTest.kt`
- [x] 4.5 Verify a public lever on a fake is now unreachable from `:test:world` at compile time

## 5. Collapse the laws duplicate

- [x] 5.1 Replace the laws digest in `CLAUDE.md` with a pointer to `openspec/specs/module-architecture/spec.md`. Do NOT copy the laws anywhere else — a copy in `openspec/config.yaml` would be the same duplicate in a quieter place, with no guard at all
- [x] 5.2 State in `CLAUDE.md` why there is no digest, so it is not helpfully reintroduced
- [x] 5.3 Delete `LawsDigestTest.kt`
- [x] 5.4 Confirm `CLAUDE.md` remains a declared input of `:test:architecture:test` — `RunbookSkillsTest` still reads it

## 6. Strengthen the surviving guards

- [x] 6.1 `ExtensionSafetyTest`: invert the 2-framework denylist to an allowlist of the permitted `platform.*` frameworks; match imports and fully-qualified references, never raw text (`platform` is also a local variable name here)
- [x] 6.2 `ExtensionSafetyTest`: derive the scanned roots from `:app:ios:extension`'s project-dependency closure, and fail if the derived scope is empty
- [x] 6.3 `EventLinkDomainTest`: delete the tautological assertion that the generated xcconfig matches the deployment it derives from; keep the seven live assertions
- [x] 6.4 `RuntimeIdentityTest`: derive the pin inventory from `openspec/specs/architecture-guards/spec.md` instead of holding a copy; the parser must fail loudly if it cannot parse
- [x] 6.5 `KotlinShellGuardTest`: derive `shellSourceRoots` by parsing `appShellSources` from the root `build.gradle.kts`, as `DetektTierCoverageTest` already does with `detektTierOf`
- [x] 6.6 `MainLaneContainmentTest`: add the Swift scan its spec already requires (`DispatchQueue.main`), which the implementation never had
- [x] 6.7 `SwiftShellGuardTest`: cover the decision forms the 4-keyword list misses — ternary `? :`, `as?`, `try?`, `while`, `for … where`
- [x] 6.8 `DeletionLedgerTest`: retire the `LedgerReader`, `LoggingPushReceiver` and `EventMetadataSource` rows; keep the accumulator, `*Enrollment`, `capability/` and catalog rows
- [x] 6.9 `ZoneFeatureBlindnessTest`: remove the `PENDING` fail-open — a missing `feature/` directory must fail

## 7. Prove each changed guard still fires

A guard that looks correct and catches nothing is the defect this whole change came from. Every guard
touched in step 6 gets its mutation replayed by hand before the change lands.

- [x] 7.1 For each guard changed in step 6: introduce the violation it claims to catch, confirm the build goes RED, revert
- [x] 7.2 Confirm `ExtensionSafetyTest` fails on a framework outside the allowlist that was NOT on the old denylist
- [x] 7.3 Confirm `MainLaneContainmentTest` now fails on `DispatchQueue.main` in a Swift shell (it passed green before)
- [x] 7.4 Confirm `SwiftShellGuardTest` now fails on a Swift ternary in an all-zero-pinned shell (it passed green before)
- [x] 7.5 RESOLVED, and the premise did not survive measurement: every Konsist-based guard used only `.path` and `.text`, so no guard needed a Kotlin parser. Konsist is removed and nothing replaces it, so there is no Konture rule to scope. Konture was verified sound first (fails loudly on every staleness path) and stays available if a guard ever needs a resolved model
- [x] 7.6 `git status` is clean after all mutation checks, and the deployment artefacts still resolve to `prod`

## 8. Land it

- [x] 8.1 `./gradlew build` green, `architectureDiagrams` fresh and committed
- [x] 8.2 PR carries exactly one changelog label — `internal` (applied by `/ship internal`; the change removes and rewrites guards and specs, and no customer sees any of it)
- [x] 8.3 The PR body states the accepted exposures: constructor blocking unguarded; the SNAPSYNC-3 class has no mechanical coverage; Apple literals in `:domain` are a review concern; `:domain`'s `jvm()` target is load-bearing and unguarded; KDoc stacking is unguarded
