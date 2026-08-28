package app.snapsync.ports

/**
 * Tells the shared event that **this device is leaving it** (capability `leave-event`; the backend
 * route is `event-leave-endpoint`). The event then renames this device's manifest to its departed
 * `.left.json` sibling and, once the last active member has gone, reaps the event and
 * garbage-collects its now-unreferenced bytes.
 *
 * **Named for the need, not the wire.** The adapter is HTTP (`HttpLeaveNotifier`, a `DELETE`), but the
 * need — "announce that this membership has ended" — is what a second platform would have to serve too,
 * and it would serve it the same way. The join-side counterpart is [EventJoin].
 *
 * **This device, always — which is why no `deviceId` crosses the seam.** The identity doing the leaving
 * is a per-process constant the adapter already needs in order to address the route; it is never a
 * choice the caller makes, and the core holds no other device's id for this purpose. Taking it as a
 * parameter would widen the port to "make any device leave any event" — a capability nothing needs, and
 * one an id mix-up could exercise by accident (this codebase has already shipped one incident of two
 * device identities being live at once). A caller that genuinely must speak for a *different* device —
 * `:test:world`, standing in for another member — binds a second instance to that id at construction,
 * so the substitution is visible where it is made rather than buried at a call site. The adapter takes
 * the id as a **thunk**: on iOS it resolves the Keychain, which must not happen while composing a
 * locked background launch.
 *
 * **Best-effort by contract.** Implementations return a failed [Result] and never throw, so the
 * caller's local teardown proceeds regardless: the config is already cleared and the screen has already
 * left the joined layer by the time this runs. A dropped notify leaves the backend membership in place
 * — the accepted abandon-leak — and never blocks or rolls back leaving locally. Invoked by both the
 * explicit Leave and the switch path (provisioning a different event while joined; see `event-link`).
 *
 * An interface of this shape existed once and was deleted as single-implementation ceremony
 * (`changes/archive/2026-07-17-delete-dead-weight`); the composition then handed the core a
 * `suspend (eventId) -> Unit` lambda closing over the adapter instead. That is the reasoning this port
 * exists to overturn: a port is not justified by a second implementation, it is the declared boundary
 * where the core stops and an external system begins (spec `module-architecture`, "Ports are the I/O
 * boundary named for the need"), and a lambda in its place makes the crossing invisible to every gate
 * that looks at types.
 */
interface LeaveNotifier {
    /** Announce that this device has left [eventId]. Never throws; failure arrives as a failed [Result]. */
    suspend fun notifyLeaving(eventId: String): Result<Unit>
}
