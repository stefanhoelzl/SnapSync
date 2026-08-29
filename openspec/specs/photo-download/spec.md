# photo-download Specification

## Purpose

The **receive** half of an event: a joined device automatically downloads the *other* contributors' complete
assets from the event-wide union listing and imports them, full-fidelity, into the system Photos library — so
a shared event's photos appear on every participant's phone without anyone opening the app.

This is what turns a one-way contribution client into photo sharing. Foreign assets are selected by
`deviceId` (anything not this device's); transfers run over Wi-Fi and cellular on a `URLSession` whose
binding is fixed by the compilation target — a **background** one on every shipped binary;
import preserves the original capture date so photos sort by when they were taken. Downloaded photos are
suppressed from re-upload — no echo — and a photo the user deletes locally is never re-imported, because
respecting a deletion matters more than completeness.

The app renders **no gallery of its own**: collected photos live in the camera roll (and, per `event-album`,
in a per-event album). Whether this device downloads at all is governed by the membership's participation
direction (`join-event`).

Decision record: `changes/archive/2026-06-30-add-photo-download` (the download client),
`changes/archive/2026-07-12-fix-download-session-lifecycle` (the transfer/session lifecycle: why
cancellation is task-level and never invalidates the background session, and the transport seam that puts
that logic under test),
`changes/bind-transport-session-by-target` (why the session's binding is fixed by the compilation target,
so downloads land on a simulator too — that record supersedes this one's D5 closing line that "downloads
remaining inert on the simulator is a known, accepted limitation", while leaving D5's refusal of a
*runtime* host downgrade intact);
`changes/archive/2026-08-27-stop-repeating-futile-import-work` (why adjudication is a once-per-process
recovery sweep rather than every trigger's first act, why an import the library cannot perform settles
terminally and is reported at `Error`, and the measured ingest-time move semantics both rest on).

## Requirements

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

### Requirement: Background resource download to durable staging

Selected resources SHALL be downloaded via a `URLSession` whose configuration is bound per **compilation
target** (`ios-url-session-upload`, "The transport binding is fixed by the compilation target"): on
`iosArm64` — every shipped binary — a **background** session, so transfers continue while the app is
suspended and the OS relaunches the app on completion; on `iosSimulatorArm64` a **default** session, which
transfers in-process and survives neither suspension nor process death. The download transport SHALL
obtain that configuration from the same seam the app-driven upload platform uses, so the two cannot hold
different bindings in one build. Every requirement below holds identically on both targets.

The session SHALL fetch each resource **directly** from the presigned S3 URL carried in its union
`resource.url` (capability `api-endpoints`) — i.e. straight from bunny's S3 endpoint, not through
the backend. The session SHALL allow **both Wi-Fi and cellular** (not Wi-Fi-only) and SHALL NOT be
discretionary, so downloads make progress on mobile networks too, with a bounded number of in-flight
tasks (enqueue more as tasks complete). (`discretionary` and the launch-events request are background-only
properties; on the default binding they are inert, which is why they are declared where the background
configuration is built rather than at this call site.) A transfer failure SHALL leave the resource pending
for retry (no terminal failure state).

A finished transfer is not a successful one, and SHALL NOT be staged on the strength of having finished.
Before its bytes are moved into staging, the download client SHALL evaluate the transfer's outcome — the
HTTP status, the expected byte count, and the received byte count — and SHALL treat as a **failed**
transfer, to be retried:

- a response whose status is not a success status (`2xx`); and
- a response whose received body is **shorter than its `Content-Length`** (a truncated download) — the
  integrity signal formerly guaranteed by the download proxy, now evaluated against bunny's S3 GET response.

A status check is not redundant with the transport's own error reporting: a `URLSession` reports
an HTTP error as a *successful transfer of an error body* — the download-finished callback fires with the
error document in hand and the completion error is absent — so the status check is the only thing that sees
a `502`.

A transfer SHALL be rejected only on **positive evidence** that its bytes are bad, never on the absence of
evidence. Where the expected byte count is **unknown** (the server omitted `Content-Length`), the transfer
SHALL NOT be rejected for length; the status check stands alone. A received count **exceeding** the expected
count SHALL NOT be rejected either. Where the status is unknown, the transfer SHALL NOT be rejected for
status. Only a known-and-under count is a truncation, and only a known non-2xx status is a failure. The
reason is not caution but arithmetic: a rejected transfer is retried, and a retry only helps when the
condition can change. A server that omits `Content-Length` omits it on every attempt, so rejecting on an
unknown length is not a retry — it is an unbounded loop in which the photo never arrives, the same
permanent, invisible loss as accepting bad bytes, reached from the other side.

An accepted transfer's bytes SHALL be moved out of the system temporary location into a durable App-Group
staging location and recorded in the download store. A **rejected** transfer's bytes SHALL NOT be moved or
recorded: the resource stays un-staged, which is the existing pending-for-retry state rather than a new
terminal one, and the next reconcile re-downloads it. Because staging replaces whatever occupies the
destination path, evaluating the outcome **before** the move also prevents a rejected body from destroying
an earlier good file. The finished-transfer callback delivers a temporary-location URL on **both**
bindings, and the move out of it SHALL be performed within that callback on both, so the staging step does
not vary by target.

The transport seam SHALL carry these outcome facts to the client, and the decision SHALL be taken in code
covered by `commonTest` rather than in the platform edge, which is the platform boundary and nothing more.

#### Scenario: A completed download is staged durably

- **WHEN** a download task for a resource finishes with a success status and no short read
- **THEN** its bytes are moved to durable App-Group staging and the resource is marked downloaded in
  the store

#### Scenario: The shipped binary downloads over a background session

- **WHEN** the app runs on a physical device
- **THEN** transfers run over a background `URLSession` and continue while the app is suspended

#### Scenario: A simulator downloads, and claims nothing about suspension

- **WHEN** the app runs on an iOS simulator and a foreign resource is enqueued
- **THEN** the bytes transfer over the default session, are staged and imported exactly as on a device, and
  the run is not treated as evidence that transfers survive suspension or that the OS relaunches the app

#### Scenario: A resource is fetched directly from bunny's S3 endpoint

- **WHEN** a resource is enqueued for download
- **THEN** the task targets its presigned S3 `url` directly (no backend byte proxy), needing
  no per-task authorization header

#### Scenario: A failed transfer is retried, not failed

- **WHEN** a download task completes with an error
- **THEN** the resource remains pending and is retried on a later sync; no terminal failure state is
  recorded

#### Scenario: A short read is treated as a failed download

- **WHEN** a download returns a body shorter than its `Content-Length`
- **THEN** the transfer is treated as failed and retried; the truncated bytes are not accepted as the
  complete object

#### Scenario: A non-2xx response is not a photo

- **WHEN** a transfer finishes with status `502` and an error body, and the transport reports no error
- **THEN** it is treated as a failed transfer and retried; its bytes are not staged

#### Scenario: An unknown Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and the response carries no `Content-Length`
- **THEN** it is accepted and staged, because an unknown expected count cannot establish a short read, and
  rejecting it would recur on every retry rather than resolve

#### Scenario: A body longer than its Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and a received body larger than its `Content-Length`
- **THEN** it is accepted and staged, because an over-long body is not a short read

#### Scenario: Rejected bytes never become the store's truth

- **WHEN** a transfer is rejected on status or length
- **THEN** the resource is not recorded as staged, so the next reconcile re-downloads it rather than
  re-importing it

#### Scenario: A rejection does not destroy an earlier good file

- **WHEN** a transfer is rejected and a previously staged, valid file already exists at that resource's
  staging path
- **THEN** the existing file is left intact, because a rejected transfer's bytes are never moved into staging
### Requirement: Expired presigned download URLs self-heal on rediscovery

A presigned download `url` that expires before its background transfer runs SHALL be **superseded by
a freshly-minted URL** on the next foreground reconcile and retried — so an expired link recovers
automatically rather than failing permanently. The `url` is a **time-limited** presigned S3 URL
(7-day expiry); because the client re-reads the union on join and on every foreground entry (see
"Foreground-only discovery of later additions"), and the download store refreshes the stored `url` of
a **not-yet-staged** resource from that read (capability `download-store`), that supersession happens
on its own. The client SHALL NOT need any credential to fetch a presigned URL; the query signature is
the sole authorization.

#### Scenario: An expired link is re-presigned and retried

- **WHEN** a resource's presigned `url` expires before its background download completes, and the app
  is next foregrounded
- **THEN** the re-read union supplies a fresh presigned `url`, the store replaces the pending resource's
  stale url with it, and the transfer is retried against the fresh url

#### Scenario: Staged and imported resources are not disturbed by re-presign

- **WHEN** the union is re-read while some of an asset's resources are already staged or the asset is
  already imported
- **THEN** only not-yet-staged resources take a refreshed url; staged bytes and terminal rows are
  untouched

### Requirement: Full-fidelity per-asset import into the camera roll

When **every** resource of a foreign asset is staged, the client SHALL import the asset with a single
`PHAssetCreationRequest` adding all of its resources, mapping each resource by `role`: `live` →
`.pairedVideo`; `primary` → `.photo`/`.video`/`.audio` selected by its `contentType`. An unrecognized
`contentType` SHALL be logged and skipped, not force-imported. The asset SHALL be imported into the
photo library (camera roll). The import SHALL reuse the existing full-library-access grant and add no new
permission state.

Each created resource SHALL carry an **explicitly supplied** human filename: the `filename` the owning
device published for that resource in its manifest (`device-manifest`), carried through the union read
and the download store. The importer SHALL NOT let the platform infer a name — a resource created with
no naming options is named after the file it is created from, and staged files are named by their
**storage object key** (`"<assetId>-<role>.<ext>"`, `sync-ledger`), which would present the internal key
and its role token to the user as the photo's name. Where the published `filename` is absent (the
manifest row was never enriched — a row predating the manifest-detail columns, or one the re-join
reconcile seeded from a filename listing), the object key SHALL be used: it is what the bytes are
actually called, and an imported resource SHALL never be left unnamed. Each resource of a multi-resource
asset SHALL be named independently, so a Live Photo's still and its paired video keep their own names.

