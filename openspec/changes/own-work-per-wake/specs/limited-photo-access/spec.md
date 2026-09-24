## MODIFIED Requirements

### Requirement: Selection changes reach the domain through a change-source port

A `ports/` seam (`PhotoSelectionChangeSource`, named for the need) SHALL deliver photo-library
change notifications to the domain as a cold-safe stream the composition subscribes to. Its iOS
adapter SHALL live in `:adapter:ios:app-only` (the observer and picker are app-process surface;
extension linkage is structurally excluded) and SHALL register a `PHPhotoLibraryChangeObserver`
**only while permission is `LIMITED`**, unregistering otherwise — under a full grant the autonomous
walks already cover every change, and the observer would add redundant reads.

The port SHALL be fakeable: `:test:world` provides an operator-drivable fake so the world harness and
`:test:integration` can emit selection changes on demand.

#### Scenario: A selection change while limited triggers at most one read
- **WHEN** permission is `LIMITED` and the user changes the selection (in-app picker, Settings edit, or
  iCloud sync)
- **THEN** at most one enumeration of the selection follows (the next requirement's consumption), with no
  other read triggered by the same change; a change that reaches the source while an earlier enumeration is
  still running shares the one enumeration that follows it with every other change queued behind it

#### Scenario: A burst of changes queued behind a running enumeration shares one
- **WHEN** permission is `LIMITED`, an enumeration for one selection change is running, and three more
  changes arrive before it finishes
- **THEN** exactly one more enumeration follows, for the latest of the three, and the snapshot it emits
  reflects all three changes

#### Scenario: The observer is not registered under a full grant
- **WHEN** permission is `GRANTED`
- **THEN** no photo-library change observer is registered by this seam, and library changes reach the
  app through the existing autonomous refresh paths only

### Requirement: No autonomous library reads under a limited grant

While permission is `LIMITED`, no autonomous trigger SHALL read the photo library: the opportunistic tail's
discovery walk (unit ③ — capability `ios-app-shell`, "Each OS wake does its own work, then hands the rest to
one opportunistic tail"), whichever wake's tail it is — foreground entry's, a silent push's, the heartbeat's, a
completion's or a session relaunch's — and the status refresh's gallery walk SHALL all skip their
`PHAsset`-fetching work; under a partial grant the tail runs no ③ at all. The tail's **top-up** (②) SHALL still
run on those triggers, a silent push's included, because it reads no library: it resolves its rows from the
in-memory selection snapshot, and is withheld while that snapshot is unread (see "The read discipline is enforced
at the mechanism, not at the trigger fan-out"). Everything else that does not touch `PHAsset` SHALL keep running
on those same triggers — config reload, HTTP reconcile, download planning, the tail's imports (①), ledger-count
polling, attestation refresh. Decision record for the tail: `changes/own-work-per-wake` (D1).

**The justification is corrected, the behaviour is not.** This requirement was recorded as *the*
load-bearing alert rule — the claim being that autonomous fetches queue the alert while in-flow reads
are clean. On-device measurement (SE2, iOS 26.5.2 — `PROBE-FINDINGS.md` in this change's decision
record) contradicts both halves:

- ~15 autonomous library walks, four launches one second apart, against an **unchanged** library
  produced **zero** alerts; and
- one camera capture followed by the **sanctioned** change-observer read produced an alert, queued,
  surfacing on the bare home screen after the app was killed.

**What the alert is.** It is iOS's automatic *"Select More Photos… / Keep Current Selection"* prompt: a
nudge to the member to add photos to a partial selection. It is **not** a guard on reads, because under
`.limited` the app can only ever read the selection. The app suppresses it with
`PHPhotoLibraryPreventAutomaticLimitedAccessAlert` and offers its own route instead (see *The app owns the
limited-library picker*).

**What is settled.** Two probes, on iOS 26.5.2 (2026-08-06) and on iOS 26.6.2
(`changes/archive/2026-09-21-album-gathers-retroactively`, both on the SE2 with the key in the bundle), agree
that neither **reads of an unchanged library** nor **the app's own creations** raise the prompt. The
app's own creations are imports, which join the selection at creation, and album creation and adds over
selected assets. That holds however many reads follow.

**What is not settled, and SHALL NOT be designed on.** The probes disagree on a change **outside** the
selection:

- **iOS 26.5 and 26.5.2** leaked the prompt **despite** the key in two probes. In July, prompts stormed
  during the first-grant picker, and queued after a camera photo plus re-fetches, surviving the app's
  death. In August, one camera capture then the app's reads surfaced exactly one queued prompt.
- **iOS 26.6.2:** the same camera stimulus, with 11 reads across 4 launch-and-kill cycles, surfaced
  **none**, which is what the key promises.

That fits the key working as documented from 26.6 on, but it is one device and one probe on the newer
release. No requirement, design or justification SHALL assume either outcome. In particular, nothing
SHALL assert that a limited member pays one prompt per photo taken, and nothing SHALL be justified as
suppressing one.

It follows that **read volume does not change the alert count**, so this discipline SHALL NOT be
justified as alert suppression. It is retained on its own merits, which are real: under a partial grant
the selection *is* the scope, so reading it rather than walking the library is the correct source, and it
removes per-foreground `PHAsset` round-trips that buy nothing. Reads under `LIMITED` therefore continue
to happen at exactly two moments and no others:

- **one baseline read on a cold foreground launch** (opening the app is a user action), establishing
  the status total and catching any backlog (selection changes made while the app was dead); and
- **on a selection-change emission** (next requirement).

Expiry trigger: re-measure on the next iOS major, or if Apple documents the automatic alert's trigger.
Caveats on the evidence: one device. The leak was seen on iOS 26.5 and 26.5.2 (two probes), and not
on iOS 26.6.2 (one probe, n = 1 out-of-scope change).

#### Scenario: Foreground entry under limited does not walk the library
- **WHEN** the app enters the foreground with permission `LIMITED` (not a cold launch)
- **THEN** no `PHAsset` fetch occurs; the reconcile, ledger-count poll, and attestation refresh still run, and
  the tail imports and tops up from the selection snapshot without walking

#### Scenario: A silent push under limited reaches the top-up but reads no library
- **WHEN** a silent push for the active event arrives while permission is `LIMITED`
- **THEN** the download reconcile runs as the push's own work; the tail that follows imports staged downloads
  and tops up, resolving rows from the selection snapshot; no discovery walk runs and no `PHAsset` fetch occurs

#### Scenario: The cold-launch baseline catches offline selection changes
- **WHEN** the selection was widened while the app was not running, and the app is then cold-launched
  to the foreground
- **THEN** the single baseline read discovers the new photos and they are enqueued

#### Scenario: Reads against an unchanged library are alert-free
- **WHEN** the app fetches repeatedly under a partial grant and the library has gained nothing outside
  the selection since its last read
- **THEN** no limited-access alert is queued, however many times it reads

#### Scenario: The app's own creations raise no prompt
- **WHEN** under a partial grant the app imports a photo, or creates an album and adds selected photos to
  it, and then reads the library any number of times
- **THEN** no limited-access prompt is presented, in the running app or after it is killed

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

The app process's **opportunistic tail** (capability `ios-app-shell`, "Each OS wake does its own work, then hands
the rest to one opportunistic tail") runs its discovery walk (③) only under a full grant. That omission is **not**
this discipline's enforcement and SHALL NOT be relied on as such: it is a scheduling choice — under a partial grant
discovery is the selection change's own work, so a walk in every tail would re-run it for nothing — and the
mechanism's own gate and discovery still hold the discipline whatever reaches them, the selection change's own
walk included. The one widening the tail made is its own decision, with its evidence: a silent push under a
partial grant now reaches the tail's top-up (②), which reads no library, because it resolves its rows from the
held snapshot and is withheld while that snapshot is unread (decision record `changes/own-work-per-wake`, D1:
accepted — already-selected rows upload sooner, with no read).

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
`upload-lifecycle`, "The upload cycle owns its entry decision") — each of its units, the tail's top-up
included. It settles narrowly, and reads, creates, deletes and publishes nothing. The observer's first emission
then triggers the selection change's own work and the tail that follows it. The
selection-scoped discovery SHALL refuse to answer for an `Unread` scope, failing the call rather than
returning an empty result, so that no caller that reaches it anyway can mistake "not read" for "nothing".

The status total already draws the same distinction, for its own reason: an unread snapshot must not settle
the screen (see "One discovery serves both the status total and the enqueue"). Both SHALL read the one
snapshot cell, so they cannot disagree about whether the selection has been read.

Decision records: `changes/selection-is-the-walk` (D1); the tail's scheduling and its one widening:
`changes/own-work-per-wake` (D1). It reverses the earlier requirement that a
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

#### Scenario: The tail's skipped walk is not the only guard

- **WHEN** an upload unit that would read the library is reached under a partial grant by any path — the tail,
  or a selection change's own work
- **THEN** the mechanism's gate and discovery decide, and read no library, whether or not the tail would have
  scheduled that unit

### Requirement: Selection snapshots are emitted in change order

The selection-change source SHALL emit its snapshots from one serial lane, in the order of the changes
they reflect, so the last snapshot a consumer holds always reflects the latest change. It SHALL emit
nothing after observation has ended, and in particular SHALL NOT emit a snapshot built under a limited grant
once the grant has become full. A change that arrives before the baseline read completes SHALL be applied
after the baseline, not dropped.

Changes queued behind a running enumeration SHALL be **folded**, not enumerated one by one. Each snapshot is
the whole selection, so a snapshot for a change that a later one already supersedes is work whose result the
conflating stream drops anyway. The lane SHALL apply the change in hand and every change already queued
behind it to the held read, one at a time, through each change's own pushed result
(`fetchResultAfterChanges` — never a library read), and SHALL then enumerate **once**, for the latest. At most
one enumeration runs at a time. The last snapshot emitted SHALL be the one that emitting per change would have
ended on. The fold SHALL stop at the first queued item that is not a change (an observation ending or
restarting), and SHALL handle that item next, so nothing is reordered and no change is applied across the end
of an observation. The baseline path is not folded. Decision record: `changes/own-work-per-wake` (D13).

The rule against emitting under a stale grant SHALL hold on the **change path** exactly as on the baseline path:
both SHALL check, before emitting, that the grant generation the snapshot was built under is still the current
one. A change that was queued — or whose enumeration was running — while the grant was limited SHALL NOT be
emitted once the grant has become full, whether or not the end of observation that the flip causes has reached
the lane yet. Without the check, the one enumeration a fold runs, or a change folded ahead of the end of
observation, would emit a limited-scope snapshot after the flip, and a consumer holding it would treat the
selection as the scope of a full grant.

#### Scenario: Two changes in quick succession

- **WHEN** the member picks more photos twice, quickly, and the second snapshot's enumeration would finish
  first on a parallel dispatcher
- **THEN** the second change's snapshot is still the last one emitted

#### Scenario: The grant becomes full while the baseline is being read

- **WHEN** the grant changes from limited to full while the baseline read is in flight
- **THEN** no snapshot is emitted from that baseline

#### Scenario: A change during the baseline read

- **WHEN** a selection change arrives before the baseline read has completed
- **THEN** a snapshot reflecting that change is emitted after the baseline one

#### Scenario: A change queued before the grant becomes full is not emitted after it

- **WHEN** a selection change, and then the end of observation that a limited → full grant change causes, are
  queued behind a running enumeration under a limited grant
- **THEN** the fold stops at the end, and no snapshot is emitted — neither for the running enumeration nor for
  the queued change — because the grant is full by the time either would be emitted; the end is handled next,
  and nothing is emitted after it

#### Scenario: A change enumerated across the grant flip is dropped

- **WHEN** a selection change's enumeration is running under a limited grant and the grant becomes full before
  it finishes
- **THEN** its snapshot is not emitted, exactly as a baseline read in flight across the flip is not

