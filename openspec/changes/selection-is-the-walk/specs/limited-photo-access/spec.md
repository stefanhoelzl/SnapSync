## MODIFIED Requirements

### Requirement: A downgrade to limited narrows the visible set without breaking sync

The app SHALL treat a grant change from `GRANTED` to `LIMITED` as an ordinary scope change. On that
transition, previously-imported foreign assets are no longer visible to the app (iOS auto-adds
app-created assets to the selection only at creation time — measured), and the own-photo scope narrows
to the selection. From the app's point of view the selection now **is** the gallery. The first upload
cycle after the selection has been read is an authoritative walk (see "The read discipline is enforced
at the mechanism, not at the trigger fan-out"). It removes the in-window rows of every photo outside the
selection, whatever their upload state, so those photos leave the device manifest in the cycle that
publishes it. Photos inside the selection keep their rows and are not re-uploaded. The status total
re-derives from the new scope. Nothing SHALL treat the narrowed visibility as an error.

A photo whose upload was in flight at the downgrade and that lies outside the selection may still finish
uploading: its bytes land, and are listed in no manifest (capability `sync-ledger`, "Deletion is a
presence diff over an authoritative walk"). A photo inside the selection whose upload finished while
the extension was withheld settles at the next foreground from the backend's per-device listing
(capability `upload-state-reconciliation`), not at the OS's next acknowledgement.

Decision record: `changes/selection-is-the-walk` (D1, D2, D4). This reverses the earlier rule that
already-uploaded photos stay in the event across a downgrade because "upload is a publish".

#### Scenario: Downgrading withdraws the photos outside the selection
- **WHEN** a member who uploaded photos under a full grant switches to limited with a selection that
  excludes some of them, and the selection has been read
- **THEN** the next cycle removes the excluded photos' rows and the manifest it publishes no longer lists
  them, the selected photos keep their rows, no re-upload occurs, and the status reflects the new
  selection-defined total

#### Scenario: An upload that landed during the downgrade settles at foreground
- **WHEN** a selected photo's upload was queued under a full grant, its bytes landed after the grant
  narrowed to limited, and the withheld extension never acknowledged it
- **THEN** its row becomes `COMPLETED` at the next foreground, and the status stops reporting it as
  outstanding

### Requirement: The read discipline is enforced at the mechanism, not at the trigger fan-out

The rule that no autonomous library read occurs under a partial grant SHALL be enforced by the upload
**mechanism** that would perform the read — at its cycle's entry gate and in its discovery — not by the
trigger fan-out that wakes it. A trigger SHALL be
delivered to the mechanism unconditionally (`upload-lifecycle`, "Triggers are delivered to the
mechanism and declined explicitly"), and the mechanism SHALL decide whether responding would read the
library.

Placing the gate at the fan-out makes it an **invoker-gate**, and its soundness then depends on the
fan-out's enumeration of who might read — an enumeration invalidated silently by a new mechanism or a new
trigger. This is the same failure shape `upload-lifecycle` records for the direction gate ("The arm's
direction gate lives at the choke point, never at the invoker"), and the same remedy applies.

The mechanism is also the only component that **knows the answer**: whether a cycle walks the library or
consumes the in-memory selection snapshot (`SelectionScopedDiscovery`, which wraps the cycle's
`UploadDiscovery`) is a property of the mechanism, and it differs between mechanisms on the same OS and the
same grant.

Relocating this gate SHALL preserve the behaviour it currently produces. It SHALL NOT be widened as a
side effect of the move — if the relocated gate would admit a trigger the fan-out currently refuses, that
widening is a separate decision requiring its own evidence.

A selection snapshot that has been **read** SHALL be reported as a full enumeration: it is authoritative for
deletion exactly as a full-library walk is under a full grant (capability `sync-ledger`, "Deletion is a
presence diff over an authoritative walk"). Under a partial grant the selection is the gallery, from the
app's point of view. What the snapshot holds may be shared, subject to the selection policy. What it no
longer holds is removed from the ledger, and so from the device manifest, whatever its upload state:
**de-selecting is deleting.** Re-selecting a removed photo records it as new work and re-uploads the same
object idempotently. That duplicate is accepted.

A snapshot that has **not been read yet** SHALL be a distinct scope (`Unread`), and SHALL NOT be treated as
an empty selection anywhere on the upload path. Between a grant turning partial (or a cold launch under
one) and the first selection read, the app holds no selection. An authoritative empty snapshot would
delete the rows of every photo, and an empty answer to a key resolution means "gone" to the enqueue, which
also deletes. So the app's upload cycle SHALL be **Withheld** while the scope is `Unread` (capability
`upload-lifecycle`, "The upload cycle owns its entry decision"). It settles narrowly, and reads, creates,
deletes and publishes nothing. The observer's first emission then triggers the cycle that runs. The
selection-scoped discovery SHALL refuse to answer for an `Unread` scope, failing the call rather than
returning an empty result, so that no caller that reaches it anyway can mistake "not read" for "nothing".

The status total already draws the same distinction, for its own reason: an unread snapshot must not settle
the screen (see "One discovery serves both the status total and the enqueue"). Both SHALL read the one
snapshot cell, so they cannot disagree about whether the selection has been read.

Decision record: `changes/selection-is-the-walk` (D1). It reverses the earlier requirement that a
selection-scoped discovery never report a full enumeration because "deselection is not withdrawal and an
upload is a publish". Its one real hazard, the un-read snapshot, is kept closed by the `Unread` scope
rather than by making every snapshot non-authoritative.

#### Scenario: A trigger that would walk the library is declined under a partial grant

- **WHEN** a background trigger reaches an upload mechanism whose response would enumerate the photo
  library, and photo access is `LIMITED`
- **THEN** the mechanism performs no library read, and the decision is made in the mechanism rather than
  by the component that delivered the trigger

#### Scenario: A selection-scoped mechanism is not blocked by a gate meant for walks

- **WHEN** a trigger reaches a mechanism whose discovery consumes the selection snapshot rather than
  walking, under a `LIMITED` grant
- **THEN** whether it responds is decided by that mechanism's own reading of the discipline, not by a
  blanket refusal at the fan-out

#### Scenario: De-selecting a photo withdraws it from the event

- **WHEN** a read selection snapshot no longer carries a photo whose `COMPLETED` row is in the event's
  window
- **THEN** the discovery reports a full enumeration, the photo's rows are deleted, and the manifest that
  cycle publishes no longer lists it

#### Scenario: De-selecting a photo mid-upload withdraws it too

- **WHEN** a read selection snapshot no longer carries a photo whose row is `REQUESTED`
- **THEN** the row is deleted and the photo is not listed. The transfer may still complete, and its
  terminal write applies to no row

#### Scenario: Re-selecting a withdrawn photo shares it again

- **WHEN** a photo whose rows a de-selection removed is selected again
- **THEN** the next cycle records it as new work, re-uploads it to the same destination, and lists it again

#### Scenario: An un-read snapshot withholds the cycle and deletes nothing

- **WHEN** the app's upload cycle runs under a partial grant before any selection snapshot has been read,
  while the ledger holds admitted `DISCOVERED` and `COMPLETED` rows
- **THEN** the cycle is withheld: no row is deleted, no job is created, nothing is published, and the
  observer's first emission starts the cycle that runs over the real selection

#### Scenario: An un-read scope never answers empty

- **WHEN** the selection-scoped discovery is asked to discover or to resolve keys while the scope is
  `Unread`
- **THEN** the call fails, and no empty result is returned

