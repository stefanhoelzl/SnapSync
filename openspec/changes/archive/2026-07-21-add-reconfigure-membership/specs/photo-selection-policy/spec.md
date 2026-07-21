# photo-selection-policy Specification

## MODIFIED Requirements

### Requirement: Per-device, per-membership capture-date cutoff

The system SHALL support a **capture-date cutoff** that scopes a device's participation in an event to
photos taken at or after a chosen instant. The cutoff SHALL be **per-device** and **per-membership**: it
is the joining device's own choice for its membership in a specific event, first set at join time, and it
SHALL NOT be sent to the backend. The cutoff SHALL be **changeable in place after join** via
`reconfigure-membership` — never by re-scanning (re-provisioning an already-joined event remains a no-op) —
and any changed value SHALL re-apply the `startsAt` **floor**. The cutoff SHALL be carried on the
per-event membership state (v1: the single persisted `EventConfig`; the data model SHALL be shaped so a
future set of memberships each carries its own cutoff without relocating the field).

The event SHALL supply the cutoff's **default** (its `startsAt`, capability `event-creation`) and its
**floor** (see *The event's start date is a floor on every membership's cutoff*). The surviving safety
invariant — the one this capability exists to protect — is directional:

- the event's start can only **narrow** a membership's scope, never widen it beyond the member's own
  choice: the member is always free to choose a **later** cutoff than the event's start, and the value
  the member is committing to SHALL be visible on the join surface **before** the confirm;
- a host SHALL NOT be able to cause any photo taken before `startsAt` to be uploaded;
- a host SHALL NOT be able to raise a member's cutoff above what that member chose.

This supersedes the prior absolute rule that the cutoff "SHALL NOT be inherited from the event and SHALL
NOT be imposed by the event's host". That rule was written when the event's only temporal fact was
`createdAt` — an implementation detail of when a JSON object was written — and a host-supplied date could
only have been a *widening* default with no compensating bound. A start date that is **both** the default
**and** the floor inverts that: the host bounds the event's contents from below, and the member chooses
freely above.

A cutoff SHALL be **required**: a membership without one is not a representable state. The persisted
membership's cutoff field SHALL be non-null, and every consumer of the cutoff SHALL receive a non-null
value. There SHALL be no scope in which a membership admits the whole library.

#### Scenario: The cutoff is a device-local choice, never sent to the backend
- **WHEN** a device joins an event with a chosen cutoff
- **THEN** the cutoff is persisted on that device's membership and no request carries it to the backend

#### Scenario: The member sees the value being committed
- **WHEN** the join surface offers the cutoff
- **THEN** the resulting instant is rendered on the surface before the confirm, so a host-supplied
  default is an informed one and never a hidden one

#### Scenario: The host cannot widen a member beyond the member's own choice
- **WHEN** an event's `startsAt` is far in the past and the member selects a later cutoff
- **THEN** the member's selection is persisted unchanged, and no photo before it is uploaded

#### Scenario: Re-provisioning an already-joined event leaves the cutoff unchanged
- **WHEN** a device is already joined with a cutoff and re-provisions (or re-scans) the same event
- **THEN** the cutoff is unchanged (no re-pick), consistent with the join being a no-op for the already-joined event

#### Scenario: The cutoff can be changed in place after join
- **WHEN** a joined member opens the reconfigure surface (capability `reconfigure-membership`) and confirms a different cutoff
- **THEN** the persisted cutoff is replaced with the new value, clamped to the `startsAt` floor, without leaving or re-enrolling, and the next upload cycle applies it

#### Scenario: A membership always carries a cutoff
- **WHEN** any joined membership is read, by the app process or the upload extension process
- **THEN** its cutoff is a non-null cutoff string, and no code path exists by which a joined membership
  admits assets of every capture date
