package app.snapsync.fake

import app.snapsync.model.Resource
import app.snapsync.ports.PhotoSelectionChangeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * An honest in-memory [PhotoSelectionChangeSource]: surfaces a constructor-injected snapshot flow as
 * the port's [snapshots]. Operator rigging (emitting a selection change on demand) lives in
 * `:test:world`'s wrapper, which holds the cell — never here (`FakeHonestyTest`).
 */
class InMemoryPhotoSelectionChangeSource(
    cell: MutableSharedFlow<List<Resource>>,
) : PhotoSelectionChangeSource {

    override val snapshots: Flow<List<Resource>> = cell
}
