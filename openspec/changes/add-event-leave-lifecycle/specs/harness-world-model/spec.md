## MODIFIED Requirements

### Requirement: Faithful leave composition helper

The world SHALL provide a `leave()` composition helper that runs the **real** leave edge —
`DownloadController.onLeaveOrSwitch()` (cancel in-flight transfers, prune non-terminal download rows),
the best-effort backend leave notify (`DELETE /events/<eventId>/devices/<deviceId>` against the world's
mini-edge), then clearing the config cell and the joined-event marker — while **retaining** imported
foreign photos and the ledger on the device side. It SHALL NOT be modelled by rebuilding the world
(which would forge the outcome and wrongly discard imported photos). The backend leave SHALL mutate the
world's object store through the same mini-edge cascade a real backend runs (rename to `.left.json`,
last-active-member reap, reference-checked GC), so integration tests can assert **both** the device
outcome (join cleared, imports retained) and the **world** outcome (the device's manifest renamed
departed; the event tree and freed byte partition removed when it was the last active member). Because
clearing the config cell is reactive, the listing-backed status projection SHALL leave the joined layer
without any world rebuild, and re-provisioning the same event afterwards SHALL still find the previously
imported foreign assets suppressed (real cross-event dedup).

#### Scenario: Leave keeps imported photos, clears the join, and notifies the backend

- **WHEN** a foreign asset has been downloaded and imported, and `leave()` is then invoked
- **THEN** the real `onLeaveOrSwitch()` runs, the backend leave is dispatched to the mini-edge, the config cell and joined-event marker are cleared, and the imported asset remains enumerable in the gallery

#### Scenario: Re-provisioning after leave still suppresses the import

- **WHEN** the same event is re-provisioned after `leave()`
- **THEN** the previously imported foreign asset is still in `suppressedLocalIds()` and the own-device
  cycle does not re-upload it

#### Scenario: Leaving as the last active device reaps the event in the world

- **WHEN** `leave()` is invoked for the world's own device when it is the event's last active member
- **THEN** the mini-edge deletes the event tree and garbage-collects the device's byte partition, and the world's backend read-models show the event and its objects gone

## ADDED Requirements

### Requirement: Mini-edge leave cascade

The `:test:world` mini-edge SHALL answer `DELETE /events/<eventId>/devices/<deviceId>` with the same
cascade the real backend runs over its in-memory object store: rename the active manifest to
`<deviceId>.left.json` (fresh write time), then, if no active member remains under
`events/<eventId>/devices/` (resolved by the last-write-wins rule over sibling write times), delete the
event tree and, for each freed device that appears in no surviving event, delete its
`files/devices/<deviceId>/` objects and its `devices/<deviceId>.json` config. Its union and notify
read-models SHALL apply the same active/departed last-write-wins resolution, so departed devices remain
in the union but are excluded from notify fan-out. The cascade SHALL be idempotent under repeated calls.

#### Scenario: Mini-edge renames then reaps

- **WHEN** the mini-edge receives `DELETE /events/<eventId>/devices/<deviceId>` for the last active member
- **THEN** it renames the manifest to `.left.json`, deletes the event tree, and GCs the orphaned device's bytes and config

#### Scenario: Mini-edge union keeps a departed device, notify drops it

- **WHEN** a device is departed (its winning sibling is `<deviceId>.left.json`) while the event has other members
- **THEN** the mini-edge union includes that device's assets and the mini-edge notify fan-out excludes it
