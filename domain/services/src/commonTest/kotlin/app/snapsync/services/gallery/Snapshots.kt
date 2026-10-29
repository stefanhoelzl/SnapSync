package app.snapsync.services.gallery

import app.snapsync.data.sharing.SelectionState

/** A read selection of one primary photo per id, each taken on the same in-range day. */
internal fun snapshotOf(vararg ids: String): SelectionState.Read = SelectionState.Read(ids.map { platformPhoto(it) })
