package app.snapsync.data.receiving

import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagingFolder
import app.snapsync.levers.WriteKind
import app.snapsync.ports.FileResult
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Receiving torn down (capability `receiving-photos`): every transfer cancelled, every photo not yet received dropped
 * with its staged bytes — sparing a claimed import, keeping every received one — and staged files no row names swept.
 */
class ReceivingCleanupTest {
    private val device = Store()

    @Test
    fun `stopping cancels every transfer and drops what was not received, keeping a claimed and a received photo`() =
        runTest {
            val open = photo(ref("OPEN"))
            val claimed = photo(ref("CLAIMED"))
            val received = photo(ref("RECEIVED"))
            device.planned(page(open, claimed, received, position = 9))
            device.stagedAll(open)
            device.stagedAll(received)
            device.queue.imported(device.queue.claimNext(setOf(open.ref)).ok()!!, testAssetId("LOCAL-R"))
            device.stagedAll(claimed)
            assertEquals(claimed.ref, device.queue.claimNext(setOf(open.ref)).ok()!!.ref)

            assertEquals(1, device.cleanup.stopReceiving().ok(), "OPEN's staged file")

            assertEquals(1, device.download.cancelled)
            assertNull(device.state(open.ref))
            assertEquals("PENDING", device.state(claimed.ref))
            assertEquals("IMPORTED", device.state(received.ref))
            assertNull(device.plans.position(EVENT).ok().position, "the next read is a whole one")
        }

    @Test
    fun `a stop the store refuses frees nothing`() = runTest {
        device.planned(page(photo(ref("A")), position = 2))
        device.databases.fail(DOWNLOADS_DB_NAME, "unionCursor", WriteKind.DELETE)
        assertIs<DownloadRead.Unavailable>(device.cleanup.stopReceiving())
        assertEquals("PENDING", device.state(ref("A")))
    }

    @Test
    fun `a sweep deletes the staged files no row names, and keeps every path a row may still land at`() = runTest {
        val landed = photo(ref("LANDED"))
        val inFlight = photo(ref("FLIGHT"))
        device.planned(page(landed, inFlight))
        device.stagedAll(landed)
        val flightPath = StagedPath.of(StagingFolder.of(EVENT.value), inFlight.ref, inFlight.resources[0].key)
        val legacyPath = StagedPath.of(StagingFolder.Legacy, inFlight.ref, inFlight.resources[0].key)
        listOf(flightPath.value, legacyPath.value, "${flightPath.value}.sealed", "download-staging/stray")
            .forEach(device.files::staged)
        device.sql(
            "INSERT INTO downloadResource VALUES ('not-a-device', 'X', 'k', 'u', 'primary', 'image/heic', 'n', " +
                "'download-staging/orphan', 0, '')",
        )
        device.files.staged("download-staging/orphan")

        assertEquals(2, device.cleanup.sweepUnclaimed().ok(), "the stray file, and the one a row with no ids names")

        assertTrue(device.files.has(flightPath.value), "a transfer finishing during the sweep keeps its bytes")
        assertTrue(device.files.has(legacyPath.value))
        assertEquals(false, device.files.has("download-staging/stray"))
    }

    @Test
    fun `a staging area that cannot be listed, or a store that cannot be read, sweeps nothing`() = runTest {
        device.files.staged("download-staging/stray")
        device.files.list = FileResult.Denied("protected")
        assertIs<DownloadRead.Unavailable>(device.cleanup.sweepUnclaimed())
        assertTrue(device.files.has("download-staging/stray"))

        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.cleanup.sweepUnclaimed())
    }
}
