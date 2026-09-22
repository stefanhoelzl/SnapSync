## MODIFIED Requirements

### Requirement: Cap-aware creation and tri-state processing result

When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle and surface a **processing** result so the system re-invokes it
promptly. It SHALL NOT stop anything else: the walk's facts are already recorded (see "In-extension
discovery by full enumeration"), the un-created remainder is already recorded `DISCOVERED`,
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
map to the completed raw value). The extension's cycle SHALL be reached through the extension's
inbound port, `ExtensionEntries.process(): CycleResult`, implemented over `uploadCore` in `compose/` (`module-architecture`, "OS entry points cross an
inbound port"); the extension root SHALL expose `processRawValue()` as that call with the mapping applied
(wiring only, no branch), and the Swift principal class SHALL construct the result via
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
