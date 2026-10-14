# Tasks

## 1. The crash reporter's double records automatic events (D1, D4)

- [x] 1.1 `InMemoryCrashReporter`: `setContext` keeps the latest fields per name; `capture` records the shaped event
      (nothing on a `null` handler answer) with the contexts standing, in a received-events record that tells the world;
      rewrite its KDoc. Verify with its existing tests plus new ones in `:adapter:generic:mock` commonTest
- [x] 1.2 `CrashReporterContract`: a `RECEIVED` state holding the five clauses about what the backend received (D1);
      the mock binding and both Sentry bindings reach it, `ON_THE_WIRE` stays the SDK's; the mock's `delivered`
      observation reads captured events and dumps from one record. Verify `CrashReporterContractBindingTest` passes and
      `:test:architecture` (`ClauseCoversTest`, `AdapterCoverageTest`, `ContractCoverageTest`) stays green
- [x] 1.3 `:test:levers`: `ReceivedEvent`, `CrashReporterLevers.received()`, `WorldEvent.EventReceived`; the operator
      implements `received()`; R8 keep rules: the module's rules are generic over every `@Rpc` stub, nothing to add. Verify `./gradlew :test:levers:build :adapter:generic:mock:jvmTest`

## 2. Process-metric reports on the JVM host (D2, D3)

- [x] 2.1 Cut the three fixtures (`QUIET` from the SE2 recording, `ENDED_ABNORMALLY` from SNAPSYNC-61, `FROZE` derived),
      randomized once per D2, with a note naming sources and what was randomized. Verify with a mock unit test that
      `processMetricEmissions` crosses on exactly the intended reason for each, and on nothing for `QUIET`
- [x] 2.2 `ProcessMetricsMock` (port face holding `MetricHandlers`, operator delivering a kind's fixture inline), its
      place in `JvmMocks`, and `JvmApp` composing it instead of `NoProcessMetrics`. Verify the `ProcessMetrics`
      contract binding over the mock passes beside `NoProcessMetricsContractTest`
- [x] 2.3 `ProcessMetricsLevers` + `MetricReportKind` + `LeverNeed.PROCESS_METRICS_MOCKED` (the module's generic R8 rules keep it); served on the
      JVM host, refused with its reason on the device hosts; `LeverFacesTest` holds the operator to it; the JVM host's
      `device/process-metrics` refusal re-worded to point at the service. Verify `:test:architecture` and `:test:control`
      tests pass
- [x] 2.4 `docs/testing.md`: the new face, `received()`, and `EventReceived` where the faces and world events are listed;
      the `ON_THE_WIRE` licensing rule where the contracts' doubles are explained. Verify the doc matches the code

## 3. The behaviour tests (D5)

- [x] 3.1 Ended abnormally: an encrypted event joined, a footprint recorded in the background, `deliver(ENDED_ABNORMALLY)`
      → an `EventReceived` naming the memory-limit reason, carrying the footprint in `process_metrics`, the key nowhere.
      Verify the test passes and fails when `ProcessAccount` drops the footprint fields
- [x] 3.2 Froze: the same with `FROZE`, the hang reason. Verify the test passes
- [x] 3.3 Quiet: `deliver(QUIET)` → `never(EventReceived)` until settled. Verify the test passes and fails when the
      fixture is swapped for a crossing one
- [x] 3.4 Fix any defect the tests reveal (a lane violation on the delivering thread included) and note it in design.md —
      none surfaced: the report runs inline on the lever call's thread, an act of the operator lane

## 4. Coverage and the gate

- [x] 4.1 Root `build.gradle.kts`: `:domain:entrypoints` to 100 with its comment deleted; `:domain:model` and
      `:domain:services` raised to what is measured. Verify `./gradlew build` passes
- [x] 4.2 `./gradlew compileIosMainKotlinMetadata` passes; `./gradlew architectureDiagrams` regenerated and committed if
      the module graph moved; `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` passes
