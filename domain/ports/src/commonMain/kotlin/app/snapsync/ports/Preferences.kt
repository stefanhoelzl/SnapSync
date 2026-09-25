package app.snapsync.ports

import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome

/**
 * **Small shared key–value preferences** — one external system (on iOS the App-Group `UserDefaults` suite, which
 * both processes read), and nothing decided here. What a key holds, and what its absence means, are the services'
 * business (`:domain:services`).
 *
 * A read keeps "absent" and "could not look" apart ([PrefRead]); a write answers a [WriteOutcome] rather than
 * throwing.
 */
interface Preferences {

    fun get(key: String): PrefRead

    fun set(key: String, value: String): WriteOutcome

    /** Remove the key. Removing an absent key is [WriteOutcome.Ok]. */
    fun remove(key: String): WriteOutcome
}
