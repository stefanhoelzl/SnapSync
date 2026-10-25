package app.snapsync.rig

import app.snapsync.levers.WorldEvent
import app.snapsync.mock.MockDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * The world's events, in the order they happened (`docs/testing.md`, "How a test waits") — what the world service's
 * `events()` serves. Told from any thread; the order is the order [tell] was called in.
 *
 * Where a stream starts, and so what is kept, is the host's:
 * - [Serving.FROM_THE_START] (the JVM host): every event is kept and every stream starts at the first. A host lives for
 *   one test, and a caller that subscribes after its first act must still find its act's marker.
 * - [Serving.FROM_THE_LAST_ACT] (a device host): every stream starts where the latest act began ([acted]), and each act
 *   lets go of what came before the act preceding it. A device's app outlives any one caller, so keeping everything
 *   would grow without end and replay all of it to every wait. An act that is followed by a wait therefore misses
 *   nothing, and a wait never replays an earlier act's events.
 *
 * Where an act begins is marked when the host runs it, not found by looking for its [WorldEvent.Acted]: the record an
 * act leaves may change, and the mark does not depend on it.
 */
class WorldEvents(private val serving: Serving = Serving.FROM_THE_START) {
    /** Where a stream starts. */
    enum class Serving { FROM_THE_START, FROM_THE_LAST_ACT }

    /** What is kept: [events], the first of them the [base]-th ever told; [mark], where the latest act began. */
    private data class Kept(val base: Long = 0, val events: List<WorldEvent> = emptyList(), val mark: Long = 0) {
        val end: Long get() = base + events.size

        fun appended(event: WorldEvent): Kept = copy(events = events + event).capped()

        /** Lets go of everything before [mark], and marks the end as where the next act begins. */
        fun markedAtEnd(): Kept = copy(base = mark, events = events.drop((mark - base).toInt()), mark = end)

        /** At most [KEPT_AT_MOST] events: a device that is not acted on for long still holds a bounded history. */
        private fun capped(): Kept {
            val over = events.size - KEPT_AT_MOST
            return if (over <= 0) {
                this
            } else {
                copy(
                    base = base + over,
                    events = events.drop(over),
                    mark = maxOf(mark, base + over),
                )
            }
        }
    }

    private val history = MutableStateFlow(Kept())

    /** The tag every act carries until it is changed, set by its caller just before it acts. */
    @Volatile private var tag: String? = null

    /** The act each delivery belongs to, by its run, until its work is done. */
    private val actOf = MutableStateFlow<Map<Long, String>>(emptyMap())

    /**
     * Appends [event]. An entry point's event carries the act it belongs to: a delivery made while a tagged act runs
     * is that act's, and so are its answer and its `done`.
     */
    fun tell(event: WorldEvent) {
        val stamped = (event as? WorldEvent.Entry)?.let(::stamped) ?: event
        history.update { it.appended(stamped) }
    }

    private fun stamped(event: WorldEvent.Entry): WorldEvent {
        if (event is WorldEvent.Delivered) {
            val act = tag ?: return event
            actOf.update { it + (event.run to act) }
            return event.copy(tag = act)
        }
        val act = actOf.value[event.run] ?: return event
        return when (event) {
            is WorldEvent.Answered -> event.copy(tag = act)
            is WorldEvent.Done -> event.copy(tag = act).also { actOf.update { it - event.run } }
            is WorldEvent.Delivered -> event
        }
    }

    /** Marks every act from now on with [tag], or none. */
    fun tagActs(tag: String?) {
        this.tag = tag
    }

    /** How many acts marked with [tag] were announced, of those still kept. */
    fun actsTagged(tag: String): Int = history.value.events.count { it is WorldEvent.Acted && it.tag == tag }

    /**
     * An act named [name] begins: where it begins is marked — on a host serving from the last act, everything before
     * the previous act's mark is let go — and the act is announced, carrying the tag its caller set, if any.
     */
    fun acted(name: String) {
        val act = WorldEvent.Acted(name, tag)
        history.update { kept ->
            when (serving) {
                Serving.FROM_THE_START -> kept.appended(act)
                Serving.FROM_THE_LAST_ACT -> kept.markedAtEnd().appended(act)
            }
        }
    }

    /**
     * Every event kept from where a stream starts — the first ([Serving.FROM_THE_START]) or the latest act's
     * ([Serving.FROM_THE_LAST_ACT]) — then each one as it is told. A stream that falls behind more than is kept
     * resumes at the oldest event kept.
     */
    fun flow(): Flow<WorldEvent> = flow {
        var next = -1L
        history.collect { kept ->
            if (next < 0) {
                next = when (serving) {
                    Serving.FROM_THE_START -> kept.base
                    Serving.FROM_THE_LAST_ACT -> kept.mark
                }
            }
            next = maxOf(next, kept.base)
            while (next < kept.end) emit(kept.events[(next++ - kept.base).toInt()])
        }
    }

    /** Every event kept. */
    fun snapshot(): List<WorldEvent> = history.value.events

    companion object {
        /** The most events a host keeps. */
        const val KEPT_AT_MOST: Int = 5_000
    }
}

/**
 * Tells [events] what each of this device's mocked systems records, as the app hands it over — the screen's states
 * where [screen] is set; a device host tells those from its decorated screen ([RigUi]), which sees the real one too.
 * A system the launch left real is never reached, so its mock tells nothing.
 */
fun MockDevice.tellInto(events: WorldEvents, screen: Boolean) {
    backend.watch { events.tell(it) }
    library.watch { events.tell(it) }
    if (screen) this.screen.watch { events.tell(WorldEvent.UiChanged(it)) }
    uploadQueue.watch { events.tell(it) }
    uploadSession.watch { events.tell(it) }
    downloads.watch { events.tell(it) }
    systemUi.watch { events.tell(it) }
    crashReporter.watch { events.tell(it) }
    // What the app's runtime tells of its entry points' work: delivered, answered, done.
    entryEvents.watch { events.tell(it) }
    databases.watch { events.tell(it) }
    keychain.watch { events.tell(it) }
    preferences.watch { events.tell(it) }
    // What the operating system records of the app: its wakes, holds, push registrations and extension registration
    // (the completion handlers are the played OS's own, told where it is made).
    wakes.watch { events.tell(it) }
    backgroundTime.watch { events.tell(it) }
    pushService.watch { events.tell(it) }
    extensionRegistry.watch { events.tell(it) }
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

    /** A host with no loop and no events: the act runs where its request arrived. */
    data object Direct : RigActs {
        override suspend fun <T> act(name: String, block: suspend () -> T): T = block()

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }

    /**
     * A device host: no loop — the act runs where its request arrived — but announced to [events] first, so a wait that
     * follows it starts where it began.
     */
    class Announced(private val events: WorldEvents) : RigActs {
        override suspend fun <T> act(name: String, block: suspend () -> T): T {
            events.acted(name)
            return block()
        }

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }
}

/**
 * The world a host serves its callers' acts in: the world service — its events, and its clock and settling where its
 * loop runs the app — and how each act runs.
 */
class HostWorld(val service: ServedService, val acts: RigActs) {
    companion object {
        /**
         * A device host's world: the events of its mocked systems and its screen ([events]), served from the last act;
         * the clock and settling refused, its operating system running work the channel cannot see.
         */
        fun onADevice(events: WorldEvents): HostWorld =
            HostWorld(
                servedWorld(DeviceWorld(events), refusal = null, DEVICE_WORLD_REFUSALS),
                RigActs.Announced(events),
            )
    }
}
