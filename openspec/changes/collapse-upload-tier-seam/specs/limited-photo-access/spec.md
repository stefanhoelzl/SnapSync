## ADDED Requirements

### Requirement: The read discipline is enforced at the mechanism, not at the trigger fan-out

The rule that no autonomous library read occurs under a partial grant SHALL be enforced by the upload
**mechanism** that would perform the read, not by the trigger fan-out that wakes it. A trigger SHALL be
delivered to the resolved mechanism unconditionally (`upload-lifecycle`, "Triggers are delivered to the
mechanism and declined explicitly"), and the mechanism SHALL decide whether responding would read the
library.

Placing the gate at the fan-out makes it an **invoker-gate**, and its soundness then depends on the
fan-out's enumeration of who might read — an enumeration invalidated silently by a new mechanism or a new
trigger. This is the same failure shape `upload-lifecycle` records for the direction gate ("The arm's
direction gate lives at the choke point, never at the invoker"), and the same remedy applies.

The mechanism is also the only component that **knows the answer**: whether a cycle walks the library or
consumes the in-memory selection snapshot (`SelectionScopedTransfer`) is a property of the mechanism, and
it differs between mechanisms on the same OS and the same grant.

Relocating this gate SHALL preserve the behaviour it currently produces. It SHALL NOT be widened as a
side effect of the move — if the relocated gate would admit a trigger the fan-out currently refuses, that
widening is a separate decision requiring its own evidence.

#### Scenario: A trigger that would walk the library is declined under a partial grant

- **WHEN** a background trigger reaches an upload mechanism whose response would enumerate the photo
  library, and photo access is `LIMITED`
- **THEN** the mechanism performs no library read, and the decision is made in the mechanism rather than
  by the component that delivered the trigger

#### Scenario: A selection-scoped mechanism is not blocked by a gate meant for walks

- **WHEN** a trigger reaches a mechanism whose discovery consumes the selection snapshot rather than
  walking, under a `LIMITED` grant
- **THEN** whether it responds is decided by that mechanism's own reading of the discipline, not by a
  blanket refusal at the fan-out

### Requirement: A limited grant resolves the app-driven mechanism by resolution, not by a branch

Under a partial grant the app-driven mechanism SHALL be the one **resolution** yields on every OS
version (`upload-lifecycle`, "The upload mechanism is resolved, never selected"), and on an OS carrying
the OS-driven mechanism that resolved producer SHALL relinquish the OS-driven registration before it
pumps.

Deregistration under a partial grant is therefore not a separate rule from the forced-tier case: both are
the same resolution cell. A limited member on such an OS previously depended on a lifecycle transition
firing to tear the registration down; making it a property of the resolved mechanism removes that
dependence.

#### Scenario: A downgrade to a limited grant deregisters via the resolved mechanism

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** resolution yields the app-driven kind, whose producer deregisters the extension before
  pumping, and no separate deregistration rule is consulted
