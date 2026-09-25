package app.snapsync.fake

import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences

/** The honest in-memory [Preferences]: [values] is the caller's own cell (initial state by constructor). */
internal class InMemoryPreferences(private val values: MutableMap<String, String>) : Preferences {

    override fun get(key: String): PrefRead = values[key]?.let { PrefRead.Value(it) } ?: PrefRead.Absent

    override fun set(key: String, value: String): WriteOutcome {
        values[key] = value
        return WriteOutcome.Ok
    }

    override fun remove(key: String): WriteOutcome {
        values.remove(key)
        return WriteOutcome.Ok
    }
}
