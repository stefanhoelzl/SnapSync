## ADDED Requirements

### Requirement: The inspector's Create event control supplies a start date

The world inspector's **Create event** control SHALL supply a `startsAt` alongside the event name, the
real `POST /events` now requiring one (capability `event-creation`). It SHALL let the operator choose
between a start in the **past** (the event has begun — the ordinary case) and one in the **future** (the
event has not begun), so both sides of the floor can be driven through the **real** stack rather than
forged.

Driving the not-started case here — rather than only in the forge harness — is what proves the *theorem*
the design rests on: that a future start uploads nothing not because a gate refuses, but because the
clamped cutoff admits no photo. The forge harness can only show the status line; only the full-stack
world can show the empty object store behind it.

#### Scenario: Creating an event supplies a start date through the real client
- **WHEN** the operator activates Create event
- **THEN** the real `HttpEventCreationClient` posts a canonical `startsAt` with the name, and the
  mini-edge registers a marker carrying it

#### Scenario: A future-start event uploads nothing through the real stack
- **WHEN** the operator creates an event starting in the future, joins it, adds own assets to the
  gallery, and invokes the extension
- **THEN** no upload job is created and no object appears in the backend column — the left pane showing
  the not-started status line beside an empty store

#### Scenario: Uploads flow once the start is in the past
- **WHEN** the operator creates an event whose start is in the past, joins it, adds own assets, and
  invokes the extension
- **THEN** upload jobs are created and objects land in the backend column exactly as before this change
