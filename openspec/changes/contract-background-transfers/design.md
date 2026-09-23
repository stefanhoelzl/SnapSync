## Context

This is phase 8b of the testing-concept sequence. The mechanism is phase 3's
(`changes/archive/2026-09-22-establish-port-contracts`). Phase 6 (`changes/archive/2026-09-23-photokit-contracts`)
added the `IOS_SIM_APP` host, the `ios-contracts` job and the simulator-app registry. This change applies the
mechanism to the app's own transfer surface.

Current state, verified against the tree on 2026-09-23:

- **The ports** (`domain/ports`):
  - `BackgroundTransfer`: implemented by `IosUrlSessionUploadPlatform` (`:adapter:ios:app-only`),
    `IosPhotoKitUploadPlatform` and `SimulatorUploadJobQueue` (`:adapter:ios:ext-safe`, one per target through
    `uploadJobQueue`), and `:test:world`'s `FakeBackgroundTransfer`.
  - `DownloadTransport`: implemented by `IosDownloadTransport` (`:adapter:ios:app-only`) and `:test:world`'s
    `FakeDownloadTransport`. It reports through a `DownloadTransportHost` its owner supplies.
  - `BackgroundScheduler`: implemented by `IosBackgroundScheduler` (`BGTaskScheduler`, `:adapter:ios:app-only`).
    Its only doubles are private `FakeScheduler`s inside two feature tests.
- **How the session is bound.** Both `URLSession` adapters get their configuration from
  `transferSessionConfiguration`, an `expect` whose `iosSimulatorArm64` actual is a **default** configuration.
  `ios-url-session-upload` ("The transport binding is fixed by the compilation target") records why: the
  simulator's `nsurlsessiond` rejects every third-party client. It also records a foreground control
  succeeding in the same process. Everything after the configuration lookup (the delegate, staging, the
  outcome read, `getAllTasks`, the terminal write) is target-independent.
- **Not ports**, checked and excluded:
  - `OsReceipt` and `BackgroundEventsReceipts`: concrete classes, tested directly.
  - `TransferRecord`: its members are clauses of `LedgerStoreContract`.
  - `PhotoDownloadJobs`: its only implementation is `QueuedPhotoDownloadJobs`, which is feature logic.
- **Phase 6b** (`upload-job-contracts`) is running at the same time. It has acknowledged that this change owns
  `BackgroundTransferContract`. It reports that `SimulatorUploadJobQueue` carries levers, so a binding over it
  is `Fake`, and it is recommending that the PhotoKit tier be documented rather than bound.

## Goals / Non-Goals

**Goals:**

- Contracts for `BackgroundTransfer`, `DownloadTransport` and `BackgroundScheduler`. Every clause runs against
  a real adapter on some host.
- The two `URLSession` adapters run **live** on `IOS_SIM_APP` on every push.
- `BackgroundScheduler` is recorded once on the device and replayed on every build.
- The world's transfer doubles, which every world and integration test stands on, held to the same clauses.

**Non-Goals:**

- Anything about the background session's lifecycle (suspension, relaunch, reattachment, system
  invalidation). It stays documented in `TransferSessions.kt` and `ios-url-session-upload`.
- The PhotoKit upload-job tier's bindings. That is 6b's call.
- A device recording of a `URLSession` transfer (D5).
- Any production behaviour change beyond fixing an adapter that a clause shows to be wrong.

## Decisions

### D1. One `BackgroundTransferContract`, owned here, with tier-neutral clauses

`port-contracts` allows exactly one contract per port, and `BackgroundTransfer` is one interface. So the clause
list states what **every** tier owes the cycle:

| id | state | clause |
|---|---|---|
| `CREATE_UNUSABLE_PAYLOAD` | `IDLE` | a resource payload the tier cannot upload answers `FAILED`, and nothing lands |
| `CREATE_BAD_DESTINATION` | `IDLE` | an unparsable destination answers `FAILED`, and nothing lands |
| `CREATE_LANDS_AND_RECORDS` | `IDLE` | a created job to an accepting destination lands the bytes, and its `REQUESTED` row reads `COMPLETED` within the bound |
| `CREATE_KEEPS_CONTENT_TYPE` | `IDLE` | the object lands with the request's `Content-Type` |
| `REJECTED_NEVER_COMPLETES` | `IDLE` | a destination answering `500` never records `COMPLETED`. When the row returns to `DISCOVERED` is tier-specific (the PhotoKit tier offers a free `.retry` first), so it is not asserted |
| `AT_CAP_DEFERS` | `AT_CAP` | at the tier's in-flight cap, `createJob` answers `LIMIT_EXCEEDED` and starts nothing |
| `TERMINAL_NEEDS_REQUESTED` | `IDLE` | a completion for a row that is not `REQUESTED` leaves the row as it was |
| `DRAIN_HANDS_UP_NO_SUCCESS` | `IDLE` | after a success lands, `drainTerminals` does not return that job |

