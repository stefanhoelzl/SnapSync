## MODIFIED Requirements

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

The registration change SHALL be made through a **port** in `:domain` `ports/`, named for the need, whose
iOS adapter — the only implementation that calls `PHPhotoLibrary.setUploadJobExtensionEnabled` or
`isUploadJobExtensionEnabled` — lives in `:adapter:ios:app-only`, because only the app process ever
registers. The mechanism that performs the ritual SHALL hold no platform call of its own — including the
discovery-cursor reset its repair performs, which SHALL go through the cursor's own port rather than a
second direct write to the same key — and SHALL therefore live in `:domain` `feature/upload` beside the
app-driven tier's mechanism, named for the need rather than for the platform. This is the ports law applied where it was not: the call sat in
`:app:ios`, which is wiring-only and gated at `CyclomaticComplexMethod` threshold 2, so it could report the
platform's raw facts but could hold no decision about them. Behind a port, the ritual, its `stop()` repair,
and every arm of the outcome classification become executable on any host that can implement the port,
including JVM.

The registration record is OS state that this repo does not own, exactly as the upload-job queue is. Where
a target's host cannot hold such a record, the port's binding for that target answers in its place; see
"The upload-job subsystem binding is fixed by the compilation target".

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

#### Scenario: The mechanism holds no platform call
- **WHEN** the mechanism that performs the disable→enable ritual is compiled
- **THEN** it names no platform API at all — the registration change and its read-back are reached through
  the registration port, and the cursor reset through the discovery-cursor port — so it compiles for every
  target the platform-free core does

#### Scenario: The ritual is executable off a device
- **WHEN** the ritual runs against a port implementation that reports a pre-existing configuration record
- **THEN** the leading disable reports that a record existed and was removed, the enable reports success,
  and the sequence is asserted without a physical device

#### Scenario: The repair completes before the re-enable
- **WHEN** the ritual runs while the ledger holds orphaned `REQUESTED` rows
- **THEN** the rows are cleared and the discovery cursor reset **before** the enable is attempted, so the
  repair cannot delete rows belonging to the registration it is about to re-create

#### Scenario: The narrow deregister repairs nothing
- **WHEN** the tier switch deregisters the OS-driven mechanism in order to hand off to the app-driven one
- **THEN** the registration is removed and neither the ledger rows nor the discovery cursor is touched,
  because both belong to the mechanism about to start

## ADDED Requirements

### Requirement: The registration reports exactly what the platform returned

A registration change SHALL be reported by its classified outcome and by nothing else. No line SHALL claim
that a registration was applied unless that claim is derived from the value the platform returned for that
change.

This exists because the opposite shipped. `start()` logged `background-upload extension re-registered
(disable→enable, cleared REQUESTED)` at `Info` **unconditionally**, milliseconds after the same method's
outcome classification may have reported the enable as failed at `Error` — so a `debug.log` from a device
whose registration had just failed terminally also carried a plain statement that it had succeeded, in the
one capability whose stated failure mode is that *"nothing else will report it"*. Both halves of that line
were already reported by the code that performed them: the enable by its own outcome, the `REQUESTED` clear
by the clear itself.

The remedy SHALL be to remove the unearned claim rather than to make it conditional. The call site is in
`:app:ios`, which the shell gate holds at `CyclomaticComplexMethod` threshold 2, so a branch on the outcome
is a decision it may not hold; the outcome type already carries its own severity and message precisely so
that the shell renders without deciding. A shell that asserts is a shell that decided.

#### Scenario: A failed enable is not followed by a success claim
- **WHEN** the enable half of the ritual reports a failure outcome
- **THEN** the log carries that failure and no statement that the extension was registered

#### Scenario: A successful enable is reported once
- **WHEN** the enable half of the ritual succeeds
- **THEN** the success is stated by the outcome alone, not restated by a second unconditional line

### Requirement: The upload-job subsystem binding is fixed by the compilation target

The **OS upload-job subsystem** SHALL be reached through seams whose implementation is chosen by
**compilation target**, never by a runtime check. That subsystem is the registration record
(`setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`), the job sets (`fetchJobsWithAction`), and
job creation, retry and acknowledgement. `iosArm64` — every shipped binary — SHALL bind the PhotoKit
implementations. A device binary SHALL contain no route to any other binding.

No other PhotoKit surface is covered by this requirement. Asset and resource fetches, the persistent
change-token walk, the selection policy's reads, and album creation and membership SHALL remain the real
platform APIs on every target.

**Forcing proof.** On `iosSimulatorArm64` the subsystem is not merely unscheduled, it is fatal.
Measured 2026-08-26 on iOS 26.5 under a full grant on a clean device, with the extension embedded and
signed: `setUploadJobExtensionEnabled(true)` returns `false` with `PHPhotosErrorDomain:-1` — a code distinct
from `3201`, `3202` and `3311` — and `isUploadJobExtensionEnabled()` then answers `false`. With no
configuration record, `creationRequestForJobWithDestination` raises `NSInvalidArgumentException` from inside
`-[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]` and **terminates the process**; it does
not return an error. Decision record: `PROBE-FINDINGS.md` in this change. A runtime check that could be taken
wrongly would therefore kill the process rather than degrade, which is why the choice is a compilation
target. Because a simulator refuses every provisionable entitlement, ad-hoc signing with the App Group alone
is the only buildable configuration for that target, so the measurement is co-extensive with the target.
**Expiry:** re-measure at the next iOS major, alongside the other PhotoKit platform facts.

The extension's composition root SHALL obtain its `BackgroundTransfer` from the target-bound seam rather
than constructing a named implementation, and SHALL be otherwise identical on every target. No caller SHALL
duplicate the root's port bundle in order to substitute one port: a second assembly of that bundle is a
second composition, and the host that most needs the real one is the host that would be running the copy.

A substituted subsystem SHALL delegate resource discovery to the real PhotoKit discovery, exactly as the
PhotoKit implementation does. Discovery is not part of the subsystem and works on every target.

This does not widen the closed and measured expected-code enumeration in "A failed extension-registration
change is reported, not discarded". A `PHPhotosErrorDomain:-1` reaching a device build remains an
unexpected, terminal failure reported at `Error`.

#### Scenario: A device binary contains no substitute
- **WHEN** the `iosArm64` binary is built
- **THEN** it binds the PhotoKit registration and job-queue implementations, and contains no source for any
  other binding

#### Scenario: A simulator build never reaches job creation
- **WHEN** the upload cycle runs on `iosSimulatorArm64` and the engine issues an upload
- **THEN** job creation is answered by that target's binding, and
  `creationRequestForJobWithDestination` is not called

#### Scenario: Discovery is unaffected by the substitution
- **WHEN** a substituted subsystem is asked to discover resources
- **THEN** it delegates to the real PhotoKit change-token walk and the real selection policy, and the
  candidates it yields are the platform's own

#### Scenario: One composition serves every target
- **WHEN** the extension root assembles its upload cycle on any target
- **THEN** it builds one port bundle, whose `BackgroundTransfer` is whatever that target's seam yields, and
  no second assembly of that bundle exists anywhere
