## ADDED Requirements

### Requirement: A failed extension-registration change is reported, not discarded

`PHPhotoLibrary.setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`. Both SHALL be
captured. A registration change that fails SHALL be reported with the error's domain and code, not
discarded.

This matters because the failure is otherwise **invisible and terminal**: if enabling fails, the extension is
never registered, the OS never launches it, no upload cycle ever runs, and the screen sits at
"Synchronization pending…" indefinitely with no error anywhere — in the log, on the screen, or in crash
reporting. The mechanism's failure mode is silence, which is precisely the case "Absence is never silent"
(spec `module-architecture`) exists to refuse.

A failing **enable**, and any failure whose error is not the expected fresh-install case below, SHALL be
logged at `Error` severity, so `crash-reporting` carries it as field telemetry rather than leaving it
knowable only by attaching to a device.

The **leading disable** of the disable→enable ritual SHALL NOT be treated as a failure when it reports
`PHPhotosError` **3201** ("Unable to find the configuration"). On any clean device there is no configuration
record to remove, so that outcome is the expected result of a first registration — measured twice on an SE2
(iOS 26.6). Raising on it would place a reporting event on every first join of every fresh install, burying
the signal this requirement exists to surface in noise the requirement itself created.

The disable's own return SHALL be used as evidence rather than only as an error check: a disable that
**finds** a record returns `true` with no error, so the write distinguishes "there was a registration" from
"there was not" as a side effect of doing its job — a distinction the read-back cannot reliably make.

Both call sites SHALL go through one helper. `setUploadJobExtensionEnabled` serves both `start()` and
`stop()`, and checking one call but not the other would be a deliberate blind spot.

#### Scenario: Enabling the extension fails
- **WHEN** `setUploadJobExtensionEnabled(true)` returns `false`
- **THEN** the failure is logged at `Error` severity with the error's domain and code, and reaches crash
  reporting as an event

#### Scenario: The fresh-install disable is not a failure
- **WHEN** the leading disable of the ritual runs on a device with no configuration record and returns
  `false` with `PHPhotosError` 3201
- **THEN** the outcome is logged at debug severity and raises no reporting event

#### Scenario: A disable that finds a record says so
- **WHEN** the leading disable runs on a device that already holds a configuration record
- **THEN** it returns `true` with no error, and that outcome is recorded as evidence that a registration
  existed

#### Scenario: Both halves go through the same check
- **WHEN** either `start()` or `stop()` changes the registration
- **THEN** the same helper captures the return and the error for both

### Requirement: The OS's own view of the registration is reported as what it reports

Where a diagnostic surface reports whether the upload-job extension is registered, it SHALL report the OS's
answer (`PHPhotoLibrary.isUploadJobExtensionEnabled()`) as **what the OS reports**, and SHALL NOT present it
as what the OS holds.

The read SHALL be **three-valued**, never a bare boolean. `isUploadJobExtensionEnabled` is a 26.1 selector
while the app deploys to a minimum of iOS 18, so an unconditional call traps as an unrecognized selector. It
SHALL be reached only through a path that exists on the OS-driven tier — the same confinement that makes
every other upload-job call safe — and SHALL report a distinct **not-applicable** answer on an OS that has no
such selector, rather than `false`. Reporting `false` there would state "not registered" about an OS on which
registration could never occur.

A `false` answer SHALL carry the qualifier that makes it readable, because the read is **grant-dependent**:
measured on an SE2 (iOS 26.6), the OS reported `false` under `NOT_DETERMINED` photo access for a record that
was live in that same install and had survived a delete-and-reinstall, and `true` for that same record once
access was granted — one install, one variable, minutes apart. So `false` collapses "there is no record" with
"I am not permitted to see one", and a surface reporting it SHALL make that distinguishable.

⏰ Two cells remain unmeasured: `LIMITED` photo access, and a record left by a differently-signed build.

#### Scenario: The read is not attempted below 26.1
- **WHEN** the diagnostic surface is read on a device running iOS 18–26.0
- **THEN** it reports the not-applicable answer, and `isUploadJobExtensionEnabled` is never called

#### Scenario: A false answer without a grant is qualified
- **WHEN** the read returns `false` while photo access is `NOT_DETERMINED`
- **THEN** the surface reports the answer together with the access state, so "no record" and "not permitted
  to see one" are distinguishable rather than collapsed

#### Scenario: The answer is labelled as reported, not held
- **WHEN** the surface presents the OS's answer
- **THEN** it is labelled as what the OS reports, and no consumer treats it as proof that no configuration
  record exists
