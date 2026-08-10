package app.snapsync.integration

import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.AssetRef
import app.snapsync.world.World
import app.snapsync.world.worldTest

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The duplicate-import defect, end to end over the real core (Bugsink `SNAPSYNC-6`).
 *
 * An import commits in the photo library and is recorded as done in the store at two different moments.
 * If the confirmation never arrives — a process death, or a wait abandoned on its deadline — the row is
 * left `PENDING` while carrying the marker of an asset that genuinely exists. Every idempotency query
 * reads the *state* and ignores the *marker*, so the next pass imports the same photo again; and because
 * a row holds one marker, confirming the second import overwrites the first, dropping the first copy out
 * of the suppression set. That copy is then uploaded back into the event, where every other member
 * imports it as a photo they have never seen.
 *
 * These assert both halves, because only the second is the reported harm: **one** import, and **no
 * object under the orphan's key**. A fix that stopped the duplicate but left the first copy unsuppressed
 * would still send someone else's photo back into the event.
 */
class InterruptedImportIntegrationTest {

    private val foreignDevice = "DEV-F"
    private val foreignAsset = "FQ"
    private val ref = AssetRef(foreignDevice, foreignAsset)

    /** The identifier the world's importer mints on a first import, and on a repeat. */
    private val firstCopy = normalizeAssetId("imported-$foreignDevice-$foreignAsset")
    private val secondCopy = normalizeAssetId("imported-$foreignDevice-$foreignAsset-2")

    /**
     * Drive a foreign asset all the way to staged, with its import abandoned after the library commit —
     * leaving exactly the state a killed process leaves: the asset exists, the row does not know it.
     */
    private suspend fun World.stageWithAbandonedImport(eventId: String) {
        provision(eventId)
        addForeignDevice(foreignDevice, eventId, listOf(World.foreignAsset(foreignAsset)))
        // The commit lands and the report never comes — the import is still parked inside the library, so
        // the transaction is genuinely open while the rest of this test runs against it. Awaiting
        // `importerSuspended` rather than assuming a delay is what keeps that deterministic.
        suspendNextImportAfterCommit()
        downloadController.reconcile(eventId)
        // The import fires from the staged-resource callback, so THAT is what parks.
        scope.launch { stageAllDownloads() } // reaped by worldTest's scope cancel
        importerSuspended.await()
    }

    @Test
    fun an_interrupted_import_is_not_repeated_on_the_next_pass() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")

        // Precondition: the asset really was created, and the row really is unconfirmed. Without both,
        // this test would pass for the wrong reason.
        assertTrue(w.gallery.current().any { it.assetId == firstCopy }, "the asset was created")
        assertTrue(firstCopy in w.downloadStore.suppressedLocalIds(), "its marker was recorded")
        assertTrue(!w.downloadStore.isImported(ref), "but the import was never confirmed")

        // The next pass — a relaunch, a foreground, a download wake. It must adjudicate the row, not
        // import it again.
        w.downloadController.importReady()

