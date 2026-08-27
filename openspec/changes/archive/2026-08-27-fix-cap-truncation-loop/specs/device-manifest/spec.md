## ADDED Requirements

### Requirement: The manifest is published on every cycle that settled its ledger

Publishing the device manifest SHALL depend only on whether this cycle believes the ledger settled for
the event (see "The manifest is published only from a ledger believed complete"). It SHALL NOT depend
on whether the cycle went on to create an upload job for every resource it discovered.

A cycle that stops creating jobs early — because the platform's job limit was reached — SHALL still
write its manifest. Its already-completed rows are in the ledger, the projection reads the ledger, and
withholding the write publishes nothing new about a device that has in fact uploaded more since the
last write. The consequence of withholding it is that a member's uploaded photos do not enter the
event union, so no other member can download them; and because a device only stops creating jobs early
when it has a backlog, the withholding lasts precisely as long as the member is contributing most.

#### Scenario: A cap-truncated cycle publishes its manifest

- **WHEN** an upload cycle settled its ledger and then stopped creating jobs because the platform's
  job limit was reached
- **THEN** it writes the device manifest for that event, projected from the ledger's completed rows as
  on any other cycle

#### Scenario: A member's photos reach the union while the member is still uploading

- **WHEN** a device has more outstanding resources than the platform will accept jobs for, and
  completes uploads across several cycles
- **THEN** each cycle whose projection changed publishes it, so other members can download those
  photos without waiting for the device to finish its backlog

### Requirement: Manifest detail is backfilled for every row the walk covered

A cycle's walk SHALL backfill the manifest detail of **every** already-recorded row it covered that is
still bare, not only those it reached before it stopped creating jobs.

A row's capture date exists only in the photo library, and the walk is the only thing that reads it. A
bare row is excluded from every projection fail-closed (see "Device-global ledger with per-event
projection"), so a bare row the discovery cursor has advanced past would stay out of the union with no
error anywhere, for as long as the cursor stands. The backfill is therefore a precondition of advancing
the cursor (capability `ios-photokit-upload`, "In-extension discovery via persistent change token"),
not an opportunistic sweep.

#### Scenario: Bare rows past the truncation point are still backfilled

- **WHEN** a cycle's walk covers rows seeded bare by a re-join reconciliation, and the cycle stops
  creating jobs partway through
- **THEN** every bare row the walk covered is backfilled with its capture date, including those after
  the point where job creation stopped

#### Scenario: A re-joined device's photos return to the union without a full re-enumeration

- **WHEN** a device re-joins an event it has already contributed to, and its first cycles stop creating
  jobs early
- **THEN** its seeded rows learn their capture dates on those cycles, so its manifest lists them and
  the event union offers its photos again
