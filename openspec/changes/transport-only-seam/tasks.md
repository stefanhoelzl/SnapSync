## 0. Evidence before the behaviour change

- [x] 0.1 Look for `retryJob: no live .retry job for` in a device's `ext-debug.log` (snapsync-device skill) or in a
      Bugsink diagnostic dump; record what was found — present, absent, or no OS-driven-tier log available — in
      this change's `design.md` Risks, replacing "inferred from the code"

## 1. Commit (a) — photo-library reads leave the transport seam

- [x] 1.1 Add `UploadDiscovery` to `:domain` `ports/` (`discover(sinceToken, policy)`, `resourcesFor(keys)`),
      moving `Discovery` and its KDoc out of `BackgroundTransfer.kt`; move the per-method contract KDoc
      (enumeration scope, partial tolerance) from `BackgroundTransfer` to the new port
- [x] 1.2 Remove `discoverResources` and `resourcesFor` from `BackgroundTransfer`; update its class KDoc to the
      lifecycle-only seam
- [x] 1.3 Rename `SelectionScopedTransfer` → `SelectionScopedDiscovery`, wrapping `UploadDiscovery`; drop
      `by delegate` and the `remainingCapacity` comment; keep both read-discipline branches byte-identical
- [x] 1.4 `UploadPorts` gains `discovery: UploadDiscovery`; `uploadCore` passes
      `SelectionScopedDiscovery(ports.discovery, ports.selectionScope)` to `UploadCycle`, which reads the library
      only through it (sites: `discoverResources` in decide, `resourcesFor` in enqueue)
- [x] 1.5 `IosDiscovery` implements `UploadDiscovery`; wrap both methods in the same `log.invocation` names and
      result/params lambdas the transports used (`platform.discoverResources`, `platform.resourcesFor`)
- [x] 1.6 Move `IosDiscovery.buildRequest` to a top-level upload-request builder in `:adapter:ios:ext-safe`'s
      upload package, unchanged; point `IosUrlSessionUploadPlatform` and `IosPhotoKitUploadPlatform` at it
- [x] 1.7 Delete the forwarding `discoverResources` / `resourcesFor` from `IosUrlSessionUploadPlatform`,
      `IosPhotoKitUploadPlatform` and `SimulatorUploadJobQueue`, and their `IosDiscovery` constructor parameter
- [x] 1.8 `UrlSessionUploadController` and `UploadExtensionRoot` bind their existing `IosDiscovery` into
      `UploadPorts.discovery`; the `uploadJobQueue` expect/actuals drop the `discovery` parameter
- [x] 1.9 `:test:world`: extract `FakeUploadDiscovery` (discover, resourcesFor, `discoverCalls`, `resolvedKeys`,
      `resolvedKeyCount`, `forceFull`, token counter) from `FakeBackgroundTransfer`; `World` exposes it as
      `discovery` and binds it into `UploadPorts`; move the expire-token lever to it
- [x] 1.10 Point integration tests that read discovery observability at `w.discovery.*`
      (`CapTruncatedPublishIntegrationTest`, `BoundedTopUpIntegrationTest`, and any other `grep`
      `platform\.(discoverCalls|resolvedKey)` hit)
- [x] 1.11 Split `UploadCycleTest.FakePlatform` into a transport double and a discovery double; retarget
      `SelectionScopedTransferTest` → `SelectionScopedDiscoveryTest` over an `UploadDiscovery` recording double
      (deviation: `FakePlatform` implements both ports rather than splitting — it is built at ~60 call sites with
      mixed transport and discovery options, and a fixture may implement two interfaces; the cycle helper passes
      it as both)
- [x] 1.12 Update the KDocs that name the old seam: `CandidateSource` ("lives on the upload seam"),
      `DiscoveryStore` ("in [BackgroundTransfer]"), the `uploadJobQueue` expect's "delegates discovery" section,
      and `UploadJobSubsystemBindingTest`'s comment
- [x] 1.13 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green; `./gradlew architectureDiagrams`
      and include `architecture/` in the commit
- [x] 1.14 Commit (a) with an `internal(upload): …` subject

## 2. Commit (b) — `TransferRecord`, `liveKeys`, the stranded pass in the cycle

- [ ] 2.1 Add `TransferRecord` to `:domain` `ports/` declaring `markTerminal(key, outcome): Boolean` and
      `entryForDestination(path)`; make `LedgerStore` extend it, moving both members and their KDoc up with
      signatures unchanged; update `markTerminal`'s "non-writer surface" paragraph to name `TransferRecord`
