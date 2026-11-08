package app.snapsync.data.store

import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("ChangedElsewhere")

/**
 * The app's stores, as the `Databases` port names them when another process — the upload extension — committed to
 * one: the commit is announced on that store's own signal ([StoreChanges]), as this process's own are. A name this
 * build opens no store for is logged, never guessed at.
 */
class ChangedElsewhere(shared: SharedDatabase, downloads: DownloadDatabaseFile) {
    private val byName: Map<String, StoreChanges> =
        mapOf(LEDGER_DB_NAME to shared.changes, DOWNLOADS_DB_NAME to downloads.changes)

    /** Another process committed to the database [name]. */
    fun changed(name: String) {
        byName[name]?.announce()
            ?: log.unlabelled.w("another process changed a database no store opens", "database" of name)
    }
}
