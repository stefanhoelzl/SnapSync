# reconfigure-membership Specification

## MODIFIED Requirements

### Requirement: A joined member changes participation settings in place, without leaving

The system SHALL let a joined member change their membership's `direction`, its capture-date **range**
(`minPhotoDate` and `maxPhotoDate` — the lower and upper bounds, `photo-selection-policy`), and
`saveToAlbum` **in place**, without leaving the event. A `ReconfigureEvent` use-case (`:domain`
`feature/membership`) SHALL read the current `EventConfig`, **guard that its `eventId` still matches** the
membership being edited, and persist
`current.copy(direction = …, minPhotoDate = …, maxPhotoDate = …, saveToAlbum = …)` as a **whole-object**
save through `ConfigStore.save` — the same one-writer discipline as
`EventName.storeEventNameIfChanged`. It SHALL NOT enter `JoinEvent` (the `AlreadyJoined` short-circuit and
the enrollment path are untouched), SHALL NOT re-enroll or clear the ledger, and SHALL preserve the
`eventId`, the sync ledger, the backend enrollment, and the device identity. Because `direction` is a
device-local gate (capability `photo-selection-policy`, `photo-download`), a reconfigure SHALL send **no**
request to the backend.

#### Scenario: A change is persisted in place
- **WHEN** a joined member confirms a reconfigure that flips direction and widens the capture-date range
- **THEN** the persisted `EventConfig` carries the new `direction`, `minPhotoDate`, and `maxPhotoDate`
  under the same `eventId`, the ledger and enrollment are unchanged, and no leave or re-enroll occurs

#### Scenario: The eventId guard prevents a stale write
- **WHEN** the current config's `eventId` no longer matches the membership the surface was opened for (e.g. a switch landed first)
- **THEN** `ReconfigureEvent` makes no write and the operation is a no-op

#### Scenario: A reconfigure reaches nothing on the backend
- **WHEN** any of direction, the capture-date range, or album is changed
- **THEN** the change is a local config write only, and no request carries it to the backend

### Requirement: The cutoff pre-fill is reconstructed from the persisted value and re-clamped to the floor

The reconfigure surface SHALL seed **both** range-bound selectors from the persisted values, because the
join UI's presets are **not** persisted — only the resulting timestamps are. A joined member SHALL edit
**both bounds in place**, each re-clamped to the event window on Save.

For the **lower bound** (From), it SHALL seed from the persisted `minPhotoDate`: when
`minPhotoDate == startsAt` it SHALL show the **Event-start** preset; otherwise it SHALL show the
**Custom** preset carrying that timestamp. The original **Now** choice SHALL NOT be reconstructable (it was
a wall-clock instant at join). A changed lower bound SHALL be clamped to the immutable `startsAt` floor —
`max(chosen, startsAt)` — exactly as at join (capability `photo-selection-policy`), so a reconfigure can
never lower a membership's cutoff below the event's start.

For the **upper bound** (Until), it SHALL seed from the persisted `maxPhotoDate`: when
`maxPhotoDate == endsAt` (or the ceiling is absent/unbounded) it SHALL show the **Event-end** preset;
otherwise it SHALL show the **Custom** preset carrying that timestamp. A changed upper bound SHALL be
clamped to the event's `endsAt` ceiling — `min(chosen, endsAt)` — exactly as at join (capability
`photo-selection-policy`), so a reconfigure can never widen a membership's range above the event's end.

#### Scenario: A cutoff equal to the floor seeds the Event-start preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` equals `startsAt`
- **THEN** the lower-bound selector shows the Event-start preset selected

#### Scenario: A cutoff above the floor seeds the Custom preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` is later than `startsAt`
- **THEN** the lower-bound selector shows the Custom preset carrying that timestamp

#### Scenario: A changed cutoff is clamped to the floor
- **WHEN** the member picks a lower bound earlier than `startsAt` and taps Save
- **THEN** the persisted `minPhotoDate` is `startsAt`, never the earlier value

#### Scenario: An upper bound equal to the ceiling seeds the Event-end preset
- **WHEN** the surface opens for a membership whose `maxPhotoDate` equals `endsAt`
- **THEN** the upper-bound selector shows the Event-end preset selected

#### Scenario: An upper bound below the ceiling seeds the Custom preset
- **WHEN** the surface opens for a membership whose `maxPhotoDate` is earlier than `endsAt`
- **THEN** the upper-bound selector shows the Custom preset carrying that timestamp

#### Scenario: A changed upper bound is clamped to the ceiling
- **WHEN** the member picks an upper bound later than `endsAt` and taps Save
- **THEN** the persisted `maxPhotoDate` is `endsAt`, never the later value