- [ ] 2.2 Add `liveKeys(): Set<String>?` to `BackgroundTransfer` with the null-is-an-answer KDoc
- [ ] 2.3 `LedgerWriter` gains `requestedKeys()` and `markStranded(key): Boolean` (delegating to
      `markTerminal(key, TerminalOutcome.FAILED)`)
- [ ] 2.4 Move `strandedKeys` from `UrlSessionOutcome.kt` to `feature/upload`; move its four
      `UrlSessionOutcomeTest` cases to a `commonTest` in `:domain:feature`
- [ ] 2.5 `UploadCycle.recreateRetrySpent`: after `platform.drainTerminals()` returns and before iterating its
      jobs, run the stranded pass over `platform.liveKeys()` (skip on null) with the two existing log lines
      verbatim
- [ ] 2.6 `UploadCycleTest`: a transport double reporting a live set strands exactly the `REQUESTED` rows
      outside it; a `null` answer strands nothing; a row settled between read and write is left and logged;
      a direction-declined cycle still runs the pass
- [ ] 2.7 `IosUrlSessionUploadPlatform`: take `TransferRecord` instead of `LedgerStore`; `liveKeys()` returns
      `liveTaskKeys()`; delete `reconcileStranded` and its call from `drainTerminals`; move its KDoc rationale to
      the cycle's pass
- [ ] 2.8 `IosPhotoKitUploadPlatform` and `SimulatorUploadJobQueue`: take `TransferRecord`; `liveKeys()` returns
      `null` with the "durable queue, two job sets" KDoc; add the "not discovery" sentence to the substitute's
      `resourceForKey` KDoc
- [ ] 2.9 `uploadJobQueue` expect/actuals become `(log, record: TransferRecord)`; both roots pass their ledger
      store where a `TransferRecord` is expected
- [ ] 2.10 `FakeBackgroundTransfer`: take `TransferRecord`; `liveKeys() = null`
- [ ] 2.11 Update remaining `BackgroundTransfer` doubles (`UploadCycleTest`, `SelectionScopedDiscoveryTest` if
      it still holds one) for `liveKeys`
- [ ] 2.12 `:test:architecture`: add the transport-ledger gate (scope derived from `BackgroundTransfer`
      implementations and `uploadJobQueue` under `adapter/` production source sets, comments stripped, fails on
      a `LedgerStore` reference and on an empty scope); prove it red by temporarily re-adding a `LedgerStore`
      parameter, then revert
- [ ] 2.13 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green; `architectureDiagrams` committed
- [ ] 2.14 Unchanged and green: `LostUploadAckIntegrationTest`, `LostUploadRecordIntegrationTest`,
      `LedgerStoreContract`, `LedgerRecordGuardContract`, `PhotoKitJobMappingTest`
- [ ] 2.15 Commit (b)'s refactor part with an `internal(upload): …` subject (or fold into 3.4's commit — one
      commit for (b) in the final history)

## 3. Commit (b) — the PhotoKit retry fix

- [ ] 3.1 Extract the retry-job selection into a pure function in `PhotoKitJobMapping.kt`: given the `.retry`
      set's classified destinations, a key resolver and a key, return the matching job or none
- [ ] 3.2 `PhotoKitJobMappingTest`: a v2 destination whose last segment is `primary` matches its key through the
      recorded destination; a v1 destination matches through the fallback; no match yields none
- [ ] 3.3 `IosPhotoKitUploadPlatform.retryJob` uses it with the existing `resolveKey`; delete `jobWithKey`; keep
      the "no live .retry job" warning for a job that left the set
- [ ] 3.4 Squash sections 2 and 3 into commit (b) with a `fix(upload): …` subject naming the retry defect

## 4. Verification

- [ ] 4.1 Run the `iosSimulatorArm64Test` suites for `:domain:feature`, `:adapter:ios:ext-safe` and
      `:adapter:ios:app-only` on the Mac (ssh-mac-build skill)
- [ ] 4.2 On a device build (TestFlight dispatch or a sideload), confirm an OS-driven retry: a `.retry` job
      reaches `retryWithDestination` and no "no live .retry job" line is logged for it; record the evidence in
      `design.md`
- [ ] 4.3 `npx --yes @fission-ai/openspec@1.5.0 validate transport-only-seam --strict`
- [ ] 4.4 Report back to the tierless design session (handoff): contradiction of D14's sink shape, and the
      scope change (retry fix folded in); on merge, the shipped report
