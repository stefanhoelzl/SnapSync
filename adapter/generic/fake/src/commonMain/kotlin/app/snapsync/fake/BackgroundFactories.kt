package app.snapsync.fake

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.BackgroundTime
import kotlinx.coroutines.flow.MutableStateFlow

// The honest doubles for background execution: when the system wakes the app, and how long it keeps it awake.
// Split out of Factories.kt only to keep that file under the core tier's TooManyFunctions ceiling; the rules in
// its header (a factory per port, returning the port type, operator state passed in) apply here unchanged.

/** [armed] is the caller's own cell: whether the system holds a pending wake for the scheduler's identifier. */
fun inMemoryBackgroundScheduler(armed: MutableStateFlow<Boolean> = MutableStateFlow(false)): BackgroundScheduler =
    InMemoryBackgroundScheduler(armed)

/**
 * [held] is the caller's own cell: the holds the system has granted and not yet seen ended, each with the expiry
 * the caller may fire as the operating system would.
 */
fun inMemoryBackgroundTime(held: MutableStateFlow<List<HeldBackgroundTime>> = MutableStateFlow(emptyList())): BackgroundTime =
    InMemoryBackgroundTime(held)
