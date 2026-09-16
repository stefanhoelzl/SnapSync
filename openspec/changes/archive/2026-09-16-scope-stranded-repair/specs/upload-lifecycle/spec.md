## MODIFIED Requirements

### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm's **lifecycle** as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger. Durable dedup state
is device-global (`sync-ledger`), and divergence from storage is repaired by reconciliation
(`upload-state-reconciliation`), never by a lifecycle wipe.

The trigger surface ("Triggers are delivered to the mechanism and declined explicitly") SHALL be a
**separate** seam on the same object, so this lifecycle seam keeps exactly the two verbs above and the
orchestrator is given no trigger to invoke.

The property being defended is **dedup**: the proof that a photo is already in the event. Destroying it
re-uploads a member's whole post-cutoff library — the failure this project exists to prevent. The ledger's
`COMPLETED` rows and the stored bytes are that proof; the **discovery cursor is not**. A cleared cursor
costs one full re-enumeration, which finds nothing new, because dedup lives in the ledger it did not touch.

`stop()` SHALL NOT clear the discovery cursor either. That is not because the cursor is dedup state — it is
not — but because no mechanism needs it: the damage a stop can leave behind is `REQUESTED` rows no transfer
will settle, and each mechanism repairs those in its own **`start()`** by demoting them to `FAILED`
(`ios-photokit-upload`, `ios-url-session-upload`), which the ledger's work read returns without a walk. A
repair belongs to the start because the start is the one moment a mechanism knows no other transfer is still
carrying those rows, and because every path back to uploading passes through one.

(This seam previously permitted a `stop()` to clear its cursor as a repair for jobs its own mechanism wiped.
Its only instance was the PhotoKit disable, whose bulk *delete* of `REQUESTED` rows could not be recovered
without a re-enumeration. With the rows demoted instead of deleted, the permission has no use and is
withdrawn rather than kept.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: Stopping clears no cursor

- **WHEN** `stop()` is called on either tier, including as part of a switch or a leave
- **THEN** the discovery cursor is left exactly where it was

#### Scenario: Rows a stop leaves stranded are repaired by the next start

- **WHEN** a `stop()` leaves `REQUESTED` rows that no transfer will settle, and a mechanism is later started
- **THEN** that start demotes those rows to `FAILED`, and the next cycle re-creates their uploads without
  re-enumerating the library

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
Relinquishing either mechanism SHALL be its ordinary `stop()`. Neither `stop()` repairs ledger state — each
mechanism's repair runs in its own `start()` (see "Upload producer seam has no destructive verb") — so there is
no teardown narrower than `stop()` for a hand-off to need, and none SHALL exist.

Stopping the arm SHALL likewise stop **every** mechanism the composition can yield, not only the one
currently held: a mechanism this process never started can still have work outstanding on its behalf.

#### Scenario: Starting the OS-driven mechanism cancels app-driven work left by an earlier process

- **WHEN** the OS-driven mechanism is resolved on a device where a previous process left in-flight
  app-driven transfers or a submitted background task
- **THEN** those are cancelled before the OS-driven mechanism starts, so only one process writes records

#### Scenario: A hand-off relinquishes with the ordinary stop

- **WHEN** either mechanism is resolved on an OS carrying both, while the other may have left work behind
- **THEN** the other mechanism's ordinary `stop()` is what relinquishes it, and no narrower teardown verb is
  invoked or exists

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
