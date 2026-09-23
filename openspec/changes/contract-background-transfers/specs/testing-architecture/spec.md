## MODIFIED Requirements

### Requirement: Device-only behaviour is measured, not mocked

Behaviour reachable only on hardware SHALL NOT be asserted by unit tests: PhotoKit's
cloud-identifier and upload-job subsystems, App Attest, background `URLSession` reattachment, APNs
delivery, and the limited-access alert's arming. It SHALL be established instead by a recorded
on-device measurement carrying its expiry trigger (`module-architecture`, "Necessity claims carry
forcing proofs").

Where the testable surface of such a system stops SHALL be stated by the port contracts' declared reach
(capability `port-contracts`), not by smoke tests that call the platform without asserting an outcome: a
binding's literal set of reachable states names what a host exercises, and a clause no real host reaches is
not written. PhotoKit under a full grant, asset and album creation, and imports are reachable on the
simulator app and are therefore asserted there; so are the app's byte transfers — both app-process
`URLSession` transports, over the default session the simulator target binds, with everything but the
background session's lifecycle (capability `port-contracts`, "An adapter bound per compilation target is real
for the clauses it runs there"). The upload-job subsystem, a partial grant, the limited-access alert's arming,
the background session's lifecycle, and `BGTaskScheduler` remain device-only; the scheduler is reached through
a device recording replayed on every build. A smoke test SHALL remain only for a device-only surface
no contract yet binds, and SHALL name the change expected to replace it.

A test that appears to cover such behaviour asserts a copy of the platform's constants against
itself, which is why the platform-vocabulary pin reads the declared vocabulary from the
Kotlin/Native distribution instead (`architecture-guards`).

#### Scenario: A device-only claim is asserted in a unit test

- **WHEN** a test asserts behaviour that only hardware exhibits
- **THEN** it is replaced by a recorded measurement with a named expiry trigger, because the test
  can only restate its own fixture

#### Scenario: A PhotoKit read is covered by a contract, not a smoke test

- **WHEN** the reach of real PhotoKit on the simulator is in question
- **THEN** the answer is the PhotoKit contracts' bindings on the simulator hosts and their outcomes, and no
  smoke test restates it

#### Scenario: A remaining smoke test names its successor

- **WHEN** a simulator smoke test still calls an unbound device-only surface, such as the upload-job fetch
- **THEN** its documentation names the change expected to replace it with a contract

#### Scenario: A byte transfer is covered by a contract, not by a device-only claim

- **WHEN** it is in question whether the download transport stages an accepted body at the owner's path
- **THEN** the answer is the `DownloadTransport` contract's live binding on the simulator app, and only the
  background session's lifecycle stays a recorded measurement
