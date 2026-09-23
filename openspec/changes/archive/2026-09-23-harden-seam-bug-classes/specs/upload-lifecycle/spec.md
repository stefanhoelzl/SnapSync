## ADDED Requirements

### Requirement: A transition that cannot read the membership defers visibly

An upload transition SHALL NOT act on an unreadable membership, and SHALL log that it deferred.
A membership transition of the upload arm (launch, permission change, override change) that cannot read
the membership SHALL treat it as **unreadable**, never as "not joined" (capability `module-architecture`,
"Reads that can be unknown say so"). It SHALL NOT register, deregister, arm or disarm on the strength of an
unreadable read. It SHALL log that it deferred and why, and the next transition or foreground entry SHALL
reconcile again.

#### Scenario: A launch transition before first unlock

- **WHEN** host assembly runs the launch reconcile while the membership file is unreadable
- **THEN** no registration is written or removed, no engine is armed or disarmed, and the device log
  records the deferral

#### Scenario: A definite absence is still a leave

- **WHEN** the membership file is definitively absent
- **THEN** the transition treats the device as not joined, as before
