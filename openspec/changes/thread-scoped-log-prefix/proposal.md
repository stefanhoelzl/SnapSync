## Why

The device log's `[<entryPoint>]` prefix is meant to trace every line to what triggered it, and it now
misattributes platform work. Measured on 2026-09-14 (build 816, `debug.log` 09:16:43): during one
292 ms MetricKit delivery, seven lines the delivery did not produce — `gallery` ×2, `Http` ×3,
`PushRegistration`, `SnapSyncRoot` — carried `[didReceiveMetricPayloads]`. They were launch-time work
with no entry point of its own, and 158 ms earlier the same kind of line carried no prefix at all.

The `diagnostic-logging` spec rules this out on a premise the measurement falsifies: that the platform
delivers entry points serially per process, so the only mislabeling possible falls on a user tap and
"never on the platform work whose trail matters most". A MetricKit delivery arrives about a second
after launch, while launch work is still running — so the spec is untrue today, independent of any fix.

## What Changes

- An entry point that completes its work **synchronously on the thread it was called on** can claim the
  ambient prefix for **that thread only**. Lines logged on other threads during the call no longer
  inherit it. A synchronous call occupies its thread, so the claim is exact by construction.
- The existing process-global claim is unchanged and stays the default, because it is what carries a
  trigger across the thread hops of asynchronous work (Ktor on the Darwin queue, SQLDelight).
- Both MetricKit callbacks (`didReceiveMetricPayloads`, `didReceiveDiagnosticPayloads`) take the
  thread-scoped claim. They are the one measured case, and they handle inline by design.
- The spec's accepted-inaccuracy statement is corrected: platform entry points are **not** serial with
  launch-time or other un-entered background work, and a process-global claim mislabels any concurrent
  work that has no entry point of its own — user taps being one instance, not the only one.
- No other entry point moves to the thread-scoped claim in this change.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `diagnostic-logging`: the "Ambient entry-point context prefix" requirement gains the thread-scoped
  claim and loses the falsified serial-delivery premise; its accepted inaccuracy is restated from the
  measurement.

## Impact

- `:adapter:ios:ext-safe` — `LogContext` resolves a thread-scoped claim before the process-global one;
  a second `LogScope` binding beside `IosLogScope` drives it. The three device-log writers read
  `LogContext.current` and need no change.
- `:adapter:ios:app-only` — the two MetricKit callbacks pass the thread-scoped binding to the existing
  two-argument `Logger.invocation(scope, …)`.
- `:domain` — untouched. `LogScope` is already a port, and the per-thread holder is a platform concern
  that belongs beside the writers, as the process-global one does.
- Depends on the unmerged `add-os-exit-attribution` work (the MetricKit callbacks exist only there), so
  this change lands after it.
