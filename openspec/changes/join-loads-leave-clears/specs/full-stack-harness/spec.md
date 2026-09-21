## MODIFIED Requirements

### Requirement: Operator-driven Invoke-extension cycle

Nothing SHALL auto-run: the operator plays the OS. The inspector SHALL provide a primary **Invoke
extension** action that runs exactly one extension invocation — the `process()`-shaped upload cycle
(reload config → build config → run the real cycle, via the world's runner — the cycle holds no
reconcile step; the upload ledger was loaded when the event was provisioned) **and** a
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

### Requirement: Presets rebuild a fresh world

Each inspector **preset** SHALL construct a fresh `World`, apply a short setup script, and swap it in —
with the left pane re-binding its status host to the new sources (keyed on a world generation) —
because the world is a live stateful stack (backend byte store, ledger, gallery) whose deposited state
cannot be un-set by resetting a cell. The presets SHALL be: **Clean** (nothing joined), **Enrolled**
(event provisioned with own assets present), **Fresh join** (a fresh event, own assets present,
nothing stored yet), **Re-provision (dedup)** (own assets already stored, then provisioned so the
join-time load seeds them `COMPLETED` from the per-device listing and a subsequent invoke uploads nothing
new), and **Foreign
download** (event provisioned with an injected foreign device's complete assets in the union).
Incremental controls SHALL mutate the current world in place (no world rebuild).

#### Scenario: Clean resets to an empty world

- **WHEN** the operator selects the Clean preset
- **THEN** a fresh world with nothing joined replaces the current one and the left pane rebuilds its
  status host against the new sources

#### Scenario: Re-provision dedup uploads nothing new

- **WHEN** the operator selects Re-provision (dedup) and invokes the extension
- **THEN** the already-stored assets are `COMPLETED` in the ledger as soon as the preset's provision
  returns — the join-time load seeded them, before any invoke — and the invoked cycle creates no new upload
  job

#### Scenario: Incremental edits keep the world

- **WHEN** the operator adds a gallery asset after selecting a preset
- **THEN** the current world is mutated in place (not rebuilt) and the change is reflected after refresh