The subject is the port plus an observation handle, `TransferObservations`: the object stored at a clause's
route (bytes and content type), and the ledger row state for a key. Both are **outcomes**, meaning state
reached. Neither is a transcript. The binding supplies the ledger as an in-memory `LedgerStore`, handed to the
adapter as its `TransferRecord`. That ledger is the adapter's collaborator, not the port under contract, and
`LedgerStoreContract` licenses it.

PhotoKit's single `.retry` (`fetchRetryJobs`/`retryJob`) is **not** tier-neutral, and the URLSession tier
answers it trivially, so no clause here asserts it. If 6b ever binds a real PhotoKit host, it adds those clauses
to this list.

*Alternatives:* one contract per tier, which the spec forbids; splitting the interface per tier, which would
restate the wiring to suit the tests.

### D2. `DownloadTransport` is observed through its own host

`DownloadTransportHost` is the port's declared output channel, so what it receives **is** the port's answer.
Each clause supplies a small host that keeps what it was told and answers `accepts`/`destinationFor` as the
clause chooses. Clauses assert on the values delivered and on the file at the staged path, never on call
counts.

| id | clause |
|---|---|
| `OK_STAGES_AT_DESTINATION` | a `200` body is judged with `(200, n, n)`, lands at `destinationFor`, and is followed by `onStaged(dest)` then `onCompleted(null)` |
| `JUDGED_BEFORE_MOVED` | when `accepts` is asked, nothing is yet at the destination |
| `REJECTED_STAYS_UNSTAGED` | a `404` judged `false` leaves the destination untouched (a prior file survives), and `onCompleted` still arrives |
| `UNATTRIBUTABLE_STAYS_UNSTAGED` | `destinationFor = null` stages nothing, and `onCompleted` arrives |
| `RESTAGE_REPLACES` | a second transfer to an occupied destination replaces the file |
| `NO_LENGTH_IS_NEGATIVE` | a body with no declared length reports a negative `expectedBytes` |
| `CANCEL_COMPLETES_WITH_ERROR` | `cancel` on an open transfer yields `onCompleted` with a non-null error and stages nothing |
| `UNPARSABLE_URL_IS_NULL` | `start` with an unparsable URL answers `null` and nothing reaches the host |

A short-read clause (a declared length the server does not deliver) is written only if its apply-time
measurement is deterministic. Otherwise the observed behaviour is documented on the adapter.

### D3. A loopback fixture server, started by `sim-contracts`

The transport clauses need an HTTP peer whose answer each clause chooses. `scripts/transfer-fixture.py`
(Python stdlib) serves `/<clause-id>/…` routes that answer a scripted status, body and length, hold a request
open (for `AT_CAP`), store a `PUT`'s bytes and headers for the handle to read, and log every request.
`sim-contracts` starts it on a port picked for the run and passes the base URL in the rig's contract verb
(`POST /contract/<name>?fixture=<url>`). The simulator shares the Mac's loopback, and ATS does not apply to a
connection by IP address, so `http://127.0.0.1:<port>` needs no plist change.

The route vocabulary lives in `:test:contracts` (`TransferFixture`) as a pure codec. The loopback server and
the world bindings' network both answer from it, so both hosts answer the same route the same way.

*Alternatives:*
- A route on the rig's own Ktor server. Rejected: it puts the peer inside the process under test, and only
  `:test:rig` may depend on `ktor-server`.
- The real `api/`. Rejected: production uploads go to the storage zone through presigned URLs, not to `api/`,
  and it cannot be scripted to answer `404` or a held request.

`port-contracts` gains the rule that such a server is a clause input, not the implementation, so the binding
stays `Live`.

### D4. `BackgroundScheduler`: an internal seam, a device recording, and replay

The port declares no reads, so the handle is `pendingWakes()`, the count of pending requests for the adapter's
identifier. `IosBackgroundScheduler` routes `submitTaskRequest`, `cancelTaskRequestWithIdentifier` and
`getPendingTaskRequests` through an `internal` `BackgroundTaskApi` seam in `:adapter:ios:app-only`, shaped like
`KeychainApi`. `earliestBeginDate` is a masked volatile key.

| id | clause |
|---|---|
| `SCHEDULE_ARMS_ONE` | from empty, `scheduleNext` leaves one pending wake |
| `SCHEDULE_IS_IDEMPOTENT` | `scheduleNext` twice leaves one pending wake |
| `CANCEL_CLEARS` | `cancel` after `scheduleNext` leaves none |
| `CANCEL_EMPTY_IS_QUIET` | `cancel` with none pending leaves none, and does not fail |

`EMPTY` is entered by a `cancel` through the seam, so it is recorded like any other call. The identifier
must be one in `BGTaskSchedulerPermittedIdentifiers`, so every clause uses the production heartbeat
identifier. A device run therefore ends with no pending heartbeat on a rig build, and the app's next trigger
re-arms it.

