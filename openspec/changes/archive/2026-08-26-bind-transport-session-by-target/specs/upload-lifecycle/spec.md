## MODIFIED Requirements

### Requirement: The upload mechanism is resolved, never selected

The system SHALL determine which upload mechanism runs by a **pure, exhaustively-tested resolution**
from OS facts, current photo permission, and whether the app-driven tier is forced, to a mechanism
**kind**. A composition-supplied factory SHALL map a kind to an instance. The tier-neutral orchestrator
SHALL hold **at most one** producer reference at any time, and SHALL obtain a new one only by
re-resolving when a resolution input changes.

The **transport binding** the app-driven mechanism uses is a different axis and SHALL NOT enter this
resolution: it is fixed by the compilation target (`ios-url-session-upload`, "The transport binding is
fixed by the compilation target"), and `module-architecture` requires that a fact fixed by the
compilation target is not re-derived at runtime nor admitted into this function. Which mechanism runs
stays a genuine runtime decision; which session kind it transfers over is not a decision at all.

Resolution SHALL be total, and SHALL NOT yield a kind whose mechanism this OS cannot run: the OS-driven
mechanism's registration selector does not exist below iOS 26.1, so a cell yielding it there would trap
and abort the process. The resolver — not a composition root — SHALL own this, because a root is
wiring-only and untested by project rule.

Presence and runnability are **separate facts**. "This OS has no such mechanism" and "the mechanism is
present but this build must not run it" SHALL NOT share an encoding. Collapsing them is what previously
left a present mechanism with no route to its own teardown on a forced build: the OS-driven producer was
not constructed, so nothing could call the `stop()` that deregisters its extension, while the OS's
upload-job configuration record — keyed by bundle id and surviving relaunch **and** reinstall — remained.

The factory SHALL cache an instance whose platform demands a process-lifetime singleton. On every shipped
binary the app-driven mechanism owns a background `URLSession` whose identifier must stay stable and whose
invalidation is terminal (`ios-url-session-upload`, "Cancellation never invalidates the background
session"), so re-resolving to that kind SHALL return the same instance rather than constructing a second
one. The caching SHALL NOT be conditioned on the transport binding: on `iosSimulatorArm64`, where the
session is a default one and its identifier is inert, a second instance would still mean two live sessions
and two task registries for one mechanism, so the same single instance SHALL be returned there too.

Where an OS carries more than one mechanism, **each** resolved mechanism SHALL relinquish what the other
leaves behind, before it starts. Both leave state the OS keeps across process death — the OS-driven one a
configuration record keyed by bundle id, the app-driven one in-flight background transfers and a submitted
background task — so a process that has just launched may be running behind work it never started.
Relinquishing the OS-driven mechanism on the way to the app-driven one SHALL be **deregistration only**
(see the repair carve-out in "Upload producer seam has no destructive verb"); relinquishing the app-driven
mechanism SHALL be its ordinary `stop()`.

Stopping the arm SHALL likewise stop **every** mechanism the composition can yield, not only the one
currently held: a mechanism this process never started can still have work outstanding on its behalf.

#### Scenario: Starting the OS-driven mechanism cancels app-driven work left by an earlier process

- **WHEN** the OS-driven mechanism is resolved on a device where a previous process left in-flight
  app-driven transfers or a submitted background task
- **THEN** those are cancelled before the OS-driven mechanism starts, so only one process writes records

#### Scenario: A forced build on an OS-driven-capable device relinquishes the registration

- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven mechanism, and an
  upload-inclusive membership is provisioned under usable access
- **THEN** resolution yields the app-driven kind for that OS, whose producer deregisters the OS-driven
  extension before it begins pumping — so the OS cannot invoke the extension behind the running tier

#### Scenario: The same cell serves a downgrade to limited access

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` on a device whose OS supports the
  OS-driven mechanism
- **THEN** resolution yields that same app-driven kind, and the extension is deregistered by the same
  mechanism rather than by a separate rule

#### Scenario: Resolution never yields an unrunnable mechanism

- **WHEN** every combination of OS facts, permission, and forced state is resolved
- **THEN** no combination yields the OS-driven kind on an OS that lacks it

#### Scenario: The transport binding is not a resolution input

- **WHEN** the resolver's inputs are enumerated
- **THEN** the session kind the app-driven mechanism transfers over is not among them, and no cell varies
  by it

#### Scenario: Re-resolving to the app-driven kind reuses its instance

- **WHEN** the resolved kind changes away from the app-driven mechanism and later back to it
- **THEN** the same instance is obtained, its session was never invalidated, and uploads
  resume without aborting the process
