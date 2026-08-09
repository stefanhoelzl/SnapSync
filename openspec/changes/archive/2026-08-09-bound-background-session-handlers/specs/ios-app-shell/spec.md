## MODIFIED Requirements

### Requirement: OS completion handlers are released only after their work completes

Every OS-supplied completion handler the shell receives SHALL be released only after the work that wake
triggered has completed, or after a per-entry-point deadline has expired, whichever comes first. Those
handlers are the background-`URLSession` handler for **each** session
(`handleEventsForBackgroundURLSession`), each `BGTask`'s `setTaskCompleted`, and the silent-push fetch
handler. Releasing one declares to the system that the app is done and may be suspended; releasing it
while the wake's work is merely *queued* is what freezes the process mid-flight.

The handler SHALL be carried by a type whose only release path takes the work as a `suspend` block, so
that releasing early is not expressible at a call site. That type SHALL live in `:domain` `ports/`, not in
`:app:*` — the shell is wiring-only and untested by rule, so behaviour placed there cannot be covered.
The shell SHALL construct it from the raw handler at the Kotlin edge; Swift SHALL continue to forward an
opaque handler and decide nothing.

The deadline SHALL be a per-entry-point constant, and where the OS offers its own expiry signal that
signal SHALL take precedence over the constant. When the deadline expires the handler SHALL be released
and the outstanding work SHALL continue rather than being cancelled, so the deadline can never make the
outcome worse than releasing immediately would have.

**The deadline SHALL begin at the handover**, not when whatever the release waits for reports. Where a
handler's release depends on a later signal — a background-`URLSession` wake is handed a handler at
`handleEventsForBackgroundURLSession` and waits for the session to report its events drained — the
interval between the handover and that signal SHALL be inside the bound, because it is exactly the
interval in which the signal may never arrive.

"At the handover" is exact to within one dispatch onto the composition's lane, measured at 5–12 ms against
a 20 s bound. The requirement is not that the clock start on the calling thread — it must not, since that
thread belongs to the OS — but that no *signal-shaped* wait sit outside it.

**Every outstanding handler SHALL be released**, and none SHALL be replaced. Where a second handover for
the same session can arrive before the first release, each handler SHALL be held independently, with its
own deadline running from its own handover, and the drain signal SHALL release every handler outstanding
at that moment. A single stored slot cannot express this: the earlier handler is overwritten and never
called, which costs the app its future background wakes.

**A background-`URLSession` handler SHALL be released on the main thread**, as its owning API requires
(`URLSessionDelegate.urlSessionDidFinishEvents(forBackgroundURLSession:)`: *"Because the provided
completion handler is part of UIKit, you must call it on your main thread."*). This applies to the
release only; where the hold waits is unconstrained. No such requirement is stated for the silent-push
fetch handler or for `BGTask` completion, and none SHALL be extended to them by this rule.

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

#### Scenario: A drain signal that never arrives is still bounded

- **WHEN** the OS hands over a background-`URLSession` handler and the session never reports its events
  drained
- **THEN** the handler is released on the deadline measured from the handover, and the expiry is logged

#### Scenario: A second handover does not orphan the first

- **WHEN** a second `handleEventsForBackgroundURLSession` for the same session arrives before the first
  handler has been released
- **THEN** both handlers are held, and the drain signal releases both — neither is discarded nor released
  early to make room for the other

#### Scenario: The URLSession handler is released on the main thread

- **WHEN** a background-`URLSession` handler is released, whether after its work or on its deadline
- **THEN** the release runs on the main thread, even though the drain signal is delivered on a
  session-owned queue and the work ran off the main thread
