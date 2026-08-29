## Context

`DownloadController.adjudicateUnconfirmed()` runs once per process and asks the photo library about every
row it inherited that carries a created-asset marker. On *absent* it clears the marker and returns the row
to importable work. The library answers about **committed** state, so it answers *absent* about an asset
whose transaction is still open — and a `performChanges` commit survives the death of the process that
opened it (measured 2026-08-09). The prior change named the resulting window, pinned it with
`a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`, and accepted it, because
"kill→adjudication was 64 s against a ~1 s commit" and "a 48 MB asset cannot reach it".

Two things have changed since, and both widen the window rather than narrow it. `shipped 2026-08-27`:
the sweep moved from every trigger to the startup path, shortening relaunch-to-adjudication; and resources
are now added with `shouldMoveFile = true`, so an unconfirmed row's bytes are **consumed** and the
re-import that *absent* triggers can no longer succeed.

The residual was then re-measured directly rather than argued (record: `PROBE-FINDINGS.md`, simulator,
iOS 26.5, two independent runners, throwaway probe never committed):

- **8 of 9 runs at 25-43 MB reproduced it.** The relaunch's first fetch answered *absent* 1.5-2.8 s after
  block entry; the asset appeared 0.3-1.8 s later. 3 of 3 runs at 1-2 MB took the safe path.
- **Killing the client widens the commit window 2-3x** — 1.11 s with a live client, 2.1-4.9 s orphaned.
  Every commit duration on record was taken with a live client, so all of them understate the crash case.
- **The staged file is gone at relaunch, 6 of 6**, and gone *before* the asset is visible.
- **The re-import that follows the clear fails**, `PHPhotosErrorDomain 3302`, 2 of 2. Production maps that
  to `consumedResources` and settles the row `UNIMPORTABLE` with `createdLocalId` NULL — terminal, never
  adjudicated again, asset unsuppressed. That is the echo, permanently.

So the crossover is between ~2 MB and ~25 MB, not the several hundred MB the acceptance rested on.

## Goals / Non-Goals

**Goals:**

- No sweep ever clears a marker whose asset the library has been asked to create.
- The decision rests on a **fact the device can state**, never on elapsed time or a launch count.
- A row settled by this path is indistinguishable, downstream, from one settled by a *present* verdict.
- The correction is provable by deterministic tests against both store implementations.

**Non-Goals:**

- Holding more than one created-asset handle per ref. That was this workspace's opening premise and is
  **not needed**: a marker that is never wrongly cleared has nothing to restore.
- Changing how presence is asked, or how the photo-access grant answers it.
- Bounding an import in time, reintroducing a clock, or making any decision from a launch counter.
- Recovering a photo whose commit genuinely failed after ingest. Its bytes are gone; nothing can.

## Decisions

**D1 — File absence is a second oracle, and it is sound because of where staging lives.** Staged bytes sit
in `<AppGroup>/download-staging` (`IosStagedBytes`), which iOS never purges — unlike `Caches` or `tmp`. For
a row that carries a marker, `StagedBytes.release` cannot have run: release happens only after a confirming
write, or immediately before dropping a row, and `pruneNonTerminal` never drops a marker-carrying row. So
on an unconfirmed row the **only** agent that can remove that file is PhotoKit's ingest. Its absence is
therefore positive evidence that a creation was submitted — available at exactly the moment the library's
answer is untrustworthy, and derived from a different subsystem.

Rejected: inferring the same thing from elapsed time, a launch counter, or a durable "seen once" bit. Each
answers "has enough happened yet" rather than "did it happen", which is the mistake D1 of
`gate-absence-on-unreported-imports` already named.

**D2 — The two evidence-bearing branches converge on ONE action.** *present* and *absent-with-bytes-gone*
both call `confirmCreatedLocalId(ref, marker)`, release the staged bytes, and release the claim. No new
store write is introduced: that call already means "settle `IMPORTED` against the marker this row holds",
already carries the marker guard, and already returns whether it applied. A row that moved on between the
lookup and the write settles nothing, on both branches, for the same reason and through the same code.

The rule therefore reads as one sentence — **settle unless there is positive evidence that nothing was
created** — rather than as a table of special cases.

**D3 — Settling is right even though it cannot distinguish two futures, because the wrong guess cannot
touch behaviour.** `absent + bytes gone` has two continuations: the commit lands (measured 8/9), or it
fails content validation and no asset ever exists. Settling treats both as imported.

- Where the commit lands, settling is simply **correct, one launch earlier** — it is the state the next
  sweep would have produced anyway.
- Where it failed, the marker names an asset that does not exist. `suppressedLocalIds()` then carries an
  identifier that matches nothing: discovery walks the real library and compares each asset it finds
  against that set, so an entry matching no asset is never compared to anything. It cannot suppress a
  photo that does not exist, and it cannot suppress a different one.

Measured that last point rather than assuming it (`PROBE-FINDINGS.md`): 40 identifiers minted by creations
that **failed** were never reissued across 200 subsequent real creations; 240 identifiers were 240 distinct
with no UUID-prefix collisions, shaped `<UUID>/L0/001`. They are minted, not pool-allocated.

So the wrong guess costs a **count**, not a photo and not an upload.

