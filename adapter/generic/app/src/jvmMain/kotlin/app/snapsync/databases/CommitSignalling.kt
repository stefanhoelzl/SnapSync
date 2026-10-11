package app.snapsync.databases

import app.cash.sqldelight.db.SqlDriver

/**
 * A driver that announces each finished commit (`Databases`, "What another process committed"): SQLDelight notifies a
 * driver's listeners once per transaction, after its commit and never for a rollback — the outermost transaction for a
 * nested one, at once for a lone statement — so the notice is the commit. [committed] runs after this process's own
 * listeners heard it.
 */
internal class CommitSignalling(private val inner: SqlDriver, private val committed: () -> Unit) : SqlDriver by inner {
    override fun notifyListeners(vararg queryKeys: String) {
        inner.notifyListeners(*queryKeys)
        committed()
    }
}