Hosts: `Fake` (JVM, `inMemoryBackgroundScheduler`); `Live` on `IOS_DEVICE_APP`, recorded; `Replay` in
`:adapter:ios:app-only` `iosTest`. `IOS_SIM_APP` is measured first. Apple documents `BGTaskScheduler` as
unsupported in the Simulator, but that is a belief until measured. If submission is refused there, its binding
declares the states unreachable, naming the measured error. If it is accepted, the binding runs live as well.

### D5. No device recording of a `URLSession` transfer

A background transfer's delegate callbacks arrive from `nsurlsessiond` on the OS's timetable. Replay matches
calls exactly and in order, and the interleaving of `getAllTasks` answers with delivered completions is not
fixed by the clause. Everything a device recording could add beyond the simulator's live run is the background
lifecycle, which the Non-Goals leave documented. Recording it would buy replay flakiness and no clause the
simulator does not already run for real.

### D6. The world's doubles are the `Fake` bindings; the binding plays the network

`FakeBackgroundTransfer` and `FakeDownloadTransport` are bound **directly**, in `:test:world` `commonTest`.
`MiniEdgeContractsTest` is the precedent. Their levers stay. The binding stands where the loopback server
stands for the live binding: after `createJob`/`start`, it answers each transfer the way the clause's route
says, through the double's own operator actions. An accepting `PUT` is `completeJob`, then `drainTerminals`.
A rejecting one is `failJob` with the `.retry` spent. A `GET` is `finish(description, outcome)`, with the
outcome built from the route. A held route is never answered. The subject the clause receives is the port, so
none of this is visible to a clause.

*Alternative, rejected:* extract honest fakes into `:adapter:generic:fake` and make the world doubles wrappers
(the phase-6 pattern). For uploads, no honest fake can be licensed for the tier the world needs. The only real
binding is the URLSession tier, while the world models the PhotoKit tier's single free `.retry` and
drain-time recording, which about 90 call sites across the world tests, the integration tests and the desktop
inspector rely on. An extracted fake would be licensed for one tier and used as the other, and the world tests
would stand on a wrapper beside it rather than on the licensed thing. Binding the doubles directly licenses
what the tests use. The world's PhotoKit-only behaviour stays uncontracted, as a tier fact with no real host
(the same call 6b makes).

`BackgroundScheduler` has no world double, so it gains `inMemoryBackgroundScheduler` in `:adapter:generic:fake`
(an `internal` class behind a port-typed factory), which replaces the two private `FakeScheduler`s where they
only stand in.

### D7. A failing clause is fixed in the adapter, after its red run

As in phase 6, a clause that fails against a real adapter is committed failing first, so the failure is on
record, and then fixed in the adapter. Reading the code, the likeliest candidates are `CREATE_KEEPS_CONTENT_TYPE`
and the cap's count after a cancelled task. Neither is confirmed.

## Risks / Trade-offs

- **[A held request must not outlive its clause]** → `AT_CAP` cancels its held tasks in the clause's cleanup,
  and the fixture's hold has a server-side timeout, so a leaked hold ends the task instead of the job.
- **[A default session caches]** → Routes are unique per clause and per attempt, and the fixture answers
  `Cache-Control: no-store`.
- **[Loopback timing on a shared CI runner]** → Waits are bounded and read `NotWithin(T)`, never `Passed`. Bounds
  are generous (a single transfer of a few KB).
- **[The upload adapter needs a real `PHAssetResource`]** → Clauses seed a photo in their own capture-date window
  through phase 6's `SeededLibrary`, under the `GRANTED` precondition the registry already enforces.
- **[The device run clears the heartbeat]** → Rig builds only. It is stated in the binding and in the
  `rig-channel` skill, and the next trigger re-arms it.
- **[The cycle records `REQUESTED` only after `createJob` returns]** (`UploadCycle`, write-after-act). A
  completion delivered before that write reaches `markTerminal` while the row is not yet `REQUESTED`, so it
  applies nothing. The row is then written `REQUESTED` with no task behind it, and nothing reconciles it. Over a
  real network the window is the gap between `resume()` and the engine's write, which is tiny; over loopback it
  is real. → Clauses seed `REQUESTED` **before** `createJob` so they stay deterministic. The race is reported for
  a separate `upload-lifecycle` change and not fixed here: fixing it changes behaviour.
- **[6b's scope may shift]** → The clause list is tier-neutral, so a later PhotoKit binding adds clauses without
  editing these.

## Migration Plan

No production behaviour changes, except adapter fixes D7 may surface. Rollback is a revert. The
`ios-contracts` job keeps its context name, so the ruleset is unchanged.

## Open Questions

- Does `BGTaskScheduler` accept a submission on the simulator app? This is measured in task 5.1, and the answer
  decides whether that host is `Live` or declared unreachable.
- Is a short read deterministic on a default session over loopback? This is measured in task 3.7, and it
  decides whether D2 gets a short-read clause.
