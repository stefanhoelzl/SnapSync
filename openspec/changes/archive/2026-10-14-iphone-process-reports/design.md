# Design

## Context

See proposal.md for why. The state this design starts from (`origin/main` `b28ded3`):

- **The rule and its account exist and are unit-tested.** `processMetricEmissions` (`model/ProcessMetrics.kt`) crosses
  on two things only: a non-zero, non-normal exit tally under `applicationExitMetrics.*` (a background memory-pressure
  exit only above a 150 MB suspended footprint), and a hang bucket ending at or above 1 s. `ProcessAccount.handle` sets
  the `process_metrics` context — the report's fields, `crossing.reasons`, `FootprintTrail.fields()` — and captures one
  event per crossing. Both are no-ops without a DSN. `OnMetricReport` is an `InlineEntryPoint`, run on the provider's
  thread (the lane law's MetricKit exception, `docs/architecture.md`).
- **No integration test reaches it.** `JvmApp` composes `processMetrics = NoProcessMetrics`, so `OnMetricReport`'s body
  is `:domain:entrypoints`' one missed line (held at 99) and `ProcessMetricsKt` is `:domain:model`'s largest miss (0 of
  505 instructions; the module is held at 71/40).
- **The crash reporter's double records dumps only.** `InMemoryCrashReporter.capture` runs the shaping handler and
  drops the result; `setContext` is `Unit`. Its contract binding reaches `NOT_STARTED` and `STARTED`, and answers
  `ON_THE_WIRE` / `ACROSS_A_RESTART` as unreachable — the contract's KDoc reserves `ON_THE_WIRE` for a binding that
  drives the real SDK, because "a double that imitated them would be licensed only by imitating".
- **The real channel already proves the outcome.** Bugsink's SNAPSYNC-61 (build 2369, SE2, iOS 26.6.2, 2026-10-12) is a
  `foregroundExitData.cumulativeMemoryResourceLimitExitCount` crossing whose event carried the whole report as the
  `process_metrics` context, `crossing.reasons`, and five `ownFootprint.N` readings — and no id. It also carries seven
  hangs of 280–949 ms that crossed nothing. It is the only crossing on the board.
- **The SE2 recording** (`ProcessMetrics@IOS_DEVICE_APP.rec`) holds two daily payloads, both quiet: empty exit data, no
  diagnostics.
- **3e's services** (`SurroundingsLevers.kt`) give `process-info` (`footprint(Footprint?)`, which `FootprintSampler`
  reads when the app is not in the foreground) and `crash-reporter` (`started()`, `sent()`).

## Goals / Non-Goals

**Goals:**

- Every outcome in the two new scenarios is observed from outside the app: on what the crash reporter's backend
  received, waited for on the `world` stream.
- `OnMetricReport`, `ProcessAccount` and `processMetricEmissions` run end to end under the integration suite.

**Non-Goals:**

- Any change to what the app sends, or to the rule's thresholds.
- The iOS app host's string verb `device/process-metrics` (3f/3g delete the string protocol) and any Android provider
  (⑧ decides the provider by platform fact; Android composes none, as its root does).
- A test for the development-build scenario: it is unchanged, and the privacy promise needs nothing about development
  builds. Should the no-DSN branch of `ProcessAccount` then go unmeasured, the mocks' unit suite covers it.
