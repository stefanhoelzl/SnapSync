package app.snapsync.mock

import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome

/**
 * The honest in-memory [Preferences]: [values] is the caller's own cell (initial state by constructor). A key in
 * [foreign] holds a value of a kind this port never writes — a number another writer left — which reads as unavailable,
 * never absent; a store that [refusesWrites] refuses every change, as a platform store that could not persist does.
 */
internal class InMemoryPreferences(
    private val values: MutableMap<String, String>,
    private val foreign: Set<String> = emptySet(),
    private val refusesWrites: Boolean = false,
) : Preferences {

    override fun get(key: String): PrefRead = when {
        key in foreign -> PrefRead.Unavailable("'$key' holds a value of another kind")
        else -> values[key]?.let { PrefRead.Value(it) } ?: PrefRead.Absent
    }

    override fun set(key: String, value: String): WriteOutcome {
        if (refusesWrites) return WriteOutcome.Failed("the store refused the write")
        values[key] = value
        return WriteOutcome.Ok
    }

    override fun remove(key: String): WriteOutcome {
        if (refusesWrites) return WriteOutcome.Failed("the store refused the removal")
        values.remove(key)
        return WriteOutcome.Ok
    }
}
