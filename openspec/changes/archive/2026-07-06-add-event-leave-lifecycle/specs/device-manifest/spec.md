## ADDED Requirements

### Requirement: Departed manifest and last-write-wins membership

A device's per-event membership SHALL be represented by two possible sibling objects under
`events/<eventId>/devices/`: the **active** manifest `<deviceId>.json` and the **departed** manifest
`<deviceId>.left.json`. Leaving renames active → departed (see `event-leave-endpoint`); rejoining writes
a fresh active `<deviceId>.json`. Both carry the same manifest document shape; the departed sibling is a
snapshot of the device's contributions at leave time, retained so the event union can still serve them.
Membership state SHALL be resolved **last-write-wins** by the two siblings' last-modified times:

- `active(device)` = `<deviceId>.json` present AND (`<deviceId>.left.json` absent OR
  `<deviceId>.json` is newer than `<deviceId>.left.json`).
- `departed(device)` = `<deviceId>.left.json` present AND NOT `active(device)`.

An exact-tie of last-modified times (not producible in practice — the two writes are on different keys
separated by a human gesture) SHALL resolve to `active`. Consumers that enumerate membership
(`bunny-list-endpoint` union, `event-notify-endpoint` fan-out, the `event-leave-endpoint` reap) SHALL
apply this rule over the last-modified time already present in the directory `LIST`, requiring no
per-object follow-up read. A device SHALL NOT be counted twice when both siblings are present.

#### Scenario: Newer departed sibling wins after a leave

- **WHEN** both `<deviceId>.json` and `<deviceId>.left.json` exist and the `.left.json` is newer
- **THEN** the device resolves to `departed` (its photos stay in the union; it is not an active member)

#### Scenario: Newer active sibling wins after a rejoin

- **WHEN** both siblings exist and the `<deviceId>.json` is newer (a rejoin superseding a prior leave)
- **THEN** the device resolves to `active` (it is an active member and is notified)

#### Scenario: Membership resolved from the directory listing alone

- **WHEN** a consumer enumerates `events/<eventId>/devices/`
- **THEN** it resolves each device's active/departed state from the two siblings' last-modified times in that one listing, with no extra per-object read
