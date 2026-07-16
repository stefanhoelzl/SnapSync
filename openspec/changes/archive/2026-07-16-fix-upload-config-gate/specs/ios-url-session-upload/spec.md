## ADDED Requirements

### Requirement: The app-driven cycle skips on an unreadable membership

The app-driven tier SHALL reach its cycle-entry decision through the three-state membership read
(capability `event-link`) and the shared decision function (capability `upload-lifecycle`). It SHALL NOT
reach it through the two-state config state flow, which cannot express "unreadable" and reports it as
`null` — indistinguishable from a leave.

This tier invokes its own cycles from the app process, from four triggers (start, foreground, background
task, session events) plus silent push. Each SHALL produce **Skip** on an unreadable membership: no
reconciliation, no `joinedEventId` marker clear, no discovery-cursor reset, no upload job. The exposure is
narrow — the membership item is stored `AfterFirstUnlock`, so an unreadable read needs a boot with no
unlock — and the requirement stands regardless: the accessibility attribute makes a false leave
improbable, the three-state read makes it impossible.

The tier SHALL probe the device identity per cycle rather than resolving it once into a held value. A held
identity cannot express "unreadable this cycle": an unresolvable identity throws out of whatever first
touches it instead of skipping cleanly. The probe is per-process in effect on both tiers already — the
identity caches for the process lifetime, and the OS-invoked tier's per-cycle probe is per-process because
its process dies each cycle.

#### Scenario: A background task on an unreadable membership does not leave the event
- **WHEN** the app-driven tier runs a cycle from its background task and the membership read fails because
  protected data is unavailable
- **THEN** the cycle skips, the `joinedEventId` marker is intact, and the device is still joined on the
  next readable cycle

#### Scenario: An unresolvable device identity skips rather than throwing
- **WHEN** the app-driven tier runs a cycle and the device identity cannot be resolved
- **THEN** the cycle skips cleanly and no error escapes the cycle

#### Scenario: A definitely-absent membership still clears the marker on this tier
- **WHEN** the app-driven tier runs a cycle after a leave, and the membership read reports no item
- **THEN** the leave-side reconciliation runs and the `joinedEventId` marker is cleared

### Requirement: The app-driven root states its selection policy explicitly

The app-driven tier's composition SHALL supply every selection and side-effect port explicitly (capability
`upload-lifecycle`). No port on this tier's controller SHALL carry a permissive default — in particular the
denylisted-album source, whose omission would let this tier upload the albums the OS-invoked tier refuses.

#### Scenario: The tier cannot be composed without its album policy
- **WHEN** the app-driven controller is constructed without a denylisted-album source
- **THEN** it does not compile
