## REMOVED Requirements

The capability is dissolved into `api-endpoints`. The push mechanism itself is `apns-push-sender` and is
unaffected.

### Requirement: Event notify route
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *Notify*.

### Requirement: Notify gated on event existence
**Reason**: Route mechanics; the gate now reads a row.
**Migration**: `api-endpoints` → *Notify*; `database` → *Event existence is a row*.

### Requirement: Member enumeration and per-member token read
**Reason**: Enumeration was a directory listing plus a last-write-wins resolution plus one object read per
member. It becomes a query over `memberships` joined to `device_records`.
**Migration**: `api-endpoints` → *Notify*; `database` → *Membership state is a column with exactly two
values*.

### Requirement: Best-effort silent fan-out to all members
**Reason**: Route mechanics; the best-effort posture is preserved verbatim.
**Migration**: `api-endpoints` → *Notify*.

### Requirement: Notify requires a device token
**Reason**: A duplicate of the rule `device-attestation` owns.
**Migration**: `device-attestation` → *Ungated routes are a closed list*.
