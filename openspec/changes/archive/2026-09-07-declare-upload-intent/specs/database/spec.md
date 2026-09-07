## ADDED Requirements

### Requirement: The declaring events of a resource are reachable without a scan

`event_assets` SHALL carry an index on `(device_id, asset_id)` so that "which events declare this asset for
this device" is answerable without scanning the table.

The need is **forced by an existing constraint, not chosen**: the v2 byte upload route addresses a resource
from its path alone, and that path carries no event (capability `api-endpoints`, and the same fact that
places `resources` outside the event ownership chain). To decide whether a landed byte completed an asset —
and therefore whom to wake (capability `upload-completion-notify`) — the route SHALL first resolve the
events declaring that asset. The table's primary key is `(event_id, device_id, asset_id)`, whose leftmost
column is the one the byte route does not have, so that key cannot serve the lookup.

The index SHALL be added by a **new migration file**, never by editing an applied one, and SHALL be
`IF NOT EXISTS` so that applying it to a store that already carries the index is inert. The generated
schema snapshot SHALL therefore show it, since that file is the replay's output rather than a second
hand-written statement of the shape.

#### Scenario: The byte route resolves an asset's events

- **WHEN** a v2 byte upload records a resource and must decide whether its asset is now complete
- **THEN** the events declaring that `(device_id, asset_id)` are found through the index, without a table
  scan

#### Scenario: The index arrives as its own migration

- **WHEN** the index is introduced
- **THEN** it is a new file in the ordered migration set rather than an edit to one already applied, and
  the committed schema snapshot regenerates to include it
