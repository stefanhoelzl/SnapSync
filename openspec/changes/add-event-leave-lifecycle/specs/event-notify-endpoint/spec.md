## MODIFIED Requirements

### Requirement: Member enumeration and per-member token read

For an existing event the endpoint SHALL enumerate the event's **active** member devices with a
**single** bunny native Storage List of the device-manifest directory `events/<eventId>/devices/`. A
device is an active member when its `<deviceId>.json` is present and (its `<deviceId>.left.json` sibling
is absent or its `<deviceId>.json` is the newer of the two by last-modified time; see `device-manifest`).
Departed devices — those whose winning sibling is `<deviceId>.left.json` — SHALL be **excluded** from the
notify audience (a device that has left is not pushed). For each active member the endpoint SHALL read
that member's config object `devices/<deviceId>.json` to obtain its `pushToken`. Every upstream read SHALL
carry the storage zone's `AccessKey` and never the account API key. The active-membership resolution is
the same last-write-wins rule the union and reap use.

#### Scenario: Active members enumerated with one LIST

- **WHEN** the event has active member devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/devices/`, resolves active membership by last-write-wins, and reads each active member's `devices/<deviceId>.json`

#### Scenario: A departed device is not notified

- **WHEN** a device's winning manifest under `events/<eventId>/devices/` is its `<deviceId>.left.json` (it has left)
- **THEN** that device is excluded from the fan-out and receives no push

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key
