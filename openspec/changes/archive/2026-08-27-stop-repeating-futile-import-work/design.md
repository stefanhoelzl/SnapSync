## Context

Two behaviours in the download/import path repeat platform work whose answer cannot change.

**The adjudication sweep.** `adjudicateUnconfirmed()` is the first act of `reconcile`, `importReady` and
`onResourceStaged`. The last of those is called by the background `URLSession` delegate once per staged
resource, so during a burst it fires hundreds of times. Each call reads the unconfirmed rows and asks
`ImportedAssetPresence`, which under a full grant is `PHAsset.fetchAssetsWithLocalIdentifiers` — a
synchronous XPC round-trip. Measured on an iPhone XS: 1,164 verdicts over a 131-asset burst, **1,149
discarded** by the in-flight gate.

The mechanism is not incidental. `IosPhotoLibraryImporter` records the created-asset marker *inside* the
change block, before the commit is observable, so a row is unconfirmed for the whole duration of its
import — and the drain claims one ref at a time. The steady state through a burst is therefore exactly
one unconfirmed row, which is the import in flight, asked about on every arriving resource. The guard's
own KDoc says it "costs nothing in the ordinary case — no row carries a marker". That premise inverts
under a burst, which is the only time it is called often.

**The retry of unimportable resources.** There is no attempt count in the schema, no terminal-failure
state, and no give-up. `drainImportable`'s `attempted` set bounds one pass, not successive triggers. The
`photo-download` spec already states the outcome — *"permanently unimportable and permanently retried,
and the photo never arrives"* — as a hazard that rejecting bad bytes before staging is supposed to make
rare, not as one anything can settle. Its only mitigation is the transfer's status/length check, which
cannot catch a body that has the right length and still will not decode.

Neither produces a wrong result. Both cost IPC and background-wake residency, and both fill `debug.log`,
which is what makes them worth fixing: the diagnostic dump is capped at 1 MiB and carries the tail of the
log, so `SNAPSYNC-23` was investigated through a window largely filled by one repeated line.

## Goals / Non-Goals

**Goals:**

- Adjudication asks the photo library only about rows a dead process left behind.
- A resource that cannot be imported settles, and its settlement is visible in Bugsink.
- Peak import disk stops being double the staged size.
- The spec says what adjudication *is*, rather than describing a cadence that no longer holds.

**Non-Goals:**

- Fixing a crash. `SNAPSYNC-23` is a background kill; a heavier deliberate reproduction did not die.
- Closing the accepted residual — a surviving commit still in flight at relaunch
  (`2026-08-10-take-imports-off-the-download-lock`, still pinned by
  `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`).
