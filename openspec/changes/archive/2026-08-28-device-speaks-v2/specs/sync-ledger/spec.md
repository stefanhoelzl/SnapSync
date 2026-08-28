## ADDED Requirements

### Requirement: The ledger records the destination a job was sent to

A ledger row SHALL carry the **destination path** the upload for that row was addressed to, recorded at the
moment the row is written as in-flight — the same write that records the request, carrying the request it
already holds. No second write, no new ordering, and therefore no window in which a job exists whose
destination the ledger does not know that did not already exist.

The column exists so a row is recoverable from **what the external system persisted**. The OS-driven upload
tier hands a destination request to the platform and the process dies; when the platform returns the
finished job, the destination is the only field reliably present — `resource` is nil for a succeeded job —
and the row must be found from it. Under the previous byte-route shape the ledger key happened to be the
destination's last path segment, so recovery was free; that was an accident of formatting, not a decision,
and it does not survive a route that names identity in its path.

The value SHALL be the URL's **path**, not the whole URL: the path is what the platform must preserve in
order to perform the request at all, while a query or header may be normalized by a store this system does
not control.

The column SHALL be **nullable**, and a row without it SHALL remain fully usable. Rows written by a build
that predates this column exist on every device that upgrades, and the recovery that reads them is defined
by the tier that owns it (capability `ios-photokit-upload`).

Recording a destination SHALL NOT make the ledger key event-dependent or expiry-dependent. The key remains
the bare, event-independent object name (see "Event-independent key"), and the destination is stable with
no expiry, so a row's recorded destination stays valid for as long as the row does.

#### Scenario: The destination is recorded with the in-flight write

- **WHEN** a row is recorded as requested for an upload that has just been created
- **THEN** the row carries the destination path that upload was addressed to, written by that same
  operation

#### Scenario: A row is recoverable by its destination

- **WHEN** a returned upload job carries a destination whose path matches a recorded row
- **THEN** that row is identified, including when the destination's last path segment is not the row's key

#### Scenario: A row written before the column is still usable

- **WHEN** a row predates this column and carries no destination path
- **THEN** the row reads and writes normally, and its recovery falls to the tier-specific fallback

#### Scenario: The key is unchanged

- **WHEN** a row carrying a destination path is read
- **THEN** its key is still the bare, event-independent object name, unchanged by the addition
