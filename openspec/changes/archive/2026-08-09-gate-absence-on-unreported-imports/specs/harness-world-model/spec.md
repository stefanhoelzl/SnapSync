## MODIFIED Requirements

### Requirement: Failure levers

The world SHALL expose controllable failure levers that drive the real stack's failure paths: a
**backend-offline** switch flipping the per-device listing and event-union routes to `502` (driving the
reconcile-seed failure path and the download union-failure path), the **job-limit** (`LIMIT_EXCEEDED`),
a **per-job `UploadError`** on the upload retry chain, and an **import failure** (`ImportResult.Failed`).

It SHALL additionally expose the two ways an import can end **without the library reporting**, because
they are different states and only the second is the one `SNAPSYNC-9` lives in (capability
`photo-download`):

- **abandoned after the commit landed** — the marker is written, the asset IS created, and the wait is
  abandoned (`ImportResult.TimedOut`). A presence lookup then answers *present*, and adjudication settles
  the row against the marker it already holds.
- **abandoned before the commit landed** — the marker is written and the asset is **not** created yet, so
  a presence lookup answers *absent* about a transaction that is still open. Acting on that answer is the
  reported defect; the world must be able to reach the state in order to prove the guard.

A lever SHALL NOT settle the state it exists to create: the before-commit lever writes no confirmation,
clears no marker, and reports no outcome, because all three are things the completion callback does and
supplying any of them would erase the very state under test.

The world SHALL also expose a **late completion** — the outcome arriving after its requester is gone —
which lands the asset, settles the row against the marker it holds, and stops the ref being distrusted,
in that order. Without it a test can reach the unreported state but never leave it, so the recovery path
is unreachable.

#### Scenario: Backend-offline leaves upload status untouched and fails the union

- **WHEN** the backend-offline switch is set and the status source refreshes and the download
  controller reconciles
- **THEN** own-device upload status is unaffected — it is ledger-backed and issues no storage read, so
  there is no last-good set to keep and nothing to go stale — and the download union read
  returns a failed `Result` (no partial import)

#### Scenario: Each lever drives its real path

- **WHEN** the job-limit, a per-job `UploadError`, or an import failure is armed and the corresponding
  cycle runs
- **THEN** the real orchestration responds (deferred cycle, engine retry with incremented attempt, or a
  non-terminal import failure respectively)

#### Scenario: An abandonment before the commit reaches the guarded state

- **WHEN** the before-commit abandonment lever is armed and an asset is imported
- **THEN** the row carries a marker, the library holds no such asset, nothing has been reported, and a
  presence lookup answers *absent*

#### Scenario: A late completion settles what it created

- **WHEN** a late completion is delivered for a ref abandoned before its commit
- **THEN** the asset appears in the library, the row is settled against the marker it holds, and the ref
  is no longer treated as unreported

#### Scenario: A repeat import is distinguishable from the first

- **WHEN** the world's importer creates a second asset for a ref it has already imported
- **THEN** that asset carries a **different** created identifier, as the photo library mints one per
  request — so a test asserting on identifiers or on asset counts can observe a duplicate rather than
  mistaking it for the original
