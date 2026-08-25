## MODIFIED Requirements

### Requirement: Error-severity log lines become events; lower severities become breadcrumbs

A Kermit `LogWriter` registered in both processes SHALL map the existing logging seam onto the reporting
channel: log lines at `Error` or `Assert` severity SHALL be captured as events (with the throwable
attached when one is present); lines at lower severities SHALL be recorded as breadcrumbs attached to
subsequent events. No new `:domain` port is introduced for capture — the Logger seam is the single
capture surface, so every error already reduced into state and logged is reported without
per-call-site instrumentation.

Because this mapping is per-call and unbounded, the severity a call site chooses **is** the decision about
whether an occurrence is reported. A condition that is routine, expected, and self-healing SHALL NOT be
logged at `Error`; the discipline the tree already applies is that the expected outcome logs at `Info` or
`Warn` and only the unexpected path logs at `Error`. No dedupe window, sampling, or suppression is
introduced in the writer: a suppressed error is the silence this capability exists to break.

**The ambient log context SHALL NOT form part of the captured event's message.** The entry-point prefix
the writer adds (`[<entryPoint>] `, capability `diagnostic-logging`) is context about *which trigger was
running*, not about *what went wrong* — but the reporting backend groups by message text, so including it
splits a single cause into one issue per trigger. The captured event SHALL carry the redacted log message
alone, and the entry point SHALL travel as an event **tag** so it stays filterable and visible. The
prefixed text SHALL still be recorded as the accompanying breadcrumb, so the event's own trail is
unchanged. This applies to both capture paths — a captured message and a captured throwable — so one rule
governs, rather than one rule per path.

#### Scenario: A handled upload failure becomes an event

- **WHEN** a feature reduces a failure into state and logs it at `Error` severity with a throwable
- **THEN** an event with that message and throwable reaches Bugsink, with the preceding lower-severity
  log lines attached as breadcrumbs

#### Scenario: Routine log lines alone send nothing

- **WHEN** a process logs only `Verbose`–`Warn` lines and no event is captured
- **THEN** those lines are held as local breadcrumbs only and no event is transmitted

#### Scenario: One cause arrives as one issue regardless of which trigger produced it

- **WHEN** the same `Error`-severity line is logged under several different entry points
- **THEN** the captured events carry the same message text and differ only by their entry-point tag, so
  they group as one issue rather than one issue per entry point

#### Scenario: The entry point remains recoverable from the event

- **WHEN** an event captured from a log line is examined
- **THEN** the entry point that was running is readable from the event's tag, and the prefixed log line
  itself is present among the breadcrumbs
