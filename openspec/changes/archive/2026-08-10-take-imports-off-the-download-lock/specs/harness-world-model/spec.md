## ADDED Requirements

### Requirement: The world's marker write is a required collaborator

The marker write the world's importer performs SHALL be a **required** collaborator of that importer, with
no default.

A no-op default makes an importer that never records a marker look like a working one: the row stays
importable, so every later pass imports the asset again while reporting success — an unbounded duplicate
generator presented as a healthy path (capability `download-store`). This is a safety-relevant collaborator
and takes the same posture as every other one in this project: supplied explicitly, or not at all.

#### Scenario: A world importer cannot be built without its marker write

- **WHEN** the world's importer is constructed
- **THEN** the marker write must be supplied, rather than defaulting to a no-op

## MODIFIED Requirements

### Requirement: Failure levers

The world SHALL expose controllable failure levers that drive the real stack's failure paths: a
**backend-offline** switch flipping the per-device listing and event-union routes to `502` (driving the
reconcile-seed failure path and the download union-failure path), the **job-limit** (`LIMIT_EXCEEDED`),
a **per-job `UploadError`** on the upload retry chain, and an **import failure** (`ImportResult.Failed`).

It SHALL additionally expose an import that **suspends after writing its marker** and resumes with an
outcome the test chooses, because that — not a report about it — is the state `SNAPSYNC-9` lives in
(capability `photo-download`), in **two** variants that differ in what the photo library can see:

- **suspended before the commit** — the marker is written and the asset is **not** created, so a presence
  lookup answers *absent* about a transaction that is still open. Acting on that answer is the reported
  defect.
- **suspended after the commit** — the marker is written, the asset **is** created, and only the report is
  missing, so a presence lookup answers *present*. This is the shape a process death leaves behind, and the
  only one adjudication can recover, since *present* is the verdict that settles a row against the marker it
  already holds.

Both leave an unconfirmed row and both keep the ref claimed. The world must be able to hold either state
open, drive other triggers against it, and only then resolve it.

Holding the transaction open is what a report about it cannot do. A lever that merely *returns* an
abandonment lets a test observe the aftermath, but never lets a second trigger run **while** the transaction
is live — which is the interleaving the defect occurs in, and the one the download controller's claim exists
to close.

A lever SHALL NOT settle the state it exists to create: while suspended it writes no confirmation, clears no
marker, and reports no outcome, because all three are things the completion callback does and supplying any
of them would erase the very state under test. Resuming it SHALL drive the real completion path for the
outcome chosen — landing the asset and settling the row against the marker it holds on success, or clearing
that marker on failure — so a test can reach the recovery as well as the defect.

The world SHALL also expose an **attempt cap** that raises once a ref has been imported more times than a
test permits. An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a hang; a
hang names no defect and proves nothing, so the cap converts it into an assertion failure that names the
count.

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

#### Scenario: A suspended import holds the guarded state open

- **WHEN** the before-commit suspending lever is armed and an asset is imported
- **THEN** the row carries a marker, the library holds no such asset, nothing has been reported, and a
  presence lookup answers *absent* — and that remains true until the test resumes it

#### Scenario: A suspension after the commit is recoverable by adjudication

- **WHEN** the after-commit suspending lever is armed and an asset is imported
- **THEN** the row carries a marker, the asset IS in the library, nothing has been reported, and a presence
  lookup answers *present* — so a later pass settles the row without creating a second asset

#### Scenario: Other triggers run while a transaction is live

- **WHEN** an import is suspended and a reconcile, a staged-resource callback, a leave or a switch is
  driven
- **THEN** each completes without waiting for the suspended import

#### Scenario: Resuming with success settles what it created

- **WHEN** a suspended import is resumed with a successful outcome
- **THEN** the asset appears in the library, the row is settled against the marker it holds, and the
  ref's claim is released

#### Scenario: Resuming with failure clears its own marker

- **WHEN** a suspended import is resumed with a failed outcome
- **THEN** the marker it wrote is cleared, the asset stays importable, and no unconfirmed row is left

#### Scenario: A runaway drain fails rather than hangs

- **WHEN** one ref is imported more times than the attempt cap permits
- **THEN** the importer raises, naming the count, so the test reports a failure rather than hanging

#### Scenario: A repeat import is distinguishable from the first

- **WHEN** the world's importer creates a second asset for a ref it has already imported
- **THEN** that asset carries a **different** created identifier, as the photo library mints one per
  request — so a test asserting on identifiers or on asset counts can observe a duplicate rather than
  mistaking it for the original
