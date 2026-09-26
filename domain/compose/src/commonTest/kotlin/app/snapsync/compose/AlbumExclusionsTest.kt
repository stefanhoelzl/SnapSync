package app.snapsync.compose

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.SelectionCalibration
import app.snapsync.model.GalleryAccess
import app.snapsync.model.captureCutoff
import app.snapsync.ports.AlbumManager
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The denylisted-album lookup is **asked only under a full grant** (capability `photo-sharing`), in the
 * one place every consumer reaches it through — the upload cycle on both tiers, the status total, the join
 * preview.
 *
 * What is pinned is the platform call, not only the answer: under `LIMITED` the device lookup already answers the
 * empty set (the album structure is unreadable — measured), so the answer alone could not tell "gated" from
 * "asked and found nothing". The whole point is that the round-trip is not paid.
 */
class AlbumExclusionsTest {

    /** Answers a fixed membership and counts every lookup. */
    private class RecordingAlbums(
        private val members: Set<AssetId> = setOf(AssetId("wa-1"), AssetId("wa-2")),
        private val failure: Throwable? = null,
    ) : AlbumManager {
        var lookups = 0
        var lastCalibration: SelectionCalibration? = null
        override suspend fun ensureCreated(name: String): String? = error("not used")
        override suspend fun exists(albumLocalId: String): Boolean = error("not used")
        override suspend fun add(albumLocalId: String, assetIds: List<AssetId>) = error("not used")
        override suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<AssetId> {
            lookups++
            lastCalibration = calibration
            failure?.let { throw it }
            return members
        }
    }

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val log = Logger.withTag("AlbumExclusionsTest")

    @Test
    fun a_full_grant_asks_and_answers_the_membership() = runTest {
        for (onFailure in AlbumLookupFailure.entries) {
            val albums = RecordingAlbums()
            val ids = denylistedAlbumMembers(albums, cutoff, GalleryAccess.GRANTED, onFailure, log)
            assertEquals(setOf(AssetId("wa-1"), AssetId("wa-2")), ids, "$onFailure")
            assertEquals(1, albums.lookups, "$onFailure")
            assertEquals(SELECTION_CALIBRATION, albums.lastCalibration)
        }
    }

    @Test
    fun any_other_grant_answers_empty_without_a_platform_call() = runTest {
        val notFull = GalleryAccess.entries - GalleryAccess.GRANTED
        for (grant in notFull) for (onFailure in AlbumLookupFailure.entries) {
            val albums = RecordingAlbums()
            val ids = denylistedAlbumMembers(albums, cutoff, grant, onFailure, log)
            assertEquals(emptySet(), ids, "$grant/$onFailure")
            assertEquals(0, albums.lookups, "$grant/$onFailure: the lookup must not be asked")
        }
    }

    @Test
    fun a_failure_under_a_full_grant_is_answered_per_tier_as_before() = runTest {
        val boom = IllegalStateException("assetsd")
        assertEquals(
            emptySet(),
            denylistedAlbumMembers(
                RecordingAlbums(failure = boom), cutoff, GalleryAccess.GRANTED, AlbumLookupFailure.AdmitOnDoubt, log,
            ),
            "the app tier admits on doubt",
        )
        assertFailsWith<IllegalStateException> {
            denylistedAlbumMembers(
                RecordingAlbums(failure = boom), cutoff, GalleryAccess.GRANTED, AlbumLookupFailure.FailCycle, log,
            )
        }
    }
}
