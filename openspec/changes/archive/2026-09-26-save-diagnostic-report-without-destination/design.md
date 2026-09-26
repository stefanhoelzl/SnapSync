## Context

The hidden bug report (capability `privacy-security`) assembles one dump — the operator's note, the sync state, the
counts, and the tails of both processes' logs — and hands it to the crash-reporting channel. Until now the command
did not exist on a build with no reporting destination (no DSN baked into `Deployment.plist`): `compose/` set
`sendDiagnostics` to `null`, the screen wired no gesture, and the control channel answered `409`. The reasoning was
that a build that can send nothing must not offer an affordance suggesting it can.

Phase 11e moved the channel behind the thin `CrashReporter` port, with every decision in `:domain:services`'
`CrashReporting` — including whether this build reports. That is the natural place to decide what a report does
when there is nowhere to send it.

## Goals / Non-Goals

**Goals:**
- The gesture and the sheet exist on every build; the sheet never suggests a destination the build does not have.
- Without a destination the report is kept on the phone, bounded, where a developer can pull it.
- Distributed builds are unchanged, byte for byte on the wire.

**Non-Goals:**
- Reading a saved report back in the app, or uploading it later when a destination appears.
- A control-channel route that returns the saved report (a developer pulls the app's Documents as for `debug.log`).
- Any change to automatic crash reporting: a development build still reports nothing anywhere.

## Decisions

**D1 — Where the report is kept: one file in the app's own Documents, replaced by each report.** The `PRIVATE` file
area, as `diagnostic-report.json`. Documents is what the device tooling already pulls (`pymobiledevice3 apps pull`,
`simctl get_app_container … data`), next to `debug.log`. One file, replaced, is bounded by construction (a dump is at
most ~720 KB, `DIAGNOSTIC_LOG_BUDGET_BYTES` plus small sections) and needs no directory listing, which the `Files`
port does not offer.
- *Rejected:* one file per report — unbounded, or a retention rule over a listing the port lacks.
- *Rejected:* the App-Group (`SHARED`) area — the extension never reads it, and it is not USB-pullable.

**D2 — What the file holds: the same five sections the channel would send, as JSON.** `note`, `state`, `ledger`,
`app_log`, `ext_log` — the contexts `diagnosticDumpEvent` builds — so a saved report reads like a received one.
Identifiers stay intact, as in a sent report; the file never leaves the phone.

**D3 — Who decides: `CrashReporting.sendDump`.** With a DSN it sends, as today. Without one it writes the file through
the process's `Files` and answers a new `DumpResult.Saved`; a refused write answers `NotSent` with the reason, which
the command logs. `ProcessPorts` already carries `Files`; the service takes it.

**D4 — Where a report goes is a fact the screen reads, not a branch the screen makes.** The sheet's body and button
depend on the destination, so `UiState` carries it (`ReportDestination.DEVELOPER` / `THIS_DEVICE`), set once from
the process's crash reporting. The `sendDiagnostics` command is no longer nullable: every composition wires it.
- *Rejected:* keeping the command nullable and adding a second command for saving — two commands for one tap, and a
  screen that decides which to fire.

**D5 — The control channel's `409` goes.** `/user/sendDiagnostics` always runs the command; on a build without a
destination it saves, as the sheet does.

## Risks / Trade-offs

- [A saved report holds identifiers on the device] → the device's own `debug.log` holds the same lines verbatim
  already; the file adds nothing that was not on the phone, and nothing sends it.
- [A developer takes a saved report for a sent one] → the sheet says "saved on this device" and the button reads
  Save; the command logs `Saved(<path>)`.
- [The file grows past the budget] → it is written from the same bounded dump the channel sends, so it cannot.
