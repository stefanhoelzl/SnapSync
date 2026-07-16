## MODIFIED Requirements

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent** config
(the Keychain reports no such item), and an **unreadable** config (the read failed for any other reason,
notably because protected data is unavailable on a locked device). An unreadable config SHALL NOT be
reported as an absent config.

A reader that acts on the absence of a config — in particular the re-join reconciliation, for which "no
event configured" means *the device left the event* and triggers clearing the persisted `joinedEventId`
marker (capability `event-rejoin-reconciliation`) — SHALL act **only** on a definitely absent config. On
an unreadable config **the upload cycle** SHALL skip entirely: it SHALL NOT reconcile, SHALL NOT clear the
join marker, SHALL NOT reset the discovery cursor, and SHALL NOT create upload jobs; the cycle SHALL
complete cleanly and the next cycle SHALL retry.

This SHALL hold on **every upload tier and at every trigger**, not only where the OS is the invoker. The
tiers differ in who invokes a cycle — the OS on iOS ≥26.1, the app on iOS 18–26.0 — and not in what an
unreadable membership means. A tier SHALL NOT reach this decision through a two-state read that cannot
express "unreadable"; the three-state read is the only permitted path (capability `upload-lifecycle`, which
owns where the decision is made).

Conflating the two is what makes an ordinary locked-device wake perform a *false leave*: the marker is
cleared, and the next readable cycle sees a marker mismatch and pays for a full re-join reconciliation
(a device listing, an atomic ledger clear-and-seed, and a discovery-cursor reset that forces a complete
library re-enumeration) — repeatedly, without the marker ever settling.

#### Scenario: An unreadable config does not clear the join marker
- **WHEN** an upload cycle reads the config and the read fails because protected data is unavailable
- **THEN** the cycle is skipped, the reconciliation is not invoked, the persisted `joinedEventId` marker
  is left intact, the discovery cursor is not reset, and the cycle completes cleanly

#### Scenario: A definitely-absent config still drives the leave path
- **WHEN** an upload cycle reads the config and the Keychain reports no such item
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker, exactly
  as a leave requires

#### Scenario: A joined device stays settled across locked wakes
- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no re-join
  reconciliation, ledger re-seed, or full re-enumeration is performed

#### Scenario: The app-driven tier skips rather than leaves
- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the `joinedEventId` marker is left intact, and the membership survives —
  the same outcome the OS-invoked tier produces
