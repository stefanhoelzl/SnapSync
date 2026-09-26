package app.snapsync.contracts

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.Gallery
import app.snapsync.ports.LibraryChangeToken
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states the app's [Gallery] can be found in, as far as a clause cares. */
enum class GalleryState {
    /** A full grant: the grant the walk memo reads a token under, and one a request cannot change. */
    GRANTED,
}

/**
 * The app's [Gallery] as a binding hands it to a clause: the [gallery], and [change] — one change to the library it
 * reads, made the way the platform makes one (on a device, an asset created through PhotoKit in the clause's own
 * capture window; nothing is deleted).
 */
class GalleryChange(val gallery: Gallery, val change: suspend () -> Unit)

/**
 * What the app's [Gallery] promises beyond its [app.snapsync.ports.GalleryReader] (`docs/architecture.md` — this
 * list IS the specification of the port's obligations).
 *
 * The walk memo (capability `photo-sharing`) serves a stored walk as a **deletion authority** whenever two tokens
 * compare equal, so the safety clause is that a change moves the token; the liveness clause is that an unchanged
 * library keeps comparing equal across distinct reads (by value, not identity), or the memo would never serve.
 * What no clause can reach: a change made **outside** the process — a Camera photo, an iCloud sync (measured on
 * the SE2 instead, `changes/own-work-per-wake` task 7.4).
 *
 * `requestAccess` is contracted only once the grant is determined: asked while undetermined, it raises a system
 * prompt only a person can answer. `widenSelection` hands the member to a system surface whose outcome arrives
 * only through the selection observer; no run can reach it (`docs/architecture.md`, "An authorization the process
 * cannot give itself is a precondition of the run").
 */
object GalleryContract : Contract<GalleryState, GalleryChange>("Gallery") {

    override val clauses = clauses {

        clause("A_REQUEST_AFTER_THE_GRANT_CHANGES_NOTHING", GalleryState.GRANTED) { subject ->
            assertEquals(GalleryAccess.GRANTED, subject.gallery.requestAccess())
            assertEquals(GalleryAccess.GRANTED, subject.gallery.requestAccess())
            assertEquals(
                GalleryAccess.GRANTED,
                subject.gallery.access(),
                "a request after the grant is determined asks nothing and changes nothing, however often it runs",
            )
        }

        clause("A_LIBRARY_CHANGE_MOVES_THE_TOKEN", GalleryState.GRANTED) { subject ->
            val before = assertNotNull(subject.gallery.changeToken(), "a full grant's library has a token")
            subject.change()
            val after = assertNotNull(subject.gallery.changeToken(), "a full grant's library has a token")
            assertFalse(
                after.sameLibraryAs(before),
                "a token read after a change compares equal to one read before it: the memo would serve a walk " +
                    "that no longer holds, and delete the rows of every asset it lacks",
            )
        }

        clause("A_QUIET_LIBRARY_KEEPS_ITS_TOKEN", GalleryState.GRANTED) { subject ->
            // Trailing changes keep moving the token for 1–9 s after any write (measured), and this library is
            // shared with every clause before this one — so wait, on the real clock, for one quiet gap.
            withinRealTime(QUIET_WITHIN_MILLIS) {
                while (!quietGap(subject.gallery)) { /* quietGap suspends for its own sampling window */ }
            }
            val token = assertNotNull(subject.gallery.changeToken())
            assertTrue(token.sameLibraryAs(token), "a token compares equal to itself")
        }
    }

    /** Two reads [QUIET_GAP_MILLIS] apart, and whether they compared equal — distinct objects, equal by value. */
    private suspend fun quietGap(gallery: Gallery): Boolean {
        val first: LibraryChangeToken = assertNotNull(gallery.changeToken())
        delay(QUIET_GAP_MILLIS)
        val second: LibraryChangeToken = assertNotNull(gallery.changeToken())
        return second.sameLibraryAs(first)
    }

    private const val QUIET_GAP_MILLIS = 1_000L
    private const val QUIET_WITHIN_MILLIS = 30_000L
}