- Removing or weakening the in-flight gate, the guarded marker writes, or the claim set.
- Replacing `localIdentifier` with `PHCloudIdentifier`, or acting on the iOS 16/17 identifier-drift
  reports (old versions, no Apple response, and `logImportedDate`'s readback would surface it).
- Re-downloading the bytes of a resource that failed to import.

## Decisions

**D1 — Adjudication is liveness, not safety, and the cadence is therefore free.** `importableAssets()`
already excludes every marker-carrying row, and `suppressedLocalIds` selects on `createdLocalId IS NOT
NULL` rather than on `state = 'IMPORTED'`. So an unconfirmed row already suppresses uploads and is already
barred from a second import. If adjudication never ran, nothing incorrect would happen — rows would
stall. It does not gate the import; clearing the marker is what *enables* one. The safety knob is the
correctness of a clear, which this change does not touch. Rejected: treating the cadence as
safety-critical, which is how the current spec reads and why the waste was never questioned.

**D2 — One call site, at process start. No latch, no dedup, no re-arm.** The calls in `reconcile`,
`importReady` and `onResourceStaged` are deleted. The sweep runs once because there is one place that
runs it, not because a flag suppresses the rest.

Rejected alternatives, both of which reach the same steady-state cost:

- *Subtract the claimed refs from the lookup set.* Sound — in a live process, `unconfirmed ⟹ claimed`
  (every marker is written inside a change block on an already-claimed ref, and every claim release
  happens after the row is settled, because `cont.resume` fires inside the completion downstream of the
  store write). So the filter is exactly "only ask about inherited rows". Rejected for two reasons: under
  a partial grant an inherited UNKNOWN row still logs once per pass forever, which is the same pathology
  in miniature; and it forces a spec sentence that requires the reader to reconstruct that theorem.
- *A latch that memoises the first sweep.* Same steady state, but it keeps three call sites alive and
  adds an ordering hazard to reason about. One call site states the same thing structurally.
- *A durable `openedBy` process column.* Would fold the in-flight gate into the store's own `WHERE`
  clause, which is philosophically tidier. Rejected: a schema migration to buy tidiness on a liveness
  path, and the gate is not what this change is fixing.

**D3 — The sweep runs after the permission subscriptions, and is followed by a drain.** Both are
load-bearing and neither is enforced by the compiler.

`latestSelectionSnapshot` is initialised to `null` and populated only by the subscription the app shell
installs via `installPermissionSubscriptions()`. `PermissionAwareAssetPresence` maps a miss under
`.limited` to UNKNOWN, never ABSENT. A sweep before that first emission therefore answers UNKNOWN for
every row — and with no re-arm (D2) that row waits for the next launch. On a relaunch driven by a
`URLSession` staging callback, which may never foreground, that ordering could be systematic rather than
racy, and the photo would wait *every* launch.

The drain matters because the *absent* branch clears a marker, which is what returns the row to importable
work. A clear that nothing then imports moves the stall rather than ending it.

**D4 — The sweep's purpose is confirmation, not rescue.** A `performChanges` commit survives the death of
the process that opened it — measured on the SE2 (iOS 26.5.2, 2026-08-09, 48 MB asset, SIGKILL 200 ms
after the change block returned; the asset was in the library afterwards). So the ordinary post-death case
is *present*: settle the row against the marker it already holds, release its bytes, let the status line
reach 100%. The *absent* branch survives for the narrow cases that need it — death inside the change block
before submission, and a commit that genuinely failed with no completion delivered — but it is not why the
sweep exists. `DownloadController`'s `importing` KDoc still asks a reader to go measure that premise and
still states it as unproven; it is corrected here.

**D5 — An import PhotoKit rejects settles terminally, and is reported at `Error`.** A row whose resources
cannot be imported gains a terminal state, so it leaves importable work instead of being retried on every
trigger. The give-up is logged at `Error` severity, which the `crash-reporting` capability routes to
Bugsink as an event. That reporting is the decision, not a detail: a photo that will never arrive is
otherwise invisible, and "absence is never silent" is exactly the law a silent give-up would break.
Rejected: an attempt counter with re-download, which is more flexible (it survives a transient failure)
but needs a column, a re-plan path, and a threshold nobody can justify from evidence.

**D6 — Resources are added with `shouldMoveFile = true`, and the consumed file is the terminal signal.**
Measured 2026-08-26 on a simulator (iPhone 17, iOS 26.2), app signed for its real App Group, five cases
staged in that container:

| case | `shouldMoveFile` | committed | error | assets | staged file after |
|---|---|---|---|---|---|
| valid, copy | `false` | yes | — | 8→9 | survives |
| valid, move | `true` | yes | — | 9→10 | **gone** |
| garbage bytes | `true` | **no** | `3302 PHPhotosErrorInvalidResource` | 10→**10** | **gone** |
| valid + garbage | `true` | no | `3300 PHPhotosErrorChangeNotSupported` | 10→10 | both survive |
| two photo resources | `true` | no | `3300 PHPhotosErrorChangeNotSupported` | 10→10 | both survive |

**Confirmed on device 2026-08-26** — SE2 (iPhone12,8), iOS **26.6**, dev-signed rig build, full photo
grant, 1,474 assets in the library: all five cases reproduced the simulator's outcomes exactly, including
`3302` consuming the file with no asset created and `3300` consuming nothing. So the table is no longer a
simulator result standing alone; it is two hosts, two iOS point releases, agreeing case for case.

The move works out of an App Group container, and happens **at ingest — before content validation and
before the commit**. A request rejected on its *shape* (3300) consumes nothing; one rejected on its
*content* (3302) has already taken the file.

Two consequences follow, and the first is narrower than it first looks. **The doubling it removes is
per-asset and windowed**: under copy, a given asset holds its bytes twice only between the commit and the
client's own release, which follows the confirming write — milliseconds for a photo, seconds for a large
video. It is emphatically NOT a halving of the burst: files downloaded and waiting to import occupy
staging identically either way, so the backlog is unchanged. What the window buys is the instant that
actually fails — the library needs room for a full second copy right then, which is the shape of
`PHPhotosErrorNotEnoughSpace` and matches the one published field report. And a failed import leaves bytes
gone with no asset created, which is an honest terminal signal for D5: there is nothing left to retry
with. `PHPhotosErrorNotEnoughSpace` is
unlikely under move — the App Group container and the photo library are on the same data volume, so the
move can be a rename needing no second copy at all, and the one published field report has copy mode
producing *more* space failures than move.

Rejected: using file-absence as the *confirmation* oracle — replacing the presence lookup entirely. It is
attractive (no XPC, no permission dimension, no identifier lookup) but its failure direction is wrong: a
consumed file after a recoverable failure would mark a row imported, terminal and silent. Used for
*giving up* instead, being wrong marks a photo failed, which is visible and deliberately recoverable.

**D7 — The in-flight gate stays, and stops being load-bearing.** With the sweep as the process's first
download act, `importing` is empty when it runs, so the gate is structurally satisfied rather than merely
read in the right place. It is kept as defence in depth and because the accepted residual — a surviving
commit still in flight at relaunch — is invisible to it either way. Rejected: deleting it, which would
make the design's correctness rest on the single call site never gaining a sibling.

**D8 — An unimportable asset leaves the download total; the loss is reported, not displayed.**
`DownloadProgress` is `downloaded of total`, and `syncHealth` hides the download arrow when
`downloaded == total`. A row that settles unimportable is therefore dropped from `total`, the arrow
hides, and the screen can read *In sync* while one photo is permanently absent from the member's gallery.
The loss is carried by D5's `Error` report to Bugsink instead.

Rejected: *keep it in `total`*, which pegs the line at `130 / 131` forever — a state the member can
neither act on nor dismiss, and the failure mode this project already names ("the screen pegs below 100%
forever"). Rejected: *a distinct "couldn't be collected" affordance* — the honest display, but it expands
this change into `sync-status` and `sync-status-screen` and needs UI design; it remains available later,
and nothing here forecloses it.

Two prior statements need reconciling, because both look like they forbid this:

- `DownloadState`'s comment cites "the no-FAILED posture" as the reason it has no terminal failure state.
  That posture belongs to `SyncState`, which classifies `SyncProgress` — the **upload** side, which still
  retries forever, so `failed ≡ 0` stays true there and `sync-status` is unchanged by this change. The
  comment over-reads an upload-side classification into the download store's row lifecycle, and is
  corrected rather than obeyed.
- `syncHealth`'s comment says "the display must not assert a contract the system is not keeping", written
  after a download-only membership uploaded a camera roll behind a masked arrow. That warning is about
  concealing a mismatch between the **direction contract** and what the system does. This is not that: the
  system attempted exactly what the membership asked for, and the photo is unobtainable. What is withheld
  is not unrequested behaviour but an unfixable loss, and it is withheld from the surface while being
  reported to the operator.

## Risks / Trade-offs

- **A transient post-ingest failure becomes unrecoverable.** With move, bytes are consumed before the
  commit's verdict, so a failure that a retry would have survived loses them. → The `Error` report is the
  mitigation that keeps it honest rather than silent; the failure population is narrow (3300-class
  rejections consume nothing, and `NotEnoughSpace` is unlikely under move); and the trade is stated in the
  spec rather than discovered. ⏰ Re-measure if a recoverable post-ingest failure is ever observed in
  Bugsink.
- **The move measurement is n=1 per case per host.** → Now measured on BOTH a simulator (iOS 26.2) and the
  SE2 (iOS 26.6), agreeing case for case, which removes the single-host risk. What remains is that each
  host was measured once, and that no *recoverable* post-ingest failure was ever forced — see Open
  Questions.
- **A partial-grant row that answers UNKNOWN waits for the next launch.** → Under a full grant this cannot
  happen (`PhotoKitAssetPresence` returns only PRESENT or ABSENT); under a partial one the query is an
  in-memory snapshot lookup, so the cost of waiting is a stalled row, not repeated IPC. D3's ordering is
  what keeps the population near zero.
- **D3's two ordering requirements are not compiler-enforced.** A future call site, or a sweep moved
  earlier than the permission subscriptions, silently degrades this. → Both need integration tests
  asserting them directly, and the spec states them as requirements rather than as notes.
- **Process death between ingest and the marker write leaves a row whose `stagedPath` names a file that no
  longer exists.** The resource is consumed at `addResource`, which precedes the marker write in the change
  block. → The next drain fails with `3303 PHPhotosErrorMissingResource` and D5's terminal settlement
  absorbs it; without D5 this would be a new retry loop, which is why D5 and D6 travel together.
- **The member is never told a photo will not arrive (D8).** → The `Error` report is the only channel,
  so the give-up's severity is load-bearing rather than cosmetic; if these appear in Bugsink at any real
  rate, the "couldn't be collected" affordance D8 rejected becomes the follow-up.
- **A schema migration for the terminal state.** → Additive; existing rows keep their current state, and
  the deletion ledger (`DeletionLedgerTest`) covers what must stay retired.

**The burst is measured, not argued.** SE2 / iOS 26.6, 2026-08-26, against a local filesystem-backed
backend seeded with a synthetic foreign contributor (40 assets, 1.28 MB each, 51 MB): 79 staged-resource
callbacks, 40 assets imported, imports demonstrably interleaved with staging (`onResourceStaged` and
`import: change block running` within the same millisecond), and:

| | 2026-08-25 baseline (build 609) | this change |
|---|---|---|
| adjudication verdicts | 1,164 | **0** |
| `absent, but its import is in flight` | 1,149 | **0** |
| sweeps | one per trigger, per staged resource | **1 per process** |

The download line reached 40/40 with 0 in flight, so nothing was lost to the counts change. No
staged-byte release warnings appeared either: the move option consuming a file before the client's own
release runs does not produce a new failure log.

## Migration Plan

1. `download-store` gains the terminal state with **no DDL migration at all** — `state` is a `TEXT` column
   behind an enum adapter, so widening the value set changes no schema. What does change is every predicate
   that spelled "non-terminal" as `state != 'IMPORTED'`: each becomes `NOT IN ('IMPORTED', 'UNIMPORTABLE')`,
   and missing one silently puts the row back into the retry loop. The prune's two predicates are
   deliberately NOT widened — a row in the new state carries no marker, so by that requirement's own
   invariant ("handle-carrying rows are permanent, not terminal rows are permanent") it is prunable.
2. The adjudication call-site move and the terminal settlement land together; `shouldMoveFile` lands only
   once the terminal settlement exists, because the flag without it converts a retry loop into a retry loop
   over missing files.
3. Rollback is per-step: reverting `shouldMoveFile` restores copy semantics with no data change; reverting
   the call-site move restores the old cadence. A **downgrade** is a different matter and is not defended
   against: code that predates this change spells non-terminal as `state != 'IMPORTED'`, so it would read an
   `UNIMPORTABLE` row as ordinary work and re-plan it. That is accepted rather than solved, because there is
   no downgrade path — TestFlight and the App Store install forward only — and the failure it would produce
   is one wasted import attempt per such row, not a lost photo.

## Open Questions

- ~~Does the SE2 reproduce the simulator's ingest-before-validation ordering?~~ **Answered 2026-08-26:
  yes, all five cases identical (SE2 / iOS 26.6).**
- Is there any recoverable post-ingest failure class in practice? `OperationInterrupted` (3301) is the
  remaining candidate and was not forced. The `Error` reports from D5 are the instrument that would answer
  this from the field.
