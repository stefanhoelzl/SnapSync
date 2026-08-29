## Why

The startup sweep clears a live suppression marker, and the device then uploads a downloaded photo back
into the event — `SNAPSYNC-9`'s harm, reached through the guard built to prevent it. A `performChanges`
commit survives the death of the process that opened it, so a relaunch can ask the photo library about an
inherited row **while that commit is still landing**. The library answers *absent* truthfully, the sweep
clears the marker, and the asset arrives moments later with nothing recording that it must not be uploaded.

`take-imports-off-the-download-lock` accepted this as a residual, on the reasoning that it needs a commit
longer than relaunch-to-adjudication latency and so "a 48 MB asset cannot reach it", with a re-measure
scheduled only for several-hundred-MB resources. **That premise is wrong by an order of magnitude, and it
was measured, not argued** (simulator, iOS 26.5, `shouldMoveFile`, two independent hosts):

- a 25-43 MB photo — an ordinary 48 MP capture — reproduced the residual in **8 of 9 runs**: the relaunch's
  first fetch answered *absent* 1.5-2.8 s after block entry and the asset appeared 0.3-1.8 s later;
- 1-2 MB assets took the safe path 3 of 3, so the crossover sits between ~2 MB and ~25 MB;
- killing the client **widens** the window 2-3x (1.11 s live vs 2.1-4.9 s orphaned), so every commit
  duration recorded with a live client understates the crash case.

## What Changes

- The sweep's *absent* branch gains the precondition the requirement always implicitly assumed: it reads
  whether the row's **staged bytes still exist**, and acts on the answer.
  - **bytes gone** — PhotoKit ingested them, so a creation was submitted: settle the row against the marker
    it already holds. Same guarded write the *present* branch uses (`confirmCreatedLocalId`), same
    byte release, same claim release.
  - **bytes still there** — the change block died before ingest, so nothing was created: clear the marker
    and re-import, exactly as today.
- The two evidence-bearing branches therefore converge on one action, and the rule reads: **settle unless
  there is positive evidence that nothing was created.**
- `StagedBytes` gains one read — whether a set of staged paths is still present — reporting the filesystem
  fact and leaving the interpretation to the feature.
- **Two scenarios that are already false on `main`** are corrected. `shouldMoveFile` (shipped in
  `stop-repeating-futile-import-work`) makes an unconfirmed row's bytes unavailable, so neither promise can
  hold today:
  - *"A created asset that never materialised is retried … so the photo still arrives"* — true only where
    the bytes survive, which is now stated.
  - **BREAKING** *"A deleted photo from an unconfirmed import returns at most once"* — impossible: consumed
    bytes cannot be re-imported. The resurrection is removed rather than left promised.

This closes the window **after** the library has ingested the bytes. It does not close the window between a
transaction being submitted and being ingested, where the bytes are still on disk and a commit is
nonetheless alive; measured on device, that left 2 orphans against 6 without this change. No public API can
prevent it (nothing reports an outstanding transaction), so the remainder is a separate change that retains
cleared handles for a later launch to repair. See `PROBE-FINDINGS.md`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-download`: the adjudication requirement's *absent* branch is split on whether the staged bytes
  survive, and only the bytes-present half clears a marker; the deletion requirement drops its
  resurrection promise, which `shouldMoveFile` already made unreachable.
- `download-store`: `StagedBytes` answers whether staged paths are still present, so the adjudicator can
  tell "the library took these bytes" from "nothing was created".

## Impact

- `:domain` — `ports/StagedBytes` (one read), `feature/download/DownloadController` (the *absent* branch).
- `:adapter:ios:app-only` — `IosStagedBytes`.
- `:adapter:generic:fake` — the in-memory `StagedBytes`.
- `:test:world` — a lever that models bytes the library has consumed.
- `:adapter:generic:fake` `commonTest` — `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`
  currently asserts the harm and inverts to assert the handle survives.
- No schema change, no migration, no change to the suppression projection. Nothing about how presence is
  asked, or how the photo-access grant answers it.
- Behavioural risk sits entirely in one branch of one function; both store implementations are covered by
  the existing contract tests. On-device acceptance is required — this is the class of bug the phone has
  caught twice after tests and reviewers passed it.