- Diagnostics payloads (MetricKit's per-incident diagnostics): the app subscribes to metric payloads only.

## Decisions

### D1. The crash reporter's double records automatic events, licensed by the on-the-wire clauses it can model

`InMemoryCrashReporter.capture` appends the shaped event — `handlers.onEvent(event)`, nothing when it answers `null` —
to a received-events record beside `ReceivedDumps`, and `setContext` keeps the latest fields per name; each recorded
event carries the contexts standing when it was captured. The record tells the world `WorldEvent.EventReceived`.

The licence is a new contract state, `RECEIVED`, between `STARTED` and `ON_THE_WIRE`: what the reporter's backend
received of the port's calls. Five clauses move into it from `ON_THE_WIRE`, renamed `RECEIVED_*` —
`CAPTURE_IS_ONE_EVENT`, `A_DROPPED_EVENT_LEAVES_NOTHING`, `TAGS_AND_EXCEPTION_RIDE_THE_EVENT`,
`LATEST_CONTEXT_RIDES_LATER_EVENTS`, `DUMP_IS_DELIVERED_VERBATIM`. Both Sentry bindings (iOS simulator, Android
emulator) reach it as they reach `ON_THE_WIRE`, so the same clauses still run on the real SDK; the double reaches it
and not `ON_THE_WIRE`, whose KDoc keeps "the real SDK only" for what only the SDK and adapter decide — breadcrumbs
riding, the handlers on the way out, build facts, the install id, the size caps. The double's binding records dumps
and events in one cell, so the clauses' sentinels see them in the order they left.

*Found while applying:* the plan was the double reaching `ON_THE_WIRE` and answering its other clauses unreachable
per clause; the contract runner refuses that ("the binding declares ON_THE_WIRE reachable but answered Unreachable"),
because reachability is a property of a state, not of a clause. A state of its own is that rule's honest form.

*Why:* the outcome the phase exists for — "carrying how much memory the app last used" — rides the context. Recording
only the event (licensed at `STARTED` by `STARTED_THE_HANDLERS_SHAPE_EVERY_EVENT_AND_CRUMB`) would leave it observable
only in the mocks' unit suite.
*Rejected:* (A) record the event without contexts — keeps the KDoc, loses the outcome; (C) a `STARTED` clause "a
context set before a capture rides it" — the real adapter cannot be observed doing that at `STARTED`, so it would be a
clause only the double runs.
*Check:* `ClauseCoversTest` / `AdapterCoverageTest` see the double proving `capture` and `setContext` cells it did not
before; they must still pass, and `ContractCoverageTest` must still find a real host for every clause.

### D2. The test reports are real reports, randomized once

Three fixtures, committed with the mock, each a flat dotted field map like the one `metricPayloadOf` reads:

| kind | source | what crosses |
|---|---|---|
| `QUIET` | a daily payload of the SE2 recording | nothing |
| `ENDED_ABNORMALLY` | SNAPSYNC-61's `process_metrics` context, without what the app added (`crossing.reasons`, `ownFootprint.*`) | `foregroundExitData.cumulativeMemoryResourceLimitExitCount` |
| `FROZE` | the same, its exit tally zeroed and one hang bucket moved to ≥ 1 s | `appHangAtOrAbove1000ms` |

Every value describing the phone or its use is randomized **once, at commit** (device model, OS build, region, time,
CPU, GPU, disk and network totals, memory figures, timestamps), keeping keys, units and formatting; values the rule
reads keep their meaning — the exit tally non-zero, the suspended footprint on its side of the ceiling, hang buckets
under 1 s except the one `FROZE` moves. Fixed values keep the tests reproducible. A short note beside the fixtures names
their sources and what was randomized.

*Rejected:* hand-written minimal maps — further from what an iPhone sends, and they would not exercise copying a whole
real report into the context.

### D3. `process-metrics`, a typed service with three kinds

```kotlin
@Rpc interface ProcessMetricsLevers {
    suspend fun deliver(report: MetricReportKind)          // QUIET · ENDED_ABNORMALLY · FROZE
    companion object : LeverService<ProcessMetricsLevers>(…, "process-metrics", setOf(LeverNeed.PROCESS_METRICS_MOCKED), …)
}
```

The mock (`ProcessMetricsMock`, `:adapter:generic:mock`) is the `ProcessMetrics` port's face — it holds the registered
`MetricHandlers` — and its operator implements the service by delivering the kind's fixture through `onReport`, on the
calling thread, as MetricKit delivers on its own. `JvmMocks` gains it and `JvmApp` composes it instead of
`NoProcessMetrics`. Only the JVM host has the need; the device hosts refuse it with the reason. R8 keep rules ship with
the interface; `LeverFacesTest` holds the operator to it. The JVM host's refusal of `device/process-metrics` is
re-worded to point at the service.

*Named `MetricReportKind`, not `MetricReport`:* rebasing onto `main` met the host service's `MetricReport` (what a
report fed through `host.processMetrics` crossed) in the same package.

*Rejected:* `deliver(fields: Map<String, String>)` — tests would hand-build reports, which D2 exists to stop.

### D4. What a test reads and waits on

`CrashReporterLevers` gains `received(): List<ReceivedEvent>` (`message`, `tags`, `contexts`), and the world gains
`WorldEvent.EventReceived(ReceivedEvent)`. No event for a context set: a context is only ever seen riding an event.

### D5. The tests

In `:test:integration`, `behaviour { given … whenever … then … }`, each `@Verifies(spec = "privacy-security",
requirement = "Automatic failure reports are minimal and anonymous", scenario = …)`:

1. **Ended abnormally** — given a joined *encrypted* event (`dev-controls.encryptNewEvents(true)`, its invite link
   read from the screen, which carries the key) and a footprint set through `process-info` and recorded by sending the
   app to the background; whenever `process-metrics.deliver(ENDED_ABNORMALLY)`; then an `EventReceived` whose message
   names the memory-limit reason, whose `process_metrics` context carries `ownFootprint.0` with the footprint set, and
   in which the key appears nowhere.
2. **Froze** — the same, `FROZE`, the message naming the hang reason.
3. **Quiet** — whenever `deliver(QUIET)`; then `never(EventReceived)` until settled.

## Risks / Trade-offs

- [The report runs inline on the delivering thread, which on the JVM host is the lever call's own thread] → it must
  count as an act of the operator lane; a lane violation in the first run is fixed in this change, never by moving the
  handling off the thread (that would contradict the lane law's exception it plays).
- [Reversing the `ON_THE_WIRE` stance invites doubles to claim more than they model] → the binding names each clause it
  runs; every other answers unreachable with its reason, and the KDoc states the rule (the observable reproduced, the
  same clause as the SDK) rather than a blanket permission.
- [Fixtures drift from what iOS sends] → they are re-cut from the recording and the board when either changes; the
  rule's own unit suite stays the authority on key handling.
- [The second-lander rule with ⑧] → if ⑧ lands first, the JVM host's provider becomes a platform fact; this change's
  composition is one line to fold into it.
