## ADDED Requirements

### Requirement: A coalesced pump trigger keeps its obligations

A coalesced pump trigger SHALL NOT be discarded. The pump admits one drain at a time and coalesces
concurrent triggers into a trailing re-run; the coalescing caller SHALL await the in-flight drain and,
when it ends, SHALL apply **its own** trigger's re-arm policy against that drain's final `CycleResult`.

Returning immediately drops two obligations at once, and both cost the app future background wakes. A
caller that awaited nothing cannot be the work an OS completion handler is held for — the handler is
released against a cycle still running elsewhere. And a caller that skipped its re-arm leaves the
`BGProcessingTask` chain unarmed, which is fatal because the request is one-shot: the heartbeat then
resumes only when the user next foregrounds the app.

The re-arm SHALL be evaluated against the drain's result rather than assumed, because the coalesced caller
ran no cycle of its own and only that result answers whether work remains. A `SKIPPED` drain SHALL
therefore still arm nothing, from any trigger, exactly as an uncoalesced one does.

The awaited span is the in-flight drain **including** the re-run the coalescing caller requested. The pump
SHALL NOT bound that wait, and nothing else bounds it either: an OS receipt bounds when the *handler* is
released (capability `ios-app-shell`), not how long the awaiting call takes. So a drain that never ends
holds its coalesced callers indefinitely; what is guaranteed is only that the OS is answered on time
regardless, and that the drain is never cancelled to achieve it.

#### Scenario: A coalesced background-session trigger awaits the drain

- **WHEN** background-session events are delivered while a drain started by a completion is already in
  flight
- **THEN** the trigger coalesces, awaits that drain, and returns only after it has ended — so the OS
  handler held for it is not released against a running cycle

#### Scenario: A coalesced heartbeat still re-submits

- **WHEN** a `BGProcessingTask` handler fires while a drain is already in flight
- **THEN** it coalesces, awaits the drain, and re-submits the next task, because its trigger's re-arm is
  unconditional

#### Scenario: A coalesced relaunch trigger re-arms only on remaining work

- **WHEN** a background-session trigger coalesces and the drain it awaited ends `COMPLETED`
- **THEN** no next task is scheduled; had the drain ended `PROCESSING`, one would be

#### Scenario: A coalesced trigger against a declining membership arms nothing

- **WHEN** a trigger coalesces into a drain that ends `SKIPPED`
- **THEN** nothing is scheduled, whatever the coalescing trigger's own policy would otherwise be
