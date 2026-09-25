## MODIFIED Requirements

### Requirement: All-real journeys are the contracts' safety net

A small set of **journeys** SHALL run end to end with every system real:
- the rig build of the iOS app, on **one** simulator;
- the real backend, served locally;
- the real photo library.

They SHALL be written against the typed client only, as the integration surface is. They SHALL cover:
- creating an event and joining it;
- a member's own photos landing in the backend and the event union;
- that member receiving another member's photos into their library.

The **other member** SHALL be played by the journey itself, over the backend's public HTTP surface only. It joins
the app's event and uploads and publishes photos exactly as a device would address them, with **real JPEG bytes**,
so the app's download ends in a real photo-library import. It SHALL NOT be a world lever or any route outside
the backend's public surface, because a member the backend could tell apart from a device is not a member.

A journey failure SHALL be read first as a **missing contract clause**: a behaviour the mocks do not hold,
fixed by a new clause, after which the mocked suite covers it.

The journeys SHALL gate merges, in the job that runs the in-app contracts (capability `ios-ci`). They SHALL
fail, never skip, when a host or the backend they are pointed at is absent.

#### Scenario: A journey finds a behaviour no mock holds

- **WHEN** a journey fails where the mocked suite passes
- **THEN** the fix adds the contract clause the mocks were missing, and the mocked suite then covers the
  behaviour

#### Scenario: A journey is run without its hosts

- **WHEN** the journey task runs with no simulator app or backend address given
- **THEN** it fails naming the missing address, rather than passing with nothing run

#### Scenario: The app receives a member it cannot tell from a device

- **WHEN** the journey's member joins the app's event and publishes photos with real JPEG bytes
- **THEN** the app downloads them and its photo-library census grows by their count, with the member having
  used nothing but the backend's public HTTP surface
