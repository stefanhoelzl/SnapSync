## Why

`DiagnosticsReporter` is the one port whose failures have already reached production unseen:
- a DSN truncated to `https:` shipped four mute TestFlight builds;
- the first real attribution event went out with no `process` tag, because `describeProcess` reached the
  channel before `start` had run.

Both obligations live only in the port's KDoc. The only test named for the contract
(`DiagnosticsReporterContractTest`, in `:adapter:generic:fake`) runs against the fake alone. `port-contracts`
forbids exactly that: "A clause reachable only by a fake SHALL NOT exist".

The fake has also drifted from the real adapter, and nothing notices. `InMemoryDiagnosticsReporter.start()`
marks itself started on an unconfigured build, where `SentryDiagnosticsReporter.start()` does nothing at all.

The obstacle the design session first named, that dev builds carry no DSN so the SDK never starts, does not
hold up. It was measured on 2026-09-23 against sentry-kmp 0.27.0 / sentry-cocoa 8.58.2 on an iOS 26.5
simulator:
- the real SDK, running in the unentitled Kotlin/Native test executable (`IOS_SIM_KEXE`), delivers
  gzip-encoded envelopes to a plain-`http` DSN on `127.0.0.1`;
- the adapter's own scrub, the SDK's per-install `user.id` and the replace-not-accumulate context are all
  readable on the wire.

## What Changes

- **A new port contract, `DiagnosticsReporterContract`, in `:test:contracts`.** It has three states:
  - `UNCONFIGURED`: the build carries no reporting configuration.
  - `CONFIGURED`: a reporting destination exists.
  - `CONFIGURED_ON_THE_WIRE`: the same, plus the obligations that are only visible in what actually leaves
    the process. These are automatic capture through the logging seam, the scrub and its `user.id`
    exception, idempotence shown as "one error log, one event", and the latest process account riding
    later events.

  The subject is the port plus an observation handle, because "the channel is running" and "what was
  delivered" are outcomes the port cannot return.
- **Live binding on `IOS_SIM_KEXE`**, beside the adapter in `:adapter:ios:ext-safe` `iosTest`:
  - `UNCONFIGURED` is entered with the **production default**. The test executable has no
    `Deployment.plist`, so the host's own lookup answers "no DSN".
  - The configured states inject a DSN pointing at a **loopback ingest fixture** in the same test
    executable. The fixture is a minimal HTTP listener with no Ktor, so the `ktor-server-*` withholding in
    `module-architecture` is untouched.
- **Fake binding** in `:adapter:generic:fake` `commonTest`, on `JVM` and `IOS_SIM_KEXE`. It reaches
  `UNCONFIGURED` and `CONFIGURED` and declares `CONFIGURED_ON_THE_WIRE` unreachable: the fake transmits
  nothing.
- **`SentryDiagnosticsReporter` gains an `internal` constructor taking the DSN.** The public no-arg
  constructor passes `bakedSentryDsn()`, so both shells are unchanged. `internal` means only the adapter's
  own module, its tests included, can arm reporting with a value it chose.
- **A test-only reset of the adapter's process-wide state.** `processStarted` becomes resettable through an
  `internal` function. The binding also:
  - closes the SDK;
  - wipes the SDK's envelope cache. Measured: a rejected envelope persists there across process launches
    and blocks every later event.
  - restores Kermit's writers.
- **Fake correction:** `InMemoryDiagnosticsReporter.start()` is inert on an unconfigured build, as the real
  adapter is. Its unused `described` cell is removed.
- **Removed:** the fake-only `DiagnosticsReporterContractTest`. Its two assertions become clauses that both
  bindings run.
- **Not a deployment.** No test deployment carries a DSN:
  - the renderer emits `sentryDsn` only on the `release` channel;
  - the test executable has no bundle to carry one;
  - weakening either would erode the off-switch `deployment-configuration` exists to guarantee.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `port-contracts`:
  - "Every clause runs against a real implementation on some host" extends the injected-location rule to an
    injected **build configuration value**. The adapter still counts as real, and the lookup it replaces is
    not counted as covered.
  - "Hosts are a closed set of what changes reachable states" states that an endpoint a binding stands up
    **only to receive and observe** what the implementation transmits is part of the observation, not a
    stand-in service, so it does not make the binding `Fake`.
  - The Purpose cites this change's decision record.

`crash-reporting` is touched in code and not in requirements. Every obligation the contract asserts is
already stated there or in the port's KDoc. "The latest report is carried on every event this process
reports" keeps its crash-survival scenario as a documented belief, because no host can crash and relaunch
inside a clause. `deployment-configuration` is deliberately untouched (see above).

## Impact

- **Code:**
  - `:test:contracts` commonMain: the contract, its state enum, the observation-handle interface, and a
    received-event vocabulary.
  - `:adapter:ios:ext-safe`:
    - iosMain: the internal DSN constructor and the start-flag reset.
    - iosTest: the ingest fixture and the live binding.
  - `:adapter:generic:fake`:
    - main: the `start()` fix and the `described` removal.
    - commonTest: the fake binding, replacing `DiagnosticsReporterContractTest`.
- **Unchanged:** `:test:world`. It builds the fake configured, so its `diagnosticsStarted` observation keeps
  its meaning.
- **Gates:** `ContractCoverageTest` sees every clause reached by the live binding on `IOS_SIM_KEXE`. It is a
  CI host, so no recording is needed.
- **CI:** runs in the existing `ios-test` job (`iosSimulatorArm64Test`). No new job, no new host, no rig
  change.
- **Never contacted:** the operator's Bugsink instance. Every configured clause sends to the loopback
  fixture.
- **Label:** `internal`.
