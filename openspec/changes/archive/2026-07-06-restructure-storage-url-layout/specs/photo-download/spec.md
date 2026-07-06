## MODIFIED Requirements

### Requirement: Foreign-asset selection by device identity

The download client SHALL consume the event-wide union read (`GET /events/<eventId>/files`) for the
joined event and SHALL select for download exactly those assets whose owning `deviceId` is **not**
this install's `deviceId` (from `device-identity`) and that are **not** already recorded as imported
in the download store. Assets owned by this device SHALL NOT be downloaded (they are already in this
library). The union returns only **complete** assets, so the client SHALL NOT perform any
completeness computation of its own.

#### Scenario: Own-device assets are skipped

- **WHEN** the union lists an asset whose `deviceId` equals this install's device id
- **THEN** the client does not download or import it

#### Scenario: Foreign, not-yet-imported assets are selected

- **WHEN** the union lists an asset whose `deviceId` differs from this device and no terminal
  download-store row exists for `(deviceId, assetId)`
- **THEN** the client selects every resource of that asset for download

#### Scenario: Already-imported foreign assets are skipped

- **WHEN** the union lists a foreign asset that the download store records as imported
- **THEN** the client does not download or import it again
