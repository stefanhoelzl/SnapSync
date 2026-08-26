## MODIFIED Requirements

### Requirement: Staged bytes are released only once their row is settled

The store SHALL expose the staged paths of an asset's resources and of all assets whose import is
confirmed, and SHALL return the staged paths of the rows a prune drops as part of that prune — so the
download side can release those bytes. Releasing an asset's bytes SHALL also drop that asset's resource
rows, so the store never records a staged path for a file that no longer exists, and so a backlog pass over
already-imported assets is **self-extinguishing**.

Staged bytes SHALL be released **by the client** only after the settling write has committed — the write
that confirms the import, or the one that settles the row as permanently unimportable — or as part of
dropping the rows referencing them. They SHALL NOT be released by the client while an import is
unconfirmed or still claimed: releasing early destroys the staged files a live import is reading from.

**A staged file may cease to exist without the client releasing it.** The photo library takes a resource's
file at ingest (capability `photo-download`), which happens while the row is still claimed and unconfirmed
and before the commit's verdict is known. The store's contract is therefore that a recorded `stagedPath`
asserts *what the client staged*, not that the file is still there. A row whose import failed SHALL NOT be
assumed to retain its bytes, and the retry guarantee below holds only for a failure that consumed nothing.

A row whose resources cannot be imported SHALL be settleable **terminally**, and the store SHALL exclude
such a row from importable work, from the unconfirmed rows offered for adjudication, and from re-planning —
exactly as it excludes a confirmed row. Without a terminal state the row is offered on every trigger
forever, because a resource already recorded as staged is never re-downloaded and the import can never
succeed.

#### Scenario: Bytes survive a failure that consumed nothing

- **WHEN** an import fails before any resource was ingested, or its outcome has not been reported
- **THEN** the asset's staged bytes are retained and the retry imports from them

#### Scenario: Bytes are released once the import is confirmed

- **WHEN** an asset's import is confirmed
- **THEN** its staged bytes are released and its resource rows dropped, while the asset row and its
  marker are retained

#### Scenario: A terminally unimportable row is not offered again

- **WHEN** a row is settled as permanently unimportable
- **THEN** it is absent from importable work, from the unconfirmed rows offered for adjudication, and from
  re-planning, and its remaining resource rows are dropped

#### Scenario: A backlog pass runs once and finds nothing thereafter

- **WHEN** a release pass runs over assets whose import is confirmed but whose resource rows remain
- **THEN** their bytes are released and their rows dropped, so a second pass finds no work

## ADDED Requirements

### Requirement: A terminal unimportable state is distinguishable from a pending one

The store SHALL record "this asset's resources cannot be imported" as a state distinct from both *pending*
and *imported*, and SHALL preserve that distinction across launches.

Collapsing it into *pending* is what produces the unbounded retry. Collapsing it into *imported* would be
worse: an imported row is a suppression handle asserting that an asset exists in the library, and asserting
that about an asset that was never created makes the row terminal for the wrong reason — the photo is
neither present nor recoverable, and the counts report it as arrived.

A terminally unimportable row SHALL NOT carry a created-asset marker, because no asset was created; and the
counts that drive the status surface SHALL treat it as neither imported nor outstanding, so the screen is
not pegged below completion by work that will never finish.

The state SHALL be introduced additively: existing rows keep their current state, and no stored value is
rewritten. It SHALL NOT be assumed inert to code that predates it — such code spells non-terminal as "not
imported", so it reads the new state as ordinary work. That is tolerable only because installs move forward
only, and its worst outcome is a repeated import attempt rather than a lost photo; it is stated here so a
future reader does not mistake the absence of a schema migration for backward compatibility.

#### Scenario: The state survives a relaunch

- **WHEN** a row is settled as permanently unimportable and the process is restarted
- **THEN** the row is still terminal, and no import or adjudication is attempted for it

#### Scenario: A terminal failure is not a suppression handle

- **WHEN** a row settles as permanently unimportable
- **THEN** it carries no created-asset marker, and it contributes nothing to the suppression set

#### Scenario: The status surface is not pegged by unimportable work

- **WHEN** an event's union includes an asset whose import settled as permanently unimportable
- **THEN** the download counts do not report it as outstanding, so the screen can still reach completion
