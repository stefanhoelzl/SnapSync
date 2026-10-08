package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
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

    /** The member was asked and granted nothing; no screen to ask on again. */
    REFUSED,

    /** The member granted a partial selection holding [GalleryChange.selection]; no screen to widen it on. */
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
    val selection: Set<AssetId> = emptySet(),
    val change: suspend () -> Unit,
)

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
 * `requestAccess` and `widenSelection` are contracted where there is no screen to ask on: then they ask nothing and
 * answer the grant as it stands, whatever it is. With a screen, each raises a system surface only a person can
 * answer, whose outcome no run can reach (`docs/architecture.md`, "An authorization the process cannot give itself is
 * a precondition of the run").
 */
object GalleryContract : Contract<GalleryState, GalleryChange>("Gallery") {

    override val clauses = clauses {

        clause(
            "NEVER_ASKED_WITH_NO_SCREEN_ASKS_NOTHING",
            GalleryState.NEVER_ASKED,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(GalleryAccess.NOT_DETERMINED)
                    answers(Gallery::widenSelection).with(GalleryAccess.NOT_DETERMINED)
                }
            },
        ) { subject ->
            assertEquals(
                GalleryAccess.NOT_DETERMINED,
                subject.gallery.requestAccess(),
                "nothing to ask on, so still never asked",
            )
            assertEquals(GalleryAccess.NOT_DETERMINED, subject.gallery.widenSelection(), "no selection to widen")
        }

        clause(
            "REFUSED_A_REQUEST_CHANGES_NOTHING",
            GalleryState.REFUSED,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(GalleryAccess.DENIED)
                    answers(Gallery::widenSelection).with(GalleryAccess.DENIED)
                }
            },
        ) { subject ->
            assertEquals(
                GalleryAccess.DENIED,
                subject.gallery.requestAccess(),
                "a refusal stands until the member changes it",
            )
            assertEquals(GalleryAccess.DENIED, subject.gallery.widenSelection())
        }

        clause(
            "PARTIAL_THE_SELECTION_IS_THE_GRANT_AND_IS_OBSERVED",
            GalleryState.PARTIAL,
            covers = cells {
                on<Gallery> {
                    answers(Gallery::requestAccess).with(GalleryAccess.LIMITED)
                    answers(Gallery::widenSelection).with(GalleryAccess.LIMITED)
                    answers(Gallery::listen).returns()
                    answers(Gallery::observeChanges).returns()
                    calls(GalleryHandlers::onChanged, SelectionSnapshot::class)
                }
                on<PhotoGrantRead>().answers(PhotoGrantRead::access).with(GalleryAccess.LIMITED)
            },
        ) { subject ->
            assertEquals(GalleryAccess.LIMITED, subject.gallery.access(), "a partial grant reads as one")
            assertEquals(GalleryAccess.LIMITED, subject.gallery.requestAccess(), "asking again changes nothing")
            assertEquals(
                GalleryAccess.LIMITED,
                subject.gallery.widenSelection(),
                "with no screen, nothing is presented",
            )
            val snapshots = mutableListOf<SelectionSnapshot>()
            subject.gallery.listen(
                GalleryHandlers(
                    onChanged = { snapshots += it },
                    onImportPlaceholder = { _, _ -> },
                    onImportSettled = { _, _ -> },
                ),
            )
            subject.gallery.observeChanges(true)
            try {
                awaitWithin { snapshots.isNotEmpty() }
                val selected = snapshots.last().assets.map { it.assetId }.toSet()
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
            "GRANTED_HAS_NO_SELECTION_TO_WIDEN",
            GalleryState.GRANTED,
            covers = cells { on<Gallery>().answers(Gallery::widenSelection).with(GalleryAccess.GRANTED) },
        ) { subject ->
            assertEquals(GalleryAccess.GRANTED, subject.gallery.widenSelection(), "a full grant has nothing to widen")
        }

        clause(
            "A_REQUEST_AFTER_THE_GRANT_CHANGES_NOTHING",
            GalleryState.GRANTED,
            covers = cells {
                on<Gallery>().answers(Gallery::requestAccess).with(GalleryAccess.GRANTED)
                on<PhotoGrantRead>().answers(PhotoGrantRead::access).with(GalleryAccess.GRANTED)
            },
        ) { subject ->
            assertEquals(GalleryAccess.GRANTED, subject.gallery.requestAccess())
            assertEquals(GalleryAccess.GRANTED, subject.gallery.requestAccess())
            assertEquals(
                GalleryAccess.GRANTED,
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
