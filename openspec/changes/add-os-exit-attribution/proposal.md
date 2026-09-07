## Why

When the OS terminates SnapSync, nothing records **why**. `crash-reporting`'s Purpose says failures
used to be invisible and that "this capability closes that gap" — it does not close this one. A
process the OS kills leaves no Sentry event (the SDK cannot catch a `SIGKILL`), no TestFlight crash
log, no `debug.log` line (the process stops mid-line), and no Apple aggregate (`productData: []`, two
testers).

This is not hypothetical, and it is not one incident. **`SNAPSYNC-1` is the largest open issue in the
project**, and Sentry's own message states the gap:

> *"The OS watchdog terminated your app, **possibly because it overused RAM**"*

Sentry cannot tell a watchdog kill from a memory kill. Reading the board on 2026-08-29: 40 events,
which are **~7 real incidents** (14 of them are one three-minute relaunch loop) across **three
devices** over five weeks, half the board being our own dispatched builds. Every one of the 40 carries
`in_foreground: true`, because Sentry's watchdog heuristic excludes background terminations *by
construction* — so `SNAPSYNC-23`'s background kills are not under-represented there, they are absent.

MetricKit is the OS's own attribution. `MXAppExitMetric` counts exits **by reason**, split
foreground/background, and the foreground set separates exactly the two things Sentry conflates
(`cumulativeAppWatchdogExitCount` vs `cumulativeMemoryResourceLimitExitCount`). A device probe has
already measured the whole surface end to end (`PROBE-FINDINGS.md`), so this proposal rests on
measurement rather than on documentation.

A home-grown "clean shutdown marker" was considered and **rejected**: it can only say *whether* the
process died abruptly, never *why*, which is the entire question.

## What Changes

- **A new port, `ProcessMetricSource`** (`:domain:ports`) — generic over providers, yielding reports
  as open `Map<String, String>`. MetricKit is today's provider; an iOS 27 `MetricManager` Swift shim
  or a test fake can feed the same seam.
- **A MetricKit adapter** in `:adapter:ios:app-only`, armed from the app shell's own initialization
  (not `AppCore`, which is `by lazy` so a cold background wake never forces the graph). It flattens
  the platform's own `dictionaryRepresentation()` to dotted keys and reads `callStackTree` **never**.
- **A pure, tested rule** in `:domain:model` turning a report into emissions: one report line, plus a
  threshold verdict.
- **Three delivery channels**: a `debug.log` line per report (always); the latest report attached to
  the reporting channel's **global scope**, so it rides every later event *and any crash*; and one
  event per report whose threshold is crossed.
- **A new `DiagnosticsReporter` operation** to attach that report context, since the reporting SDK is
  confined to `:adapter:ios:ext-safe`.
- **A dev-only rig route** feeding a synthetic report through the same rule and channels, so the path
  is exercisable in seconds rather than waiting a day for a real payload.
- Not a behaviour change to the app: no persisted state, no `UiState` surface, no effect on upload,
  download, or membership.

## Capabilities

### New Capabilities

None. This extends existing capabilities rather than introducing one — the reporting channel,
the device log and the shell all already exist.

### Modified Capabilities

- `crash-reporting`: the primary change. Adds OS-attributed process termination as a reported
  signal, the threshold that makes a report eventful, the report context carried on the global
  scope, and the `DiagnosticsReporter` operation that attaches it. States what the channel covers
  (the app process) and what it cannot (the extension), with the reason and an expiry trigger.
- `diagnostic-logging`: adds the per-report line to the device log — the channel that exists on
  every build, including those with no reporting configuration.
- `ios-app-shell`: adds the OS integration (subscriber registration) and states that it is armed
  from process start rather than from graph assembly, because delivery is **one-shot** and arming
  without a live consumer discards a payload the OS would otherwise have held.

## Impact

**Code**

- `:domain:ports` — new `ProcessMetricSource`; new operation on `DiagnosticsReporter`.
- `:domain:model` — the report value type, its key constants, and the pure rule + thresholds.
- `:adapter:ios:app-only` — the MetricKit adapter (`NSObject` + `MXMetricManagerSubscriberProtocol`).
- `:adapter:ios:ext-safe` — the reporter seats the new context operation.
- `:app:ios` — one adapter construction in the shell's initialization.
- `:test:rig` — one `/device` route.
- `PROBE-FINDINGS.md` moves from the repo root into this change directory.

**Explicitly not touched**: the upload tiers, the ledger, download/import, membership, or any
`UiState`.

**Dependencies**: none added. MetricKit ships in the Kotlin/Native platform klibs and links without
an Xcode change — measured, `_kclass:app.snapsync.metrics.MetricKitProbe` present in the arm64 binary.

**Platform expiry**: the entire `MX*` surface is deprecated at iOS 27, and its successor
(`MetricManager`, `MetricResult`) is **Swift-only** — unreachable from Kotlin/Native. The port is the
seam across which a future Swift shim is swapped. Nothing breaks at iOS 27 (deprecated ≠ removed), but
the design names the trigger rather than discovering it later.

**Known limits, carried deliberately**: attribution says *what* killed the process, never *which
code* (no call stacks). Reports arrive on the OS's cadence — roughly daily, and always long after the
event. Extension-process exits are not attributable at all.
