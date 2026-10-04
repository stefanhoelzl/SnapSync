package app.snapsync.mock

/**
 * One mock's guard over durable state it cannot hold as a single immutable value: collections of records the port
 * face changes in place, and transitions spanning several of them (a publish reads the union, updates a membership,
 * may close the event and records the pushes it sends). The app writes a mock from its own threads while an inspector
 * reads it from another — a rig `/device` request runs on the server's thread — so every read and write of that state
 * goes through [locked], and a read is of one whole state, never of a transition half-applied.
 *
 * Reentrant. Hold it only across synchronous work: never across a suspension (an operator's hold, the network, a
 * parked import) and never across a call into the app's handlers, or one side waits on the other forever. State
 * already held as one immutable value needs none of this: a [kotlinx.coroutines.flow.MutableStateFlow] cell, or a
 * [SnapshotMap].
 */
internal interface MockLock {
    fun <T> locked(block: () -> T): T
}

/** A new, unheld [MockLock] — the platform's own reentrant lock. */
internal expect fun mockLock(): MockLock
