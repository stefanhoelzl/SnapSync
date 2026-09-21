## ADDED Requirements

### Requirement: Imported local identifiers are readable by source ref

The store SHALL answer, for a given set of source refs (`sourceDeviceId`, `sourceAssetId`), the created
local identifier of each ref whose row is **`IMPORTED`**. Its only consumer is the event album's gather
(capability `event-album`), which uses it to place the foreign photos this device already holds.

A ref SHALL be absent from the answer when it has no row, when its row is non-terminal (including an
**unconfirmed** row that carries a marker), or when its row is `UNIMPORTABLE`. An unconfirmed row is left out
because its asset's existence has not been adjudicated yet. Leaving it out costs only a placement that the
next gather makes, once adjudication has settled the row.

The read SHALL be **keyed by ref and blind to events**, like the rest of the store. The store does not record
which event a download was for, and SHALL NOT start to: a row is shared across every event that lists its
ref. The caller decides which refs belong to an event, by supplying the refs from that event's union.

It SHALL be a read-only operation of the app-side store and SHALL NOT join the extension's narrow
suppression read: the extension never gathers, so it has no use for it.

It SHALL need **no schema change**: the ref is the row's primary key, and the identifier is the existing
marker column.

#### Scenario: An imported ref answers its local identifier
- **WHEN** the read is asked about a ref whose row is `IMPORTED` with created local identifier `L`
- **THEN** the answer maps that ref to `L`

#### Scenario: A pending ref is absent
- **WHEN** the read is asked about a ref whose row is still `PENDING` and carries no marker
- **THEN** that ref is absent from the answer

#### Scenario: An unconfirmed ref is absent
- **WHEN** the read is asked about a ref whose row is non-terminal but carries a marker
- **THEN** that ref is absent from the answer

#### Scenario: An unimportable or unknown ref is absent
- **WHEN** the read is asked about a ref whose row is `UNIMPORTABLE`, or a ref with no row
- **THEN** that ref is absent from the answer

#### Scenario: Only the asked refs are answered
- **WHEN** the store holds `IMPORTED` rows for refs `A` and `B` and the read is asked about `A` only
- **THEN** the answer contains `A` and not `B`
