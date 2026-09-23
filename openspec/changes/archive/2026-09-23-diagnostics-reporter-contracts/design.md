## Context

`DiagnosticsReporter` (`:domain` `ports/`) has one real seat, `SentryDiagnosticsReporter` in
`:adapter:ios:ext-safe`, over sentry-kmp 0.27.0, which pins sentry-cocoa 8.58.2. Its one double is
`InMemoryDiagnosticsReporter` in `:adapter:generic:fake`, which the world composes configured and observes
through `diagnosticsStarted` / `diagnosticsSent`.

The port's obligations are stated in its KDoc and in `crash-reporting`:
- `start` is idempotent;
- an unconfigured build makes `start`, `send` and `describeProcess` complete no-ops;
- `describeProcess` starts the channel itself;
- the latest process account replaces the earlier one and rides every later event;
- automatic capture rides the logging seam that `start` installs.

Only two of these are asserted, and only against the fake, by `DiagnosticsReporterContractTest`. The SDK is
exercised in three places, none through the port:
- `ScrubExemptionSdkTest` and `SentryLogWriterTest` initialise Sentry with an unroutable DSN and a
  `beforeSend` that drops every event;
- `DumpScrubExemptionTest` reads source.

The handoff for this phase assumed a DSN could only arrive through a test deployment. That does not hold:
- `resolve-deployment.py` writes `sentryDsn` into `Deployment.plist` only when `channel == "release"`;
- the Kotlin/Native test executable has no bundle to carry a `Deployment.plist` at all.

