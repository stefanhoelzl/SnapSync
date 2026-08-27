## ADDED Requirements

### Requirement: Foreign keys are relied upon, and their enforcement is trusted rather than asserted

The schema SHALL rely on foreign-key enforcement rather than re-checking referential integrity in
application code. Measured on this platform, `PRAGMA foreign_keys` defaults to `1` — unlike stock SQLite —
violations are rejected both on a bare statement and inside a batch, and the value persists across requests
(`PROBE-FINDINGS.md` §4.1).

That measurement is now **trusted**, not re-asserted on every deploy. State the trust and its limits
precisely, because the failure it admits is silent:

- **What it rests on.** The value was measured against the deployed store, and no code path in this
  repository can change it. Only a provisioning or engine change on the platform's side could.
- **What it would cost.** Enforcement being off disables every constraint with no error and no rejected
  write, making two staleness classes the schema is designed to make unstateable quietly reachable again.
- **What would falsify it.** A platform provisioning or engine change that alters the default. Nothing in
  the deploy path would notice; the next signal would be a data anomaly.
- **Expiry trigger.** Re-measure when the store is re-provisioned, moved, or its engine version changes.

This is a guarantee traded away, not relocated. It was traded to make the boot probe's health route a
simple statement of reachability rather than a carrier of store diagnostics; if that trade proves wrong,
the assertion returns as its own change with its own argument.

#### Scenario: A dangling reference is rejected

- **WHEN** a write inserts a membership for an event id with no `events` row
- **THEN** the write is rejected by the constraint, not by application code

#### Scenario: Enforcement is not re-checked at deploy time

- **WHEN** a deployment boots and is probed
- **THEN** the probe witnesses that the store is reachable, and does not assert the pragma's value

## REMOVED Requirements

### Requirement: Foreign keys are relied upon and asserted at boot

**Reason**: The boot-probe assertion is removed. It was the probe's only terminal store cause, and with it
gone the health route has nothing left to report about the store that a non-success status does not already
carry — which is what lets the route reduce to reachability plus bundle identity (capability
`backend-deployment`). The reliance on foreign keys is unchanged and is restated in "Foreign keys are
relied upon, and their enforcement is trusted rather than asserted", which records what the trust rests on
and what would falsify it.

**Migration**: None at the schema or data level — no constraint, table or statement changes. Operationally:
a deploy no longer fails when foreign-key enforcement is off, so re-measure the pragma whenever the store
is re-provisioned, moved, or its engine version changes.
