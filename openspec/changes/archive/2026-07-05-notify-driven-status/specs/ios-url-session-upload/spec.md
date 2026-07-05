## ADDED Requirements

### Requirement: Pump triggers an in-process status refresh after each cycle

On the app-driven tier the upload pump runs **in the main app process**, so it SHALL NOT use the
cross-process Darwin notification (that is the separate-process PhotoKit tier's mechanism). Instead,
after **each** `UploadCycle.run()` the pump SHALL trigger the **same** in-process status refresh the
extension notification triggers on the PhotoKit tier — a re-read of the ledger counts
(`LedgerCountsSource.refresh()`, per `sync-status`) — so foreground upload status moves live without a
cross-process hop. The refresh SHALL be a fire-and-forget side effect that does not alter the pump's
single-flight cycle behavior or its `PROCESSING` re-arm.

#### Scenario: A completed pump cycle refreshes status in-process
- **WHEN** an app-driven `UploadCycle.run()` completes (any result)
- **THEN** the pump triggers the in-process ledger-counts refresh, and issues no cross-process Darwin
  post

#### Scenario: The refresh does not disturb the cycle scheduler
- **WHEN** the in-process refresh runs after a cycle
- **THEN** the pump's single-flight behavior and `PROCESSING` re-arm are unaffected
