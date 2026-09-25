package app.snapsync.contracts

import app.snapsync.ports.AlbumMapStore
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The states the event-album map can be found in, as far as a clause cares. */
enum class AlbumMapStoreState {
    /** Readable, and holding no mapping. */
    EMPTY,

    /** Holding [AlbumMapStoreContract.seedAlbum] for [AlbumMapStoreContract.seedEvent], for the clause being run. */
    HOLDING,

    /** Holding a stored map this build cannot decode. */
    CORRUPT,
}

/**
 * What the `eventId → albumLocalId` map promises (`docs/architecture.md`; the port's KDoc carries why).
 * It is a self-healing cache: `null` covers "never created" and "unreadable" alike, and costs one redundant
 * lookup — so a corrupt map reads as empty and is overwritten, never raised. A write for one event must
 * keep every other event's album.
 */
object AlbumMapStoreContract : Contract<AlbumMapStoreState, AlbumMapStore>("AlbumMapStore") {

    /** The event a [AlbumMapStoreState.HOLDING] map holds, for [clauseId]. Bindings seed exactly this. */
    fun seedEvent(clauseId: String) = "event:$clauseId"

    /** The album [seedEvent] maps to in a [AlbumMapStoreState.HOLDING] map. */
    fun seedAlbum(clauseId: String) = "album:$clauseId"

    override val clauses = clauses {

        clause("EMPTY_GET_IS_NULL", AlbumMapStoreState.EMPTY) { map ->
            assertNull(map.get(seedEvent("EMPTY_GET_IS_NULL")))
        }

        clause("EMPTY_PUT_THEN_GET", AlbumMapStoreState.EMPTY) { map ->
            map.put("event:new", "album:new")
            assertEquals("album:new", map.get("event:new"))
        }

        clause("HOLDING_GETS_THE_MAPPING", AlbumMapStoreState.HOLDING) { map ->
            assertEquals(seedAlbum("HOLDING_GETS_THE_MAPPING"), map.get(seedEvent("HOLDING_GETS_THE_MAPPING")))
        }

        clause("HOLDING_PUT_OVERWRITES_THE_EVENT", AlbumMapStoreState.HOLDING) { map ->
            val event = seedEvent("HOLDING_PUT_OVERWRITES_THE_EVENT")
            map.put(event, "album:replacement")
            assertEquals("album:replacement", map.get(event))
        }

        clause("HOLDING_PUT_KEEPS_OTHER_EVENTS", AlbumMapStoreState.HOLDING) { map ->
            map.put("event:other", "album:other")
            assertEquals("album:other", map.get("event:other"))
            assertEquals(
                seedAlbum("HOLDING_PUT_KEEPS_OTHER_EVENTS"),
                map.get(seedEvent("HOLDING_PUT_KEEPS_OTHER_EVENTS")),
                "a re-join reuses the album only if another event's write left it in place",
            )
        }

        clause("CORRUPT_GET_IS_NULL", AlbumMapStoreState.CORRUPT) { map ->
            assertNull(map.get(seedEvent("CORRUPT_GET_IS_NULL")), "an unreadable map is a cache miss, never a raise")
        }

        clause("CORRUPT_PUT_HEALS", AlbumMapStoreState.CORRUPT) { map ->
            map.put("event:healed", "album:healed")
            assertEquals("album:healed", map.get("event:healed"))
        }
    }
}
