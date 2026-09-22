package app.snapsync.fake

import app.snapsync.ports.ProtectedStorage
import kotlinx.coroutines.flow.StateFlow

/**
 * An honest in-memory [ProtectedStorage]: answers whatever the caller's cell holds. A world models a device locked
 * since boot by setting it `false`; nothing in the core decides on the answer, so the cell only changes what the
 * background entry points record.
 */
internal class InMemoryProtectedStorage(private val readable: StateFlow<Boolean>) : ProtectedStorage {
    override suspend fun readable(): Boolean = readable.value
}
