package app.snapsync.inbound

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What started a piece of work: the entry points it runs for, by name (`OnHeartbeat`). It rides in the coroutine
 * context, so everything the work does — however far it suspends, hops or launches through scheduling — still knows
 * it, and every log line it writes is labelled `[OnHeartbeat]`. Work shared by several entry points (a single-flight
 * sequence a second trigger joined) carries all of them: `[OnHeartbeat+OnSilentPush]`. [of] and [plus] are the only
 * ways in, so a trigger names at least one entry point.
 */
class Trigger private constructor(val names: List<String>) : AbstractCoroutineContextElement(Trigger) {
    /** How a line shows it. */
    val label: String get() = names.joinToString("+")

    /** This trigger and [other]'s, each named once, in order of arrival. */
    operator fun plus(other: Trigger): Trigger = Trigger((names + other.names).distinct())

    override fun toString(): String = label

    companion object Key : CoroutineContext.Key<Trigger> {
        /** The trigger of one entry point. */
        fun of(name: String): Trigger = Trigger(listOf(name))
    }
}
