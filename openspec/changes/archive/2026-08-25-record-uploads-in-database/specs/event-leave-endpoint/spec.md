## REMOVED Requirements

The capability is dissolved into `api-endpoints`. The client half of leaving is `leave-event` and is
unaffected.

### Requirement: Leave route and departed rename
**Reason**: The departed **rename** does not survive: membership state is a column, not the relative age
of two objects (`database` → *Membership state is a column with exactly two values*). What remains is
route mechanics.
**Migration**: `api-endpoints` → *Leave*; `database` → *Membership state is a column with exactly two
values*.

### Requirement: Idempotent, leak-safe cascade
**Reason**: Idempotence is route mechanics; the leak-safety it was defending — an orphaned manifest with
no marker — is unstateable once `memberships` sits under an `ON DELETE CASCADE`.
**Migration**: `api-endpoints` → *Leave*; `database` → *Five tables, with resources outside the event
ownership chain*.

### Requirement: Leave requires a device token
**Reason**: A duplicate of the rule `device-attestation` owns.
**Migration**: `device-attestation` → *Ungated routes are a closed list*.
