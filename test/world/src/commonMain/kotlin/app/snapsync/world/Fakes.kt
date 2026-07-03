package app.snapsync.world

import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import app.snapsync.rejoin.JoinedEventMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A settable in-memory [PermissionStatusSource] — the world's stand-in for the iOS PhotoKit permission
 * adapter (there is no shared in-memory permission fake in `commonMain`). Drives the status projection's
 * `active` flag.
 */
class MutablePermissionStatusSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) : PermissionStatusSource {
    private val _permission = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = _permission.asStateFlow()

    fun set(value: PermissionStatus) {
        _permission.value = value
    }
}

/** An in-memory [JoinedEventMarker] for the composed `ExtensionReconciler`. */
class InMemoryJoinedEventMarker(private var value: String? = null) : JoinedEventMarker {
    override fun read(): String? = value
    override fun set(eventId: String) {
        value = eventId
    }
    override fun clear() {
        value = null
    }
}
