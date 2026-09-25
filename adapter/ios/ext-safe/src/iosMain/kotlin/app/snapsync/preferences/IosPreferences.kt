package app.snapsync.preferences

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import platform.Foundation.NSUserDefaults

/**
 * The iOS [Preferences]: the **App-Group `UserDefaults` suite**, which the app and the upload extension both read.
 * Its container inherits `CompleteUntilFirstUserAuthentication`, so it is readable while locked after the first
 * unlock with no accessibility class to get wrong.
 *
 * `UserDefaults` reports no failure on a read or a write: a missing key is `nil`, and a write is accepted into the
 * suite's in-memory copy and persisted by the OS. So this adapter answers [PrefRead.Unavailable] only for a suite
 * it could not open, and [WriteOutcome.Failed] likewise — it has nothing else to report, and says so here rather
 * than inventing a failure it cannot observe.
 *
 * [suiteName] is the App Group in production; a test passes a suite of its own.
 */
class IosPreferences(suiteName: String) : Preferences {

    /** Production: the App Group's suite (a secondary constructor, not a default — `docs/architecture.md`). */
    constructor() : this(LEDGER_APP_GROUP)

    private val defaults: NSUserDefaults? = NSUserDefaults(suiteName = suiteName)
    private val unavailable = "UserDefaults suite '$suiteName' could not be opened"

    override fun get(key: String): PrefRead {
        val suite = defaults ?: return PrefRead.Unavailable(unavailable)
        return suite.stringForKey(key)?.let { PrefRead.Value(it) } ?: PrefRead.Absent
    }

    override fun set(key: String, value: String): WriteOutcome {
        val suite = defaults ?: return WriteOutcome.Failed(unavailable)
        suite.setObject(value, forKey = key)
        return WriteOutcome.Ok
    }

    override fun remove(key: String): WriteOutcome {
        val suite = defaults ?: return WriteOutcome.Failed(unavailable)
        suite.removeObjectForKey(key)
        return WriteOutcome.Ok
    }
}