Rejected: `settleUnimportable`, which drops the marker by contract (*"a terminal row is a suppression
handle only when an asset exists"*). Guessed wrongly there, it strips a live asset's only suppression
handle and the row is terminal, so the loss is permanent — the defect this change exists to remove,
re-created by its own cleanup.

Rejected: keeping the marker and doing nothing. It resolves the landing case on the next launch and the
failing case **never** — that row keeps its marker forever, is swept once per launch, and counts toward the
download denominator permanently, pegging the status screen below 100%. That is precisely the defect
`UNIMPORTABLE` was introduced to remove one day earlier (`stop-repeating-futile-import-work`, D8).

**D4 — The consequence of D3 is stated, not hidden.** Where a commit ingests the bytes and then fails
content validation, and the process dies before the completion reports it, this change records the photo as
imported although it never arrived: the install reads 40 of 40 holding 39 photos. The photo is
unrecoverable either way — its bytes were consumed — so the choice is only how the loss is displayed. It
requires content PhotoKit rejects (these bytes already passed the transfer's status and length checks)
**and** death inside the commit window.

**D5 — Any missing resource counts as missing; an empty path list is UNKNOWN.** A multi-resource asset
(a Live Photo's still and its paired video) may be partway through ingest, so one missing file is enough to
prove a creation was submitted. An unconfirmed row with **no** staged resource rows at all is a different
thing: it is not evidence of ingest and not evidence against it, so it is treated exactly as *unknown* —
nothing is cleared and nothing is settled. Deciding it either way would guess, and the whole change exists
to stop guessing on this branch.

**D6 — The port reports the fact, and the feature interprets it.** `StagedBytes` gains a read answering
whether a set of paths is still present, not "were these consumed" — consumption is an inference the
download feature draws from a filesystem fact plus its own knowledge that nothing else can delete them.
Placing the inference in the adapter would put a load-bearing decision where `commonTest` cannot reach it.
`StagedBytes` is the right home rather than a new port: it already owns where staging lives and what may be
reclaimed from it, so one owner decides both, and neither can name a directory the other does not.

**D7 — Two spec scenarios that `shouldMoveFile` already falsified are corrected here.** Both live in the
requirements this change edits, and both are false on `main` today:

- *"A created asset that never materialised is retried … so the photo still arrives"* holds only where the
  bytes survive — which is exactly the branch this change isolates, so the requirement gains the condition
  rather than losing the promise.
- *"A deleted photo from an unconfirmed import returns at most once"* cannot happen: consumed bytes cannot
  be re-imported. Removed rather than left promised.

Fixing them elsewhere would mean editing the same two requirements twice, and leaving them would leave the
contract of record asserting behaviour the tree does not have.

**D8 — The settle is logged distinguishably, at Info.** A dump must say which evidence settled a row —
`library confirms` versus `bytes consumed, commit not yet visible`. Not at `Error`: the dominant outcome of
this branch is the benign one (8 of 9 measured), so `Error` would send a steady stream of normal recoveries
to Bugsink and train the operator to ignore it.

## Risks / Trade-offs

- **A photo that never arrived is counted as arrived (D4)** → Stated in the spec and in the log line, so
  the behaviour is discoverable by reading rather than by surprise. Requires two independent rare
  conditions to coincide.
- **Every measurement here is from a simulator** → The device is slower to commit large assets (5.2 s at
  197 MB with a live client) and the orphaned window is 2-3x wider than the live one, so the device is
  expected to be more exposed, not less. Acceptance therefore includes a device run rather than resting on
  the simulator.
- **Identifier reuse is measured only for the failed-creation path** → Recycling after *deletion* is
  unmeasured: `deleteAssets` raises a system confirmation alert a simulator cannot answer. It is a
  different mechanism from the one that produces a leftover marker, but it is genuinely unmeasured.
- **The gate trusts the store's staged paths** → If a recorded path were wrong or stale, `allPresent` would
  answer false and the row would settle without a photo behind it. The path is the one the transfer wrote
  when it staged the bytes and the same value the importer reads, so a wrong path already breaks the import
  it feeds; this adds no new source of truth.
- **The residual test inverts, so the suite stops pinning today's behaviour** → Deliberate: it pinned an
  accepted harm, and the harm is no longer accepted. The mutation bar applies — removing the gate must turn
  that test red, and a kill must name a failing test rather than a compile error or a hang.
- **It closes the post-ingest window only, and the other half is measured** → `performChanges` submits the
  transaction when the block RETURNS, while `photolibraryd` ingests the file later still; a process dying in
  between leaves a live transaction whose bytes are untouched. D1 establishes *absence ⇒ submitted* (4/4 on
  device); the clear branch leans on the converse, *presence ⇒ nothing created*, which is **false**. Measured
  on the SE2 (2026-08-29, 18 imports killed mid-burst): 4 rows saved by this change, **2 orphaned** through
  that window — against 6 that would have orphaned without it. A strict reduction, not an elimination.
  Prevention is not available: no public PhotoKit API reports an outstanding transaction (checked against the
  declared `Photos` klib surface and Apple's documentation — change history records **committed** changes
  only). The repair is to RETAIN a cleared handle so a later launch can find its asset and re-suppress it,
  which is its own change.
- **This change closes the reason this workspace was opened** → The multi-handle download store is not
  built, and D2 records why, so a later reader does not conclude it was forgotten.

## Migration Plan

No data migration and no schema version: `DownloadStore.sq` is untouched, no persisted field changes
meaning, and no state written by this change needs interpreting by an older build. A revert restores the
previous behaviour exactly, including its residual. Deployment is an ordinary branch → PR → `/ship`.

## Open Questions

- Production relaunch-to-`sweepInterruptedImports` latency on a real device, which is the other half of the
  exposure comparison. The change is correct regardless — it removes an action that cannot succeed — but the
  number would say how often the old behaviour was actually firing in the field.
- Whether `photo-download`'s *"Deletion is respected"* requirement wants a positive statement of what now
  happens to a deleted-then-adjudicated unconfirmed row, beyond removing the resurrection promise.
