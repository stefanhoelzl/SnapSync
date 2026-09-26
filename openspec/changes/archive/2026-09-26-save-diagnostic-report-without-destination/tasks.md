## 1. The decision in services

- [x] 1.1 `CrashReporting` takes the process's `Files`; `sendDump` without a DSN writes `diagnostic-report.json` to the
      `PRIVATE` area (the five sections as JSON) and answers `DumpResult.Saved`, or `NotSent` when the write is refused
- [x] 1.2 `DumpResult.Saved(path)` in `model/`; a pure encoder for the saved report beside `diagnosticDumpEvent`
- [x] 1.3 Tests over the in-memory `Files`: saved without a DSN, replaced by the next report, nothing sent; sent
      unchanged with one

## 2. The command and the screen

- [x] 2.1 `UserCommands.sendDiagnostics` is no longer nullable; `compose/` always wires it and logs the `DumpResult`
- [x] 2.2 `UiState` carries `ReportDestination` (`DEVELOPER` / `THIS_DEVICE`), set from the process's crash reporting
- [x] 2.3 The sheet's body and confirm label follow the destination ("Send" to the error-tracking service / "Save" on
      this device); the double-tap is always wired
- [x] 2.4 Screen and host tests for both destinations; the forge and both desktop harnesses state a destination

## 3. The control channel and docs

- [x] 3.1 Retire the rig's `sendDiagnostics` refusal; a world without a DSN saves to its in-memory `PRIVATE` area
- [x] 3.2 A world test over the real composition (`World(dsn = null)`): the report is saved and nothing is sent; with a
      destination it is sent and nothing is saved
- [x] 3.3 CLAUDE.md "Sending the logs off-device" and `docs/` updated: a dev build saves the report to Documents
