## ADDED Requirements

### Requirement: OS completion handlers are released only after their work completes

Every OS-supplied completion handler the shell receives SHALL be released only after the work that wake
triggered has completed, or after a per-entry-point deadline has expired, whichever comes first. Those
handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`), each `BGTask`'s `setTaskCompleted`, and the silent-push fetch
handler. Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's work is merely *queued* is what freezes the process mid-flight.

The handler SHALL be carried by a type whose only release path takes the work as a `suspend` block, so
that releasing early is not expressible at a call site. That type SHALL live in `:domain` `model/`, not in
`:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot be covered.
The shell SHALL construct it from the raw handler at the Kotlin edge; Swift SHALL continue to forward an
opaque handler and decide nothing.

The deadline SHALL be a per-entry-point constant, and where the OS offers its own expiry signal that
signal SHALL take precedence over the constant. When the deadline expires the handler SHALL be released
and the outstanding work SHALL continue rather than being cancelled, so the deadline can never make the
outcome worse than releasing immediately would have.

#### Scenario: A wake's work completes before the handler is released

- **WHEN** an OS wake triggers work and that work completes within the entry point's deadline
- **THEN** the OS completion handler is released after the work finishes, and the logged duration for
  that entry point reflects the work rather than the dispatch

#### Scenario: A deadline releases the handler without cancelling the work

- **WHEN** the work a wake triggered has not completed when the entry point's deadline expires
- **THEN** the OS completion handler is released, the expiry is logged, and the work continues

#### Scenario: Releasing early is not expressible

- **WHEN** a new OS entry point is added that releases its handler without awaiting its work
- **THEN** the handler type offers no such call, so the shape does not compile

#### Scenario: The OS's own expiry wins over the constant

- **WHEN** a `BGTask` reports expiration before the entry point's constant deadline
- **THEN** the handler is released on the OS signal rather than waiting for the constant

### Requirement: The download background task registers an expiration handler

The download import-tail `BGTask` SHALL register an expiration handler with the OS. Without one the
system has no way to reclaim the task before terminating the app, and holding the task until its work
completes would convert a stalled unit of work into a termination.

#### Scenario: An overrunning backstop is reclaimed, not terminated

- **WHEN** the download backstop's work has not completed when the OS signals expiration
- **THEN** the task is completed, the expiry is logged, and the app is not terminated for overrunning

### Requirement: Background-wake requests carry an explicit request timeout

The shared HTTP client SHALL configure an explicit request timeout rather than relying on the platform
session's defaults. A request left to the platform default is unbounded in practice on a background wake:
the session runs in-process, so a suspended app services nothing, its wall-clock idle timer expires
unobserved, and the task reports only when the app next runs — producing failures reported as minutes or
tens of minutes that are neither network measurements nor honest durations.

The timeout SHALL be short enough to bound the network portion of any receipt-held span. Callers already
treat a failed fetch as "keep last-good state", so a fast failure costs a retry and never correctness.

#### Scenario: A request starved by suspension fails fast on resume

- **WHEN** a background-wake request is interrupted by suspension and the app next runs
- **THEN** the request fails within the configured timeout rather than reporting the whole
  suspension interval

#### Scenario: A union fetch failure keeps last-good state

- **WHEN** the union fetch fails on its timeout during a background wake
- **THEN** last-good download state is retained and no rows are dropped

## MODIFIED Requirements

### Requirement: Forward an incoming silent push to the receiver seam

The `AppDelegate` SHALL forward an incoming remote notification's `userInfo` dictionary **whole**
to `SnapSyncRoot.onSilentPush(userInfo:completion:)`, performing no field extraction, parsing, or
decision in Swift (the transcriber law — the `eventId` extraction is the tested `model/` payload
codec, applied inside the `flow/SilentPush` trigger), and SHALL pass a completion that signals the
OS fetch completion handler. Kotlin SHALL always release the completion — including for a payload
with no usable `eventId`, which fans out to no arm (an unanswered `content-available` push costs
the app its future background wakes).

Kotlin SHALL release that completion only after the fan-out has finished or the silent-push deadline
has expired. The flow's work is not "the synchronous portion": the receivers it drives are the fetch,
enqueue and import that the push exists to cause, and releasing before they run leaves them to race a
suspension.

#### Scenario: An incoming push is routed to Kotlin whole

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards the complete `userInfo` and a completion to Kotlin, with no
  parsing or decision in Swift

#### Scenario: The handler is released after the fan-out, not before it

- **WHEN** a silent push fans out to the download and upload receivers
- **THEN** the OS completion handler is released after both receivers return or the deadline expires,
  and the logged duration for the entry point covers that work

#### Scenario: A malformed payload still releases the handler

- **WHEN** a silent push arrives whose payload carries no usable `eventId`
- **THEN** no receiver runs, the miss is logged, and the OS completion handler is still called
