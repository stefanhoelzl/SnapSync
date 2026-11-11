package app.snapsync.android.storage

import android.content.Context
import android.content.SharedPreferences
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome

/**
 * The Android [Preferences]: one private `SharedPreferences` file. Every write is a synchronous `commit()`, because
 * the port answers whether the write landed — `apply()` would answer before the disk did.
 */
class AndroidPreferences(private val prefs: SharedPreferences) : Preferences {

    /** Production: the app's own preferences file (a secondary constructor, not a default). */
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override fun get(key: String): PrefRead = try {
        prefs.getString(key, null)?.let { PrefRead.Value(it) } ?: PrefRead.Absent
    } catch (e: ClassCastException) {
        // A value of another type under the key: something is there, and it is not a string this port can hand back.
        PrefRead.Unavailable("${e::class.simpleName}: ${e.message}")
    }

    override fun set(key: String, value: String): WriteOutcome = committed("set $key") { putString(key, value) }

    override fun remove(key: String): WriteOutcome = committed("remove $key") { remove(key) }

    private fun committed(what: String, changes: SharedPreferences.Editor.() -> Unit): WriteOutcome =
        if (Committed.write(prefs, changes)) {
            WriteOutcome.Ok
        } else {
            WriteOutcome.Failed("$what: the preferences file was not written")
        }

    private companion object {
        const val FILE = "app.snapsync.preferences"
    }
}
