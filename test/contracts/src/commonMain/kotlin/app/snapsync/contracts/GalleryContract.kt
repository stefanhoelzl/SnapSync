@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The states the app's [Gallery] can be found in, as far as a clause cares. */
enum class GalleryState {
    /*
     * The three grants short of full, in the one order a process can pass through them — granting never ends a process,
     * revoking does — so a binding that reaches them enters them as the clauses run: never asked, then refused, then a
     * partial selection.
     */

    /** The member was never asked, and there is no screen to ask on. */
    NEVER_ASKED,

    /** The member was asked and granted nothing; asking again raises nothing. */
    REFUSED,

    /**
     * The member granted a partial selection holding [GalleryChange.selection]; asking again raises nothing, and a
     * picker widening it, if a screen shows one, waits on a person.
     */
    PARTIAL,

    /** A full grant: the grant the walk memo reads a token under, and one a request cannot change. */
    GRANTED,

    /** The member refused, and the platform tells a refused process nothing of the library — not even its change token (iOS). */
    TOKEN_WITHHELD,
}

/**
 * The app's [Gallery] as a binding hands it to a clause: the [gallery], and [change] — one change to the library it
 * reads, made the way the platform makes one (on a device, an asset created through PhotoKit in the clause's own
 * capture window; nothing is deleted).
 */
class GalleryChange(
    val gallery: Gallery,
    /** The assets a [GalleryState.PARTIAL] selection holds. */
    val selection: Set<PlatformAssetId> = emptySet(),
    val change: suspend () -> Unit,
)

/**
 * What the app's [Gallery] promises beyond its [app.snapsync.ports.GalleryReader] (`docs/architecture.md` — this
 * list IS the specification of the port's obligations).
 *
 * The walk memo serves a stored walk as a **deletion authority** whenever two tokens
 * compare equal, so the safety clause is that a change moves the token; the liveness clause is that an unchanged
 * library keeps comparing equal across distinct reads (by value, not identity), or the memo would never serve.
 * What no clause can reach: a change made **outside** the process — a Camera photo, an iCloud sync (measured on
 * the SE2 instead, `changes/own-work-per-wake` task 7.4).
 *
 * `requestAccess` and `widenSelection` are contracted where asking raises nothing a run must answer — no screen to ask
 * on, or a grant already decided, which iOS does not ask about again: then they answer the grant as it stands, whatever
 * it is. Otherwise each raises a system surface only a person can answer, whose outcome no run can reach
 * (`docs/architecture.md`, "An authorization the process cannot give itself is a precondition of the run").
 */
object GalleryContract : Contract<GalleryState, GalleryChange>("Gallery") {

