## Why

A device's `debug.log` is the only un-redacted account of what SnapSync did, and today it is
reachable **over USB only**. That is fine for the device on the desk and useless for every other
one: a TestFlight tester whose photos silently never uploaded, or the operator's own phone at an
event with no cable, leaves no trace anyone can read. The crash-reporting channel already carries
failures off-device, but only failures that were *logged as errors* — the common report is "it just
didn't sync", which produces no error at all.

This adds the missing route: a deliberate, hidden gesture that sends the recent log to the operator's
Bugsink instance. Hidden because it is operator infrastructure, not a product feature — SnapSync's
screen is a glanceable status display, and a "send diagnostics" button belongs to a different app.

## What Changes

- A **double-tap on the `ScreenLayout` app-name label** (present in every UI state) opens a confirm
  dialog; confirming sends one diagnostic dump to the reporting channel. No button, no menu, no
  settings entry, and no semantics that make the label look interactive.
- The dump is **one event** carrying four contexts: `state` (build/OS/device, membership, tier,
  permission, baked upload base), `ledger` (five existing counts), and the tail of the **app** and
  **extension** logs within a fixed byte budget.
- **The extension's log moves into the App Group** (`ext-debug.log`) so the app process can read it;
  the app's own log stays at `Documents/debug.log`, so every existing pull command keeps working. A
  new dev/test launch trigger `SNAPSYNC_EXPORT_LOGS` copies the extension's log into the app's
  `Documents/` for USB access.
- The `CrashReporting` port is renamed **`DiagnosticsReporter`** and gains the send operation.
- Dump text is sent **verbatim** — an explicit, narrow carve-out from the channel's UUID scrub, which
  continues to apply unchanged to every automatic event and breadcrumb.
- **BREAKING** (operator runbook, not shipped behaviour): `pymobiledevice3 apps pull
  app.snapsync.BackgroundUpload Documents/debug.log` stops returning fresh content.

## Capabilities

### New Capabilities

None. The dump is a delivery contract over the log this project already defines, so it lands in the
capability that owns that log rather than minting a sibling that would have to keep referring to it.

### Modified Capabilities

- `diagnostic-logging`: adds the operator-initiated dump (trigger, consent, contents, byte budget,
  verbatim delivery), moves the extension's log to the App Group, and adds the USB export trigger.
- `crash-reporting`: renames the `CrashReporting` port to `DiagnosticsReporter` and gives it the send
  operation; carves the dump out of the "every UUID-shaped token is scrubbed" requirement while
  leaving that requirement in force for automatic events and breadcrumbs.
- `design-system`: `ScreenLayout` gains the optional `onTitleDoubleTap` action callback (the hidden
  affordance), so the component inventory the spec pins stays true.
- `ios-app-shell`: adds `SNAPSYNC_EXPORT_LOGS` to the launch-environment triggers. (No change to the
  composition-root requirement: its "calls only `aggregates()`" constraint is scoped to the
  `LedgerCountsSource` it names, and the dump reads through its own read-only path.)

## Impact

- `:domain` — `ports/` (port rename + send), `model/` (the dump value + `UserCommands` gains a
  nullable `sendDiagnostics`), a diagnostics assembly in `feature/`, wiring in `compose/`.
- `:adapter:ios:ext-safe` — `FileLogWriter` gains a destination, the Sentry adapter seats the send,
  a log-tail reader is added.
- `:ui:components` / `:ui:screens` — the gesture on the app-name label and the confirm dialog.
- `:app:ios` — parses the new launch trigger and performs the export copy; `:app:ios:extension`
  points its writer at the App Group.
- `:adapter:generic:fake`, `:test:world`, `:test:integration`, `:test:architecture` — the renamed
  fake, the new literal, and the coverage.
- **Runbook**: CLAUDE.md's extension-log pull instructions.
- No backend, API, storage, or entitlement change. Nothing new leaves the device without a
  deliberate, confirmed gesture.
