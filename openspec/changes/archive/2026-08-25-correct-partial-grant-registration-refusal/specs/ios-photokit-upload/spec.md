## MODIFIED Requirements

### Requirement: The registration cannot be changed under a partial grant

The OS-driven tier SHALL be treated as **unavailable** while the containing app holds a partial
(`.limited`) photo grant, because a partially-granted process **cannot change its upload-job registration
in either direction**.

Forcing proof: `setUploadJobExtensionEnabled` is refused with `PHPhotosErrorAccessUserDenied` (3311) for
both `false` and `true` — measured on device (SE2 / iOS 26.6, 2026-08-24 and 2026-08-25; decision record
`changes/archive/2026-08-25-collapse-upload-tier-seam`, D11 and D11b). The **enable** was reached only by
pinning the OS-driven mechanism under a partial grant through a development mechanism override, which no
shipped build can supply; in production an enable is never attempted there, because resolution never
yields this mechanism under a partial grant.

An earlier probe (SE2 / iOS 26.5, 2026-07-20;
`changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`) measured that with real
pending work and the extension re-registered twice under `.limited`, the OS issued **zero** `process()`
invocations over 22 minutes, then invoked the extension **within seconds** of the grant returning to full.
That observation stands. The mechanism it was read as — *"registration succeeds and lies, with no error
and no callback"* — is **contradicted by measurement**: the call site discarded its `Boolean` and
`NSError` at the time, so "succeeds" described a return value nobody read and "no error" meant none was
looked for. A registration that could not be created explains those 22 minutes at least as economically.
Because that probe is not re-runnable, this SHALL be stated as the asserted mechanism being contradicted,
never as a claim about what that probe observed.

Evidence limits, stated so a reader can tell what would falsify this: one device, one OS point release,
and an enable reached through a development pin rather than a path a user can take. Expiry trigger:
re-evaluate at the iOS 27 GM re-assessment (~Sept 2026, the existing
`PHBackgroundResourceUploadJobExtension` trigger) — the constraint MUST be re-measured against the async
protocol before assuming it persists.

Consequently, under `LIMITED` the upload arm SHALL NOT start this tier's producer — it starts the
app-driven producer instead (capability `upload-lifecycle`). A `LIMITED` membership relying on this tier
would be a silent no-op: the screen would sit at "Synchronization pending…" indefinitely, which is exactly
the failure mode this requirement exists to prevent.

A registration that **survives** a downgrade to a partial grant SHALL be understood as **inert rather than
hazardous**: the OS does not invoke the extension under that grant, and a return to a full grant
re-registers through the disable→enable ritual regardless. There is therefore no state in which a
surviving registration and a running app-driven mechanism produce two ledger writers. Deregistration
remains both possible and required under a **full** grant, which is where a development mechanism override
places the app-driven mechanism (`upload-lifecycle`).

#### Scenario: A limited grant never waits on the extension
- **WHEN** photo access is `LIMITED` and an upload-inclusive membership has pending work
- **THEN** no upload waits on a `process()` invocation — the work runs on the app-driven mechanism

#### Scenario: A downgrade to a partial grant cannot deregister
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while this tier's producer is started
- **THEN** the deregistration attempt is refused with `PHPhotosErrorAccessUserDenied`, the configuration
  record survives, and the app-driven producer starts regardless

#### Scenario: The surviving registration causes no second writer
- **WHEN** a registration survives a downgrade to a partial grant and the app-driven mechanism is running
- **THEN** the OS does not invoke the extension, so exactly one process writes ledger records

#### Scenario: An enable under a partial grant is refused too
- **WHEN** the OS-driven mechanism is pinned by a development override under a `LIMITED` grant and its
  registration ritual calls `setUploadJobExtensionEnabled(true)`
- **THEN** the call is refused with `PHPhotosErrorAccessUserDenied` and no configuration record is created

### Requirement: A failed extension-registration change is reported, not discarded

`PHPhotoLibrary.setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`. Both SHALL be
captured. A registration change that fails SHALL be reported with the error's domain and code, not
discarded.

