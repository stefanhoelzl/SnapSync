## ADDED Requirements

### Requirement: The event album's collection is cached and invalidated only by the library observer

Where the importer holds the event album's `PHAssetCollection` across imports, the cached collection SHALL be
dropped **only** by the photo library's change observer — when the album is deleted or changed — and SHALL NOT
be dropped because an import's commit failed. Holding it is optional (it spares a fetch by identifier per
import); what is not optional is how it is invalidated.

A failed commit is the wrong witness for a stale album. Resources are handed to the library by move (see
"Resources are handed to the photo library by move, not copy"), so a commit that fails because its album add
named a collection that no longer exists may already have consumed the staged bytes of the photo it was
importing. Invalidating on that failure would learn of the stale album only by losing a photo to it — which
is exactly what "the album add SHALL be best-effort — it SHALL never fail or defer the import" (see
"Full-fidelity per-asset import into the camera roll") forbids. The observer learns of the deletion without
spending an import.

The cache SHALL NOT change which album an import is added to, or whether one is: the album identifier is still
sourced from the shared `eventId → albumLocalId` map through the injected lookup, and an absent identifier
still imports into the camera roll only. The cache holds only the platform handle that identifier resolves to.

**Measured: a stale collection costs an album add, never an import.** Against a **deleted** collection (deleted
from inside the app or from the Photos app), `changeRequestForAssetCollection` returns a non-nil request, the
commit **succeeds**, the asset **is created** and its staged file is consumed, and only the album add is dropped —
silently (iOS 26.5 simulator, n=6, 2026-09-27). So the feared outcome (a failed commit consuming move-semantics
bytes) does not occur, and the cache MAY be enabled. Because a stale collection gives no failure signal at all,
the library change observer SHALL be the only thing that drops the cache, and the drop SHALL be logged so a
missed album add stays diagnosable. The same measurement on device hardware remains to be recorded. Decision
record: `changes/own-work-per-wake` (design D10).

#### Scenario: A deleted album is dropped by the observer, not by a failed import

- **WHEN** the user deletes the event album while imports continue, and the library change observer reports
  the deletion
- **THEN** the importer drops its cached collection on that report, and the next import resolves the album
  afresh (or imports into the camera roll only if the identifier no longer resolves)

#### Scenario: A failed commit does not invalidate the cache

- **WHEN** an import's commit fails for a reason other than the album
- **THEN** the cached collection is kept, and the failure is handled exactly as any failed import is

#### Scenario: The cache is off until measured

- **WHEN** no device measurement of a change request against a deleted collection has been recorded
- **THEN** the importer fetches the event album's collection per import, holding no cached collection

## MODIFIED Requirements

### Requirement: Import without foreground; staged by the wake, imported by the tail

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.

A background-session wake's **own work** SHALL be **staging** the delivered files: the transport moves each
accepted transfer's bytes into durable staging and the download controller records it staged (see "A staged
resource reaches the controller on every entry point"). The OS completion handler SHALL be released once that
own work is done — at the session's report that its events are drained — and SHALL NOT be held for the imports
the staging made possible (capability `ios-app-shell`). Holding the handler across imports is what the field
evidence condemned: a download wake delivers ~24 transfers, which is 20–40 s of serial imports on an iPhone XS,
and a handler held for them was released by our own deadline mid-batch with iOS suspending the app ≤ 0.4 s
later (92 assets left staged on one day).

The imports SHALL run as unit ① of the process's **tail** (capability `ios-app-shell`, "Each OS wake does its
own work, then hands the rest to one opportunistic tail"; decision record `changes/own-work-per-wake`, design
D1): after the wake's own work, one process-wide, single-flight tail imports every asset whose resources are all
staged, before any upload work. A staging SHALL therefore request the tail's **import alone** — ① with no
top-up and no walk, since a staged photo changes nothing either would see — joining the tail when it is already
running, starting it when not, without awaiting it (a staging holds no OS handler of its own; the wake that
delivered it requests, and holds, its own tail after its drain), rather than import in its own callback. The
tail SHALL run under the background time of the wake that requested it — a `BGTask`'s own grant for the upload
heartbeat — whose expiry is Apple's signal that time is up (capability `ios-app-shell`, "Time is up is learned
only from the operating system"); the imports SHALL NOT be bounded by any clock of the app's own.
The drain SHALL be awaited by the tail — each import it starts is awaited until it reports, unless a stop or a
joining request makes that wait give way (below) — not dispatched and forgotten by the composition, so the
background time ends only when the drain has, unless Apple's expiry ends it first. Reporting the work done while
the imports it caused are merely queued is what leaves an asset staged-but-unimported at suspension.

