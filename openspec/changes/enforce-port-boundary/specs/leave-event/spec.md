## MODIFIED Requirements

### Requirement: Leave notifies the backend

The `LeaveEvent` use-case SHALL notify the backend that this device is leaving through the
need-named **`LeaveNotifier` port** (`ports/LeaveNotifier`, one method
`suspend fun notifyLeaving(eventId): Result<Unit>`), whose production adapter `HttpLeaveNotifier`
issues `DELETE /events/<eventId>/devices/<deviceId>` (implemented over the device's HTTP client in
the main app, mirroring the `DeviceFilesSource` listing seam). The use-case and the switch path both
receive it as a `suspend (eventId) -> Unit` **built in `compose/` from that port** — they may not
name a port at all (`flow/Provision` is in the zone the flow-no-ports gate covers), and both take the
same shape so the two paths cannot diverge. What the composition SHALL NOT do is hand the core a
lambda it built by closing over the adapter directly: a backend call is a crossing out of the
process, and a `suspend (String) -> Unit` field says nothing about that (spec `module-architecture`,
"Ports are the I/O boundary named for the need").

**No `deviceId` crosses the port.** The identity doing the leaving is a per-process constant the
adapter already needs in order to address the route, never a per-call choice; taking it as a
parameter would widen the port to "make any device leave any event". `HttpLeaveNotifier` SHALL hold
it as a **thunk**, resolved per call, because on iOS resolving it reads the Keychain and an eager
bind would drag that read into composition. A caller that must speak for a *different* device (the
world harness, standing in for another member) SHALL bind a second instance to that id at
construction, so the substitution is visible where it is made.

The notify SHALL be **dispatched fire-and-forget** on the injected app-lifetime
`CoroutineScope` **after** the local teardown — its result SHALL NOT gate, delay, or roll back the
local teardown — and SHALL be invoked by **both** the explicit Leave action and the switch path
(provisioning a different event while joined; see `event-link`). `HttpLeaveNotifier` SHALL return a
`Result` and never throw into the use-case, and the composition's wrapper SHALL **log** a failed
`Result` rather than propagate it: the accepted abandon-leak (a backend membership left in place
until the sweep) SHALL NOT be silent (spec `module-architecture`, "Absence is never silent").

#### Scenario: Explicit leave issues the backend DELETE

- **WHEN** the user confirms leaving the joined event
- **THEN** `LeaveEvent` clears the config, then dispatches the notify with the snapshotted `eventId` on the app-lifetime scope, and the `LeaveNotifier` adapter issues `DELETE /events/<eventId>/devices/<deviceId>` against the device id it resolves for itself

#### Scenario: The notifier failure is contained

- **WHEN** the `DELETE` call errors or times out
- **THEN** `HttpLeaveNotifier` returns a failed `Result`, the composition-built wrapper logs it, and the already-completed local teardown is unaffected

#### Scenario: The composition does not supply the notify as a closure over the adapter

- **WHEN** a composition root wires the backend leave
- **THEN** it supplies the `LeaveNotifier` port on the composition bundle, and the seam gate
  (capability `architecture-guards`) fails if that field is reintroduced as a function type
