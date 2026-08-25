## MODIFIED Requirements

### Requirement: Unattested preset

The control panel SHALL provide a preset that forges the joined layer's **unattested** health
(`SyncHealth.Unattested`, capability `sync-status-screen`) by injecting its own **attestation-health
cell** in place of the live one — a plain mutable `Boolean` read-model, the same shape production
publishes — and forcing config present + permission granted while that cell reads **unattested**. Not
by fabricating the health value, so the real reduction and its precedence are exercised.

The panel forges an **input**, and this requirement SHALL name no production type. It once named the
injected implementation class outright; that class was later deleted, the name outlived it here, and
this requirement went on describing something the tree no longer contained — in a spec the deleting
change never opened. What the harness needs of the seam is only that it be writable and that the
container read it. How the trust feature publishes the same fact is that capability's business, and
naming its type here buys this capability nothing but a way to go stale. (Which type it was is in the
decision record; repeating it here would re-create the problem, and would trip the archive's dead-type
gate on every future deletion.)

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

