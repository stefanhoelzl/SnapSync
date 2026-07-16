## ADDED Requirements

### Requirement: The extension root contains only what is tier-specific

`process()` SHALL contain only the three concerns that cannot be shared with another upload tier:

- **The synchronous OS contract** — the cycle is driven to completion and its result returned, because the
  OS invokes `process()` synchronously and the process does not outlive it.
- **The cross-process liveness notification** — posted after every run, because this tier writes the ledger
  in a different process from the UI. A tier that writes it in-process refreshes in-process instead and
  posts nothing (capability `ios-url-session-upload`).
- **The pending→processing requeue** — because the OS invokes this tier lazily, on library changes rather
  than on upload completion, this tier alone must ask to be re-invoked while jobs are still in flight.

Everything else the root does today — the membership read's decision, the leave-side reconciliation, the
engine and cycle assembly, the manifest and notify hooks, the cutoff and contribution derivation — SHALL
move to the shared cycle (capability `upload-lifecycle`). What remains SHALL be translation: mapping this
platform's storage and bundle into the shared decision function's arguments, with no branch a second tier
could answer differently.

The root is `iosMain`-only and untestable by project rule (`:app:ios` and the extension's composition root
are wiring-only). That rule is a constraint on what may live there, not a licence: a decision placed in an
untested root reaches whichever tiers its author enumerated, which is how the reconciliation, the direction
gate, and the membership read each shipped on one tier and not the other.

#### Scenario: The skip decision is not made in the root
- **WHEN** the extension is invoked and its membership is unreadable
- **THEN** the skip is decided by the shared cycle, and the root neither branches on the read nor
  reconciles

#### Scenario: The liveness notification still fires on every invocation
- **WHEN** the extension completes an invocation, whatever its result
- **THEN** the cross-process liveness notification is posted

#### Scenario: A drained cycle with pending jobs still asks for re-invocation
- **WHEN** the cycle would otherwise report completed and the ledger still holds pending rows
- **THEN** the extension surfaces processing instead, unchanged

### Requirement: The extension root's skip diagnostic survives the move

The forensics for a skipped cycle SHALL remain a single log line carrying why the read failed — the
membership read's status and whether the device identity resolved. The skip decision is made in shared
code, which cannot see either; the root SHALL therefore supply the detail with the decision, and the cycle
SHALL log it verbatim.

An unreadable membership is invisible on a device except through this line: nothing else distinguishes "we
skipped, correctly" from "we did nothing, wrongly". `debug.log` is the canonical un-redacted channel for
it, and one line in one file is the readable form.

#### Scenario: A skipped cycle names the cause
- **WHEN** a cycle is skipped because protected data is unavailable
- **THEN** one log line records the membership read's status, whether the device identity resolved, and
  that this was not treated as a leave
