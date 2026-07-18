# harness-world-model — delta for rehome-ui-modules

## MODIFIED Requirements

### Requirement: Integration tests assert UiState and world outcomes

The `:test:integration` module SHALL consume `:test:world` and `:ui:presentation` (re-homed from
`:domain:presentation` at migration step 9) to assert both the
projected `UiState` **and** world outcomes from world mutations and cycle invocations — not `UiState`
alone. World outcomes SHALL include: objects landed in the backend store (the per-device listing grows),
ledger rows reaching `COMPLETED`, and foreign photos imported into the in-memory gallery. This is the
testing-rule-3 seam ↔ UI-state integration surface, now spanning the real upload/download execution edge
rather than injected `SyncEvent`s alone, and it SHALL run on JVM and `iosSimulatorArm64`.

#### Scenario: A completed upload advances both UiState and the store

- **WHEN** an asset is added, its job created and completed, and the cycle plus a status refresh run
- **THEN** the projected `UiState` reaches `Joined(SyncHealth.InSync)` **and** the object is present in
  the per-device listing with a `COMPLETED` ledger row

#### Scenario: A foreign download imports and is observable

- **WHEN** a foreign device's complete asset is reconciled, staged, and imported
- **THEN** the imported asset is present in the in-memory gallery and (via suppression) is not
  re-uploaded, and the outcome is assertable at the store/gallery level alongside `UiState`
