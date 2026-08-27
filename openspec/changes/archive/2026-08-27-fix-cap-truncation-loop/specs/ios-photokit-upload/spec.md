## MODIFIED Requirements

### Requirement: In-extension discovery via persistent change token


On each `process()` invocation, the extension SHALL discover work itself (the system does not
enumerate). On first run (no token) it SHALL enumerate the whole library via `PHAsset.fetchAssets`
and capture `currentChangeToken` as baseline; in steady state it SHALL call
`fetchPersistentChanges(since:)` and derive the changed asset set. On `persistentChangeTokenExpired`
it SHALL re-enumerate the whole library, relying on the ledger to skip already-recorded keys. The
change token SHALL be **persisted across extension process death** in a shared App-Group store (an
archived `PHPersistentChangeToken`; see "Persisted change-token cursor"), so a short-lived wake
resumes incrementally instead of re-enumerating the whole library.

The token SHALL be advanced (persisted to `currentChangeToken`) **once every fact the walk produced is
durable**, and SHALL NOT be conditioned on how many upload jobs that cycle went on to create. A walk
produces exactly three facts that exist nowhere else, and all three are ledger writes:

- every admitted resource the engine judged to be new work — recorded `DISCOVERED` (capability
  `sync-ledger`);
- every asset the change feed reported removed — marked absent;
- the manifest detail of every already-recorded row still lacking it — backfilled.

The token SHALL be persisted **after** those writes and **before** any upload job is created, so a
process death between them costs one re-derivation rather than losing work. The ordering, not
atomicity, is what makes that safe: the writes are idempotent, so a repeated walk converges, while
persisting the token first would discard resources that no row records.

This **replaces** the previous rule that the token advance only at the end of a fully-drained cycle —
a cycle in which every discovered resource was turned into a job with no `limitExceeded`. That rule
was correct in its purpose and wrong in its condition: it protected against advancing past resources
nothing durably recorded, but it used "every job was created" as the proxy for "every resource is
recorded", and on a device whose outstanding work exceeds the platform's job limit those two are never
the same. The proxy made the cursor unwritable for as long as a device was behind, so every cycle
re-enumerated the whole library. With the un-created remainder now recorded `DISCOVERED`, the walk's
information survives without the cursor standing still, and the producer resumes that remainder from
the ledger rather than by re-deriving it.

#### Scenario: First run enumerates the whole library
- **WHEN** `process()` runs with no persisted change token
- **THEN** the extension enumerates the full library and records the current change token as the
  baseline cursor in the App-Group store

#### Scenario: Cursor survives a process restart
- **WHEN** the extension process is torn down after a cycle that recorded its walk and later re-invoked
- **THEN** it loads the persisted token and calls `fetchPersistentChanges(since:)` from it, rather
  than re-enumerating the whole library

#### Scenario: Token advances on a cap-truncated cycle whose walk was recorded
- **WHEN** a cycle records `DISCOVERED` rows for every admitted new-work resource, marks the reported
  removals, backfills bare rows, and then stops creating jobs because `creationRequestForJob` raised
  `PHPhotosErrorLimitExceeded`
- **THEN** the persisted token has already advanced, and the next wake discovers only what changed
  since it while resuming the un-created remainder from the ledger

#### Scenario: Token does not advance when the walk was not recorded
- **WHEN** a cycle's ledger writes for the walk's facts do not complete
- **THEN** the persisted token is left unchanged, so the next wake re-derives the same change set

#### Scenario: Token expiry re-enumerates harmlessly
- **WHEN** `fetchPersistentChanges(since:)` reports `persistentChangeTokenExpired`
- **THEN** the extension re-enumerates the whole library and the ledger answers `AlreadyUploaded` for
  keys already recorded, so no duplicate jobs are created

### Requirement: Cap-aware creation and tri-state processing result


When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle and surface a **processing** result so the system re-invokes it
promptly. It SHALL NOT stop anything else: the change token has already advanced (see "In-extension
discovery via persistent change token"), the un-created remainder is already recorded `DISCOVERED`,
and the cycle SHALL still publish its device manifest, its enumeration audit line, and its completion
notify. On the next wake, the producer resumes exactly the un-created remainder from the ledger — with
no duplicate jobs, no persisted residue list, and no re-derivation.

Because the OS invokes the extension lazily (on library changes, not when an upload quietly
finishes), a drained cycle that reported `completed` would leave already-succeeded jobs
un-acknowledged until the next change. Therefore, whenever the cycle would otherwise complete but the
ledger still has **pending** (in-flight) rows, the extension SHALL instead surface **processing** to
request another invocation so those completions are recorded promptly; it reports `completed` only
once the ledger has no pending rows (everything backed up), letting the system rest. (The OS
throttles re-invocation, so this polls at its cadence rather than looping.)

**Kotlin decides; Swift constructs** (migration step 12, settled forcing proof ①:
`PHBackgroundResourceUploadProcessingResult` is Swift-only — declared in the SDK's swiftinterface
with no ObjC header — but `RawRepresentable` over `Int`). The mapping from `CycleResult` to the
system result SHALL be the tested, **exhaustive** Kotlin function
`CycleResult.processingResultRawValue()` (`:domain` `ports/`, raw values pinned in `commonTest`:
`failure` = 0, `processing` = 1, `completed` = 2; `completed` and `skipped` — nothing to do — both
map to the completed raw value). The extension root SHALL expose it as `processRawValue()` (wiring
only, no branch), and the Swift principal class SHALL construct the result via
`init?(rawValue:)`, mapping a `nil` (a raw value the SDK enum does not carry) to `.failure` — so
an untaught value surfaces as a retried, visible failure, never a silently "successful" upload
cycle. A future Kotlin `CycleResult` case cannot slip through untaught: the exhaustive `when`
stops compiling instead.

#### Scenario: Cap during discovery yields a processing result
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** the extension stops creating jobs and the cycle surfaces a processing result (raw value 1,
  constructed as `.processing`)

#### Scenario: A cap-truncated cycle still publishes
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** that cycle still writes its device manifest, emits its enumeration audit line, and fires
  its completion notify if the projection changed

#### Scenario: Pending in-flight work requests re-invocation
- **WHEN** a cycle drains and creates with no cap, but the ledger still has pending (in-flight) rows
- **THEN** the cycle surfaces a processing result so the system re-invokes the extension to record
  their completions, rather than resting until the next library change

#### Scenario: Fully backed up reports completion
- **WHEN** a cycle ends with no pending rows in the ledger
- **THEN** the cycle surfaces the completed raw value and the system rests

#### Scenario: Re-entry resumes the remainder from the ledger
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the un-created remainder is enqueued from its `DISCOVERED` rows, with no duplicate jobs and
  without re-deriving the same change set

#### Scenario: An unconstructible raw value surfaces as failure
- **WHEN** the raw value forwarded to `init?(rawValue:)` is one the SDK enum does not carry
- **THEN** the shell reports `.failure`, so the system retries and the defect stays visible, rather
  than reporting a successful cycle that cannot be trusted
