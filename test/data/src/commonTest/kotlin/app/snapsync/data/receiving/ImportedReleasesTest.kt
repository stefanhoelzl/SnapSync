package app.snapsync.data.receiving

import app.snapsync.domain.receiving.AssetPresence
import app.snapsync.domain.receiving.ImportedAsset
import app.snapsync.domain.receiving.InterruptedImportVerdict
import app.snapsync.domain.receiving.MintsImportedAsset
import app.snapsync.domain.receiving.UnconfirmedImport
import app.snapsync.domain.receiving.UnionPhoto
import app.snapsync.levers.WriteKind
import app.snapsync.ports.PlatformAssetId
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What follows an import (capability `receiving-photos`): an imported photo's staged bytes released — once, since the
 * release drops the rows that named them — and an import a dead process left unconfirmed adjudicated through a write
 * guarded on the marker it was decided for.
 */
class ImportedReleasesTest {
    private val device = Store()

    /** [photo] planned, staged and its marker placed, as an import whose completion never came leaves it. */
    private suspend fun unconfirmed(photo: UnionPhoto, created: String): UnconfirmedImport {
        device.planned(page(photo))
        device.stagedAll(photo)
        device.markers.placeholder(ImportCorrelation.of(photo.ref), PlatformAssetId(created))
        return UnconfirmedImport(photo.ref, testAssetId(created))
    }

    @Test
    fun `an imported photo's staged bytes are released once`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))
        device.stagedAll(a)
        device.queue.imported(device.queue.claimNext(emptySet()).ok()!!, testAssetId("LOCAL-A"))

        val holding = device.releases.holdingBytes().ok().single()
        assertEquals(a.ref, holding.ref)
        assertEquals(1, device.releases.release(holding).ok())
        assertEquals(emptyList(), device.releases.holdingBytes().ok(), "self-extinguishing")
        assertEquals(0, device.releases.release(holding).ok())
    }

    @Test
    fun `a photo adopted while one of its resources was still on its way releases what was staged`() = runTest {
        val a = photo(ref("A"), captured(), resource("a-primary.heic"), resource("a-live.mov", role = "live"))
        device.planned(page(a))
        device.stagedAll(photo(a.ref, captured(), a.resources[0]))
        device.records.adopt(
            EVENT,
            listOf(app.snapsync.domain.receiving.AdoptedPhoto(a.ref, testAssetId("L"), captured())),
        )

        assertEquals(1, device.releases.release(device.releases.holdingBytes().ok().single()).ok())
    }

    @Test
    fun `a row whose ids are no ids is no imported photo and no unconfirmed one`() = runTest {
        device.sql(
            "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, createdLocalId) VALUES ('', 'X', 'IMPORTED', '$DURING', 'LOCAL-X')",
            "INSERT INTO downloadResource VALUES ('', 'X', 'k', 'u', 'primary', 'image/heic', 'n', " +
                "'download-staging/x', 1, '${EVENT.value}')",
            "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, createdLocalId) VALUES ('${ANNA.value}', 'Y', 'PENDING', '$DURING', '')",
        )
        assertEquals(emptyList(), device.releases.holdingBytes().ok())
        assertEquals(emptyList(), device.releases.unconfirmed().ok())
    }

    @Test
    fun `an unconfirmed import the library holds is settled and its bytes released`() = runTest {
        val row = unconfirmed(photo(ref("A")), "LOCAL-A")
        assertEquals(listOf(row.ref), device.releases.unconfirmed().ok().map { it.ref })

        val adjudicated = device.releases.adjudicate(row, AssetPresence.PRESENT).ok()

        assertSame(InterruptedImportVerdict.Settle, adjudicated.verdict)
        assertEquals(true, adjudicated.applied)
        assertEquals("IMPORTED", device.state(row.ref))
        assertEquals(emptyList(), device.releases.unconfirmed().ok())
    }

    @Test
    fun `a settle against a marker the row no longer carries applies nothing`() = runTest {
        val row = unconfirmed(photo(ref("A")), "LOCAL-A")

        val stale = device.releases.adjudicate(
            UnconfirmedImport(row.ref, testAssetId("LOCAL-OLD")),
            AssetPresence.PRESENT,
        )

        assertEquals(false, stale.ok().applied)
        assertEquals("PENDING", device.state(row.ref))
    }

    @Test
    fun `an absent asset whose bytes are all still staged is cleared, so the row is imported again`() = runTest {
        val row = unconfirmed(photo(ref("A")), "LOCAL-A")

        val cleared = device.releases.adjudicate(row, AssetPresence.ABSENT).ok()

        assertSame(InterruptedImportVerdict.Clear, cleared.verdict)
        assertEquals(true, cleared.applied)
        assertEquals(null, device.marker(row.ref))
        assertEquals(row.ref, device.queue.claimNext(emptySet()).ok()!!.ref)
    }

    @Test
    fun `an asset the library cannot say anything of is left`() = runTest {
        val row = unconfirmed(photo(ref("A")), "LOCAL-A")

        val left = device.releases.adjudicate(row, AssetPresence.UNKNOWN).ok()

        assertSame(InterruptedImportVerdict.Leave, left.verdict)
        assertEquals("LOCAL-A", device.marker(row.ref))
    }

    @OptIn(MintsImportedAsset::class)
    @Test
    fun `a store that cannot be read or written adjudicates and releases nothing`() = runTest {
        val row = unconfirmed(photo(ref("A")), "LOCAL-A")
        device.databases.fail(DOWNLOADS_DB_NAME, "downloadResource", WriteKind.DELETE)
        assertIs<DownloadRead.Unavailable>(device.releases.adjudicate(row, AssetPresence.PRESENT), "the release failed")

        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.releases.adjudicate(row, AssetPresence.PRESENT))
        assertIs<DownloadRead.Unavailable>(device.releases.holdingBytes())
        assertIs<DownloadRead.Unavailable>(device.releases.unconfirmed())
        assertIs<DownloadRead.Unavailable>(device.releases.release(ImportedAsset(row.ref, row.createdLocalId)))
    }
}