This matters because the failure is otherwise **invisible and terminal**: if enabling fails, the extension is
never registered, the OS never launches it, no upload cycle ever runs, and the screen sits at
"Synchronization pending…" indefinitely with no error anywhere — in the log, on the screen, or in crash
reporting. The mechanism's failure mode is silence, which is precisely the case "Absence is never silent"
(spec `module-architecture`) exists to refuse.

A failing **enable**, and any failure whose error is not one of the **expected cases enumerated below**,
SHALL be logged at `Error` severity, so `crash-reporting` carries it as field telemetry rather than leaving
it knowable only by attaching to a device. The enumeration is **closed and measured**: a code is expected
only once a device measurement shows it arising on an ordinary path, and widening it is a change to this
requirement.

The **leading disable** of the disable→enable ritual SHALL NOT be treated as a failure when it reports
`PHPhotosError` **3201** ("Unable to find the configuration"). On any clean device there is no configuration
record to remove, so that outcome is the expected result of a first registration — measured twice on an SE2
(iOS 26.6). Raising on it would place a reporting event on every first join of every fresh install, burying
the signal this requirement exists to surface in noise the requirement itself created.

A **disable** that reports `PHPhotosErrorAccessUserDenied` (**3311**) SHALL likewise not be treated as a
failure. Under a partial photo grant the platform refuses the change outright ("The registration cannot be
changed under a partial grant"), so this is the expected outcome of an ordinary, supported user action —
switching Photos to Limited Access — and it recurs on every membership-lifecycle action taken while that
grant is held. It SHALL be reported **below `Error`**, so no reporting event is raised, and at a severity
that still reaches the device log and the diagnostic dump, because the app's model of the registration is
knowingly wrong afterwards even though the surviving record is inert. This is what `crash-reporting`
requires of any condition that is routine, expected, and self-healing.

An **enable** that reports **3311** SHALL remain at `Error`, and SHALL be reported as its own outcome
naming the cause rather than collapsing into the generic failure. The two directions have opposite
consequences: a refused disable leaves an inert record and costs nothing, while a refused enable means no
registration exists, the OS never launches the extension, and nothing else reports it. Reporting them
identically would hide the terminal case behind the routine one.

The disable's own return SHALL be used as evidence rather than only as an error check: a disable that
**finds** a record returns `true` with no error, so the write distinguishes "there was a registration" from
"there was not" as a side effect of doing its job — a distinction the read-back cannot reliably make.

Both call sites SHALL go through one helper. `setUploadJobExtensionEnabled` serves both `start()` and
`stop()`, and checking one call but not the other would be a deliberate blind spot. The classification
SHALL remain a decision of the tested `:domain` `model/` classifier, which carries the severity as a
property of the outcome, so the call site renders without branching (`module-architecture`, "Shells are
wiring only").

#### Scenario: Enabling the extension fails
- **WHEN** `setUploadJobExtensionEnabled(true)` returns `false`
- **THEN** the failure is logged at `Error` severity with the error's domain and code, and reaches crash
  reporting as an event

#### Scenario: The fresh-install disable is not a failure
- **WHEN** the leading disable of the ritual runs on a device with no configuration record and returns
  `false` with `PHPhotosError` 3201
- **THEN** the outcome is logged at debug severity and raises no reporting event

#### Scenario: A refused disable under a partial grant is not a failure
- **WHEN** a disable returns `false` with `PHPhotosError` 3311 because the app holds a partial photo grant
- **THEN** the outcome is logged below `Error` severity, raises no reporting event, and still appears in
  the device log and any diagnostic dump

#### Scenario: A refused enable under a partial grant stays an error
- **WHEN** an enable returns `false` with `PHPhotosError` 3311
- **THEN** the outcome is logged at `Error` severity as a distinct outcome whose message names the partial
  grant as the cause, and reaches crash reporting as an event

#### Scenario: A disable that finds a record says so
- **WHEN** the leading disable runs on a device that already holds a configuration record
- **THEN** it returns `true` with no error, and that outcome is recorded as evidence that a registration
  existed

#### Scenario: Both halves go through the same check
- **WHEN** either `start()` or `stop()` changes the registration
- **THEN** the same helper captures the return and the error for both

## RENAMED Requirements

- FROM: `### Requirement: The OS does not invoke the extension under a limited grant`
- TO: `### Requirement: The registration cannot be changed under a partial grant`
