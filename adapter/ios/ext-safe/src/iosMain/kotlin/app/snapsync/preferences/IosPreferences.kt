package app.snapsync.preferences

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome
import platform.Foundation.NSUserDefaults

/**
 * The iOS [Preferences]: the **App-Group `UserDefaults` suite**, which the app and the upload extension both read.
 * Its container inherits `CompleteUntilFirstUserAuthentication`, so it is readable while locked after the first
 * unlock with no accessibility class to get wrong.
 *
 * `UserDefaults` reports no failure on a read or a write: a missing key is `nil`, and a write is accepted into the
 * suite's in-memory copy and persisted by the OS. So this adapter answers [PrefRead.Unavailable] only for a value of a
 * kind it never writes, and [set]/[remove] always answer [WriteOutcome.Ok] — it has nothing else to report, and says
 * so here rather than inventing a failure it cannot observe. Opening the suite cannot fail either:
 * `initWithSuiteName:` answers nil only for a reserved name (the app's own bundle identifier, `NSGlobalDomain`), and
 * Kotlin/Native's constructor throws then rather than answering null.
 *
 * [suiteName] is the App Group in production; a test passes a suite of its own.
 */
@NeverAnswers(
    Never(member = "set", answer = "WriteOutcome.Failed"),
    Never(member = "remove", answer = "WriteOutcome.Failed"),
)
class IosPreferences(suiteName: String) : Preferences {

    /** Production: the App Group's suite (a secondary constructor, not a default — `docs/architecture.md`). */
    constructor() : this(LEDGER_APP_GROUP)

    private val defaults = NSUserDefaults(suiteName = suiteName)

    override fun get(key: String): PrefRead =
        // `objectForKey`, never `stringForKey`, which answers a number as its text: a value of a kind never written here
        // is unreadable, and never absent.
        when (val held = defaults.objectForKey(key)) {
            null -> PrefRead.Absent
            is String -> PrefRead.Value(held)
            else -> PrefRead.Unavailable("'$key' holds a ${held::class.simpleName}, not a string")
        }

    override fun set(key: String, value: String): WriteOutcome {
        defaults.setObject(value, forKey = key)
        return WriteOutcome.Ok
    }

    override fun remove(key: String): WriteOutcome {
        defaults.removeObjectForKey(key)
        return WriteOutcome.Ok
    }
}
