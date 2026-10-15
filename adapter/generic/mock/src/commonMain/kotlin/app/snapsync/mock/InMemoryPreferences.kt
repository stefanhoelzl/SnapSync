package app.snapsync.mock

import app.snapsync.levers.StoreFault
import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome

/**
 * The honest in-memory [Preferences]: [values] is the caller's own cell (initial state by constructor). [refusals]
 * are asked before every call: a reason they answer refuses it — a read answers unavailable, never absent, as a value of a
 * kind this port never writes does; a write or a removal fails, as a platform store that could not persist does — and
 * nothing changes.
 */
internal class InMemoryPreferences(
    private val values: MutableMap<String, String>,
    private val refusals: StoreRefusals<String>,
) : Preferences {

    override fun get(key: String): PrefRead =
        refusals.of(key, StoreFault.READ)?.let { PrefRead.Unavailable(it) }
            ?: values[key]?.let { PrefRead.Value(it) }
            ?: PrefRead.Absent

    override fun set(key: String, value: String): WriteOutcome {
        refusals.of(key, StoreFault.WRITE)?.let { return WriteOutcome.Failed(it) }
        values[key] = value
        return WriteOutcome.Ok
    }

    override fun remove(key: String): WriteOutcome {
        refusals.of(key, StoreFault.WRITE)?.let { return WriteOutcome.Failed(it) }
        values.remove(key)
        return WriteOutcome.Ok
    }
}
