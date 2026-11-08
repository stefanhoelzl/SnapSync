package app.snapsync.data.store

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart

/**
 * **That a store changed** — one signal per store, whoever changed it: a commit this process made ([AnnouncingDriver]),
 * or one another process made, which the `Databases` port tells (`OnChangedElsewhere`). It carries no table and no
 * row: the other process's notice names only the database, so what changed is the reader's to re-read. Several changes
 * before a reader looks are one: a reader re-reads once per look, however many commits it missed.
 *
 * Announcing opens nothing and reads nothing, so a store with no reader — the upload extension's — costs one emit.
 */
class StoreChanges {
    private val changed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The store changed. */
    fun announce() {
        changed.tryEmit(Unit)
    }

    /** One look at once — so a reader reads what the store holds before any change — then one per change. */
    val looks: Flow<Unit> = changed.onStart { emit(Unit) }
}

/**
 * [inner], announcing each commit it makes on [changes]: SQLDelight notifies a driver's listeners once a statement
 * outside a transaction has run, and once a transaction has committed — never for a rollback — which is exactly "this
 * store changed". The same hook the platform adapters announce to the other process from.
 */
internal class AnnouncingDriver(private val inner: SqlDriver, private val changes: StoreChanges) : SqlDriver by inner {
    override fun notifyListeners(vararg queryKeys: String) {
        inner.notifyListeners(*queryKeys)
        changes.announce()
    }
}
