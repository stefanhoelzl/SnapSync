package app.snapsync.fake

import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Wake
import kotlinx.coroutines.flow.MutableStateFlow

// The honest doubles for background execution: when the system wakes the app, and how long it keeps it awake.
// Split out of Factories.kt only to keep that file under the core tier's TooManyFunctions ceiling; the rules in
// its header (a factory per port, returning the port type, operator state passed in) apply here unchanged.

/**
 * [pending] is the caller's own cell: the wake requests the system holds, at most one per [WakeId]. [supported] are the
 * wakes this platform has; the default is every one, as on Android.
 */
fun inMemoryWake(
    pending: MutableStateFlow<Map<WakeId, WakeTrigger>> = MutableStateFlow(emptyMap()),
    supported: Set<WakeId> = WakeId.entries.toSet(),
): Wake = InMemoryWake(pending, supported)

/**
 * [held] is the caller's own cell: the holds the system has granted and not yet seen ended, each with the expiry
 * the caller may fire as the operating system would.
 */
fun inMemoryBackgroundTime(held: MutableStateFlow<List<HeldBackgroundTime>> = MutableStateFlow(emptyList())): BackgroundTime =
    InMemoryBackgroundTime(held)

/**
 * [record] is the caller's own cell: whether a registration exists. `null` (the default) is a platform without the
 * upload extension, answering `Unsupported`.
 */
fun inMemoryExtensionRegistry(record: MutableStateFlow<Boolean>? = null): ExtensionRegistry =
    InMemoryExtensionRegistry(record)
