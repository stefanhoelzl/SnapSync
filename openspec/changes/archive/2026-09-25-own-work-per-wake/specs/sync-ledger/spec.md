## ADDED Requirements

### Requirement: An unchanged library is answered from the walk memo

The **app process** SHALL hold an in-memory **walk memo** for its full-grant library walk, and SHALL answer a
walk from it — without enumerating the library — when the library has not changed since the walk it holds.
A memo entry SHALL be keyed on all three of:

- the photo library's **change token** (`PHPhotoLibrary.currentChangeToken`), compared for equality;
- the membership's whole **selection policy** (capability `photo-selection-policy`) — a superset of the fetch
  predicate it narrows the walk by, keyed whole because that predicate is built only in the platform adapter —
  so a changed cutoff, window, origin exclusion or excluded-id set never reuses a walk made under another; and
- the **photo grant** the walk was made under.

A memo answer SHALL reproduce **exactly** what a fresh full walk would return — the same candidates, and the
same `fullEnumeration` report — which is what keeps it authoritative for deletion (see "Deletion is a
presence diff over an authoritative walk"). The decision it amends was that every walk is a full enumeration;
it becomes that **every walk's answer** is one (decision record: `changes/own-work-per-wake`, D9, amending
`changes/archive/2026-09-21-always-full-enumerate`).

The memo SHALL NOT upgrade a result that was not authoritative. Only a walk that completed, under a full
grant, over a readable library, SHALL be stored; an unreadable read, a walk abandoned by a stop, and any
answer under another grant SHALL NOT be. Because the grant is part of the key, an entry taken under one
grant is never served under another. The change token SHALL be read **before** the walk it is stored with,
so a change that lands while the walk runs leaves the stored token stale and the next walk enumerates
afresh — never the reverse.

The memo SHALL live in memory only: nothing SHALL persist it, and a new process starts without one and walks
afresh. It is therefore not the discovery cursor `always-full-enumerate` removed — no token is archived,
stored, loaded or cleared, and no change feed is read. The upload extension SHALL NOT hold one (capability
`ios-photokit-upload`, "In-extension discovery by full enumeration"): its 32 MB memory limit and its
per-`process()` lifetime leave neither room nor time for it to pay off.

Measured (SE2, iOS 26.6.2): reading and comparing the change token costs about **2 ms** in the background
(darwinbg) role, against **1.3–2.0 s** for a walk of 4.5k–6.3k assets. The token held steady across idle
periods and relaunches, moved on every asset creation and album add the app made, and was never equal
across a library change; after any write it kept moving for 1–9 s of trailing changes, which costs only
extra walks. A change made **outside** the process — the case the memo's soundness rests on — was verified
before the memo was relied on: on the SE2 (iOS 26.6.2, 2026-09-25) one photo taken with the Camera app moved the
token (the next walk was a memo miss and found the new photo; the token moved once more shortly after, which
costs one more walk), and on the iOS 26.5 simulator 15/15 external changes (adds via another process, favourites
and deletes in the Photos app, with the app foregrounded or suspended) moved it. iCloud sync remains unmeasured.
⏰ Re-measure at the next iOS major.

**The memo serves.** It SHALL be switched between serving and **shadow** only by a build constant, never by a
runtime or rig switch, because what it gates is a deletion authority and a build either relies on the token or
it does not. In shadow every walk enumerates the library, exactly as without a memo; a matching entry is only
**compared** with the fresh walk's answer, and a disagreement — the token did not move, yet the walk found other
candidates or another `fullEnumeration` report — SHALL be logged at `Error`, which reaches crash reporting. Shadow
is the revert, should the field ever show a stale answer. The token is read behind a need-named port with a
contract bound to a real implementation; where it cannot be read, the walk runs bare and nothing is memoised.

#### Scenario: An unchanged library is not enumerated again
- **WHEN** the app process walks the library under a full grant and walks again with
  the same selection policy while the library's change token is unchanged
- **THEN** the second walk is answered from the memo without enumerating the library, and returns the same
  candidates and the same `fullEnumeration` report the first did

#### Scenario: In shadow a matching entry is compared, not served
- **WHEN** the memo runs in shadow and a walk's key matches the memo entry
- **THEN** the library is enumerated anyway, the fresh answer is returned, and an answer that differs from the
  entry is logged at `Error`

