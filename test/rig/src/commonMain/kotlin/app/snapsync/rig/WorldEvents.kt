package app.snapsync.rig

import app.snapsync.levers.WorldEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * The world's events, in the order they happened (`docs/testing.md`, "How a test waits") — what the world service's
 * `events()` serves. Every event is kept: a host lives for one test, and a caller that subscribes after its first act
 * must still find its act's marker. Told from any thread; the order is the order [tell] was called in.
 */
class WorldEvents {
    private val history = MutableStateFlow<List<WorldEvent>>(emptyList())

    /** The tag every act carries until it is changed, set by its caller just before it acts. */
    @Volatile private var tag: String? = null

    /** Appends [event]. */
    fun tell(event: WorldEvent) {
        history.update { it + event }
    }

    /** Marks every act from now on with [tag], or none. */
    fun tagActs(tag: String?) {
        this.tag = tag
    }

    /** How many acts marked with [tag] were announced. */
    fun actsTagged(tag: String): Int = history.value.count { it is WorldEvent.Acted && it.tag == tag }

    /** Announces an act named [name], carrying the tag its caller set, if any. */
    fun acted(name: String) {
        tell(WorldEvent.Acted(name, tag))
    }

    /** Every event so far, then each one as it is told. */
    fun flow(): Flow<WorldEvent> = flow {
        var sent = 0
        history.collect { events ->
            while (sent < events.size) emit(events[sent++])
        }
    }

    /** Every event so far. */
    fun snapshot(): List<WorldEvent> = history.value
}

/**
 * How a host runs a caller's **act** — a request that moves the device or the world, as opposed to one that reads it.
 * The JVM host runs each one as a task of its loop, announced first ([WorldEvents.acted]), so every event after the
 * announcement is the act's or later, and the loop is not settled until the act and what it started are done.
 */
interface RigActs {
    suspend fun <T> act(name: String, block: suspend () -> T): T

    /**
     * Runs a **read** where an act would run, unannounced: a read may still start app work (the state read refreshes
     * the counts it reports), which the loop must see like any other.
     */
    suspend fun <T> read(block: suspend () -> T): T

    /** A host with no loop: the act runs where its request arrived. */
    data object Direct : RigActs {
        override suspend fun <T> act(name: String, block: suspend () -> T): T = block()

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }
}

/**
 * The world a host serves its callers' acts in: the world service — its events and clock, served by a host whose loop
 * knows when the app is settled, refused by one that cannot — and how each act runs.
 */
class HostWorld(val service: ServedService, val acts: RigActs) {
    companion object {
        /** A device host: the world's events refused, every act run where its request arrived. */
        val ON_A_DEVICE = HostWorld(servedWorld(null, WORLD_REFUSED_ON_A_DEVICE), RigActs.Direct)
    }
}
