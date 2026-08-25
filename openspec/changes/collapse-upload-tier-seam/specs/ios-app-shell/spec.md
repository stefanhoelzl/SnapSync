## ADDED Requirements

### Requirement: OS entry points delegate upload triggers to the resolved mechanism

Every OS entry point that drives upload work SHALL delegate to the **resolved upload mechanism**
(`upload-lifecycle`, "The upload mechanism is resolved, never selected") rather than to a tier-dependent
thunk bound at composition — foreground entry, a silent push, the upload heartbeat background task, and
a photo-selection change. The root SHALL NOT bind per-tier upload behaviour, and no
entry point SHALL re-check a tier.

A mechanism is always resolved, so an entry point always has a delegate ("A mechanism is always
resolved"). The entry point SHALL construct the `OsReceipt` for its own OS wake, using the deadline
named for that wake, and SHALL hold it across the delegated call — so the mechanism receives a plain
`suspend` trigger and never holds a raw OS completion handler. This preserves "OS completion handlers
are released only after their work completes" while removing every mechanism's ability to violate it.

Binding upload behaviour per tier in the root is what previously made a forced build unable to reach a
mechanism it had not composed. Delegating to the resolved mechanism removes the root's opportunity to
answer that question at all.

#### Scenario: A background wake reaches the resolved mechanism

- **WHEN** the OS invokes an upload-driving entry point
- **THEN** the entry point holds a receipt for that wake's deadline, delegates to the resolved mechanism,
  and releases the handler when the delegated work completes or the deadline expires

#### Scenario: No entry point re-checks a tier

- **WHEN** the shell's upload-driving entry points are inspected
- **THEN** none of them branches on an upload tier, and none binds a per-tier thunk
