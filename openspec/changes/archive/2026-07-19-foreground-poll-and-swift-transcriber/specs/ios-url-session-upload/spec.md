# ios-url-session-upload — delta for foreground-poll-and-swift-transcriber

## MODIFIED Requirements

### Requirement: Pump triggers an in-process status refresh after each cycle

On the app-driven tier the pump SHALL, after **each** `UploadCycle.run()` (it runs in the main
app process), trigger an in-process status refresh — a re-read of the ledger
counts (`LedgerCountsSource.refresh()`, per `sync-status`) — so foreground upload status moves at
cycle granularity, not only at the foreground-gated poll's cadence. (The cross-process Darwin
liveness notification this requirement used to contrast against is deleted on every tier —
migration step 12; the poll in `sync-status` is the cross-process mechanism's replacement, and
this pump-side refresh stands beside it unchanged.) The refresh SHALL be a fire-and-forget side
effect that does not alter the pump's single-flight cycle behavior or its `PROCESSING` re-arm.

#### Scenario: A completed pump cycle refreshes status in-process
- **WHEN** an app-driven `UploadCycle.run()` completes (any result)
- **THEN** the pump triggers the in-process ledger-counts refresh, and posts no cross-process
  notification

#### Scenario: The refresh does not disturb the cycle scheduler
- **WHEN** the in-process refresh runs after a cycle
- **THEN** the pump's single-flight behavior and `PROCESSING` re-arm are unaffected
