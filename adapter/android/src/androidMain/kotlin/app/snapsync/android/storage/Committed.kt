package app.snapsync.android.storage

import android.content.SharedPreferences

/**
 * A `SharedPreferences` write that is on disk when it returns, and says whether it got there: for the stores whose
 * caller acts on a write that failed. KTX's `edit(commit = true) {}` writes just as synchronously but answers nothing,
 * so a write whose answer is not read uses that instead.
 */
object Committed {
    /** Apply [changes] to [prefs] and `commit()` them on this thread; true when the file was written. */
    fun write(prefs: SharedPreferences, changes: SharedPreferences.Editor.() -> Unit): Boolean =
        prefs.edit().apply(changes).commit()
}
