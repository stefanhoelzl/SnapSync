## ADDED Requirements

### Requirement: A staged resource reaches the controller on every entry point

A resource the download transport finishes staging SHALL reach the download controller — which records it
staged and runs its import — whichever entry point brought the process up, including a cold background
relaunch that only delivers download-session events and builds nothing else. The jobs' staging callback
SHALL be supplied at construction (capability `module-architecture`, "Callbacks are bound at construction")
and SHALL resolve the controller when invoked. A staging report that nevertheless cannot be delivered SHALL
be logged with the resource it concerns; it SHALL NOT be dropped silently.

#### Scenario: The OS relaunches the app only to deliver download completions

- **WHEN** iOS relaunches the process in the background for the download session, and the transport
  reports a staged resource before anything else in the core has been built
- **THEN** the controller records it staged and imports it, and the OS handler is released only after
  that import settles

#### Scenario: A staging report cannot be delivered

- **WHEN** a staging report arrives and the controller cannot be obtained
- **THEN** the device log records the resource and the reason, and the staged bytes are left for the
  interrupted-import sweep