#### Scenario: A library change forces a fresh walk
- **WHEN** the library's change token differs from the memo entry's
- **THEN** the walk enumerates the library afresh and replaces the memo entry

#### Scenario: A changed policy forces a fresh walk
- **WHEN** the membership's selection policy changes (a new cutoff or window) while the library is unchanged
- **THEN** the walk enumerates the library afresh under the new policy

#### Scenario: A grant change never reuses the memo
- **WHEN** the photo grant differs from the one the memo entry was taken under
- **THEN** the memo entry is not served

#### Scenario: A non-authoritative walk is never memoized
- **WHEN** a walk reports the library not readable, or is abandoned before it completes
- **THEN** no memo entry is stored from it, and the next walk enumerates afresh

#### Scenario: A change during the walk is not hidden
- **WHEN** the library changes while a walk is enumerating it
- **THEN** the memo entry carries the token read before the walk, so the next walk sees a different token
  and enumerates afresh

#### Scenario: A new process walks afresh
- **WHEN** the app process starts, in the foreground or the background
- **THEN** it holds no memo, and its first walk enumerates the library

## MODIFIED Requirements

### Requirement: The DISCOVERED state and the ledger as the upload work source

`LedgerState` SHALL carry a `DISCOVERED` value meaning **the resource's asset was admitted and its key needs an
upload job: no job is in flight for it and its bytes are not on the backend**. It SHALL be recorded for
every resource a cycle's walk admitted and the engine judged to be new work, **before** any upload
job is created for that cycle. It SHALL also be what a failed upload returns its row to — through the engine's
failure record or a transport's terminal write — because a
failure and a never-attempted discovery are the same fact to a producer. Nothing else returns a `REQUESTED` row
to it: there is no stranded reconciliation and no bulk demote, because nothing orphans a `REQUESTED` row any
more (decision record: `changes/both-uploaders-active`). There is no separate failed state:
the engine retries forever with no attempt budget, so "an attempt was already made" decides nothing.
`LedgerState` therefore has exactly three values: `DISCOVERED`, `REQUESTED` and `COMPLETED`.

The ledger SHALL be the upload cycle's **source of work**: a producer SHALL enqueue from the ledger's
rows rather than from the walk's return value, so a cycle can make progress on work it already knows
about whatever the walk returns. Every walk's answer is a full enumeration — read afresh, or reproduced by the
app process's walk memo (see "An unchanged library is answered from the walk memo") — but the cycle re-reads
resources only for assets the ledger does not fully know (see "A walk re-reads only the assets the ledger does not fully know"), so a
row that needs a job is found by this read, never re-derived by the walk. The `LedgerStore` SHALL expose a
state-scoped read of the rows that need a job, and it SHALL return the `DISCOVERED` rows — whether never
attempted or returned there by a failure.

A cycle in **either** process SHALL pick its work only from these `DISCOVERED` rows, and SHALL NOT create a job
for a `REQUESTED` or `COMPLETED` row. With both uploaders active over one ledger, that is what keeps a second
cycle off the first one's in-flight work: a key the first cycle recorded `REQUESTED` is never offered to the
second, so only two cycles picking the same `DISCOVERED` key before either records can duplicate an upload —
identical bytes to the same destination, converged by the guarded terminal write (see "Reader and writer
capability split").

A row needing a job records that the policy admitted its asset **when the row was written**, which is not
the same fact as the membership's *current* admission (`photo-selection-policy`). The cycle SHALL
therefore admit the rows this read returns before resolving or enqueuing any of them, and any bound on
how much work one cycle takes SHALL be applied to the **admitted** rows — bounding what a cycle
**resolves**, never what it reads. A bound applied to the read instead can starve: rows are returned in a
stable key order, so excluded rows sorting ahead of admitted ones would fill the bound on every cycle and
admitted work further down would never be reached. The cycle takes the admitted rows **one at a time**
and creates each one's job until the platform refuses (`LIMIT_EXCEEDED`), so a refusal wastes no resolve.
Creation is bounded only by the platform's refusal, which follows the admitted rows (capability
`sync-engine`; decision records: `changes/both-uploaders-active`, and `changes/selection-is-the-walk` D5,
which retired the resolve chunk).

