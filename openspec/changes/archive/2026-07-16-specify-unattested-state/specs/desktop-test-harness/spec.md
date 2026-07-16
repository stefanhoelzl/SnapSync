## MODIFIED Requirements

### Requirement: Unattested preset

The control panel SHALL provide a preset that forges the joined layer's **unattested** health
(`SyncHealth.Unattested`, capability `sync-status-screen`) via an injected `MutableAttestedSource`, by
forcing config present + permission granted and setting the attestation cell to **unattested** — not
by fabricating the health value, so the real reduction and its precedence are exercised.

Because `!attested` outranks the sync states in the reduction, a stuck attestation cell would silently
mask every subsequently-forged sync state; therefore every other precondition-forcing preset (sync,
not-started, join, switch) SHALL force the attestation cell back to **attested**, the same discipline
by which sync presets already force permission-granted and config-present.

#### Scenario: Forcing the unattested state
- **WHEN** the operator selects the unattested preset
- **THEN** the joined layer's status line renders the cannot-verify-device attention state

#### Scenario: Presets reset the attestation cell
- **WHEN** the operator selects the unattested preset and then activates a sync preset
- **THEN** the forged sync mood is shown, because the sync preset forced the attestation cell back to attested
