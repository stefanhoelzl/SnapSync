## MODIFIED Requirements

### Requirement: Operator-driven Invoke-extension cycle

Nothing SHALL auto-run: the operator plays the OS. The inspector SHALL provide a primary **Invoke
extension** action that runs exactly one extension invocation — the `process()`-shaped upload cycle
(reload config → reconcile → build config → run the real cycle, via the world's runner) **and** a
download reconcile over the event union — and then refreshes the status and download sources so the
left pane reflects the new world state. Every invocation's discovery is a full enumeration of the world's
gallery (capability `harness-world-model`), so there is no change token for the inspector to expire.

#### Scenario: Invoke runs one real process cycle

- **WHEN** the operator presses Invoke extension with an asset pending upload and its job completed
- **THEN** the real upload cycle records the asset `COMPLETED`, the download reconcile runs, and the
  left pane updates from the refreshed real sources

#### Scenario: A removed gallery asset is retracted by the next invocation

- **WHEN** the operator removes an uploaded asset from the world's gallery and then presses Invoke extension
- **THEN** the cycle's walk no longer returns it, its ledger rows are deleted, and the left pane no longer
  counts it
