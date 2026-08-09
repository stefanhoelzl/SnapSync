package app.snapsync.integration

import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.AssetRef
import app.snapsync.world.World
import app.snapsync.world.worldTest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reported defect, end to end over the real core (Bugsink `SNAPSYNC-9`).
 *
 * `PHPhotoLibrary` answers about **committed** state. While a `performChanges` transaction is open the
 * asset it is creating genuinely is not there — so a presence lookup answers *absent*, honestly, about an
 * asset that is about to exist. The guard then cleared that row's marker, which is the only record that a
 * downloaded photo must not be uploaded; the photo was re-uploaded and every member received it again.
 * Measured on the reporting device: **19** such clears, each **9-44 ms** after the asset was created.
 *
 * These assert the HARM, not only the bookkeeping: how many assets exist, and whether an upload job was
 * ever created. A test that checked only the marker's value could not see a duplicate — the parked
 * `settle-imports-by-transaction` branch had exactly that test, and it passed while the second asset was
 * being created.
 */
class UnreportedImportIntegrationTest {

    private val foreignDevice = "DEV-F"
    private val foreignAsset = "FQ"
    private val ref = AssetRef(foreignDevice, foreignAsset)

    /** The identifier the world's importer mints for the first created asset, and for a repeat. */
    private val firstCopy = normalizeAssetId("imported-$foreignDevice-$foreignAsset")
    private val secondCopy = normalizeAssetId("imported-$foreignDevice-$foreignAsset-2")

    /**
     * Stage a foreign asset and abandon its import **before the commit lands** — marker written, asset not
     * yet in the library, nothing reported. The exact state the field defect was adjudicated in.
     */
    private suspend fun World.stageWithOpenTransaction(eventId: String) {
        provision(eventId)
        addForeignDevice(foreignDevice, eventId, listOf(World.foreignAsset(foreignAsset)))
        abandonNextImportBeforeCommit()
        downloadController.reconcile(eventId)
        stageAllDownloads()
    }

    @Test
    fun an_absent_verdict_about_an_open_transaction_never_clears_its_marker() = worldTest {
        val w = World(this)
        w.stageWithOpenTransaction("E")

        // Preconditions, or this test would pass for the wrong reason: the marker exists, the asset does
        // NOT (the commit has not landed), and we stopped waiting for the outcome.
        assertTrue(firstCopy in w.downloadStore.suppressedLocalIds(), "the marker was written")
        assertFalse(w.gallery.current().any { it.assetId == firstCopy }, "and the commit has not landed")
        assertTrue(w.unreportedImports.holds(ref), "and its outcome is unreported")

        // The next pass — a foreground, a push, a download wake. It adjudicates, and the library says
        // absent, because the transaction creating that asset is still open.
        w.downloadController.reconcile("E")

        assertTrue(
            firstCopy in w.downloadStore.suppressedLocalIds(),
            "the marker of a live transaction survived — clearing it is what re-uploads the photo",
        )
        assertFalse(
            w.gallery.current().any { it.assetId == secondCopy },
            "and no SECOND asset was created — creating it IS the duplicate",
        )
    }

    @Test
    fun the_late_completion_settles_the_row_and_still_leaves_one_asset() = worldTest {
        val w = World(this)
        w.stageWithOpenTransaction("E")
        w.downloadController.reconcile("E") // adjudicated while unreported: nothing done

        // The transaction commits at last. On device this is the `performChanges` completion callback,
        // which fires whether or not anything is still awaiting it.
        w.deliverLateCompletion(ref, firstCopy, World.foreignAsset(foreignAsset).creationDate)
        w.downloadController.reconcile("E")

        assertTrue(w.downloadStore.isImported(ref), "the row is settled")
        assertEquals(
            1, w.gallery.current().count { it.assetId.startsWith(firstCopy) },
            "exactly one asset exists for this photo",
        )
        assertTrue(
            firstCopy in w.downloadStore.suppressedLocalIds(),
            "and it is suppressed, so the upload arm never offers it",
        )
    }

    /**
     * The echo, which is the reported harm rather than its cause: an asset that lost its marker is
     * enumerated by the upload cycle as an ordinary own-device photo and sent back into the event.
     */
    @Test
    fun the_downloaded_photo_is_never_uploaded_back_into_the_event() = worldTest {
        val w = World(this)
        w.stageWithOpenTransaction("E")
        w.downloadController.reconcile("E")
        w.deliverLateCompletion(ref, firstCopy, World.foreignAsset(foreignAsset).creationDate)
        w.downloadController.reconcile("E")

        w.runUploadCycle()

        assertTrue(
            w.platform.created.none { it.assetId == firstCopy },
            "the downloaded photo stays suppressed and creates no upload job",
        )
        assertTrue(
            w.platform.created.none { it.assetId == secondCopy },
            "and neither does any repeat copy",
        )
    }
}
