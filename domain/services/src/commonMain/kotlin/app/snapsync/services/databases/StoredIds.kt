package app.snapsync.services.databases

import app.snapsync.data.store.Stored

/**
 * The id [stored] holds — MIGRATING: the old zones' stores read an id column as they always did, throwing when it
 * spells none, as the column's adapter threw before it answered a [Stored] (`PENDING_CLEANUPS.md`). The data layer
 * reads the same columns as values; each store here moves to that with its phase.
 */
internal fun <T : Any> held(stored: Stored<T>): T = when (stored) {
    is Stored.Valid -> stored.value
    is Stored.Invalid -> throw IllegalArgumentException("a stored id spells none: '${stored.text}'")
}
