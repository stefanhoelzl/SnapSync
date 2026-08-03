package app.snapsync.feature.membership

import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.EventRename
import app.snapsync.ports.RenameOutcome

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **rename** use-case (capability `event-rename`): change the event's name for **every** member,
 * without leaving and without touching any other setting.
 *
 * It is the **fifth writer** of the one-writer membership config — join/provision saves it, leave clears
 * it, [MembershipRefresh] reconciles it against fresh details, [ReconfigureEvent] rewrites the
 * participation fields, and this rewrites the name — and it mirrors their discipline exactly: read the
 * current config, **guard the `eventId` still matches**, and save the **whole** object with only the
 * intended field replaced (`copy(name = …)`). It never enters `JoinEvent`, so the `AlreadyJoined`
 * short-circuit and the enrollment path are untouched, and the ledger / enrollment / device identity are
 * all preserved.
 *
 * Seated in `feature/membership` rather than beside `CreateEvent` in `feature/creation` because the
 * durable state it writes is *this* feature's, and features are mutually blind: a rename seated in
 * creation could not persist the name itself, and would need a flow to re-drive the refresh instead —
 * an extra round trip to avoid a reference the enumerated-writers convention already tolerates.
 *
 * The subject of the rename is the *event*, which is shared; the state it lands in is the *membership*,
 * which is this device's. Both are true, and the second is what decides where the code lives.
 *
 * ⚠️ **No outcome of this use-case is destructive.** In particular a `404` — which arrives as
 * [RenameOutcome.Transient], see that type — never clears the config, notifies a leave, or cancels
 * downloads. A `404` is a *single* witness that the event is gone; the self-leave (capability
 * `leave-event`) requires two, one of them offline, and reaching that verdict stays [MembershipRefresh]'s
 * job alone. There is exactly one door to the teardown, and this is not it.
 */
class RenameEvent(
    private val configSource: ConfigSource,
    private val store: ConfigStore,
    private val client: EventRename,
    private val status: MutableRenameStatusSource,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("RenameEvent"),
) : EventRenamer, ResetRename {

    /**
     * Rename [eventId] to [name], fire-and-forget: the outcome arrives via [MutableRenameStatusSource].
     *
     * The name is trimmed here (the same split `EventCreator`/`CreateEvent` use — the client is a dumb
     * sender), but what is **persisted** is the name the backend echoed, never this trimmed input. The
     * backend trims too, and echoing is the only way the two cannot disagree about whitespace.
     */
    override fun rename(eventId: String, name: String) {
        scope.launch {
            status.set(RenameStatus.InFlight)
            when (val outcome = client.rename(eventId, name.trim())) {
                is RenameOutcome.Renamed -> {
                    persist(eventId, outcome.name)
                    status.set(RenameStatus.Succeeded)
                }
                RenameOutcome.InvalidName -> {
                    log.i { "rename rejected: invalid name" }
                    status.set(RenameStatus.Failed(RenameFailureReason.INVALID_NAME))
                }
                RenameOutcome.Transient -> {
                    log.i { "rename failed: transient/server error" }
                    status.set(RenameStatus.Failed(RenameFailureReason.SERVER))
                }
            }
        }
    }

    /** Clear the [RenameStatus.Succeeded] / [RenameStatus.Failed] latch once the screen has read it. */
    override fun reset() {
        status.set(RenameStatus.Idle)
    }

    /**
     * Fold the echoed [name] into the persisted membership, whole-object.
     *
     * The `eventId` guard is the same one [MembershipRefresh] and [ReconfigureEvent] apply: a result that
     * lands after a switch or a leave describes someone else's membership, and writing it would resurrect
     * a departed one. An unchanged name saves nothing — the backend may echo exactly what we already hold
     * (a rename to the value the trim produces), and a needless write would wake every config observer.
     */
    private suspend fun persist(eventId: String, name: String) {
        val current = configSource.config.value
        if (current == null) {
            log.i { "rename landed with no configured event — nothing to persist" }
            return
        }
        if (current.eventId != eventId) {
            log.i { "rename landed for $eventId but the membership is ${current.eventId} — not persisted" }
            return
        }
        if (current.name == name) return
        store.save(current.copy(name = name))
    }
}
