## ADDED Requirements

### Requirement: A confirmed-gone event tears the membership down without user action

The device SHALL return itself to the unjoined resting state when its configured event is confirmed gone,
running the **same** local teardown the user's explicit Leave performs (capability `leave-event`: stop the
producer, clear the persisted config, then notify the backend best-effort — the notify is expected to
fail against a deleted event and its failure changes nothing).

The teardown SHALL fire only when **two independent witnesses agree**:

1. an event-details fetch resolves to a **definitive absence** — the sealed `NotFound` outcome of the one
   details client (capability `join-event`), never a transport failure, a timeout, a non-404 status, or an
   unparseable body, all of which resolve as inconclusive; **and**
2. the membership's **own persisted `deletesAt` has passed**, compared against the device's clock.

Neither witness alone SHALL be sufficient. A membership whose `deletesAt` is absent (persisted before the
field existed and not yet backfilled) SHALL never satisfy the second witness and SHALL therefore never
self-leave.

**Why two.** The persisted config is the only record of the join, and the invite QR is derived from the
`eventId` it holds — so a wrongful teardown is unrecoverable, and a systemic backend fault that answered
`404` for every event would otherwise destroy every membership in the install base at once. A
misconfiguration cannot move the device's own clock, so requiring an offline witness bounds that failure
to "every device declines to act". This is not a heuristic: emptiness-driven deletion (capability
`scheduled-cleanup`) requires every enrolled device to have departed, and departing clears the config
before the backend is notified — so a device that still holds a membership can only ever be observing a
**deadline** deletion, which is exactly what the second witness tests.

The teardown SHALL fire from the **foreground** trigger path only. The background trigger flows SHALL
keep their existing guarantee that no fetch outcome is destructive: a background wake may run against a
config that could not be read, and reading unreadable as absent there would destroy a healthy membership.
The foreground path re-reads the persisted membership from an unlocked device before any consumer runs,
which is the only context in which the destructive branch is safe.

Every failure mode of this rule SHALL resolve toward **keeping** the membership. A device that never
enrolled a manifest may outlive an emptiness sweep and hold a membership for an event that is already
gone; it is corrected at its deadline rather than earlier, and no photo or identifier is lost in the
interim.

#### Scenario: A deleted event past its deadline tears the membership down

- **WHEN** a foreground details fetch for the configured event resolves to a definitive `NotFound` and
  the membership's persisted `deletesAt` has passed
- **THEN** the device stops the producer, clears the persisted config, and returns to the unjoined
  resting state — the same teardown an explicit Leave performs

#### Scenario: A 404 before the deadline is disbelieved

- **WHEN** a foreground details fetch resolves to a definitive `NotFound` while the membership's
  persisted `deletesAt` has **not** passed
- **THEN** the membership is left completely intact and syncing continues

#### Scenario: An inconclusive fetch after the deadline changes nothing

- **WHEN** a foreground details fetch fails on the network, times out, or returns a non-404 error, while
  the membership's persisted `deletesAt` has passed
- **THEN** the membership is left completely intact — absence was never confirmed

#### Scenario: A membership with no persisted deadline never self-leaves

- **WHEN** a membership persisted before `deletesAt` existed observes a definitive `NotFound` at any time
- **THEN** the membership is left intact, and it becomes eligible only once a reconcile has backfilled its
  deadline

#### Scenario: Background triggers never tear down

- **WHEN** a silent push or a background task wake runs while the configured event is in fact deleted
- **THEN** no teardown occurs from that trigger, and the membership is cleared only on a subsequent
  foreground entry that satisfies both witnesses

#### Scenario: The failed backend notify does not matter

- **WHEN** the self-leave's best-effort backend notify fails because the event no longer exists
- **THEN** the local teardown has already completed and the failure is logged and ignored
