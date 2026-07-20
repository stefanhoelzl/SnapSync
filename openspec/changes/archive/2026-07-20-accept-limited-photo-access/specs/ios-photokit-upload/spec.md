# ios-photokit-upload — delta

## ADDED Requirements

### Requirement: The OS does not invoke the extension under a limited grant

The OS-driven tier SHALL be treated as **unavailable** while the containing app holds a partial
(`.limited`) photo grant. Forcing proof (measured on device, SE2 / iOS 26.5, 2026-07-20 — decision
record `PROBE-FINDINGS.md` on the change branch): with real pending work and the extension re-registered
twice under `.limited`, the OS issued **zero** `process()` invocations over 22 minutes, then invoked the
extension **within seconds** of the grant returning to full — registration succeeds and lies, with no
error and no callback. Expiry trigger: re-evaluate at the iOS 27 GM re-assessment (~Sept 2026, the
existing `PHBackgroundResourceUploadJobExtension` trigger) — the constraint MUST be re-measured against
the async protocol before assuming it persists.

Consequently, under `LIMITED` the upload arm SHALL NOT start this tier's producer — it starts the
app-driven producer instead (capability `upload-lifecycle`) — and the arm's `stop()` of this producer
(the disable→enable registration toggle's disable side) SHALL be what deregisters the extension when a
membership's mechanism switches away from it. A `LIMITED` membership relying on this tier would be a
silent no-op: the screen would sit at "Synchronization pending…" indefinitely, which is exactly the
failure mode this requirement exists to prevent.

#### Scenario: A limited grant never waits on the extension
- **WHEN** photo access is `LIMITED` and an upload-inclusive membership has pending work
- **THEN** no upload waits on a `process()` invocation — the work runs on the app-driven mechanism

#### Scenario: Switching to limited deregisters the extension
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while this tier's producer is started
- **THEN** the producer is stopped (deregistering the extension) before the app-driven producer starts
