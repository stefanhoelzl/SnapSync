## ADDED Requirements

### Requirement: Log timestamps carry millisecond resolution

Every device-log line SHALL carry a timestamp with at least millisecond resolution. At whole-second
resolution the ordering of lines emitted within the same second is unrecoverable, and that ordering is
exactly what separates "the library call was slow" from "the process was frozen after it returned" —
a distinction that had to be reconstructed by deduction from durations rather than read.

#### Scenario: Two lines in the same second are ordered

- **WHEN** two log lines are emitted within the same second, on the same or different threads
- **THEN** their timestamps distinguish which was written first

### Requirement: Deadline expiry is logged

Every bounded wait that reaches its deadline SHALL be logged, naming what expired and what was still
outstanding. Those waits are an OS completion handler released on its deadline (capability
`ios-app-shell`) and an import abandoned on its deadline (capability `photo-download`).
A bound that fires silently
is indistinguishable from work that completed, so the mechanism that protects the app would be invisible
in exactly the dumps that exist to explain it.

#### Scenario: A released-on-deadline handler is attributable

- **WHEN** an OS completion handler is released because its deadline expired rather than because its
  work finished
- **THEN** the log records the expiry and the entry point it belongs to

#### Scenario: An abandoned import is attributable

- **WHEN** an import is abandoned at its deadline
- **THEN** the log records the expiry and the asset it applied to

### Requirement: Photo-library change blocks and completions are traced

The photo-library asset-creation change block and its completion callback SHALL each be logged — block
entry, and completion with its success or error. An import whose completion never arrives currently
leaves no trace of whether the change block ran or whether the transaction committed, which is precisely
the state that decides whether an abandoned import becomes a duplicate.

#### Scenario: A never-completing import leaves evidence

- **WHEN** an import's completion callback never arrives before the process ends
- **THEN** the log shows whether the change block ran, so the transaction's fate is diagnosable

#### Scenario: A failed commit is attributable

- **WHEN** an asset-creation commit reports failure
- **THEN** the completion's error is logged, not only the resulting import outcome
