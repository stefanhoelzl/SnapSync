package app.snapsync.contracts

import app.snapsync.ports.TransferNetwork
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The promises every background transfer session owes alike — the app's upload session and its download session:
// a transfer held to unrestricted networks waits for one, and a relaunch's events are handed over, then drained.
// Each contract drives its own port through a driver and declares its own cells; the clause ids are the same in both.

/** A session as the shared hold clause drives it. */
interface HeldSession {
    /** Start a transfer tagged [tag] to the fixture [route] under [network]; answers whether the session started it. */
    suspend fun start(route: String, tag: String, network: TransferNetwork): Boolean

    /** How the transfer tagged [tag] ended, as the session told it: `null` while it has not, `true` for a success. */
    suspend fun endOf(tag: String): Boolean?

    /** Puts the device back on an unrestricted network; `null` where the binding cannot. */
    val liftRestriction: (suspend () -> Unit)?
}

/** A session the operating system relaunched the app for, as the shared relaunch clause drives it. */
interface RelaunchedSession {
    /** Hand the port the relaunch, with the system's completion handler, as the app's delegate does. */
    fun deliver()

    /** How many completions the port handed its owner for a relaunch. */
    fun handedOver(): Int

    /** How many drain reports the port gave its owner. */
    fun drains(): Int

    /** How many times the session told the end of the transfer tagged [tag]. */
    fun toldTimes(tag: String): Int

    /** Release what the owner was handed, once the events are drained — where the owner has not already. */
    fun release()
}

/**
 * `A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE` in [state]: a transfer held to unrestricted networks sends
 * nothing on a restricted one, and ends once the network allows it. [route] is the clause's accepting route.
 */
internal fun <K : Enum<K>, T> ClauseList<K, T>.heldUntilUnrestricted(
    state: K,
    covers: Covers,
    route: (clauseId: String) -> String,
    session: suspend (T) -> HeldSession,
) {
    val id = "A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE"
    clause(id, state, covers) { subject ->
        val held = session(subject)
        val lift = assertNotNull(held.liftRestriction, "a binding that reaches a restricted network can lift it")
        val tag = "held-$id"
        assertTrue(
            held.start(route(id), tag, TransferNetwork.UNRESTRICTED_ONLY),
            "a held transfer is still a transfer: the session keeps it until the network allows it",
        )
        heldSettle()
        assertNull(held.endOf(tag), "a transfer held to unrestricted networks does not run on a restricted one")
        lift()
        awaitWithin { held.endOf(tag) == true }
    }
}

/**
 * `RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED` in [state]: the relaunch's completion is handed over once, the end
 * of the transfer [tag] that ended while the app was gone is told once, before the drain report, and then released.
 */
internal fun <K : Enum<K>, T> ClauseList<K, T>.relaunchHandedOverThenDrained(
    state: K,
    covers: Covers,
    tag: String,
    session: (T) -> RelaunchedSession,
) {
    clause("RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED", state, covers) { subject ->
        val relaunched = session(subject)
        relaunched.deliver()
        awaitWithin { relaunched.drains() > 0 }
        assertEquals(1, relaunched.handedOver(), "the relaunch's completion is handed over once")
        assertEquals(
            1,
            relaunched.toldTimes(tag),
            "the transfer that ended while the app was gone is told once, before the drain",
        )
        relaunched.release()
    }
}
