## MODIFIED Requirements

### Requirement: Foreground settles in-flight rows the backend already stores

On every foreground entry, the **app** SHALL ask the backend which resources it stores for this device, and
SHALL record `COMPLETED` for every `REQUESTED` row whose key the listing contains. It SHALL:

1. read the ledger's pending rows, and make no request when there are none;
2. fetch the **per-device** listing (`list(deviceId)`, see "Event file list seam"), bounded by the same
   15-second timeout as the join-time load;
3. for each pending key the listing contains, record `COMPLETED` through the guarded terminal write
   (`markTerminal(key, COMPLETED)`, capability `sync-ledger`), which applies only while the row is still
   `REQUESTED`.

It SHALL write nothing else. It SHALL NOT touch a `DISCOVERED` or `COMPLETED` row, SHALL NOT record `FAILED`,
SHALL NOT create or delete a row, and SHALL NOT seed or reset the ledger. It SHALL mark nothing done without
the backend listing the key's stored bytes. A listing entry exists only after a completed upload of that
key (see "A join loads the ledger from the per-device listing" for why a successful listing is
authoritative). Bytes stored by an earlier upload of the same key are the same object, so they settle the
row just as well.

A failed or timed-out fetch SHALL be logged, at `Warn` for a transport failure or timeout and at `Error`
for a listing this build cannot decode. It SHALL change nothing, and SHALL leave no flag, retry or gate
behind: the next foreground asks again.

**Why.** An upload's bytes can land long before the OS acknowledges the job, and sometimes the
acknowledgement never reaches this ledger at all. Under a full grant the PhotoKit extension learns of a
completion only at its next invocation. After a downgrade to a partial grant it is withheld and is presented
nothing: measured on an SE2 (iOS 26.6, 2026-09-22), four objects landed within ~30 s while their rows stayed
`REQUESTED` and the status read `Syncing` until full access returned. The backend is the one party that
knows, and the status the member looks at on foreground is what this corrects. A later acknowledgement then
finds a settled row, and its guarded write applies to nothing.

The settle SHALL run in the app process only, as a trigger step of its own — part of foreground entry's **own
work** (capability `ios-app-shell`, "Each OS wake does its own work, then hands the rest to one opportunistic
tail"). It SHALL NOT run inside the upload cycle or as a unit of the opportunistic tail (see "The upload cycle
does not detect membership changes") and SHALL NOT be sequenced behind the tail, which can remain outstanding
for many minutes after a long suspension: foreground's own work runs outside the tail runner, so a tail another
wake started never holds it up. It needs no serialization with the tail's upload units, because its one write is
the guarded terminal write the platform's own callbacks already make beside a running cycle. It SHALL NOT run in
the upload extension. Decision records: `changes/selection-is-the-walk` (D4); `changes/own-work-per-wake` (D1),
which replaced the upload pump this step used to run beside.

#### Scenario: A stored in-flight upload settles at foreground
- **WHEN** the app enters the foreground while the ledger holds a `REQUESTED` row whose key the per-device
  listing contains
- **THEN** that row becomes `COMPLETED`, and the status counts it as done

#### Scenario: A row without stored bytes is left alone
- **WHEN** a `REQUESTED` row's key is absent from the listing
- **THEN** the row stays `REQUESTED`

#### Scenario: Only in-flight rows are settled
- **WHEN** the listing contains the key of a `DISCOVERED` row
- **THEN** the row stays `DISCOVERED`, and no other state is written

#### Scenario: Nothing pending, nothing fetched
- **WHEN** the app enters the foreground with no pending ledger rows
- **THEN** no listing request is made

#### Scenario: A failed listing changes nothing
- **WHEN** the listing fetch fails or times out
- **THEN** no row changes, the failure is logged, and the next foreground tries again

#### Scenario: A late acknowledgement after the settle is a no-op
- **WHEN** a row the settle recorded `COMPLETED` is later acknowledged by the OS as succeeded
- **THEN** the guarded write applies to nothing and the row stays `COMPLETED`

#### Scenario: The extension never settles from the listing
- **WHEN** the upload extension runs a cycle
- **THEN** it makes no per-device listing request
