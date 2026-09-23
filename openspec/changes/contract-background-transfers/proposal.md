## Why

The app moves every byte it uploads and downloads through two `URLSession` adapters, and schedules its upload
heartbeat through a third. No test runs any of them. Each is faked in the world, the doubles are checked
against nothing, and several port obligations exist only in doc comments. `BackgroundScheduler`'s "repeated
calls are idempotent" is one; `DownloadTransport`'s "asked before the bytes are moved" is another. This is
phase 8b of the testing-concept sequence: it runs the mechanism `port-contracts` established against the
background-transfer ports.

The reason these ports stayed uncontracted is only half true. A **background** `URLSession` transfers nothing
on a simulator. But both adapters build their session through `transferSessionConfiguration`, which the
`iosSimulatorArm64` target compiles to a **default** session, and a default session does transfer. So the
adapters' own code can run live on the simulator app on every push: the delegate, staging, how the outcome is
read, the cap, and the terminal write. Only the configuration lookup and the background session's lifecycle
stay out of reach.

## What Changes

- **Three port contracts** in `:test:contracts`, hand-written as clause values:
  - **`BackgroundTransfer`**: **one** contract for both upload tiers, because `port-contracts` allows one per
    port. It holds the tier-neutral clauses. This change binds the honest fake and `IosUrlSessionUploadPlatform`.
    The PhotoKit tier (phase 6b) may add bindings, and PhotoKit-only clauses, to this contract. It does not
    write a second one.
  - **`DownloadTransport`**: its outcomes are observed through the `DownloadTransportHost` a clause supplies,
    which is the port's own output channel, and through the bytes at the staged path.
  - **`BackgroundScheduler`**: its outcomes are observed through a handle over the pending wake requests,
    because the port declares no reads.
- **Live on `IOS_SIM_APP`.** The two `URLSession` contracts are registered in the simulator-app registry and
  run by `ios-contracts` on every push. They transfer against a **loopback fixture server**, which
  `scripts/sim-contracts` starts before launch. Its routes are derived from the clause id, and each answers a
  scripted status, body and length.
- **Recorded on `IOS_DEVICE_APP`, replayed in CI.** Only `BackgroundScheduler` is recorded, because
  `BGTaskScheduler` is expected to be unavailable on a simulator (measured as part of this change). Its calls
  go through a new `internal` seam in `:adapter:ios:app-only`, shaped like `KeychainApi`. The device run goes
  over the rig, and the recording is replayed in `iosTest`.
- **Honest fakes** in `:adapter:generic:fake`: `inMemoryBackgroundTransfer`, `inMemoryDownloadTransport`
  (both over an in-memory object server) and `inMemoryBackgroundScheduler`. `:test:world`'s
  `FakeBackgroundTransfer` and `FakeDownloadTransport` become wrappers that keep the operator levers.
- **A port-contracts rule for target-bound configuration.** An adapter whose platform configuration is fixed
  per compilation target counts as a real implementation for the clauses it runs on the target that compiles
  it. The configuration lookup, and every property only the other binding has, are not counted as covered.
  In the same rule, a fixture server that answers a transport's requests is a clause input, not the
  implementation under contract, so it does not make the binding `Fake`.
- **Stated exclusions.** Each is excluded for a reason, and none gets a fake-only clause.
  - **Background-session behaviour**: surviving suspension, the relaunch for
    `handleEventsForBackgroundURLSession`, reattaching to a prior process's tasks, and system invalidation. No
    host CI reaches exercises these, and the OS's timetable cannot replay deterministically. They stay
    documented, with their evidence and expiry, in `TransferSessions.kt` and `ios-url-session-upload`.
  - **`OsReceipt` and `BackgroundEventsReceipts`**: not ports. They are concrete classes with one
    implementation, already covered by `OsReceiptTest` / `BackgroundEventsReceiptsTest` and the
    handler-containment guard.
  - **`TransferRecord`**: its two members are clauses of `LedgerStoreContract`, which `LedgerStore` extends.
  - **`PhotoDownloadJobs`**: its only implementation is `QueuedPhotoDownloadJobs`, the project's own logic,
    covered by ordinary fake-backed tests.
  - **The completion classifier** (`classifyUrlSessionCompletion`): the adapter's own logic, covered by
    `UrlSessionOutcomeTest`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `port-contracts`: an adapter bound per compilation target counts as real for the clauses it runs there,
  without covering the lookup or the other binding's properties; a fixture server a transport clause talks
  to is a clause input, not the implementation.
- `ios-ci`: `ios-contracts` starts the loopback transfer fixture server before launching the app.
- `testing-architecture`: the app's byte transfers, apart from their background lifecycle, are asserted on
  the simulator app, and the contracts' declared reach is where the device-only surface starts.
- `harness-world-model`: the world's transfer doubles wrap honest fakes in `:adapter:generic:fake` rather
  than being levered fakes of their own.

## Impact

- **New:**
  - The three contracts, their state vocabularies and observation handles (`:test:contracts`).
  - The three honest fakes, and their bindings in `:adapter:generic:fake` `commonTest`.
  - The live bindings and registry entries in `:adapter:ios:app-only`'s rig source set.
  - The `BGTaskScheduler` seam, its device binding, and its replay test (`:adapter:ios:app-only`).
  - `test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec`.
  - The fixture server (`scripts/`).
- **Changed:**
  - `IosBackgroundScheduler`: routes through the seam.
  - `:test:world`: its transfer doubles become wrappers.
  - `scripts/sim-contracts` and the rig's contract verb: they pass the fixture base URL.
  - `ContractCoverageTest`: picks up the new clauses with no path list.
- **Adapters may change.** A clause that fails against a real adapter is fixed in the adapter
  (`port-contracts`, "A live binding binds the composition production calls").
- **Device work:** one recording session on the SE2, under the device lease.
- **Coordination:** phase 6b (`upload-job-contracts`) contributes to `BackgroundTransferContract` and does not
  create its own. Its session has been told, and has acknowledged. 6b notes that `SimulatorUploadJobQueue` has
  levers of its own, so a binding over it is `Fake`.
- **Label:** `internal`. Nothing a user sees changes.