    override val clauses = clauses {

        clause(
            "NEVER_ASKED_WITH_NO_SCREEN_ASKS_NOTHING",
            GalleryState.NEVER_ASKED,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(LibraryGrant.NOT_DETERMINED)
                    answers(Gallery::widenSelection).with(LibraryGrant.NOT_DETERMINED)
                }
            },
        ) { subject ->
            assertEquals(
                LibraryGrant.NOT_DETERMINED,
                subject.gallery.requestAccess(),
                "nothing to ask on, so still never asked",
            )
            assertEquals(LibraryGrant.NOT_DETERMINED, subject.gallery.widenSelection(), "no selection to widen")
        }

        clause(
            "REFUSED_A_REQUEST_CHANGES_NOTHING",
            GalleryState.REFUSED,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(LibraryGrant.DENIED)
                    answers(Gallery::widenSelection).with(LibraryGrant.DENIED)
                }
            },
        ) { subject ->
            assertEquals(
                LibraryGrant.DENIED,
                subject.gallery.requestAccess(),
                "a refusal stands until the member changes it",
            )
            assertEquals(LibraryGrant.DENIED, subject.gallery.widenSelection())
        }

        clause(
            "PARTIAL_THE_SELECTION_IS_THE_GRANT_AND_IS_OBSERVED",
            GalleryState.PARTIAL,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(LibraryGrant.LIMITED)
                    answers(Gallery::widenSelection).with(LibraryGrant.LIMITED)
                    answers(Gallery::inbound).returns()
                    answers(Gallery::observeChanges).returns()
                    emits(Gallery::selection).returns()
                }
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.LIMITED)
            },
        ) { subject ->
            assertEquals(LibraryGrant.LIMITED, subject.gallery.access(), "a partial grant reads as one")
            assertEquals(LibraryGrant.LIMITED, subject.gallery.requestAccess(), "asking again changes nothing")
            assertEquals(
                LibraryGrant.LIMITED,
                subject.gallery.widenSelection(),
                "the answer is the grant as it stands; a revised selection arrives through the observer",
            )
            // Registered as a composition registers them; the selection itself arrives on the flow, not through them.
            subject.gallery.inbound(
                GalleryHandlers(onImportPlaceholder = inboundOf { }, onImportSettled = inboundOf { }),
            )
            subject.gallery.observeChanges(true)
            try {
                awaitWithin { subject.gallery.selection.value != null }
                val selected = subject.gallery.selection.value!!.assets.map { it.asset.id }.toSet()
                assertTrue(
                    selected.containsAll(subject.selection),
                    "the baseline is the selection: missing ${subject.selection - selected}",
                )
            } finally {
                subject.gallery.observeChanges(false)
            }
        }

        clause(
            "TOKEN_WITHHELD_IS_NO_TOKEN",
            GalleryState.TOKEN_WITHHELD,
            covers = cells { on<LibraryChangeTokenRead>().answers(LibraryChangeTokenRead::changeToken).with(null) },
        ) { subject ->
            assertNull(subject.gallery.changeToken(), "no token is answered, so no walk can be served as unchanged")
        }

        clause(
            "GRANTED_OBSERVES_NO_SELECTION",
            GalleryState.GRANTED,
            covers = cells { on<Gallery>().emits(Gallery::selection).with(null) },
        ) { subject ->
            subject.gallery.observeChanges(true)
            try {
                assertNull(subject.gallery.selection.value, "a full grant has no selection: the observer reads none")
            } finally {
                subject.gallery.observeChanges(false)
            }
        }

        clause(
            "GRANTED_HAS_NO_SELECTION_TO_WIDEN",
            GalleryState.GRANTED,
            covers = cells { on<Gallery>().answers(Gallery::widenSelection).with(LibraryGrant.GRANTED) },
        ) { subject ->
            assertEquals(LibraryGrant.GRANTED, subject.gallery.widenSelection(), "a full grant has nothing to widen")
        }

        clause(
            "A_REQUEST_AFTER_THE_GRANT_CHANGES_NOTHING",
            GalleryState.GRANTED,
            covers = cells {
                on<Gallery>().answers(Gallery::requestAccess).with(LibraryGrant.GRANTED)
                on<GalleryReader>().answers(GalleryReader::access).with(LibraryGrant.GRANTED)
            },
        ) { subject ->
            assertEquals(LibraryGrant.GRANTED, subject.gallery.requestAccess())
            assertEquals(LibraryGrant.GRANTED, subject.gallery.requestAccess())
            assertEquals(
                LibraryGrant.GRANTED,
                subject.gallery.access(),
                "a request after the grant is determined asks nothing and changes nothing, however often it runs",
            )
        }

        clause(
            "A_LIBRARY_CHANGE_MOVES_THE_TOKEN",
            GalleryState.GRANTED,
            covers = cells {
                on<LibraryChangeTokenRead> {
                    answers(LibraryChangeTokenRead::changeToken).returns()
                    handle<LibraryChangeToken>().answers(LibraryChangeToken::sameLibraryAs).with(false)
                }
            },
        ) { subject ->
            val before = assertNotNull(subject.gallery.changeToken(), "a full grant's library has a token")
            subject.change()
            val after = assertNotNull(subject.gallery.changeToken(), "a full grant's library has a token")
            assertFalse(
                after.sameLibraryAs(before),
                "a token read after a change compares equal to one read before it: the memo would serve a walk " +
                    "that no longer holds, and delete the rows of every asset it lacks",
            )
        }

        clause(
            "A_QUIET_LIBRARY_KEEPS_ITS_TOKEN",
            GalleryState.GRANTED,
            covers = cells {
                on<LibraryChangeTokenRead> {
                    answers(LibraryChangeTokenRead::changeToken).returns()
                    handle<LibraryChangeToken>().answers(LibraryChangeToken::sameLibraryAs).with(true)
                }
            },
        ) { subject ->
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
