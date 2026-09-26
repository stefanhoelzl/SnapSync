package app.snapsync.compose

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.AssetFacts
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.WriteOutcome
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.model.GalleryAccess
import app.snapsync.model.captureCutoff
import app.snapsync.services.gallery.GalleryAlbums
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

    /**
     * The library's album structure at the port: one denylisted album holding [members] and one user album holding
     * another asset; every album-list read is counted, and [failure] is thrown from it. Only a lookup through the
     * product calibration answers exactly [members].
     */
    private class RecordingLibrary(
        private val members: Set<AssetId> = setOf(AssetId("wa-1"), AssetId("wa-2")),
        private val failure: Throwable? = null,
    ) : GalleryReader {
        var lookups = 0
        override fun access(): GalleryAccess = GalleryAccess.GRANTED
        override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> = error("not used")
        override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> = error("not used")
        override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = error("not used")
        override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = error("not used")
        override suspend fun createAlbum(title: String): AlbumId? = error("not used")
        override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome = error("not used")
        override suspend fun export(resource: Resource, to: String): WriteOutcome = error("not used")
        override suspend fun albums(): GalleryRead<List<AlbumRecord>> {
            lookups++
            failure?.let { throw it }
            return GalleryRead.Read(listOf(AlbumRecord(DENYLISTED, SELECTION_CALIBRATION.denylistTitles.first()), AlbumRecord(OWN, "Holiday")))
        }
        override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> =
            GalleryRead.Read(if (album == DENYLISTED) members else setOf(AssetId("own-1")))

        private companion object {
            const val DENYLISTED = "album-denylisted"
            const val OWN = "album-own"
        }
    }

    private fun library(members: Set<AssetId> = setOf(AssetId("wa-1"), AssetId("wa-2")), failure: Throwable? = null) =
        RecordingLibrary(members, failure)

    private suspend fun denylistedAlbumMembers(
        library: RecordingLibrary,
        cutoff: CaptureCutoff,
        grant: GalleryAccess,
        onFailure: AlbumLookupFailure,
        log: Logger,
    ) = app.snapsync.compose.denylistedAlbumMembers(GalleryAlbums(library), cutoff, grant, onFailure, log)

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val log = Logger.withTag("AlbumExclusionsTest")

    @Test
    fun a_full_grant_asks_and_answers_the_membership() = runTest {
        for (onFailure in AlbumLookupFailure.entries) {
            val albums = library()
            val ids = denylistedAlbumMembers(albums, cutoff, GalleryAccess.GRANTED, onFailure, log)
            assertEquals(setOf(AssetId("wa-1"), AssetId("wa-2")), ids, "$onFailure")
            assertEquals(1, albums.lookups, "$onFailure")
        }
    }

    @Test
    fun any_other_grant_answers_empty_without_a_platform_call() = runTest {
        val notFull = GalleryAccess.entries - GalleryAccess.GRANTED
        for (grant in notFull) for (onFailure in AlbumLookupFailure.entries) {
            val albums = library()
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
                library(failure = boom), cutoff, GalleryAccess.GRANTED, AlbumLookupFailure.AdmitOnDoubt, log,
            ),
            "the app tier admits on doubt",
        )
        assertFailsWith<IllegalStateException> {
            denylistedAlbumMembers(
                library(failure = boom), cutoff, GalleryAccess.GRANTED, AlbumLookupFailure.FailCycle, log,
            )
        }
    }
}
