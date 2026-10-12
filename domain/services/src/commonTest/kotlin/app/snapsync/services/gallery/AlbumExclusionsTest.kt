package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.WriteOutcome
import app.snapsync.model.captureCutoff
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

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
        private val members: Set<PlatformAssetId> = setOf(PlatformAssetId("wa-1"), PlatformAssetId("wa-2")),
        private val failure: Throwable? = null,
    ) : GalleryReader {
        var lookups = 0
        override fun access(): LibraryGrant = LibraryGrant.GRANTED
        override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> = error("not used")
        override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> = error("not used")
        override suspend fun resources(
            ids: Set<PlatformAssetId>,
        ): GalleryRead<List<GalleryAssetResources>> = error("not used")
        override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = error("not used")
        override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION
        override suspend fun createAlbum(title: String): PlatformAlbumId? = error("not used")
        override suspend fun addToAlbum(
            album: PlatformAlbumId,
            assets: Set<PlatformAssetId>,
        ): WriteOutcome = error("not used")
        override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome = error("not used")
        override suspend fun albums(): GalleryRead<List<GalleryAlbum>> {
            lookups++
            failure?.let { throw it }
            return GalleryRead.Read(
                listOf(
                    GalleryAlbum(PlatformAlbumId(DENYLISTED), SELECTION_CALIBRATION.denylistTitles.first()),
                    GalleryAlbum(PlatformAlbumId(OWN), "Holiday"),
                ),
            )
        }
        override suspend fun albumMembers(
            album: PlatformAlbumId,
            createdFrom: Instant?,
        ): GalleryRead<Set<PlatformAssetId>> =
            GalleryRead.Read(if (album.value == DENYLISTED) members else setOf(PlatformAssetId("own-1")))

        private companion object {
            const val DENYLISTED = "album-denylisted"
            const val OWN = "album-own"
        }
    }

    private fun library(failure: Throwable? = null) = RecordingLibrary(failure = failure)

    private suspend fun denylistedAlbumMembers(
        library: RecordingLibrary,
        cutoff: CaptureCutoff,
        grant: GalleryAccess,
        onFailure: AlbumLookupFailure,
        log: Logger,
    ) = app.snapsync.services.gallery.denylistedAlbumMembers(GalleryAlbums(library), cutoff, grant, onFailure, log)

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
                library(failure = boom),
                cutoff,
                GalleryAccess.GRANTED,
                AlbumLookupFailure.AdmitOnDoubt,
                log,
            ),
            "the app tier admits on doubt",
        )
        assertFailsWith<IllegalStateException> {
            denylistedAlbumMembers(
                library(failure = boom),
                cutoff,
                GalleryAccess.GRANTED,
                AlbumLookupFailure.FailCycle,
                log,
            )
        }
    }
}
