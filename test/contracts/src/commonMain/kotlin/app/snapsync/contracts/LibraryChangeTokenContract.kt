package app.snapsync.contracts

import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import kotlinx.coroutines.delay
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states a [LibraryChangeTokenRead]'s library can be found in, as far as a clause cares. */
enum class LibraryChangeTokenState {
    /** A full grant: the only grant the walk memo ever reads a token under. */
    GRANTED,
}

/**
 * A [LibraryChangeTokenRead] as a binding hands it to a clause: the [port], and [change] — one change to the
 * library the port reads, made the way the platform makes one (on a device, an asset created through PhotoKit in
 * the clause's own capture window; nothing is deleted).
 */
class LibraryChange(val port: LibraryChangeTokenRead, val change: suspend () -> Unit)

/**
 * What every [LibraryChangeTokenRead] promises (capability `port-contracts` — this list IS the specification of
 * the port's obligations).
 *
 * The walk memo (capability `sync-ledger`) serves a stored walk as a **deletion authority** whenever two tokens
 * compare equal, so the safety clause is that a change moves the token; the liveness clause is that an unchanged
 * library keeps comparing equal across distinct reads (by value, not identity), or the memo would never serve.
 *
 * What no clause can reach: a change made **outside** the process — a Camera photo, an iCloud sync. That is the
 * device check of `changes/own-work-per-wake` (task 7.4), and until it is recorded the memo only shadows walks
 * (`APP_WALK_MEMO_USE`).
 */
object LibraryChangeTokenContract : Contract<LibraryChangeTokenState, LibraryChange>("LibraryChangeToken") {

    override val clauses = clauses {

        clause("A_LIBRARY_CHANGE_MOVES_THE_TOKEN", LibraryChangeTokenState.GRANTED) { library ->
            val before = assertNotNull(library.port.current(), "a full grant's library has a token")
            library.change()
            val after = assertNotNull(library.port.current(), "a full grant's library has a token")
            assertFalse(
                after.sameLibraryAs(before),
                "a token read after a change compares equal to one read before it: the memo would serve a walk " +
                    "that no longer holds, and delete the rows of every asset it lacks",
            )
        }

        clause("A_QUIET_LIBRARY_KEEPS_ITS_TOKEN", LibraryChangeTokenState.GRANTED) { library ->
            // Trailing changes keep moving the token for 1–9 s after any write (measured), and this library is
            // shared with every clause before this one — so wait, on the real clock, for one quiet gap.
            withinRealTime(QUIET_WITHIN_MILLIS) {
                while (!quietGap(library.port)) Unit
            }
            val token = assertNotNull(library.port.current())
            assertTrue(token.sameLibraryAs(token), "a token compares equal to itself")
        }
    }

    /** Two reads [QUIET_GAP_MILLIS] apart, and whether they compared equal — distinct objects, equal by value. */
    private suspend fun quietGap(port: LibraryChangeTokenRead): Boolean {
        val first: LibraryChangeToken = assertNotNull(port.current())
        delay(QUIET_GAP_MILLIS)
        val second: LibraryChangeToken = assertNotNull(port.current())
        return second.sameLibraryAs(first)
    }

    private const val QUIET_GAP_MILLIS = 1_000L
    private const val QUIET_WITHIN_MILLIS = 30_000L
}