A row whose resources **this cycle's walk already read** SHALL be created from the resource that walk read —
the very handle a resolve would return, read through the same port moments earlier — and SHALL NOT be resolved
a second time. Only a row the walk did not read (a failure returned to `DISCOVERED`, or a truncated cycle's
remainder whose asset the ledger already fully knows) SHALL be resolved through the port, and only a miss
**there** is evidence that its asset is gone. The shape is unchanged: one row at a time, stopping at the first
refusal before the next row is looked up. The second round-trip bought nothing but its cost — about 4.5 ms per
request plus 3.45 ms per photo, measured, which doubled the per-photo PhotoKit cost of a cycle that discovered
a fresh batch (decision record: `changes/own-work-per-wake`, D13).

A row this read returned whose key the platform resolves to **nothing** SHALL have that row, and only that
row, deleted (see "Deletion is a presence diff over an authoritative walk"). Its asset has left the library,
or, under a partial grant, left the selection. Either way it can no longer be uploaded, and a row that
still needs a job for it would be offered on every cycle. Deleting by key rather than by asset is what
leaves the asset's settled rows alone: this read selects rows by key.

`DISCOVERED` SHALL NOT be a done state, so a row in it counts toward the backlog everywhere. It SHALL
nonetheless be **included** in the device-manifest projection: the manifest declares what this device
intends to provide, and a resource the walk found and the policy admitted is precisely that (capability
`device-manifest`).

#### Scenario: A discovered resource is recorded before any job exists

- **WHEN** a cycle's walk admits a resource the engine judges to be new work
- **THEN** a `DISCOVERED` row is recorded for that resource's key before `createJob` is called for it

#### Scenario: A top-up enqueues from the ledger, not from the walk's output

- **WHEN** a cycle runs with rows in `DISCOVERED` and its walk returns no asset it has not
  already recorded
- **THEN** it resolves those rows' keys and enqueues them, rather than treating a walk with nothing new as
  no work

#### Scenario: A row the walk just read is not resolved again

- **WHEN** a cycle's walk reads a new asset's resources and records their rows `DISCOVERED`
- **THEN** the cycle creates those rows' jobs from the resources the walk read, and makes no resolve call for
  them

#### Scenario: Only a row the walk did not read is resolved

- **WHEN** a cycle's enqueue pass holds one row its walk just read and one failed row whose asset the walk
  skipped
- **THEN** only the failed row is resolved through the port, and both rows' jobs are created

#### Scenario: A failed row is re-enqueued without re-reading its asset

- **WHEN** a row's upload failed, returning it to `DISCOVERED`, and its asset is fully recorded, so the walk
  skips its resources
- **THEN** the next cycle re-enqueues it from the ledger, rather than waiting for the walk to re-derive it

#### Scenario: The ledger has three states

- **WHEN** the `LedgerState` values are listed
- **THEN** they are exactly `DISCOVERED`, `REQUESTED` and `COMPLETED`, and `DISCOVERED` is the only state that
  needs a job

#### Scenario: A row that no longer resolves is deleted by key

- **WHEN** a `DISCOVERED` row's key resolves to nothing at enqueue, while a sibling row of the same asset is
  `COMPLETED`
- **THEN** the unresolved row is deleted, the `COMPLETED` sibling is untouched, and no upload is attempted
  for the deleted key

#### Scenario: A discovered row is backlog AND manifest

- **WHEN** a row is `DISCOVERED`
- **THEN** it counts toward the pending aggregate and the pending-resource read, and it appears in the
  device-manifest projection for every membership whose policy admits its asset

#### Scenario: A second cycle does not re-pick an in-flight row

- **WHEN** one cycle has created a job for a key and recorded it `REQUESTED`, and a cycle in the other process
  then reads the work source
- **THEN** that key is not among the rows needing a job, and the second cycle creates no job for it

#### Scenario: The bound applies to the resolved work, not the read

- **WHEN** the rows needing a job exceed what the platform accepts in one cycle and some of them are excluded
  by the membership's current policy
- **THEN** the cycle resolves and creates only **admitted** rows until the platform refuses, and the excluded
  ones neither consume the bound nor prevent admitted rows from being enqueued

#### Scenario: An excluded row is retained, not pruned

- **WHEN** the membership's policy stops admitting an asset whose row needs a job
- **THEN** the row is left in the ledger untouched — no state change, no prune — so widening the policy
  again re-admits it and the next cycle enqueues it with no re-read of its asset

