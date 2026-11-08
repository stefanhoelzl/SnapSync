package app.snapsync.data.crypto

import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.ports.DevControls

/**
 * **Whether a new event is encrypted, and its key**: a fresh key for every event, unless the build's development
 * control asks for a plain one ([DevControls.createsPlainEvents] — a rig build's switch; a shipped build answers plain
 * until encrypted creation is re-enabled, `PENDING_CLEANUPS.md`). Asked once per create.
 */
class NewEventKeys(private val ids: EventKeyIds, private val controls: DevControls) {
    fun forNewEvent(): NewEventKey = when (controls.createsPlainEvents()) {
        true -> NewEventKey.Plain
        false -> NewEventKey.Encrypted(ids.mint())
    }
}

/** A new event's encryption. */
sealed interface NewEventKey {
    data object Plain : NewEventKey

    /** Encrypted under [key], made here, on the creating device. */
    class Encrypted(val key: IdentifiedKey) : NewEventKey
}