**On Apple's expiry signal the drain SHALL stop cooperatively** (capability `ios-app-shell`, "Expiry stops work
cooperatively at the next boundary"; design D4, D5): the background time is ended at once, the stop is checked
before each import is claimed so no further import is started, and the import whose change block is running is
left to complete its change block if the process runs long enough.
The stop SHALL NOT cancel an import already claimed — its transaction may still commit, and the claim is
retained exactly as for a cancelled importing coroutine (see "The import lock covers the decision, and a claim
provides the exclusion"). Every import left unstarted is a safe retry: its staged bytes and store row are
untouched.

An import that never reports is the one case awaiting cannot resolve, and it SHALL NOT hold the tail hostage:
each import runs as its own job, and the tail's wait for it SHALL give way — leaving the import claimed and
running — when Apple's stop arrives, or when another request joins the tail (every later request would
otherwise wait behind it). After a join interrupts the wait, the drain moves on to the next importable asset,
which the claim keeps from being the stalled one; after a stop it starts nothing further. No clock decides
either: the tail stops waiting only because something else is due. Nothing bounds a single import in time. A wall-clock bound on one import expires against transactions
that are alive — the process is suspended for arbitrary spans between a change block and its completion — and
every expiry manufactures an unconfirmed row for the adjudication guard to reason about. The stalled import
blocks no other work, because it does not hold the download controller's lock and its ref is claimed rather
than serialised.

