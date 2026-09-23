package app.snapsync.contracts

import app.snapsync.ports.AlbumManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states an [AlbumManager]'s library can be found in, as far as a clause cares. */
enum class AlbumManagerState {
    /**
     * A full grant, [SEED_COUNT] assets seeded in the clause's window, and no album titled
     * [AlbumManagerContract.title] for the clause.
     */
    GRANTED_SEEDED,
}

/**
 * What every [AlbumManager] promises (capability `port-contracts` — this list IS the specification of the
 * port's obligations).
 *
 * Album titles derive from the clause id, so no clause meets another's album in a shared library. The
 * denylist lookup ([AlbumManager.assetIdsInAlbums]) is the one read here that decides what a member
 * contributes: an asset it misses is uploaded from a WhatsApp album, and one it wrongly includes is never
 * uploaded at all.
 */
object AlbumManagerContract : Contract<AlbumManagerState, SeededLibrary<AlbumManager>>("AlbumManager") {

    /** The album title a clause creates. Unique per clause, so a shared library cannot confuse two. */
    fun title(clauseId: String) = "snapsync-contract-$clauseId"

    private fun window(clauseId: String) = PhotoLibrary.window(name, clauseId)

    /** An album identifier no library holds. */
    private fun absentAlbumId(clauseId: String) = "00000000-0000-4000-8000-${clauseId.length.toString().padStart(12, '0')}/L0/040"

    override val clauses = clauses {

        clause("CREATED_ALBUM_EXISTS", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val id = assertNotNull(seeded.port.ensureCreated(title("CREATED_ALBUM_EXISTS")))
            assertTrue(seeded.port.exists(id))
        }

        clause("UNKNOWN_ALBUM_DOES_NOT_EXIST", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            assertFalse(seeded.port.exists(absentAlbumId("UNKNOWN_ALBUM_DOES_NOT_EXIST")))
        }

        clause("ADDED_ASSETS_ARE_FOUND_BY_TITLE", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "ADDED_ASSETS_ARE_FOUND_BY_TITLE"
            val album = assertNotNull(seeded.port.ensureCreated(title(clauseId)))
            seeded.port.add(album, seeded.rawIds)
            assertEquals(
                seeded.ids,
                seeded.port.assetIdsInAlbums(setOf(title(clauseId)), window(clauseId).start),
                "every asset added to an album is a member of it, in the normalized form",
            )
        }

        clause("TITLES_MATCH_IGNORING_CASE_AND_SURROUNDING_SPACE", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "TITLES_MATCH_IGNORING_CASE_AND_SURROUNDING_SPACE"
            val album = assertNotNull(seeded.port.ensureCreated("  ${title(clauseId)}  "))
            seeded.port.add(album, seeded.rawIds)
            assertEquals(
                seeded.ids,
                seeded.port.assetIdsInAlbums(setOf(title(clauseId).uppercase()), window(clauseId).start),
                "a denylist title matches an album whatever its case and surrounding space",
            )
        }

        clause("ASSETS_CAPTURED_BEFORE_SINCE_ARE_NOT_RETURNED", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "ASSETS_CAPTURED_BEFORE_SINCE_ARE_NOT_RETURNED"
            val album = assertNotNull(seeded.port.ensureCreated(title(clauseId)))
            seeded.port.add(album, seeded.rawIds)
            assertEquals(
                emptySet(),
                seeded.port.assetIdsInAlbums(setOf(title(clauseId)), window(clauseId).end),
                "an asset captured before the membership's floor cannot be uploaded anyway, and the lookup is " +
                    "bounded by it so that its cost follows the event, not the library",
            )
        }

        clause("ADD_TO_A_MISSING_ALBUM_IS_A_NO_OP", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "ADD_TO_A_MISSING_ALBUM_IS_A_NO_OP"
            seeded.port.add(absentAlbumId(clauseId), seeded.rawIds)
            assertFalse(seeded.port.exists(absentAlbumId(clauseId)), "adding to an album never creates it")
        }

        clause("ADD_OF_A_MISSING_ASSET_IS_SKIPPED", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "ADD_OF_A_MISSING_ASSET_IS_SKIPPED"
            val album = assertNotNull(seeded.port.ensureCreated(title(clauseId)))
            seeded.port.add(album, seeded.rawIds + absentAssetId(clauseId).replace('_', '/'))
            assertEquals(
                seeded.ids,
                seeded.port.assetIdsInAlbums(setOf(title(clauseId)), window(clauseId).start),
                "an asset the library does not hold is skipped, and the ones it holds are still added",
            )
        }

        clause("NO_TITLES_FIND_NOTHING", AlbumManagerState.GRANTED_SEEDED) { seeded ->
            val clauseId = "NO_TITLES_FIND_NOTHING"
            assertEquals(emptySet(), seeded.port.assetIdsInAlbums(emptySet(), window(clauseId).start))
        }
    }
}