        assertEquals(listOf(ref), w.importer.imported, "exactly one asset created for this ref")
        assertTrue(
            w.gallery.current().none { it.assetId == secondCopy },
            "no second copy in the library",
        )
        assertTrue(w.downloadStore.isImported(ref), "the row settles against the asset that exists")
    }

    @Test
    fun the_first_copy_is_never_uploaded_back_into_the_event() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")

        w.downloadController.importReady()
        w.runUploadCycle()

        // The reported harm. `SNAPSYNC-6` uploaded key `BB4F7765-…-primary.heic` — the orphaned first
        // copy — and every other member imported it as a photo they had never seen.
        assertTrue(
            w.platform.created.none { it.assetId == firstCopy },
            "the first copy stays suppressed and creates no upload job",
        )
        assertTrue(
            w.platform.created.none { it.assetId == secondCopy },
            "and neither does any repeat copy",
        )
        assertTrue(firstCopy in w.downloadStore.suppressedLocalIds(), "its marker survives")
    }

    /**
     * Staged bytes are the **only** source for a retry — a resource already recorded as staged is never
     * re-downloaded. So releasing them before a row is settled does not cost a retry; it loses the photo
     * permanently and silently. These pin the ordering from both sides.
     */
    @Test
    fun staged_bytes_survive_an_unconfirmed_import_and_are_released_once_it_settles() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")

        val staged = w.stagedBytes.files.toSet()
        assertTrue(staged.isNotEmpty(), "the transfer staged bytes")
        assertEquals(staged, w.stagedBytes.files, "unconfirmed → the bytes stay; the retry needs them")

        w.downloadController.importReady() // adjudicates → PRESENT → settles the row

        assertTrue(w.downloadStore.isImported(ref))
        assertTrue(
            w.stagedBytes.files.none { it in staged },
            "confirmed → the bytes are redundant and released, so a received photo is not stored twice forever",
        )
    }

    @Test
    fun a_failed_import_keeps_its_bytes_for_the_retry() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addForeignDevice(foreignDevice, "E", listOf(World.foreignAsset(foreignAsset)))
        w.failNextImport()
        w.downloadController.reconcile("E")
        w.stageAllDownloads()

        assertTrue(w.stagedBytes.files.isNotEmpty(), "a failed import must not take its bytes with it")
        assertTrue(!w.downloadStore.isImported(ref))

        // The retry now succeeds off those same bytes.
        w.downloadController.importReady()
        assertTrue(w.downloadStore.isImported(ref), "the photo still arrives")
    }

    /**
     * The compounding defect: `pruneNonTerminal` drops rows by state, so it deletes an unconfirmed row —
     * taking with it the only record that its asset must never be uploaded. Reachable from any leave,
     * event switch, or durable state reset.
     */
    @Test
    fun a_leave_does_not_strand_the_created_asset_unsuppressed() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")

        w.leave()

        assertTrue(
            firstCopy in w.downloadStore.suppressedLocalIds(),
            "the marker survives a leave — it is the only record that this asset must not be uploaded",
        )
    }

    /** The full leave/switch shape: the row survives, adjudicates correctly afterwards, and never echoes. */
    @Test
    fun after_a_leave_the_interrupted_import_still_settles_without_a_duplicate() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")

        w.leave()
        w.provision("E2")
        w.downloadController.importReady()
        w.runUploadCycle()

        assertEquals(listOf(ref), w.importer.imported, "still exactly one asset created for this ref")
        assertTrue(
            w.platform.created.none { it.assetId == firstCopy || it.assetId == secondCopy },
            "and nothing echoed into the new event either",
        )
    }

    /**
     * Download progress has to *settle*. An unconfirmed row counts toward the denominator but not the
     * numerator, so a guard that merely stopped the duplicate — without settling the row — would peg the
     * status line below 100% forever, which reads as a broken app and would ship silently.
     */
    @Test
    fun download_progress_settles_after_an_interrupted_import() = worldTest {
        val w = World(this)
        w.stageWithAbandonedImport("E")
        assertTrue(
            w.downloadStore.importedCount() < w.downloadStore.assetCount(),
            "precondition: unconfirmed, so progress is genuinely short",
        )

        w.downloadController.importReady()

        assertEquals(
            w.downloadStore.assetCount(),
            w.downloadStore.importedCount(),
            "imported reaches total — the counter is not left stuck",
        )
    }

    /**
     * The whole change, end to end: a transaction held OPEN while a full trigger cycle runs against it
     * (capability `photo-download`).
     *
     * This is the state the field defect was adjudicated in — the library answering *absent* about an
     * asset whose change block has committed nothing yet — and the state no test could reach before,
     * because the fixture could only report an abandonment after the fact. Every trigger must complete
     * without waiting on it, nothing may act on that *absent* answer, and when the import finally lands
     * there must be exactly ONE asset and ONE suppression handle.
     *
     * Asserted on how many assets EXIST, not on a marker's value: creating the second asset is the harm,
     * and an assertion on a marker can pass while the duplicate is being made.
     */
    @Test
    fun a_live_transaction_survives_a_full_trigger_cycle_without_a_duplicate() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addForeignDevice(foreignDevice, "E", listOf(World.foreignAsset(foreignAsset)))
        // Park BEFORE the commit: the library will answer *absent* about a transaction that is alive.
        w.suspendNextImport()
        w.downloadController.reconcile("E")
        val importing = launch { w.stageAllDownloads() }
        w.importerSuspended.await()

        val galleryBefore = w.gallery.current().size
        assertTrue(
            w.downloadStore.unconfirmedImports().isNotEmpty(),
            "precondition: the change block wrote its marker and the commit has not landed",
        )

        // A full cycle of triggers against the live transaction. None may block, and none may act on the
        // library's honest "absent" about it.
        w.downloadController.reconcile("E")
        w.downloadController.importReady()

        assertEquals(
            galleryBefore, w.gallery.current().size,
            "no second asset was created while the first transaction was still open",
        )
        assertTrue(
            w.downloadStore.unconfirmedImports().isNotEmpty(),
            "and its marker survived — clearing it is what re-uploads the photo",
        )

        // The commit finally lands and reports.
        w.resumeSuspendedImport(succeeded = true)
        importing.join()

        assertTrue(w.downloadStore.isImported(ref), "the row settles")
        assertEquals(
            1, w.gallery.current().count { it.assetId == firstCopy },
            "exactly one asset for this photo",
        )
        assertEquals(
            setOf(firstCopy), w.downloadStore.suppressedLocalIds(),
            "and exactly one suppression handle, so nothing echoes back into the event",
        )

        // The REPORTED harm, asserted rather than inferred: run the real upload cycle and require that the
        // downloaded photo produces no upload job. A suppression handle that exists but is not consulted
        // would satisfy every assertion above and still send someone else's photo back into their event.
        w.runUploadCycle()
        assertTrue(
            w.platform.created.none { it.assetId == firstCopy },
            "the downloaded photo creates no upload job — the reported harm, asserted rather than inferred",
        )
    }
}