The naming rule SHALL live in `:domain` `model/` as a pure function and be covered by `commonTest`
(running on JVM and the iOS simulator), not inside the PhotoKit adapter — the same placement, and for
the same reason, as the upload-key layout it falls back to.

The filename is **display metadata on the receiving device**: no upload key, ledger key, or suppression
handle is derived from it, so naming an imported asset SHALL NOT cause any resource to re-upload.

When the membership opted into an event album (`EventConfig.saveToAlbum`, capability `event-album`) and
that album already exists, the importer SHALL, **in the same `PHPhotoLibrary.performChanges` commit** as
the creation, add the newly-created asset to the event album (via the album's
`PHAssetCollectionChangeRequest` and the creation request's placeholder), so a received photo is
atomically already-in-the-album and never briefly loose. The album identifier SHALL be sourced from the
shared `eventId → albumLocalId` map via an injected lookup; when no album identifier is available (album
not yet created, or `saveToAlbum` is false), the importer SHALL import into the camera roll only and add
to no album. The album add SHALL be best-effort — it SHALL never fail or defer the import.

#### Scenario: A Live Photo round-trips

- **WHEN** a foreign Live Photo's `primary` (image) and `live` (paired video) are both staged
- **THEN** one `PHAssetCreationRequest` recreates a working Live Photo in the library

#### Scenario: Import waits for the complete resource set

- **WHEN** only some of an asset's resources are staged
- **THEN** the asset is not imported until every resource is staged

#### Scenario: Imported asset lands in the camera roll

- **WHEN** an asset import succeeds for a membership that did not opt into an album
- **THEN** the asset is present in the photo library and is added to no SnapSync album

#### Scenario: An imported photo carries the capturing device's filename

- **WHEN** a foreign asset whose manifest publishes `filename: "IMG_4471.HEIC"` is imported
- **THEN** the created resource is named `IMG_4471.HEIC`, and neither the `assetId` nor the `-primary` /
  `-live` role token of its storage object key appears in the name

#### Scenario: A resource with no published filename keeps the object key

- **WHEN** a foreign asset is imported whose manifest carries no `filename` for a resource
- **THEN** that resource is named by its storage object key, and is never left unnamed

#### Scenario: Naming an import re-uploads nothing

- **WHEN** an imported asset is named from the capturing device's filename
- **THEN** its upload key, ledger key, and suppression handle are unchanged, and no resource re-uploads

#### Scenario: An album-opted import lands atomically in the album

- **WHEN** an asset import succeeds for a `saveToAlbum` membership whose event album exists
- **THEN** the asset is created and added to the event album in a single commit, never appearing outside it

#### Scenario: Import proceeds when the album is not yet created

- **WHEN** an import runs for a `saveToAlbum` membership before the event album has been created
- **THEN** the asset is imported into the camera roll and no album add is attempted for it

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
The imports that a background-session wake triggers SHALL be **awaited** by that wake — the staged-resource
callback SHALL be awaitable and its outstanding work tracked by the download-job owner, rather than
dispatched and forgotten by the composition — so the wake reports itself finished only when its work is.
Reporting the session's events drained while the imports they caused are merely queued is what leaves an
asset staged-but-unimported at suspension.

An import that never reports is the one case that awaiting cannot resolve, and it is bounded at the **wake**
rather than at the import: the OS completion handler is released on its own per-entry-point deadline and the
import is left running (capability `ios-app-shell`). Nothing bounds a single import in time. A wall-clock
bound on one import expires against transactions that are alive — the process is suspended for arbitrary
spans between a change block and its completion — and every expiry manufactures an unconfirmed row for the
adjudication guard to reason about. The stalled import blocks no other work, because it does not hold the
download controller's lock and its ref is claimed rather than serialised.

Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry. The backstop's coordination — the trigger-time membership re-read
(`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail cleanly
before first unlock*), the attestation wake, then the import drain — SHALL be the
`flow/DownloadBackstop` trigger (`:domain` `flow/`, built in `compose/` with the re-read and wake
injected as **suspend** effect lambdas, per the law *A trigger flow never outlives its own run*); the
untested app shell keeps only the entry-point log wrap, the re-arm, and the OS task-completion handler.
A backstop wake landing before the first unlock since boot fails cleanly and converges at the next wake
(the import's reads are caught; the adapters distinguish unreadable from absent; nothing mints, clears,
or leaves).

That last property is **conditional, and the transfer check is its condition**. A deferred import is a safe
retry only because staged bytes were accounted for at transfer time. Absent that check, a permanently
invalid body — an error document staged under a photo's path — makes the retry a trap rather than a
safeguard: the import fails on every reconcile, and the transfer is never re-run, because a resource
recorded as staged is never re-planned. The asset is then permanently unimportable and permanently retried,
and the photo never arrives. Retrying a failed import is correct for a transient failure and poison for
invalid bytes; only rejecting bad bytes before staging keeps the two apart.

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background

#### Scenario: A wake awaits the imports it triggered

- **WHEN** a background-session wake delivers several staged resources
- **THEN** the imports they trigger are awaited, so the wake does not report itself finished while they are
  merely queued

#### Scenario: A wake whose import never reports still answers the OS

- **WHEN** an import a wake triggered never receives its completion
- **THEN** the OS completion handler is released on that entry point's deadline, the import is left
  running rather than cancelled, and no other reconcile, import, leave or switch is blocked by it

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because the library has not yet reported — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before a
process imports anything, it SHALL ask the photo library about the unconfirmed rows it inherited, and act
on the answer:

- **present** — settle the row against the marker it already holds and create nothing;
- **absent, and the row's staged bytes are gone** — settle the row against the marker it already holds,
  exactly as for *present*. The photo library consumed those bytes when it ingested them, which it does
  only as part of creating an asset, so their absence is positive evidence that a creation was submitted;
- **absent, and the row's staged bytes are still present** — clear the marker, then import, **but only if
  no import for that ref is running in this process**;
- **absent, and the row records no staged resources at all** — treat exactly as *unknown*;
- **unknown** — do nothing this pass, and retry later.

**Adjudication is a recovery sweep, not a gate on the import path.** A row carrying a marker is already
excluded from importable work and already suppresses uploads, so no second asset can be created whether or
not the sweep runs; what the sweep does is *release* rows that are otherwise stuck. Its dominant outcome is
**present**: a `performChanges` commit survives the death of the process that opened it (measured), so the
ordinary post-death case is an asset that exists, whose row must be settled, whose staged bytes must be
released, and whose absence from the imported count otherwise pegs the status screen below 100% forever.

The sweep SHALL run **exactly once per process, from a single call site**, and SHALL NOT be performed at
any other entry point. Running it per reconcile, per import pass, or per staged resource asks the library
about the import currently in flight — whose transaction is open, so the answer can only be *absent*, and
whose *absent* the gate below is required to discard. Only a process that has died can leave a row no
running import will settle, and only a later process can adjudicate it.

The sweep SHALL run **after** the client's photo-permission subscriptions are installed. Under a partial
grant the answer comes from the held selection snapshot, which is empty until its first observer emission;
a sweep that precedes it answers *unknown* for every row, and with a single per-process sweep that row
then waits for the next launch rather than for the next pass.

The sweep SHALL be followed by an import drain in the same process. The *absent* branch clears a marker,
which is what returns the row to importable work; a clear that nothing then imports moves the stall
instead of ending it.

An *absent* answer SHALL NOT be acted on while an import for that ref is **claimed** — that is, from the
moment the ref is taken out of circulation for an import until the library reports that import's outcome.
The library answers about **committed** state, so it answers honestly that an asset does not exist while the
transaction creating it is still open; acting on that clears the marker of an asset that does exist, drops it
from the suppression set, and the device uploads a downloaded photo back into the event. A claimed ref SHALL
therefore be treated exactly as *unknown*.

The gate SHALL be the claimed/not-claimed **fact**, never an elapsed-time estimate of it. The process is
suspended for arbitrary spans between a change block and its completion, so any wall-clock bound expires
against transactions that are alive.

The record of claimed refs SHALL NOT survive the process. It describes imports running **here**, and a
process that has ended is running none; a durable record would distrust a ref forever and its photo would
never arrive. This SHALL NOT be justified by the claim that a transaction cannot outlive the process that
opened it — that premise was measured and is **false** (SE2, iOS 26.5.2, 2026-08-09: a SIGKILL 200 ms after
the change block returned still left the asset in the library).

Because a commit outlives its process, a relaunch can adjudicate a row **while that commit is still in
flight**, and the library then answers *absent* about an asset that is about to exist. That is not a
theoretical window: measured across two hosts, 8 of 9 runs at 25-43 MB reproduced it, and killing the
client widens the commit window 2-3x over the same import with a live client. The staged bytes are what
close it — they are already gone at that moment, and nothing but the library's ingest can have taken them.
A relaunch SHALL therefore NOT be treated as making *absent* actionable on its own; only the staged bytes
still being present makes it actionable.

**Every** verdict SHALL be applied through a store write that is **guarded on the row's current marker**, and
SHALL take effect only while the row still carries the marker the verdict was computed for. This applies to
*present* and to *absent* alike: verdicts are computed outside the download controller's lock, while the
photo library's completion callback settles rows from the platform's own queue holding no lock. Applying a
stale *present* records an import against a marker the row no longer holds; applying a stale *absent* strips
the marker from a row the completion has already settled, leaving its asset in the library with nothing
recording that it must not be uploaded — and because that row is terminal it is never adjudicated or
re-imported again, so the loss is permanent. The guard SHALL live in the store's write rather than in a
caller's preceding read, because the two writers reach it with no shared lock and a read-then-write pair is
not atomic against the one that does not take it.

Clearing the marker **before** importing on *absent* is required: an import that fails before reaching
the change block (an unmapped resource type, for example) would otherwise leave the stale marker in
place and the row would be skipped on every future pass.

The lookup SHALL be **batched** — one query for every inherited unconfirmed row at once — and SHALL NOT be
performed at all when no row carries a marker, which is the ordinary case and, with a single per-process
sweep, the case in every process that did not inherit interrupted work.

The claimed/not-claimed gate SHALL be read **under** the download controller's lock, together with the
write it guards — never sampled before acquiring it. Reading it earlier reproduced this defect on real
hardware once already: the gate answer went stale while the adjudication queued on the lock, and the marker
of a live asset was cleared. The record of claimed refs is also mutated only under that lock, so reading it
from outside is a data race besides. With the sweep running before the process claims anything, the gate is
structurally satisfied rather than merely read in the right place; it is retained because that property
depends on there being one call site, and a guard that costs nothing SHALL NOT be the thing a future call
site silently removes.

The lookup SHALL run **outside** the download controller's lock; only the gate and the verdicts' guarded
writes run under it. The library lookup is a synchronous, thread-blocking call that no timeout can abandon
(cancellation is cooperative), so performing it under the lock would let a stalled photo library block every
reconcile, import, leave, and switch. The adapter SHALL own its dispatcher hop for the same reason.

A *present* verdict SHALL also release that ref's claim. The write that precedes it has already made the row
terminal, so no reader can distinguish a released claim from a retained one; what the release buys is a
bounded set rather than a behaviour.

A row settled on the strength of consumed bytes SHALL be settled **against the marker it already holds**,
never against a fresh one, and SHALL be indistinguishable downstream from one settled by a *present*
verdict. Where that row's commit had in fact failed content validation, the marker then names an asset that
does not exist. That is accepted and SHALL be stated rather than corrected: such a marker is inert — the
suppression projection is compared against assets the library actually holds, so an entry matching none is
never compared to anything — while the alternatives either strip a live asset's only suppression handle or
leave the row outstanding forever, pegging the download total below completion. The cost is that the photo
is counted as imported although it never arrived; its bytes were consumed, so no path could have recovered
it.

#### Scenario: A relaunch after an interrupted import creates no second asset

- **WHEN** an import records its created asset, the process ends before the import is confirmed, and a
  later pass reaches the same asset
- **THEN** the library is asked, the asset is found, the import is recorded against the existing created
  asset, and no second asset is created

#### Scenario: A burst asks the library nothing

- **WHEN** a process that inherited no unconfirmed rows downloads and imports many assets, staging
  resources and draining imports throughout
- **THEN** the photo library is asked about presence exactly zero times, because the only sweep ran at
  process start and found no row carrying a marker

#### Scenario: A staged resource does not trigger a sweep

- **WHEN** a resource finishes downloading and is staged, while an import for another asset is in flight
- **THEN** no presence lookup is performed, and no verdict about the in-flight import is computed or
  discarded

#### Scenario: The sweep precedes the first import of the process

- **WHEN** a process starts and download work is entered by any trigger
- **THEN** the sweep has completed before that process creates any asset

#### Scenario: A partial grant is not swept before its selection is known

- **WHEN** a process starts while the app holds a partial photo grant
- **THEN** the sweep runs only after the selection subscription's first emission, so an answerable row is
  not recorded as unknown

#### Scenario: A cleared marker is imported in the same process

- **WHEN** the sweep clears the marker of a row whose asset is definitively absent
- **THEN** an import drain runs in that same process and imports it, rather than leaving it for a later
  trigger

#### Scenario: An absent answer about a claimed import is not acted on

- **WHEN** an import for a ref is claimed, its outcome has not been reported, and the library answers that
  the created asset is absent
- **THEN** the marker is kept, nothing is imported, and the row is retried on a later pass

#### Scenario: A relaunch makes absence actionable again

- **WHEN** the process ends while a ref is claimed and a later process adjudicates the same row
- **THEN** the ref is not treated as claimed, because the claim recorded imports running in a process that
  no longer exists

#### Scenario: A stale absent verdict does not clear a settled row's marker

- **WHEN** an *absent* verdict is computed for a ref, and the library's completion settles that row
  before the verdict is applied
- **THEN** the guarded write matches no row, the marker is left intact, and the asset stays suppressed

#### Scenario: A stale present verdict is discarded

- **WHEN** a *present* verdict is computed for a marker and the row is settled under a different marker
  before that verdict is applied
- **THEN** the guarded write matches no row, and the marker the row now holds is left intact

#### Scenario: The orphan is never uploaded

- **WHEN** an interrupted import is adjudicated as present
- **THEN** the created asset stays in the suppression set throughout, and no upload job is ever created
  for it

#### Scenario: A created asset that never materialised is retried

- **WHEN** an unconfirmed row's asset is absent from the library, its staged bytes are still present, and
  no import for that ref is claimed
- **THEN** the marker is cleared and the asset is imported, so the photo still arrives

#### Scenario: A commit still in flight at relaunch is settled, not cleared

- **WHEN** a process died mid-import, a later process adjudicates the row, and the library answers *absent*
  because the surviving commit has not yet become visible
- **THEN** the row's staged bytes are found to be gone, the row is settled against the marker it already
  holds, no second asset is created, and the created asset stays in the suppression set

#### Scenario: An unconfirmed row with no staged resources is left alone

- **WHEN** an unconfirmed row carries a marker and records no staged resources at all
- **THEN** nothing is cleared and nothing is settled, because their absence is evidence neither way

#### Scenario: An unanswerable lookup defers rather than guesses

- **WHEN** the library cannot give a trustworthy answer about an unconfirmed row
- **THEN** nothing is imported and nothing is recorded, and the row is retried on a later launch

### Requirement: Asset presence is answered by the photo-access grant

The presence lookup SHALL be answered differently according to the current photo-access grant, because
an "absent" answer is only trustworthy under a **full** grant:

- **full access** — the library is queried directly; *present* is authoritative, and *absent* is
  authoritative **about committed state only**;
- **partial access** — the answer comes from the selection the app already holds; a hit is *present*, a
  miss is **unknown**, never *absent*, because an asset created under a full grant is real but invisible
  after a downgrade (app-created assets join the selection only at creation time);
- **no usable grant** — **unknown**, because a query returns nothing for assets that exist, and imports
  cannot succeed anyway.

Even under a full grant, an *absent* answer describes only what the library has **committed**. An asset
whose creating transaction is still open is genuinely absent by that measure and present moments later,
so the grant alone does not make *absent* safe to act on; the adjudication requirement additionally
requires that no import for that ref is running in this process.

Reporting *absent* where the grant cannot support it would clear a live marker, re-import the asset, and
orphan the first copy — the defect this capability's adjudication exists to prevent.

Choosing the source by grant SHALL be composition's, not the download feature's: the feature asks one
question and reads one three-valued answer.

#### Scenario: A partial grant never reports absent

- **WHEN** an unconfirmed row is adjudicated while the app holds a partial photo grant and the asset is
  not in the selection
- **THEN** the answer is unknown, the marker is kept, and nothing is imported

#### Scenario: A revoked grant never reports absent

- **WHEN** an unconfirmed row is adjudicated while the app has no usable photo access
- **THEN** the answer is unknown and the marker is kept, so restoring access does not expose an
  unsuppressed asset

#### Scenario: A full grant's absent answer is about committed state

- **WHEN** the app holds full photo access and asks about an asset whose creating transaction has not
  committed
- **THEN** the answer is absent, and the adjudication does not act on it because an import for that ref is
  claimed

### Requirement: Staged bytes are released when their import is settled

The client SHALL release an asset's staged bytes once its import is **settled** — confirmed, or settled as
permanently unimportable — and SHALL release the staged bytes of the rows a leave, switch, or durable state
reset actually drops, taking those paths from the prune itself, which returns what it stranded (capability
`download-store`). A pass over assets whose import is confirmed but whose staged bytes remain SHALL release
those too, so installs that accumulated bytes before this behaviour existed are reclaimed.

The client SHALL NOT read those paths **before** the prune as a separate step. That is two reads at two
instants over a store the photo library's change and completion blocks mutate without taking the client's
lock, so a marker cleared in between turns a row the read protected into a row the prune deletes — and its
files are then orphaned with nothing referencing them, unfindable and surviving relaunch.

The release the **client** performs SHALL happen **after** the settling write commits, never before.
Releasing first and recording second loses the photo permanently if the process ends between them: the
bytes are gone, the resource is still recorded as staged, and a staged resource is never re-downloaded.

**The photo library consumes a resource's file at ingest, and that is not under the client's control.**
Resources are handed to PhotoKit with the move option (capability `photo-download`, below), which takes the
file when the resource is ingested — before the content is validated and before the commit's verdict, and
therefore while the row is still claimed and unconfirmed. The client SHALL therefore treat a staged file as
possibly already consumed from the moment an import is attempted, and SHALL NOT assume it survives a failed
or interrupted import. Where the file is gone and no asset was created, the row SHALL settle as permanently
unimportable rather than be retried against a file that no longer exists.

Bytes SHALL NOT be released **by the client** for an import that is still claimed or unconfirmed. Where the
release follows a verdict, it SHALL happen only if that verdict's guarded write took effect: releasing the
bytes of a row that has moved on destroys the staged files a live import is reading from. Release SHALL be
best-effort: a failure to delete SHALL never fail an import.

Bytes SHALL be released only where they can be **positively attributed** to a settled or dropped row.
The client SHALL NOT delete staged files inferred to be unreferenced by scanning storage: a transfer
moves its bytes into place before the store records them, so a scan can delete a file whose row is about
to claim it is staged — losing that photo permanently.

#### Scenario: A received photo does not occupy storage twice forever

- **WHEN** a foreign asset's import is confirmed
- **THEN** its staged bytes are released, while the row recording the created asset is retained

#### Scenario: An importing asset does not hold its bytes twice

- **WHEN** an asset's resources are imported into the photo library
- **THEN** the library takes each file rather than copying it, so that asset's bytes are never resident
  twice while its import runs

#### Scenario: A discarded verdict releases nothing

- **WHEN** a *present* verdict's guarded write matches no row because the row moved on
- **THEN** that row's staged bytes are not released

#### Scenario: Bytes are not stranded by a leave

- **WHEN** rows are dropped on leave, switch, or reset
- **THEN** the prune returns exactly those rows' staged paths and the client releases them, so no file is
  left with nothing referencing it and no surviving row loses the bytes it still needs

### Requirement: Deletion is respected — no re-download

Once a foreign asset's import is **confirmed** (a terminal download-store row), the client SHALL NOT
download or import it again, even if the user later deletes the imported asset from their library. The
same asset linked into more than one event SHALL be imported only once (cross-event dedup via the store).

This guarantee attaches to a **confirmed** import. An **unconfirmed** row — one whose created-asset marker
was recorded but never confirmed — SHALL NOT be re-imported once its staged bytes have been consumed, and
they are consumed by the library's own ingest whenever a creation was submitted. A deleted photo whose
import was never confirmed therefore does **not** return: there are no bytes to import it from, and the row
settles against the marker it holds (capability `photo-download`, adjudication). Only a row whose bytes
survive — a change block that died before ingest — is re-imported, and that row's asset never existed to be
deleted.

#### Scenario: A deleted collected photo is not restored

- **WHEN** the user deletes a previously-imported foreign photo and a later sync runs
- **THEN** the asset is not re-downloaded or re-imported

#### Scenario: The same asset across two events imports once

- **WHEN** a device's asset appears in the unions of two events this install joins
- **THEN** it is imported only the first time and skipped thereafter

#### Scenario: A deleted photo from an unconfirmed import does not return

- **WHEN** the user deletes an asset whose import was never confirmed, and a later sweep adjudicates that
  row as absent
- **THEN** the row's staged bytes are found to be gone, the row is settled against the marker it holds, and
  the photo is not re-imported

### Requirement: Event-driven discovery of later additions

The client SHALL re-read the union on join/(re)provision, on foreground entry, **and** when it receives
a silent push for its **active event** (capability `push-registration`). It SHALL NOT run a background
**poll** of the union (no timer, no periodic background fetch); background discovery is **event-driven**
(woken by a push), not polled. Assets contributed by others **after** the initial read SHALL be
discovered on the next of: foreground entry, or a silent push for the active event. A push whose event
is **not** the active event SHALL NOT trigger discovery (the active-event guard lives in the receive
seam, capability `push-registration`). Transfers and imports already enqueued SHALL continue in the
background regardless of foreground state. Because push delivery is best-effort (OS-throttled and
coalesced), foreground entry remains the standing backstop, so no asset is lost — only, at worst,
delayed to the next foreground visit.

#### Scenario: A push for the active event triggers background discovery

- **WHEN** another contributor adds photos and a silent push for this device's active event arrives
  while the app is not foregrounded
- **THEN** the client reconciles in the background — reading the union, enqueueing the new foreign
  resources' downloads, and importing any already-staged asset — without a foreground visit

#### Scenario: Later-added foreign photos still appear on next foreground

- **WHEN** another contributor adds photos while this app is not foregrounded and no push is delivered
  (throttled/coalesced/dropped)
- **THEN** those photos are discovered and enqueued on the next foreground entry (the backstop)

#### Scenario: No background poll

- **WHEN** the app is backgrounded and no silent push arrives
- **THEN** the client runs no periodic union poll; discovery happens only on a push or the next
  foreground entry

#### Scenario: Initial-join transfers complete in background

- **WHEN** the app reads the union on join and is then backgrounded
- **THEN** the enqueued downloads and imports complete in the background without reopening the app

### Requirement: Download is gated on the membership's participation direction

The download reconcile SHALL be a **no-op** for any membership whose persisted participation direction
excludes download (`UploadOnly`) — at **every** trigger (join/(re)provision, foreground entry, and
silent push). The gate SHALL live at the **single choke point** through which all triggers funnel
(`DownloadController.reconcile`), reading the persisted `EventConfig.direction`, so the skip decision
sits in a **tested capability** rather than being duplicated across the untested app shell's call sites.
When the direction is `Both` or `DownloadOnly`, reconcile SHALL run exactly as before. This gate is
**orthogonal** to the existing active-event guard in the silent-push receive seam (capability
`push-registration`): the active-event guard answers "is this push for my current event," the direction
gate answers "should this device ever download for its current event." A push for the active event on an
`UploadOnly` membership SHALL therefore be received (active-event guard passes) yet perform **no**
reconcile (direction gate blocks), leaving no foreign photos downloaded or imported.

The gate's read SHALL be **posture-explicit**: *no membership* is a distinct answer from *a membership whose
direction excludes download*, and **neither** enables the arm. The read SHALL NOT resolve an absent
membership to "enabled" via a permissive fallback, and the gate SHALL carry **no default value** that would
let a caller omit the posture entirely. A three-valued read collapsed into a permissive boolean is what
allowed an upload producer to be enabled for an event that did not exist (capability `upload-lifecycle`); the
same collapse here would run a reconcile with no membership to reconcile against.

Because the download total is populated **only** by this reconcile (`store.plan` is reached only past this
gate), an `UploadOnly` membership's download total is `0`, and its download arrow is hidden by the ordinary
completeness rule with no masking in the status projection (capability `sync-status-screen`).

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read, download enqueue, or import occurs (reconcile is a no-op)

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push arrives for the active event on an `UploadOnly` membership
- **THEN** the push is received (the active-event guard passes) and reconcile is a no-op — no foreign photo
  is downloaded or imported

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets, enqueuing downloads, and importing staged assets

#### Scenario: An absent membership enables nothing
- **WHEN** the direction gate is read with no membership configured
- **THEN** the answer is "no arm" — the reconcile does not run, rather than defaulting to enabled

#### Scenario: Upload-only's download total is zero without a mask
- **WHEN** the membership is `UploadOnly` and the status projection reads the download total
- **THEN** the total is `0` because nothing was ever planned, so the download arrow is hidden by the
  completeness rule rather than by a direction mask

### Requirement: Transfer cancellation is task-level; the background session is never invalidated

Cancelling the download client's transfers (on leave, switch, or re-provision) SHALL cancel the
outstanding background **tasks** and SHALL leave the background `URLSession` **alive and reusable**. The
client SHALL NOT call `invalidateAndCancel` (or any other invalidation) as a cancellation mechanism.

Invalidation is terminal: creating a task on an invalidated `NSURLSession` throws an Objective-C
`NSException`, which Kotlin/Native cannot catch and which aborts the process. Because a cancel is always
followed by a later download (a re-join, a foreground reconcile, or a silent push), a session destroyed by
cancellation is a crash waiting for the next reconcile. Cancellation therefore acts on tasks, never on the
transport. The background-session identifier SHALL remain stable across cancellations and app launches, so
`handleEventsForBackgroundURLSession` can re-adopt the session and redeliver completions.

#### Scenario: Cancelling transfers leaves the session usable

- **WHEN** all transfers are cancelled and a resource is subsequently enqueued for download
- **THEN** a new download task is created and started successfully — the cancel did not destroy the
  transport

#### Scenario: Leave then re-join downloads again

- **WHEN** the user leaves an event (cancelling in-flight transfers) and later re-joins an event whose
  union lists foreign assets
- **THEN** the reconcile enqueues those resources and their downloads run; the app does not abort

#### Scenario: Cancellation stops the in-flight transfers

- **WHEN** transfers are cancelled while downloads are in flight
- **THEN** those tasks are cancelled, the pending queue is emptied, and the bounded in-flight window
  frees up for later enqueues

### Requirement: A session invalidated by the system is rebuilt, not reused

The client SHALL discard a background `URLSession` that the system has invalidated (delivered as the
delegate's `didBecomeInvalidWithError`) and SHALL build a fresh session — reusing the same stable
identifier — before creating any further task. It SHALL NOT create a task on a session it has been told is
invalid.

#### Scenario: A system-invalidated session self-heals

- **WHEN** the system invalidates the background session and a resource is subsequently enqueued
- **THEN** the client builds a fresh session and creates the download task on it, rather than reusing the
  invalidated session

### Requirement: A resource whose URL is not fetchable is skipped, not fatal

A planned resource whose `url` is not a fetchable `http`/`https` URL with a host SHALL be logged and
skipped, and SHALL NOT be handed to the background session (which raises an uncatchable Objective-C
exception for an unsupported URL). This covers both a `url` that fails to parse and one that parses to an
unsupported scheme or has no host. Skipping SHALL leave the resource pending for a later reconcile,
consistent with "a failed transfer is retried, not failed" — no terminal failure state is recorded.

#### Scenario: An unsupported download URL is skipped

- **WHEN** a planned resource carries a URL that is not an `http`/`https` URL with a host
- **THEN** it is logged and skipped, the remaining resources still enqueue, and the app does not abort

### Requirement: Download-job orchestration is tested behind a transport seam

The download client's orchestration SHALL live in a platform-independent unit covered by `commonTest`
(running on **both** JVM and `iosSimulatorArm64`), with the `NSURLSession` / `NSFileManager` calls behind a
narrow **transport** seam that a test fake can substitute. That orchestration comprises the pending queue,
the bounded in-flight window and its refill, the task-description encoding that carries
`(deviceId, assetId, resourceKey)` through a transfer, the staging-path computation, the URL guard, and the
cancellation lifecycle above.

The fake SHALL be able to model a destroyed transport (task creation after destruction fails), so that
"cancellation never destroys the transport" is pinned by an automated test rather than by device
verification alone. Faking only the outer `PhotoDownloadJobs` seam is insufficient — it replaces the very
implementation that carries this logic.

#### Scenario: The cancellation lifecycle is covered without a device

- **WHEN** the download client's tests run on JVM and `iosSimulatorArm64`
- **THEN** a fake transport that fails task creation after destruction demonstrates that cancelling
  transfers and then enqueueing still creates tasks

#### Scenario: The bounded window and task-description codec are covered

- **WHEN** the download client's tests run
- **THEN** the in-flight window's refill-on-completion behavior and the round-trip of
  `(deviceId, assetId, resourceKey)` through the task description are asserted without any iOS runtime

### Requirement: A failed union fetch still drains the staged imports

A reconcile whose union fetch fails SHALL still drain the assets whose resources are already staged,
rather than returning. Discovery and import are independent: the drain reads only the download store and
bytes already on disk, so a network failure has nothing to say about whether they can be imported.

This was inert while a failing fetch consumed the whole wake. Once the client carries an explicit request
timeout (capability `ios-app-shell`) a failure returns in seconds with most of the wake budget unspent,
and skipping the drain strands importable assets until some later wake for no reason.

Planning and enqueueing SHALL still be skipped, since those are exactly what the missing union would have
informed.

#### Scenario: A fast union failure still imports what is staged

- **WHEN** the union fetch fails and assets in the store already have all their resources staged
- **THEN** those assets are imported in that same wake, and no new downloads are planned or enqueued

#### Scenario: Last-good state survives the failure

- **WHEN** the union fetch fails
- **THEN** no planned or staged rows are dropped

### Requirement: The download session's OS handler is bounded, and its adoption is visible

The download session's `handleEventsForBackgroundURLSession` handler SHALL be carried by the same bounded
receipt every other OS handler uses (capability `ios-app-shell`), rather than stored in a field and
invoked when the imports happen to finish. Awaiting the imports is correct and SHALL continue; awaiting
them **without a bound** is not, because an import that never reports leaves the handler unanswered
forever, and an unanswered handler costs the app its future background wakes — including the download
wakes this capability depends on.

The bound SHALL run from the handover, and its expiry SHALL release the handler and leave the imports
running, never cancel them. It is the **only** bound in this capability: a single import is deliberately
not bounded in time, because a wall-clock bound expires against transactions that are alive and because
an import no longer holds the download controller's lock while it runs. So this bound governs how long
the OS is kept waiting, and nothing governs how long one import may take.

Adopting the handler SHALL be logged as an invocation, like every other platform-triggered entry
(capability `diagnostic-logging`; law *Absence is never silent*). Without it a diagnostic dump cannot
distinguish a handler that was released from one that never was — the download side's behaviour was
unreadable in the field for exactly this reason, while the upload side's was measurable.

#### Scenario: The handler is released after the imports, within the bound

- **WHEN** a background-session wake delivers staged resources and the imports they trigger finish inside
  the bound
- **THEN** the OS completion handler is released after those imports, on the main thread

#### Scenario: A stalled import does not strand the handler

- **WHEN** an import started by a background-session wake has not reported when the bound expires
- **THEN** the OS completion handler is released, the expiry is logged, and the import continues rather
  than being cancelled

#### Scenario: The adoption is readable in a dump

- **WHEN** the OS relaunches the app to deliver download-session events
- **THEN** the adoption is logged with its entry point, so a later dump shows the wake arrived and what
  became of its handler
### Requirement: The import lock covers the decision, and a claim provides the exclusion

The download controller's lock SHALL cover the **decision** — import selection, the claim below, the read of
an asset's staged resources, and every download-store write — and SHALL NOT be held across the photo-library
import call. A library call is synchronous, thread-blocking and unabandonable (cancellation is cooperative),
so holding the lock across it lets a stalled library block every reconcile, import, leave, switch and
staged-resource callback in the process. That callback is the sharp edge: it is delivered inside an
OS-granted wake, so blocking it can cost that wake its staging work.

Because the lock no longer spans the platform call, the mutual exclusion its *span* provided SHALL be
replaced by an explicit **claim**: before any import begins, that ref SHALL be taken out of circulation
under the lock, and SHALL NOT be offered as importable work to any concurrent pass until the library reports
its outcome. Without it two triggers can both find one asset importable before either records a marker, and
both create an asset — the duplicate this capability exists to prevent.

The claim SHALL be **in memory** and SHALL NOT survive the process: a claim that outlives the process which
owned the transaction is never released, and that photo never arrives. Nothing SHALL expire a claim on
elapsed time; a claim ends because the library reported, or because the process did.

The drain SHALL claim **one ref at a time**, and SHALL offer each ref at most once per drain. Claiming the
whole importable set up front makes one non-reporting import strand every other ref in that set until the
process is relaunched. Offering a ref more than once per drain live-locks it: a failed import leaves its row
importable *and* releases its claim, so the loop re-selects the same ref forever, spinning on any
permanently bad resource.

A claim SHALL be released when the library reports the outcome, and when adjudication establishes that the
created asset is *present* — the latter being the only recovery for a completion that is never delivered. A
claim SHALL be **retained** when the importing coroutine is cancelled: the transaction may still be open, and
treating "this coroutine is gone" as "this transaction is gone" is the inference this capability refuses.
The release SHALL happen **after** the writes that record the import, not before, or the row can move on
between the release and the write.

Imports of **distinct** refs MAY proceed concurrently. The per-ref claim is the only exclusion correctness
requires, and serializing the platform call behind a second lock would let one stalled import block every
other import for the life of the process, with nothing to end it.

#### Scenario: A stalled library blocks nothing else

- **WHEN** an import's completion has not arrived and other triggers fire
- **THEN** a reconcile, a staged-resource callback, a leave and a switch each proceed to completion
  without waiting for that import

#### Scenario: Two triggers cannot both import one asset

- **WHEN** two triggers reach the drain concurrently and one asset is importable
- **THEN** exactly one of them claims and imports it, and exactly one asset is created

#### Scenario: One stalled import strands no other ref

- **WHEN** one importable ref's import never reports and other refs are importable
- **THEN** a later trigger imports those other refs, skipping only the claimed one

#### Scenario: A permanently failing asset does not live-lock the drain

- **WHEN** an asset's import fails on every attempt and its row stays importable
- **THEN** the drain attempts it at most once in that pass and proceeds, rather than re-selecting it

#### Scenario: A relaunch releases every claim

- **WHEN** the process ends while refs are claimed and a later process drains
- **THEN** those refs are importable again, because no transaction from the previous process can still
  commit

#### Scenario: A cancelled import keeps its claim

- **WHEN** an importing coroutine is cancelled while its transaction may still be open
- **THEN** that ref stays claimed, so no *absent* answer about it is acted on and no second asset is created

### Requirement: An import the library cannot perform settles, and says so

An asset whose resources the photo library rejects SHALL settle as **permanently unimportable** — a
terminal download-store row — rather than be retried on every later trigger.

Today such a row is retried without bound: the import fails, the row remains importable, and the transfer
is never re-run because a resource already recorded as staged is never re-planned. Each retry is a full
photo-library transaction, once per trigger, for the life of the install, and nothing anywhere records that
the photo will never arrive.

The settlement SHALL be reported at a severity that reaches the crash-reporting sink (capability
`crash-reporting`), naming the ref and the library's reported error. A photo that will never arrive is
otherwise invisible: it is absent from the user's library with no error surface, and absent from the logs
except as an indistinguishable repetition of the failure that caused it. "This import failed and will be
retried" and "this photo will never arrive" are different answers with different consequences, so they
SHALL be distinguishable.

A settlement SHALL be distinguished from a **transient** failure by whether the resource's bytes remain.
Where the library has consumed the staged file and created no asset, there is nothing left to retry with
and the row SHALL settle. Where the request was rejected before any file was consumed, the failure SHALL
be treated as transient and the row SHALL remain importable.

A terminally unimportable row SHALL be excluded from importable work, from adjudication, and from
re-planning, exactly as a confirmed row is; its staged bytes, where any remain, SHALL be released.

#### Scenario: A resource the library rejects is not retried forever

- **WHEN** an import fails because the library rejects the resource's content, and the staged file has
  been consumed
- **THEN** the row settles as permanently unimportable, is not offered on any later trigger, and no
  further library transaction is attempted for it

#### Scenario: The give-up is visible off-device

- **WHEN** a row settles as permanently unimportable
- **THEN** it is reported at a severity that reaches the crash-reporting sink, naming the ref and the
  library's error, so the lost photo is discoverable without the device

#### Scenario: A rejected request is still retried

- **WHEN** an import fails because the request was rejected before any resource was ingested, so the
  staged files remain
- **THEN** the row stays importable and a later trigger retries it from those bytes

#### Scenario: A consumed file with no asset does not retry against nothing

- **WHEN** a process ends after the library has taken a resource's file but before any asset is created,
  and a later process drains
- **THEN** the row settles as permanently unimportable rather than attempting an import against a staged
  path whose file no longer exists

### Requirement: Resources are handed to the photo library by move, not copy

The client SHALL add each staged resource to the photo library with the move option, so the library takes
the file rather than duplicating it.

An importing asset otherwise holds its bytes **twice** — the staged file and the library's copy — for the
window between the commit and the client's own release, which follows the confirming write. That window is
where a device short of space fails, because the library must find room for a full second copy at that
instant. The requirement is about that window and not about total occupancy: resources already downloaded
and waiting to import occupy staging identically either way. The option also makes the consumed file the
signal the settlement requirement above rests on.

The ingest-time consumption SHALL be treated as measured platform behaviour, not as an assumption: the
library takes the file when the resource is ingested — **before** it validates the content and **before**
the commit — so a request rejected on its *content* has already consumed the file while a request rejected
on its *shape* has not. Measured 2026-08-26 (iOS 26.2): a resource whose bytes were invalid failed with
`PHPhotosErrorInvalidResource` and its file was gone with no asset created; requests rejected with
`PHPhotosErrorChangeNotSupported` consumed nothing — reproduced case for case on device (SE2, iOS 26.6,
2026-08-26). ⏰ Re-measure at the next iOS major.

#### Scenario: A successful import consumes the staged file

- **WHEN** an asset's resources are imported successfully
- **THEN** the staged files are gone, and the client's own release finds nothing left to delete

#### Scenario: A content rejection consumes the file

- **WHEN** the library rejects a resource's content
- **THEN** the staged file is gone and no asset was created, which is the condition the permanently-
  unimportable settlement acts on

#### Scenario: A shape rejection consumes nothing

- **WHEN** the library rejects the change request itself, before ingesting any resource
- **THEN** every staged file remains and the row is retried later
