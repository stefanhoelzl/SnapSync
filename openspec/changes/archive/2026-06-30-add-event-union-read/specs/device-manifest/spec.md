## MODIFIED Requirements

### Requirement: Per-event device manifest document

For each (event, device) pair it backs up, the producer SHALL maintain exactly one device manifest
object at the key `/events/<eventId>/device/<deviceId>.json` with `Content-Type: application/json`. The
manifest SHALL be a UTF-8 JSON object carrying `deviceId` (the stable per-install device id) and
`assets` (an array). Each `assets` element SHALL carry `assetId` (the device-local asset identity),
`creationDate` (the asset's capture timestamp as an ISO-8601 string), and `resources` (a non-empty
array). Each `resources` element SHALL carry `role`, `contentType` (the resource's MIME type), `key`
(the resource's object name — its `/files/<deviceId>/` storage key minus that prefix, the fetch
handle), and `filename` (the resource's human filename as captured). The field names `key` and
`filename` are shared verbatim with the event-wide union read (`bunny-list-endpoint`), so the union is
a straight projection of the manifest.

#### Scenario: One manifest per event and device

- **WHEN** a device backs up assets for event `E`
- **THEN** exactly one object `/events/E/device/<deviceId>.json` exists for that device, with
  `Content-Type: application/json`, carrying `deviceId` and an `assets` array

#### Scenario: Fields present on each entry

- **WHEN** the manifest lists an asset entry
- **THEN** that entry carries `assetId`, `creationDate`, and a non-empty `resources` array
- **AND** each resource entry carries `role`, `contentType`, `key` (the storage object name), and
  `filename` (the human capture name)
