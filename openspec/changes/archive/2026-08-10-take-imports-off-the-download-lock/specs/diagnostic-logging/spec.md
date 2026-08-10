## ADDED Requirements

### Requirement: An import that never returns is attributable

Each per-asset photo-library import SHALL be traced with the uniform enter/exit invocation logging, naming
the asset and reporting the duration on exit — so an import that entered and never exited is visible in a
pulled log and in a diagnostic dump, and is distinguishable from one that was never attempted.

This is the only route by which a never-reporting import becomes visible. Nothing bounds such an import in
time (capability `photo-download`), so no expiry line will ever name it, and the OS-handler receipt's own
expiry line reports that *something* outran the wake without saying what.

#### Scenario: A stuck import is identifiable from the log

- **WHEN** an import is entered and its completion never arrives
- **THEN** the log carries that import's entry line naming the asset, with no matching exit line

#### Scenario: An ordinary import reports its duration

- **WHEN** an import completes normally
- **THEN** the log carries matching entry and exit lines for it, the exit carrying the elapsed duration

## MODIFIED Requirements

### Requirement: Deadline expiry is logged

Every bounded wait that reaches its deadline SHALL be logged, naming what expired and what was still
outstanding. That wait is an OS completion handler released on its deadline (capability `ios-app-shell`).
A bound that fires silently
is indistinguishable from work that completed, so the mechanism that protects the app would be invisible
in exactly the dumps that exist to explain it.

#### Scenario: A released-on-deadline handler is attributable

- **WHEN** an OS completion handler is released because its deadline expired rather than because its
  work finished
- **THEN** the log records the expiry and the entry point it belongs to
