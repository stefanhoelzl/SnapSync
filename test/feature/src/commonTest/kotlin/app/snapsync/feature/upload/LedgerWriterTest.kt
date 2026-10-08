package app.snapsync.feature.upload

import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.TerminalOutcome
import app.snapsync.services.ledger.LedgerService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one ledger writer (capability `photo-sharing`): it turns a resource into a row, and a later transition never
 * blanks what an earlier one learned. Over the real [LedgerService] on the `Databases` mock.
 */
class LedgerWriterTest {

    private val ledger = LedgerService(inMemoryDatabases()) { "E" }
    private val writer = LedgerWriter(ledger)

    @Test
    fun `a recorded resource becomes a self-contained row carrying its manifest detail`() = runTest {
        writer.recordRequested(resource("cloud-1-ios.photo.heic", "cloud-1"), destinationPath = "/d")

        assertEquals(
            LedgerEntry(
                "cloud-1-ios.photo.heic",
                AssetId("cloud-1"),
                LedgerState.REQUESTED,
                creationDate = CREATION_DATE,
                role = ResourceRole.PRIMARY,
                contentType = "image/heic",
                originalFilename = "IMG_0001.HEIC",
                destinationPath = "/d",
            ),
            writer.entry("cloud-1-ios.photo.heic"),
        )
    }

    @Test
    fun `a failure returns the row to the work and repeated failures converge`() = runTest {
        writer.recordRequested(resource("k", "A"))

        writer.recordFailed(resource("k", "A"))
        writer.recordFailed(resource("k", "A"))

        val row = writer.entry("k")!!
        assertEquals(AssetId("A"), row.assetId)
        assertEquals(LedgerState.DISCOVERED, row.state)
        assertEquals(listOf("k"), writer.rowsNeedingJob().map { it.key })
    }

    @Test
    fun `a transition from a bare resource never erases the detail or the destination`() = runTest {
        // A retry-spent job comes back from the platform as a key, and the cycle rebuilds its resource from that
        // key alone — with empty metadata. Overwriting with those blanks would drop the capture date, and the photo
        // would leave the event union while its upload was retried; blanking the destination would strand the row
        // exactly when the platform hands its job back.
        writer.recordRequested(resource("cloud-1-ios.photo.heic", "cloud-1"), destinationPath = "/d")

        writer.recordFailed(Resource("cloud-1-ios.photo.heic", AssetId("cloud-1"), "image/heic", emptyMap(), Unit))

        val row = writer.entry("cloud-1-ios.photo.heic")!!
        assertEquals(LedgerState.DISCOVERED, row.state)
        assertEquals(CREATION_DATE, row.creationDate, "the detail written at REQUESTED survives")
        assertEquals("IMG_0001.HEIC", row.originalFilename)
        assertEquals("/d", row.destinationPath)
    }

    @Test
    fun `a settled row survives every record the writer makes`() = runTest {
        writer.recordRequested(resource("k", "A"))
        ledger.markTerminal("k", TerminalOutcome.COMPLETED)
        val settled = writer.entry("k")

        assertFalse(writer.recordRequested(resource("k", "A"), destinationPath = "/late"))
        assertFalse(writer.recordFailed(resource("k", "A")))

        assertEquals(settled, writer.entry("k"))
    }

    @Test
    fun `a discovery records only what the ledger does not hold - as one batch`() = runTest {
        writer.recordRequested(resource("held", "H"))

        val recorded = writer.recordDiscovered(
            listOf(resource("held", "H"), resource("X-primary.heic", "X"), resource("X-live.mov", "X")),
        )

        assertEquals(2, recorded)
        assertEquals(LedgerState.REQUESTED, writer.entry("held")?.state, "a held row is not rewound to DISCOVERED")
        assertEquals(listOf("X-live.mov", "X-primary.heic"), writer.rowsNeedingJob().map { it.key })
        assertTrue(writer.manifestRows().map { it.key }.containsAll(listOf("held", "X-primary.heic", "X-live.mov")))
    }

    @Test
    fun `the backfill and the delete pass through to the ledger`() = runTest {
        ledger.recordUnlessSettled(LedgerEntry("bare.heic", AssetId("B"), LedgerState.COMPLETED))

        writer.backfillManifestDetail(resource("bare.heic", "B"))
        assertEquals(CREATION_DATE, writer.entry("bare.heic")?.creationDate)

        writer.deleteKeys(listOf("bare.heic"))
        assertEquals(null, writer.entry("bare.heic"))
    }
}

private const val CREATION_DATE = "2026-06-27T10:00:00Z"

private fun resource(key: String, assetId: String) = Resource(
    filename = key,
    assetId = AssetId(assetId),
    contentType = "public.heic",
    metadata = mapOf(
        RESOURCE_META_CREATION_DATE to CREATION_DATE,
        RESOURCE_META_MIME to "image/heic",
        RESOURCE_META_ORIGINAL_FILENAME to "IMG_0001.HEIC",
    ),
    data = Unit,
)
