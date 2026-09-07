## ADDED Requirements

### Requirement: The app subscribes for process-metric reports at process start

The app shell SHALL register the process-metric subscriber (capability `crash-reporting`) during its
own initialization — the path that runs on **every** process start, whatever woke it — and SHALL NOT
seat it on the deferred application graph.

Two measured facts force this, and neither is a preference:

1. **The platform accumulates nothing until first asked.** Reports begin accruing only after the
   process first touches the platform's metric manager, and never retroactively. A launch that does
   not subscribe is attribution nobody gets back, so the earliest unconditional path is the only
   correct seat.
2. **Delivery is one-shot.** Reports wait indefinitely while no subscriber exists, but once handed
   over they are not redelivered. Subscribing without a live consumer therefore converts a report the
   OS was holding safely into a discarded one — strictly worse than not subscribing at all.

From (2): **subscribing and handling SHALL be inseparable.** Whatever registers the subscriber SHALL
already be able to handle what arrives, on every launch shape — including a background wake that never
assembles the application graph.

Registration SHALL NOT force assembly of the deferred application graph, preserving the existing
property that a cold background wake assembles only what that wake needs.

The subscriber SHALL be retained for the process lifetime, and the platform surface it uses SHALL be
linked only into the app process — the background-upload extension SHALL NOT link it.

#### Scenario: A background wake receives a report

- **WHEN** the process is woken in the background and never assembles the application graph
- **THEN** the subscriber is registered and the report is handled, rather than delivered to nothing

#### Scenario: Registration does not assemble the graph

- **WHEN** the process starts and registers the subscriber
- **THEN** the deferred application graph is not forced, and nothing reads protected data or opens a
  store earlier than it otherwise would

#### Scenario: The extension does not subscribe

- **WHEN** the background-upload extension process runs
- **THEN** it links no process-metric platform surface and registers no subscriber
