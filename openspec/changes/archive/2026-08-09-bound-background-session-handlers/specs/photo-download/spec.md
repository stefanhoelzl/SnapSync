## ADDED Requirements

### Requirement: The download session's OS handler is bounded, and its adoption is visible

The download session's `handleEventsForBackgroundURLSession` handler SHALL be carried by the same bounded
receipt every other OS handler uses (capability `ios-app-shell`), rather than stored in a field and
invoked when the imports happen to finish. Awaiting the imports is correct and SHALL continue; awaiting
them **without a bound** is not, because an import that never reports leaves the handler unanswered
forever, and an unanswered handler costs the app its future background wakes — including the download
wakes this capability depends on.

The bound SHALL run from the handover, and its expiry SHALL release the handler and leave the imports
running, never cancel them. This is the same trade the import deadline already makes in the opposite
direction: that deadline bounds a single import so it cannot hold the controller's lock forever, while
this one bounds only how long the OS is kept waiting.

Adopting the handler SHALL be logged as an invocation, like every other platform-triggered entry
(capability `diagnostic-logging`; law *Absence is never silent*). Without it a diagnostic dump cannot
distinguish a handler that was released from one that never was — the download side's behaviour was
unreadable in the field for exactly this reason, while the upload side's was measurable.

#### Scenario: The handler is released after the imports, within the bound

- **WHEN** a background-session wake delivers staged resources and the imports they trigger finish inside
  the bound
- **THEN** the OS completion handler is released after those imports, on the main thread

#### Scenario: A stalled import does not strand the handler

- **WHEN** an import started by a background-session wake has not reported when the bound expires
- **THEN** the OS completion handler is released, the expiry is logged, and the import continues rather
  than being cancelled

#### Scenario: The adoption is readable in a dump

- **WHEN** the OS relaunches the app to deliver download-session events
- **THEN** the adoption is logged with its entry point, so a later dump shows the wake arrived and what
  became of its handler
