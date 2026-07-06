## MODIFIED Requirements

### Requirement: Union device enumeration and per-device fan-out

The endpoint SHALL discover the event's contributing devices with a **single** bunny native Storage
List Files request against the device-manifest directory `events/<eventId>/devices/`; each direct-child
`<deviceId>.json` **or** `<deviceId>.left.json` object names one contributing device. **Both** active
and departed manifests contribute to the union — a departed device's already-shared photos remain
downloadable until the event is reaped — but a device SHALL be counted **once**: when both siblings are
present the endpoint SHALL read the **last-write-wins** winner (the newer of `<deviceId>.json` /
`<deviceId>.left.json` by the last-modified time in the listing; see `device-manifest`). An absent/empty
directory (bunny `404` or no children) SHALL be treated as "no contributors" → `200 []`. For each
enumerated device the endpoint SHALL read that device's winning manifest object under
`events/<eventId>/devices/` **and** LIST that device's byte partition `files/devices/<deviceId>/` (the
same single-LIST per-device read the per-device list route uses). Every upstream request (the
manifest-directory LIST, each manifest read, each per-device file LIST) SHALL carry the storage zone's
`AccessKey` header from configuration and never the account API key. The stored device manifest is
**already** the event's date-filtered projection, so the union SHALL trust its `assets` list and SHALL
NOT re-apply any date filter.

#### Scenario: Devices enumerated with one LIST

- **WHEN** the event has contributing devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/devices/` and then, per
  device, reads its winning manifest and lists its byte partition `files/devices/<deviceId>/`

#### Scenario: A departed device's photos remain in the union

- **WHEN** a device has only a `<deviceId>.left.json` manifest (it left the event, which still has other members)
- **THEN** its assets are included in the union (served from the departed manifest), so remaining members can still download them

#### Scenario: A device with both siblings is counted once via last-write-wins

- **WHEN** both `<deviceId>.json` and `<deviceId>.left.json` exist for a device
- **THEN** the endpoint reads only the newer sibling's manifest and includes that device's assets exactly once

#### Scenario: Empty manifest directory yields empty array

- **WHEN** `events/<eventId>/devices/` lists no `<deviceId>.json` or `<deviceId>.left.json` children (empty or `404`)
- **THEN** the endpoint responds `200` with `[]` and reads no manifest

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key

#### Scenario: Manifest asset list is not re-filtered by date

- **WHEN** a device manifest lists its projected assets
- **THEN** the endpoint takes that asset list as the event's set and applies no further date filtering