**Measured on 2026-09-23** (throwaway probe; macOS runner; iOS 26.5 simulator; `IOS_SIM_KEXE`; sentry-kmp
0.27.0 / sentry-cocoa 8.58.2; a Python fake ingest on the host's loopback):

| # | Observation |
|---|---|
| M1 | The SDK delivers to `http://<key>@127.0.0.1:<port>/1` as `POST /api/1/envelope/`, user agent `sentry.cocoa.kmp/0.27.0`. Plain `http` to the loopback literal needs no ATS exception, as `upload_scheme` already recorded for the app. |
| M2 | Every body is **gzip**-encoded and carries `Content-Length`. Envelope items can include `client_report` alongside `event`. |
| M3 | Through the adapter's own `scrubbedEvent` as `beforeSend`, `reconcile(<uuid>) failed` arrived as `reconcile(‹uuid›) failed`, and the event carried a UUID-shaped `user.id`, unredacted. |
| M4 | Two `setContext("process_metrics", …)` calls on the global scope: the event carried only the second, and a key present only in the first was gone. |
| M5 | `Sentry.isEnabled()` is `false` before `init`, `true` after, and `false` after `close()`. A re-`init` after `close()` starts with an empty global scope. |
| M6 | Delivery latency is not reliable. Usually 15–150 ms after capture. On the first run after a fresh build (3 of 3 times) it stalled 8–26 s, and once `SentrySDK.flush(10.0)` returned about 10 s **before** the event arrived. |
| M7 | A `413` does not drop the envelope. The SDK re-sent it on every later send trigger, delivered nothing queued behind it, and the envelope persisted in `Caches/io.sentry` **across three separate test processes**. Deleting that directory before `init` cleared it. |
| M8 | Compression hides size. A 1.5 MB event went over the wire as 3.8 KB, so a size cap has to be judged on the decoded body. |
| M9 | (task 1.1, same setup, listener **inside** the test executable) A POSIX listener on `127.0.0.1:0` in `test.kexe`, accepting on an `NSThread`, receives the real SDK's envelopes. Foundation's `NSDataCompressionAlgorithmZlib` (raw DEFLATE) inflates the gzip body once its 10-byte header and 8-byte trailer are stripped. Delivery took 157, 95 and 20 ms for three consecutive captures. |
| M10 | (task 1.2) In `test.kexe`, `NSBundle.mainBundle.bundleIdentifier` is `null` (bundle path: the `debugTest` output directory). The adapter therefore sets no `process` tag on this host. |

## Goals / Non-Goals

**Goals:**
- Every obligation of the port that a host can exercise becomes a clause run against the real adapter and
  the fake.
- The fake's divergence from the adapter on an unconfigured `start` is caught, and fixed.
- The operator's Bugsink instance is never contacted by a test.

**Non-Goals:**
- **Crash survival:** "the report rides a crash delivered on a later launch" needs a fatal event and a
  relaunch inside one clause, which no host offers. It stays a documented belief in the adapter's KDoc
  (`port-contracts`: "A belief about the platform that no host can exercise belongs in the adapter's
  documentation with its evidence").
- **Whether production Bugsink's `413` also blocks the queue (M7).** Establishing that means sending to the
  real instance. It is recorded under Open Questions as a follow-up and not decided here.
- **`ProcessMetricSource`** (phase 8c): `describeProcess` takes a `ProcessMetricReport` value, so this
  contract needs nothing from that port.
- **Retiring `ScrubExemptionSdkTest` / `SentryLogWriterTest`.** They pin SDK and writer details (level
  mapping, the entry-point tag, scope-tag ordering) below the port. The overlap is noted, not resolved.

## Decisions

### D1. The DSN is injected through an `internal` constructor value, not a deployment and not a lambda

`SentryDiagnosticsReporter` gains `internal constructor(dsn: String?)`. The public no-arg constructor
delegates with `bakedSentryDsn()`, and `isConfigured` / `start` read the stored value.

**A plain value is enough.** `deploymentValues` is a lazily cached map of a file that cannot change under a
running process, so reading it once at construction is the same as reading it per call.

**`internal` is the containment.** `:app:ios` and `:app:ios:extension` cannot name the constructor. Only the
module itself, its `iosTest` included, can arm reporting with a value it chose.

**This follows the precedent `port-contracts` already set for storage.** A real adapter with its location
injected counts as a real implementation, and the single lookup it bypasses (`bakedSentryDsn()`) is not
counted as covered. That lookup's truth is already guarded where it matters: `ios.yml` reads `sentryDsn`
back out of both archived bundles.

Rejected:
- **A test deployment.** It would mean either a `release` channel, which also flips `apnsEnv` and
  `sentryEnvironment` to production, or weakening the renderer's "absence is the off-switch", and the test
  executable still has no bundle to carry it.
- **A rig-written plist override.** It races the lazy cache and is a production-shaped route to arming
  reporting.

### D2. `UNCONFIGURED` is entered with the production default

The live binding enters `UNCONFIGURED` by calling the **public** constructor. The test executable has no
`Deployment.plist`, so the host's own lookup answers `null`, which is the same shape as `port-contracts`'
"An unavailable container is the host's own answer". This means the unconfigured clauses do cover the
lookup's absent branch, and no binding passes the absence itself.

### D3. Three states, split by what the fake models

| state | meaning | fake | live |
|---|---|---|---|
| `UNCONFIGURED` | the build carries no reporting configuration | ✓ | ✓ (production default) |
| `CONFIGURED` | a destination exists; obligations visible at the port's surface and its running state | ✓ | ✓ (DSN → ingest) |
| `CONFIGURED_ON_THE_WIRE` | the same system; obligations visible only in what left the process | ✗ | ✓ (DSN → ingest) |

The fake answers `Unreachable` for `CONFIGURED_ON_THE_WIRE`: "the fake transmits nothing; what leaves a
device is the SDK's serialization and the adapter's scrub".

**Why not one configured state:** the fake would then have to model automatic capture, the scrub and SDK
serialization, which amounts to a second Sentry. No world test depends on any of that, and a fake that
imitated it would be licensed only by imitating.

**Why not drop the fake from `CONFIGURED`:** the world composes the fake configured and reads its `started`
and `sent` cells. Those are exactly the behaviours `CONFIGURED` licenses.

### D4. The observation handle: channel state and delivered events

The subject is `DiagnosticsReporterSubject(port, observe)`. `observe: DiagnosticsObservation` offers:
- `channelRunning: Boolean`
  - live: `Sentry.isEnabled()` (M5);
  - fake: its `started` cell.
- `suspend fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent>`. It polls what
  has left the process until the predicate holds, or throws `WaitExpired` (→ `NotWithin`) after a bounded
  deadline of 45 s: a margin over M6's worst 26 s, and inside `runTest`'s one-minute timeout, which would
  otherwise pre-empt the expiry with its own error.
  - live: events the ingest decoded;
  - fake: its `sent` dumps mapped to `DeliveredEvent`s.
- `DeliveredEvent` is a neutral projection: `message`, `tags`, `userId`, `processAccount: Map<String,
  String>?`, `isDump`. The binding maps adapter vocabulary onto it (the `process_metrics` context name, the
  dump's `non-redacted` tag and contexts), so no clause names Sentry.

These are **outcomes** (the channel running, events landed), never a record of which SDK call ran, as
`port-contracts` requires of an observation handle.

**Negative assertions use a sentinel barrier.** Examples: "exactly one event", or "a warning alone sends
nothing". After the stimulus, the clause emits a sentinel error and waits until the sentinel is delivered,
then judges everything delivered before it. The probe saw delivery in capture order. This avoids a sleep,
and M6 rules out trusting `flush`.

### D5. Clause list

| id | state | asserts |
|---|---|---|
| `UNCONFIGURED_IS_NOT_CONFIGURED` | UNCONFIGURED | `isConfigured` is false |
| `UNCONFIGURED_START_IS_INERT` | UNCONFIGURED | after `start()` ×2, the channel is not running |
| `UNCONFIGURED_SEND_IS_INERT` | UNCONFIGURED | `send(dump)` returns and the channel is not running |
| `UNCONFIGURED_DESCRIBE_IS_INERT` | UNCONFIGURED | `describeProcess(r)` returns and the channel is not running; its guarantee to start does not override the no-op rule |
| `CONFIGURED_IS_CONFIGURED` | CONFIGURED | `isConfigured` is true, and the channel is not running before anything asks |
| `CONFIGURED_START_RUNS_CHANNEL` | CONFIGURED | after `start()` ×2, the channel is running |
| `CONFIGURED_DESCRIBE_STARTS_CHANNEL` | CONFIGURED | `describeProcess(r)` with no prior `start` leaves the channel running (the SNAPSYNC-38 ordering) |
| `CONFIGURED_DUMP_IS_DELIVERED_VERBATIM` | CONFIGURED | `send` of a dump whose note quotes a UUID delivers one dump carrying that note unredacted |
| `WIRE_ERROR_LOG_IS_ONE_EVENT` | ON_THE_WIRE | `start()` ×2, one Kermit `Error`: exactly one event with its message before the sentinel (a duplicated writer would double it) |
| `WIRE_WARNING_IS_NOT_AN_EVENT` | ON_THE_WIRE | a Kermit `Warn` alone delivers nothing before the sentinel, and rides the sentinel as a breadcrumb |
| `WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED` | ON_THE_WIRE | a UUID in an `Error` message and in a preceding breadcrumb arrives redacted |
| `WIRE_INSTALL_ID_IS_KEPT` | ON_THE_WIRE | an automatic event carries a UUID-shaped `user.id`, unredacted (the deliberate exception) |
| `WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS` | ON_THE_WIRE | `describeProcess(r1)`, `describeProcess(r2)`, then an event: it carries r2's fields and none of r1's own |

**Dropped after M10:** `WIRE_DESCRIBE_FIRST_TAGS_THE_PROCESS` (an event after an early `describeProcess` names
its process). The adapter reads the identity from the bundle, and the test executable has none, so the clause
has no live host. `CONFIGURED_DESCRIBE_STARTS_CHANNEL` carries the ordering guarantee on its own, because
`start` sets the tag before anything else can report on a host that has a bundle. The contract's KDoc and the
adapter's record this.

The contract code is the specification of these clauses, and this table is its design-time sketch. Clause
inputs are derived from the clause id: log messages, notes and report field values.

### D6. The ingest fixture: an in-process loopback listener, no Ktor

`LoopbackIngest` lives in `:adapter:ios:ext-safe` `iosTest`, beside the binding. It:
- binds `127.0.0.1:0` with POSIX sockets, and the binding builds the DSN from the assigned port;
- accepts on a background worker;
- reads the request line, headers, and a `Content-Length` body (M2);
- inflates the gzip body with Foundation's raw-DEFLATE `zlib` algorithm after stripping the gzip framing,
  refusing a member with optional header fields rather than misreading it (M9);
- splits the envelope into newline-delimited JSON items, keeping `event` items and ignoring `client_report`
  (M2) and any other type;
- answers `200`, or **`413` without recording** when the decoded body exceeds 1 MiB (M8). Bugsink's measured
  cap is applied to the decoded size, so no clause can pass here with a payload production would reject.

Why in-process, over the alternatives:
- **Rig route on `IOS_SIM_APP`:** it needs an `xcodebuild`, a simulator install and the rig for a port that
  needs no bundle, no grant and no entitlement. Failures would surface only in `ios-contracts`, and the SDK
  would run inside the live app's process.
- **A Mac-side server started by Gradle:** an out-of-process fixture in another language, with a task
  lifecycle and a port handed to `simctl` children through environment variables.
- **Ktor server in a test source set:** `ktor-server-*` is withheld to `:test:rig` by the module set.
  Widening that is a `module-architecture` change bought for one route and one client.

The fixture is **not** a stand-in for the service behind the port (D7), and it models none of Bugsink's
behaviour beyond the size cap.

### D7. The fixture does not make the binding `Fake`

`port-contracts` says an external service a binding reaches is part of the implementation under contract,
and that a stand-in service makes the binding `Fake`. That rule exists for ports whose obligations are **the
service's answers**, such as a backend's refusal or an echoed window.

This port's obligations are about what the **client** does:
- whether the channel starts;
- what it emits;
- what it scrubs.

No clause asserts anything the ingest decides. The ingest only receives, and the binding reads it to
observe. So it is part of the observation handle, and the binding stays `Live`. The `port-contracts` delta
states this, so the next contract does not re-argue it.

### D8. Per-clause isolation of process-wide state

The adapter and the SDK are process-global:
- `processStarted` is a file-level `var`;
- `Sentry.init` configures a shared hub;
- `Logger.addLogWriter` appends to Kermit's global writer list.

Other tests in the same executable also initialise Sentry.

Before creating each subject, the live binding:
1. calls `Sentry.close()`;
2. deletes `Caches/io.sentry`, so a rejected or undelivered envelope from an earlier clause, test or run
   cannot block or leak into this one (M7);
3. resets `processStarted` through a new `internal fun resetProcessStart()` in iosMain;
4. restores Kermit's writers to the list it found.

Its `dispose` stops the ingest and repeats steps 1 and 2.

`resetProcessStart` is the only test affordance in production code. It is `internal`, it changes nothing a
shell can reach, and without it "every clause receives a fresh instance" cannot hold for an adapter whose
idempotence is process-wide by contract.

### D9. The fake is fixed to the adapter's behaviour and trimmed

- `start()` flips `started` only when `isConfigured`. This is the divergence `UNCONFIGURED_START_IS_INERT`
  catches.
- The `described` cell and its factory parameter are removed. Only the retired test used them, and
  `processAccount` is asserted on the wire, where the fake does not go.
- `describeProcess` keeps calling `start()` first, which is what makes `CONFIGURED_DESCRIBE_STARTS_CHANNEL`
  pass on the fake.
- The world is unaffected: it builds the fake with `isConfigured = true`.

## Risks / Trade-offs

- **[Resolved, M9] A listener and the SDK in one test executable.** It works, so the Gradle-launched
  fallback is not needed.
- **[Resolved, M10] The kexe has no bundle identifier.** `WIRE_DESCRIBE_FIRST_TAGS_THE_PROCESS` is dropped
  (see D5).
- **[Risk] First-run delivery stalls (M6) slow `ios-test`.** → The deadline is 45 s per wait. Only the first
  clause on a fresh build pays the stall. The sentinel barrier means no clause sleeps a fixed time.
- **[Trade-off] The fixture is hand-written HTTP.** It serves one client, one route, loopback only, and a
  fault in it shows up as `NotWithin` or `Failed` on the live binding rather than as a silent pass.
- **[Trade-off] Wire clauses assert through the SDK's current envelope shape.** An SDK upgrade that changes
  it fails these clauses. That is the intent: it is the same silent-degradation class `ScrubExemptionSdkTest`
  exists for.

## Open Questions

- **Does production Bugsink's `413` block the queue the way M7 does?** If it does, one over-cap event mutes
  a device's reporting across launches until the cache evicts it. Dumps are bounded near 700 KB today, but
  nothing enforces that bound. Establishing it needs the real instance, or a reading of sentry-cocoa's
  transport against Bugsink's actual response. That is a separate change for the operator to schedule, not
  part of this one.
- **A delivery that never happens reads `NotWithin`, and `NotWithin` fails no run.** Found during apply:
  `verify` fails only on `Failed` or `Diverged`, and `ContractCoverageTest` counts clauses by declaration. So a
  regression that stopped delivery outright would leave the wire clauses timing out and CI green. The deadline
  is 45 s, inside `runTest`'s one-minute timeout, so an expiry at least reads as the contract's own outcome.
  Whether a live `NotWithin` on a CI host should fail the run is a `port-contracts` mechanism question for a
  later change, and it affects every contract. It is not decided here.
