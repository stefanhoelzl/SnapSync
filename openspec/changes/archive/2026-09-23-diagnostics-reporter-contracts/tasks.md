## 1. Measure before writing (design Risks; `iosSimulatorArm64Test` on a macOS runner via `ssh-mac-build`)

- [x] 1.1 In the kexe, measure an in-process loopback listener on `127.0.0.1:0` (POSIX socket, accept on a
  background worker) receiving the real SDK's envelope. Record the latency and whether `platform.zlib`
  inflates the gzip body. If it does not work, stop and amend design D6 to the Gradle-launched server before
  continuing.
- [x] 1.2 In the kexe, measure `NSBundle.mainBundle.bundleIdentifier`. If it is `nil`, drop
  `WIRE_DESCRIBE_FIRST_TAGS_THE_PROCESS` from D5, record why in `design.md`, and add the reasoning to
  `SentryDiagnosticsReporter`'s KDoc (task 3.4).
- [x] 1.3 Record both results in `design.md`'s measurement table (M9, M10) with date, OS and SDK versions.

## 2. The adapter seams (behaviour-preserving)

- [x] 2.1 `SentryDiagnosticsReporter`: add `internal constructor(dsn: String?)`. The public no-arg
  constructor delegates with `bakedSentryDsn()`, and `isConfigured` / `start` read the stored value. Both
  shells stay unchanged.
- [x] 2.2 Add `internal fun resetProcessStart()` beside `processStarted`, with a KDoc naming it the contract
  binding's per-clause reset and nothing else.
- [x] 2.3 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` are green, and
  `SwiftShellGuardTest` / `KotlinShellGuardTest` are unmoved.

## 3. The contract

- [x] 3.1 `:test:contracts` commonMain:
  - `DiagnosticsReporterState` (`UNCONFIGURED`, `CONFIGURED`, `CONFIGURED_ON_THE_WIRE`);
  - `DeliveredEvent`, `DiagnosticsObservation` and `DiagnosticsReporterSubject` (design D4), with the
    45 s `delivered` deadline throwing `WaitExpired`;
  - add Kermit to `:test:contracts` commonMain if `:domain:model` does not already expose it.
- [x] 3.2 `DiagnosticsReporterContract` with the D5 clauses:
  - inputs are derived from the clause id;
  - negative assertions use the sentinel barrier;
  - no clause names a Sentry type or context name.
- [x] 3.3 KDoc on the contract: what the three states mean, why the fake stops at `CONFIGURED`, and that crash
  survival is a documented belief rather than a clause.
- [x] 3.4 `SentryDiagnosticsReporter` KDoc: point at the contract for the obligations it now asserts. Keep the
  crash-survival claim as a belief with its evidence (`changes/archive/2026-09-14-add-os-exit-attribution`).

## 4. The fake binding and the fake fix

- [x] 4.1 `InMemoryDiagnosticsReporter.start()` flips `started` only when `isConfigured`. Remove the
  `described` cell and its factory parameter.
- [x] 4.2 `:adapter:generic:fake` commonTest: the fake binding (`currentHost`, `Fake`, reaching
  `UNCONFIGURED` and `CONFIGURED`), with the handle over the `started` and `sent` cells. Delete
  `DiagnosticsReporterContractTest`.
- [x] 4.3 `./gradlew build`: the fake binding passes on JVM, and the world and integration tests that read
  `diagnosticsStarted` / `diagnosticsSent` are unchanged and green.

## 5. The ingest fixture and the live binding

- [x] 5.1 `:adapter:ios:ext-safe` iosTest `LoopbackIngest` (design D6):
  - port-0 bind; request line, headers and `Content-Length` body;
  - gzip inflate; envelope split, keeping only `event` items;
  - `200`, or `413` without recording above 1 MiB decoded;
  - `stop()`.
- [x] 5.2 The live binding on `IOS_SIM_KEXE`:
  - `UNCONFIGURED` uses the public constructor;
  - the configured states use the internal constructor with the fixture's DSN;
  - per-clause isolation per design D8 (close, cache wipe, `resetProcessStart`, Kermit writers restored);
  - the handle maps adapter vocabulary onto `DeliveredEvent`.
- [x] 5.3 Run `:adapter:ios:ext-safe:iosSimulatorArm64Test` on the macOS runner twice in a row on the same
  simulator. Every clause is `Passed` both times, which proves the cache wipe isolates runs (M7).
- [x] 5.4 `ContractCoverageTest` is green: every clause is reached by the live binding on `IOS_SIM_KEXE`.

## 6. Close-out

- [x] 6.1 CLAUDE.md's module map still names the pre-rename `CrashReporting` port, `SentryCrashReporting` and
  `InMemoryCrashReporting`. Correct them to `DiagnosticsReporter`, `SentryDiagnosticsReporter` and
  `InMemoryDiagnosticsReporter`, and name `DiagnosticsReporterContract` in `:test:contracts`' contract list.
- [x] 6.2 At sync, add this change's decision record to `port-contracts`' Purpose sentence listing decision
  records (a delta cannot carry a Purpose change).
- [x] 6.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and the change's own validation
  are green.

## Archive gates (recorded at archive)

- Placeholder Purpose: none in the tree.
- Delta completeness:
  - `:test:contracts` → `port-contracts`, with a delta.
  - `:adapter:ios:ext-safe` → `crash-reporting`, no delta: only `internal` seams changed, and behaviour is
    preserved.
  - `:adapter:generic:fake` → no delta: the fake's unconfigured `start()` now matches the adapter, and no
    requirement states the fake's behaviour. The world builds it configured.
- Dead types: `DiagnosticsReporterContractTest` is removed and named by no spec. `SentryDiagnosticsReporter`
  survives, and only its declaration line changed.
