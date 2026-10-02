package app.snapsync.model

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile

/**
 * What an event port does with a delivery that arrives before its composition listened (`docs/architecture.md`,
 * "Events arrive through `listen`"). Each port states its own — the answer is the port's, not this cell's: a
 * lifecycle tick a process not yet composed cannot use is nothing, while a link or a push the platform will not
 * repeat is a wiring fault worth a line in the device log.
 */
sealed interface BeforeListen {
    /** Dropped without a word. */
    data object Dropped : BeforeListen

    /** Dropped, and logged on [log] as an error. */
    class Logged(val log: Logger) : BeforeListen

    /** Thrown: whoever delivers has no answer without the handlers (an operator playing the platform). */
    data object Thrown : BeforeListen
}

/**
 * An event port's one handler cell: the handlers the process's composition registered through `listen`, read by
 * every delivery. The last registration wins, as the platform's own delegate slot does.
 *
 * A `@Volatile` single-writer cell (law "State reached from OS callbacks is confined"): `listen` writes it once, on
 * the composition's thread, and the platform reads it from whichever thread it delivers on.
 *
 * [beforeListen] is what [orNull] does while nothing has listened; a delivery that answers the platform differently
 * from its port's other deliveries passes its own. [require] is for a delivery that cannot go on without handlers.
 */
class HandlerSlot<H : Any>(private val port: String, private val beforeListen: BeforeListen) {
    @Volatile
    private var handlers: H? = null

    /** The composition registered [handlers] — what the port's `listen` does. */
    fun set(handlers: H) {
        this.handlers = handlers
    }

    /** The handlers [event] is delivered to, or `null` — after [beforeListen]'s report — while none listened. */
    fun orNull(event: String, beforeListen: BeforeListen = this.beforeListen): H? {
        val current = handlers
        if (current == null) {
            when (beforeListen) {
                BeforeListen.Dropped -> Unit
                is BeforeListen.Logged -> beforeListen.log.e { unheard(event) }
                BeforeListen.Thrown -> error(unheard(event))
            }
        }
        return current
    }

    /** The handlers [event] is delivered to; throws while none listened. */
    fun require(event: String): H = handlers ?: error(unheard(event))

    private fun unheard(event: String) = "$event arrived before the $port port was listened to"
}