**There is no download backstop background task.** Imports left staged when a tail stopped SHALL be drained by
unit ① of **any** later wake's tail — a silent push, a download or upload session relaunch, the upload
heartbeat, a selection change, and foreground entry, whose tail runs through the same runner. ① is the **only**
drain a wake runs: no wake's own work — the download reconcile included, at foreground as at a push — imports
staged assets itself. The one import outside the tail is the **once-per-process interrupted-import sweep** at
host assembly (see "An interrupted import is adjudicated, never repeated blindly"), which settles the rows a dead
process left unconfirmed and then drains what is importable; it imports under the same per-asset claims as ①,
so running beside a tail it can never import an asset twice. The backstop `BGProcessingTask`
found work in 0 of 108 field runs; it is removed together with its `flow/DownloadBackstop` trigger and its task
identifier (design D7). The drain's coordination is the tail's: the wake's shared prelude — the trigger-time
membership re-read (`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail
cleanly before first unlock*) and the attestation wake — runs before the wake's own work, and the tail's ① then
drains. A tail whose ① runs before the first unlock since boot fails cleanly and converges at a later wake (the
import's reads are caught; the adapters distinguish unreadable from absent; nothing mints, clears, or leaves).

Staged bytes + the store make any deferred import a safe retry. That property is **conditional, and the
transfer check is its condition**. A deferred import is a safe retry only because staged bytes were accounted
for at transfer time. Absent that check, a permanently invalid body — an error document staged under a photo's
path — makes the retry a trap rather than a safeguard: the import fails on every drain, and the transfer is
never re-run, because a resource recorded as staged is never re-planned. The asset is then permanently
unimportable and permanently retried, and the photo never arrives. Retrying a failed import is correct for a
transient failure and poison for invalid bytes; only rejecting bad bytes before staging keeps the two apart.

Decision record: `changes/own-work-per-wake` (design D1, D4, D5, D7).

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background, by the tail's unit ①

#### Scenario: A download wake releases its handler after staging

- **WHEN** a background-session wake delivers several finished transfers
- **THEN** their bytes are staged and recorded, the OS completion handler is released once the session reports
  its events drained, and the imports run afterwards in the tail under the app's own background task

#### Scenario: The tail awaits the imports it drains

- **WHEN** the tail's unit ① drains several importable assets
- **THEN** the imports are awaited, so the tail's background task does not end while they are merely queued

#### Scenario: Apple's expiry stops the drain at the next import

- **WHEN** the tail's background time receives its expiration signal while importable assets remain
- **THEN** the background time ends at once, the import whose change block is running is not cancelled, no
  further import starts, and the remaining assets stay staged for a later wake

#### Scenario: An import that never reports does not hold the task past expiry

- **WHEN** an import the tail started never receives its completion, and the expiration signal arrives
- **THEN** the background time ends, the import is left claimed and running rather than cancelled, and no other
  reconcile, import, leave or switch is blocked by it

#### Scenario: A join does not wait behind a stalled import

- **WHEN** an import the tail is awaiting never reports, and another wake requests the tail
- **THEN** the tail stops waiting for that import, leaving it claimed and running, drains the other importable
  assets, and makes the pass the joiner requested

#### Scenario: A staging requests the import alone

- **WHEN** a download finishes staging in a process that is already running
- **THEN** the controller records it staged and the tail's ① imports it; no upload top-up or walk is requested
  by that staging

#### Scenario: Leftover staged imports drain at a later wake

- **WHEN** an asset's resources are all staged but its import did not run before a tail stopped, and no
  further download is pending
- **THEN** the next wake of any kind — or the next foreground entry — imports it in its tail's unit ①, with no
  dedicated background task scheduled for it

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable

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

A silent push's **own work** SHALL be the download reconcile — the union read, planning, and enqueueing the
new resources' transfers. Importing already-staged assets is not the push's own work: it is unit ① of the tail
that runs after it (see "Import without foreground; staged by the wake, imported by the tail"), so a push
whose union read is slow or fails still imports what is staged, and a push is never kept waiting behind an
import burst. Decision record: `changes/own-work-per-wake` (design D1).

#### Scenario: A push for the active event triggers background discovery

- **WHEN** another contributor adds photos and a silent push for this device's active event arrives
  while the app is not foregrounded
- **THEN** the client reconciles in the background — reading the union and enqueueing the new foreign
  resources' downloads as the push's own work — and the tail that follows imports any already-staged asset,
  without a foreground visit

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

Because the download total is populated **only** by this reconcile — its planning is reached only past this
gate, as one batch: a single `settledAmong` read of which of the union's foreign assets are already settled,
then a single `planAll` transaction recording the rest, then a single `markAllEnqueued` transaction for the
resources sent to the OS (capability `download-store`) — an `UploadOnly` membership's download total is `0`,
and its download arrow is hidden by the ordinary completeness rule with no masking in the status projection
(capability `sync-status-screen`). Planning a backlog one asset at a time cost a store read and a durable
commit per asset (measured on an iPhone XS in a background wake: ~11.5 s for 101 assets); the batch is a
wording sync of a behaviour-preserving change (decision record `changes/own-work-per-wake`, design D13).

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read or download enqueue occurs (reconcile is a no-op), so nothing new is staged for the
  tail's unit ① to import

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push arrives for the active event on an `UploadOnly` membership
- **THEN** the push is received (the active-event guard passes) and reconcile is a no-op — no foreign photo
  is downloaded or imported

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets and enqueuing downloads — and
  the staged assets are imported by the tail's unit ① that follows

#### Scenario: An absent membership enables nothing
- **WHEN** the direction gate is read with no membership configured
- **THEN** the answer is "no arm" — the reconcile does not run, rather than defaulting to enabled

#### Scenario: Upload-only's download total is zero without a mask
- **WHEN** the membership is `UploadOnly` and the status projection reads the download total
- **THEN** the total is `0` because nothing was ever planned, so the download arrow is hidden by the
  completeness rule rather than by a direction mask

#### Scenario: A backlog is planned in one transaction
- **WHEN** a reconcile's union lists many foreign assets that are not yet settled
- **THEN** their settled-ness is read once for the whole union, every unsettled asset is planned in one
  transaction, and the resources sent to the OS are marked enqueued in one transaction

### Requirement: A failed union fetch still drains the staged imports

A reconcile whose union fetch fails SHALL NOT prevent the drain of the assets whose resources are already
staged in that same wake. Discovery and import are independent: the drain reads only the download store and
bytes already on disk, so a network failure has nothing to say about whether they can be imported. The drain
is unit ① of the tail that follows the wake's own work (see "Import without foreground; staged by the wake,
imported by the tail"), in **every** wake, foreground entry included, so it runs whatever the union fetch
answered. The reconcile itself (`DownloadController.reconcile`) SHALL NOT drain staged imports, on success or
on a union failure: a reconcile that drained would be a second import path beside the tail's ①, running
concurrently with it at foreground — which the single-flight tail exists to rule out.

This was inert while a failing fetch consumed the whole wake. Once the client carries an explicit request
timeout (capability `ios-app-shell`) a failure returns in seconds with the wake's background time largely
unspent, and skipping the drain strands importable assets until some later wake for no reason.

Planning and enqueueing SHALL still be skipped, since those are exactly what the missing union would have
informed.

#### Scenario: A fast union failure still imports what is staged

- **WHEN** the union fetch fails and assets in the store already have all their resources staged
- **THEN** those assets are imported in that same wake, by its tail's unit ①, and no new downloads are planned
  or enqueued

#### Scenario: A reconcile never imports

- **WHEN** a reconcile runs at foreground entry, at a push, or at a provision, and assets are importable
- **THEN** the reconcile plans and enqueues only; the importable assets are imported by the tail's unit ①,
  and no wake's own work imports them

#### Scenario: Last-good state survives the failure

- **WHEN** the union fetch fails
- **THEN** no planned or staged rows are dropped

### Requirement: The download session's OS handler is released after staging, and its adoption is visible

The download session's `handleEventsForBackgroundURLSession` handler SHALL be held for the wake's **own work**
only — staging the delivered transfers — and SHALL be released when the session reports its events drained
(`urlSessionDidFinishEvents(forBackgroundURLSession:)`) **and** every staging the delivered events started has
been recorded, on the main thread (capability `ios-app-shell`). The session's report says only that it delivered
its events, not that the store writes they caused are done, so the download-job owner tracks each staging it
starts and the release waits for them. It
SHALL NOT be stored in a field and invoked when the imports happen to finish, and it SHALL NOT be held for the
imports: those run afterwards as the tail's unit ① under the app's own background task (see "Import without
foreground; staged by the wake, imported by the tail"). Apple documents no budget for a background-session
relaunch and treats a held handler as a watchdog-backed assertion — an overrun is a kill without warning — so
the handler covers the least work that satisfies the wake.

No deadline of the app's own SHALL bound the handler. Where the drain report does not arrive, the handler is
released on Apple's expiry signal for the wake (capability `ios-app-shell`, "Time is up is learned only from the
operating system"), never on a constant; the former
per-entry-point deadline (`ReceiptDeadlines`) is deleted, because the field evidence showed that constant
releasing on 45 % of download wakes and cutting imports off. Nothing bounds a single import in time either: a
wall-clock bound expires against transactions that are alive, and an import no longer holds the download
controller's lock while it runs. Decision record: `changes/own-work-per-wake` (design D3, D5).

Adopting the handler SHALL be logged as an invocation, like every other platform-triggered entry
(capability `diagnostic-logging`; law *Absence is never silent*). Without it a diagnostic dump cannot
distinguish a handler that was released from one that never was — the download side's behaviour was
unreadable in the field for exactly this reason, while the upload side's was measurable.

#### Scenario: The handler is released after staging, before the imports

- **WHEN** a background-session wake delivers finished transfers and the session reports its events drained
- **THEN** the OS completion handler is released on the main thread once those transfers are staged and
  recorded, without waiting for the imports they make possible

#### Scenario: A drain report that never arrives is released on Apple's signal

- **WHEN** a background-session wake's session never reports its events drained
- **THEN** the OS completion handler is released on Apple's expiry signal for the wake, the expiry is logged,
  and no constant of the app's own decided the moment

#### Scenario: The adoption is readable in a dump

- **WHEN** the OS relaunches the app to deliver download-session events
- **THEN** the adoption is logged with its entry point, so a later dump shows the wake arrived and what
  became of its handler

### Requirement: A staged resource reaches the controller on every entry point

A resource the download transport finishes staging SHALL reach the download controller — which records it
staged and requests the tail that imports it — whichever entry point brought the process up, including a cold
background relaunch that only delivers download-session events and builds nothing else. The jobs' staging
callback SHALL be supplied at construction (capability `module-architecture`, "Callbacks are bound at
construction") and SHALL resolve the controller when invoked. A staging report that nevertheless cannot be
delivered SHALL be logged with the resource it concerns; it SHALL NOT be dropped silently.

#### Scenario: The OS relaunches the app only to deliver download completions

- **WHEN** iOS relaunches the process in the background for the download session, and the transport
  reports a staged resource before anything else in the core has been built
- **THEN** the controller records it staged, the OS handler is released once the session's events are
  drained, and the asset is imported by the tail that follows under the app's own background task

#### Scenario: A staging report cannot be delivered

- **WHEN** a staging report arrives and the controller cannot be obtained
- **THEN** the device log records the resource and the reason, and the staged bytes are left for the
  interrupted-import sweep

## RENAMED Requirements

- FROM: `### Requirement: Import without foreground; relaunch and backstop`
- TO: `### Requirement: Import without foreground; staged by the wake, imported by the tail`

- FROM: `### Requirement: The download session's OS handler is bounded, and its adoption is visible`
- TO: `### Requirement: The download session's OS handler is released after staging, and its adoption is visible`
