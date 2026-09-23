## 1. Contracts and fixture vocabulary (`:test:contracts`)

- [x] 1.1 Add `TransferFixture`: the route vocabulary derived from a clause id (scripted status/body/length, hold, stored `PUT` read-back), shared by the loopback server and the world bindings' network
- [x] 1.2 Add `BackgroundTransferContract`: the state vocabulary (`IDLE`, `AT_CAP`), the `TransferObservations` handle (stored object + row state), and D1's eight clauses
- [x] 1.3 Add `DownloadTransportContract`: the clause-supplied host that records what it is told, and D2's eight clauses
- [x] 1.4 Add `BackgroundSchedulerContract`: the `pendingWakes()` handle and D4's four clauses

## 2. Fakes and world bindings

- [x] 2.1 Add `inMemoryBackgroundScheduler` to `:adapter:generic:fake` (an `internal` class behind a port-typed factory), bind `BackgroundSchedulerContract` to it in `commonTest`, and make it green
- [x] 2.2 Bind `BackgroundTransferContract` and `DownloadTransportContract` to `FakeBackgroundTransfer` / `FakeDownloadTransport` in `:test:world` `commonTest`, with the binding playing the network through their operator actions
- [x] 2.3 Fix the world doubles wherever a clause fails (commit red first), keeping their levers and inspection; `./gradlew :test:world:jvmTest :test:integration:jvmTest` and the desktop module still pass
- [x] 2.4 Replace the two private `FakeScheduler`s in `:domain:feature` tests with the honest fake where they only stand in; keep a private one only where a test records calls on purpose — both do (they assert whether the pump re-armed), and `:domain:feature` cannot depend on the fake module, so both stay

## 3. Live `URLSession` bindings on `IOS_SIM_APP`

- [x] 3.1 Add `scripts/transfer-fixture.py` (stdlib only: health route, request log, per-clause routes, a server-side hold timeout, `Cache-Control: no-store`)
- [x] 3.2 Pass the fixture base URL through the rig's contract verb (`?fixture=`), and refuse a transport contract run without one
- [x] 3.3 Add the `DownloadTransport` live binding (over `IosDownloadTransport`) to `:adapter:ios:app-only`'s rig source set, and register it in `SimulatorAppContracts`
- [x] 3.4 Add the `BackgroundTransfer` live binding (over `IosUrlSessionUploadPlatform`, an in-memory ledger as its `TransferRecord`, and a photo seeded in the clause's own capture-date window), and register it
- [x] 3.5 Start the fixture in `scripts/sim-contracts` before launch, fail on no health answer, and keep its request log in `build/sim-contracts/`
- [ ] 3.6 Run `scripts/sim-contracts` on a Mac session (`ssh-mac-build`); commit any failing clause red first (D7), then fix the adapter, never the clause, unless the clause is wrong
- [ ] 3.7 Measure a short read (declared length > delivered) on the default session; add the clause only if it is deterministic, otherwise document the observed behaviour on `IosDownloadTransport`

## 4. `BackgroundScheduler` seam

- [x] 4.1 Add the `internal` `BackgroundTaskApi` seam in `:adapter:ios:app-only` (`submit`, `cancel`, `pending`), rendering deterministic call/answer strings with `earliestBeginDate` masked; route `IosBackgroundScheduler` through it with no behaviour change
- [x] 4.2 Add the recording/replaying tape for the seam in the rig source set, following `KeychainTape`
- [x] 4.3 Add the `IOS_DEVICE_APP` live binding and register it for the device's contract verb

## 5. Scheduler hosts

- [ ] 5.1 Measure `BGTaskScheduler.submit` on the simulator app; bind `IOS_SIM_APP` live if it is accepted, or declare the states unreachable naming the measured error
- [ ] 5.2 Take the lease (`snapsync-device`, `rig-channel`), install a rig build on the SE2, `POST /contract/BackgroundScheduler`, and commit `test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec` unedited
- [ ] 5.3 Add the `Replay` binding in `:adapter:ios:app-only` `iosTest` over that recording

## 6. Gates and docs

- [ ] 6.1 `./gradlew build` is green, including `ContractCoverageTest`, which names every new clause as covered by a real host
- [ ] 6.2 `./gradlew compileIosMainKotlinMetadata` is green; `./gradlew architectureDiagrams`, commit any diff
- [ ] 6.3 Update CLAUDE.md's module entries (`:test:contracts` contract list, `:adapter:generic:fake` fakes, `:test:world` wrappers) and the `rig-channel` / `ios-simulator` skills (fixture flag, heartbeat cleared by a device run)
- [ ] 6.4 Point `TransferSessions.kt`'s "does NOT evidence" section at the contracts for what IS evidenced
- [ ] 6.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate contract-background-transfers --strict` pass