### Requirement: Deletion is a presence diff over an authoritative walk

The upload cycle SHALL delete a ledger row because its asset left the library **only** when all of the
following hold:

1. **The walk is authoritative.** The cycle's discovery reported `fullEnumeration`. Either it read the
   library itself under a full grant and the read succeeded, so every asset inside the policy's capture
   window was returned; or, under a partial grant, it returned a selection snapshot that **has been read**,
   which is the whole gallery from the app's point of view (capability `limited-photo-access`). An
   unreadable library returns nothing and is not evidence of absence. A selection that has not been read
   yet never reaches a walk: the app's cycle is withheld while it is unread. In the app process a
   full-grant walk MAY be answered from the walk memo; that answer is authoritative exactly when, and
   because, it reproduces what a fresh full walk would return under the same grant (see "An unchanged
   library is answered from the walk memo"). A walk abandoned before it completed — stopped because the
   OS signalled that background time is up (capability `ios-app-shell`) — is not authoritative: its
   decide stage writes nothing, and the next wake walks again. A walk that is not authoritative SHALL
   delete nothing.
2. **The row is inside the walk's window.** The row's asset is admitted by the membership's policy through
   the same row-admission derivation the device manifest and the enqueue use (`admittedAssetIds`). The
   ledger holds rows outside the window — the join-time load seeds everything the device ever stored, for
   any event — and the walk is bounded by the policy's capture range, so a row outside that range is not
   evidence either way. A bare row (empty `creationDate`) is never admitted, so it is never
   deleted this way.
3. **The asset is absent from the walk.** Presence SHALL be the asset ids of **every** candidate the walk
   returned, before admission. Being in the library is not a question of scope.

A row's upload state SHALL NOT exempt it. A `REQUESTED` row is deleted like any other. Its transfer may
still complete, and then its guarded terminal write matches no row and applies to nothing ("Guarded
terminal write"). The bytes land and are listed in no manifest, because the manifest projects the rows. A
failure or retry the platform later presents for that key SHALL write nothing and SHALL NOT be retried
(capability `upload-lifecycle`, "A presented job whose row is gone is answered and nothing more"). The
earlier exemption kept a photo that had left the library (or the selection) listed until its job settled.
It existed only because a late terminal write for a missing row was treated as an anomaly, and it no longer
is one (decision record `changes/selection-is-the-walk`, D2).

The cycle SHALL decide the deletion from the same walk that supplies presence, and SHALL apply it through
`deleteKeys` before it records that walk's discoveries and before it publishes the device manifest, so a
departed photo is never listed by the cycle that saw it leave.

A second path deletes one row at a time: a ledger key the cycle asked the platform to resolve for a job that
resolves to **nothing** SHALL have **that row** deleted, and no other (see "The DISCOVERED state and the
ledger as the upload work source"). It needs no authoritative gate, because it only ever reaches a row that
still needs a job, and deleting one costs a re-discovery, never a photo. It SHALL NOT be reached with a
selection that has not been read: an unread scope answers no resolution at all (capability
`limited-photo-access`), because an empty answer there would delete every admitted row that needs a job.

#### Scenario: A departed in-window asset's rows are deleted
- **WHEN** an authoritative walk does not return asset `X`, whose `COMPLETED` rows carry a capture date the
  policy admits
- **THEN** the cycle deletes those rows before recording its discoveries, and the manifest it publishes no
  longer lists `X`

#### Scenario: A row outside the walk's window is kept
- **WHEN** the ledger holds a `COMPLETED` row whose capture date is before the membership's cutoff (dated
  while an earlier cutoff admitted it), and an authoritative walk does not return its asset
- **THEN** the row is kept

#### Scenario: A bare row is never deleted by the walk
- **WHEN** the ledger holds a row with an empty `creationDate` and an authoritative walk does not return its
  asset
- **THEN** the row is kept

#### Scenario: A read selection snapshot deletes a de-selected photo's rows
- **WHEN** the cycle runs under a partial grant with a read selection snapshot, and the member has
  de-selected a photo whose `COMPLETED` row is in-window
- **THEN** the discovery is authoritative, the photo's rows are deleted, and the manifest that cycle
  publishes no longer lists it

#### Scenario: An un-read selection deletes nothing
- **WHEN** the app's cycle runs under a partial grant before the selection has been read
- **THEN** the cycle is withheld and no row is deleted, by the walk or by the enqueue's resolve

#### Scenario: An unreadable walk deletes nothing
- **WHEN** the platform reports the library not readable and the discovery returns no candidates
- **THEN** no row is deleted

#### Scenario: A memoized walk deletes as a fresh walk would
- **WHEN** the app's cycle runs under a full grant on an unchanged library, its discovery is answered from
  the walk memo, and the ledger holds an in-window row whose asset the memoized walk did not return
- **THEN** the discovery is authoritative and the row is deleted, exactly as a fresh full walk would have
  deleted it

#### Scenario: An abandoned walk deletes nothing
- **WHEN** a walk is stopped before it completes because the OS signalled that background time is up
- **THEN** its cycle deletes no row and records nothing from it, and the next wake's walk decides afresh

#### Scenario: An in-flight row is deleted with its asset
- **WHEN** an authoritative walk does not return asset `X`, one of whose rows is `REQUESTED`
- **THEN** all of `X`'s in-window rows are deleted, including the `REQUESTED` one, and the manifest that cycle
  publishes no longer lists `X`

#### Scenario: A late completion for a deleted row writes nothing
- **WHEN** the transfer of a `REQUESTED` row the walk deleted then succeeds
- **THEN** its guarded terminal write applies to no row, no row is created, and `X` stays unlisted

#### Scenario: A late failure for a deleted row writes nothing
- **WHEN** the platform presents a failure for a key whose row the walk deleted
- **THEN** no row is recorded for that key, no retry is made, and no job is created

#### Scenario: A resolve failure deletes only its own key
- **WHEN** a `DISCOVERED` row `X-live.mov` resolves to nothing while its sibling `X-primary.heic` is
  `COMPLETED`
- **THEN** `X-live.mov` is deleted and `X-primary.heic` is untouched

### Requirement: A walk re-reads only the assets the ledger does not fully know

The upload cycle SHALL read an admitted candidate's resources **only** when the ledger does not fully know
its asset. Every upload walk's answer is a full enumeration — whether read afresh or reproduced by the app
process's walk memo — so reading every admitted candidate's resources would repeat one synchronous platform
round-trip per photo on every cycle, for photos already recorded. The memo spares the enumeration, never
this skip: a memoized answer is decided against the ledger exactly as a fresh one is. A
candidate's resources are read when the ledger holds no row for its asset, or when at least one
of its asset's rows is bare (its manifest detail is not yet filled). Every other admitted candidate SHALL be
skipped. An uploaded resource is immutable and the ledger keeps no content version, so re-reading it could
only answer "already uploaded". A row that still needs a job is picked up from the ledger, not from the walk.

The skip is sound only if an asset is never partly recorded. The cycle SHALL therefore record the new
`DISCOVERED` rows of one walk with **one** batch record write, `recordAllUnlessSettled(entries)`. That write
applies each entry under the same done-state guard as the single record write, in **one storage
transaction**, and signals `changes` once if any entry applied. A process death then leaves either all of a
walk's new rows or none of them, never one role of a photo whose other role is skipped forever.

The join-time load produces bare rows, so an asset whose stored-file listing named only some of its roles is
read again, and its missing roles are discovered.

#### Scenario: A fully-known asset is not re-read
- **WHEN** an authoritative walk returns an admitted asset all of whose rows exist and carry manifest detail
- **THEN** the cycle does not read that asset's resources

#### Scenario: An unknown or bare asset is read
- **WHEN** an authoritative walk returns an admitted asset with no row, or with a bare row
- **THEN** the cycle reads its resources, records new work `DISCOVERED`, and fills the bare rows' detail

#### Scenario: A walk's discoveries land together or not at all
- **WHEN** a walk discovers a Live Photo's primary and paired-video resources, and the process dies during
  the batch record write
- **THEN** afterwards the ledger holds either both rows or neither, so the next walk either skips a fully
  recorded asset or reads it again

#### Scenario: A partial seed is completed by the walk
- **WHEN** a join-time load seeded only `X-primary.heic` (bare), and the walk returns `X`
- **THEN** the cycle reads `X`'s resources, fills the primary row's detail, and records `X-live.mov`
  `DISCOVERED`

