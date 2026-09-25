package app.snapsync.services.preferences

import app.snapsync.fake.inMemoryPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

/** The retired join marker's key goes on every start, and nothing else does (capability `sync-status`). */
class OrphanedJoinMarkerTest {

    @Test
    fun `the orphaned key is removed and every other key is kept`() {
        val values = mutableMapOf("rejoin.joinedEventId" to "E", "app.snapsync.album.map" to "{}")

        removeOrphanedJoinMarker(inMemoryPreferences(values))
        removeOrphanedJoinMarker(inMemoryPreferences(values)) // idempotent

        assertEquals(mapOf("app.snapsync.album.map" to "{}"), values)
    }
}
