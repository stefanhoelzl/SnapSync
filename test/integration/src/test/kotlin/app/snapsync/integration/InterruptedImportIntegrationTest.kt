package app.snapsync.integration

import app.snapsync.rig.DownloadView
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The duplicate-import defect, end to end over the real stack, driven through the control protocol (Bugsink
 * `SNAPSYNC-6`).
 *
 * An import commits in the photo library and is recorded as done in the store at two different moments.
 * If the confirmation never arrives — a process death, or a wait abandoned on its deadline — the row is
 * left `PENDING` while carrying the marker of an asset that genuinely exists. Every idempotency query
 * reads the *state* and ignores the *marker*, so the next pass imports the same photo again; and because
 * a row holds one marker, confirming the second import overwrites the first, dropping the first copy out
 * of the suppression set. That copy is then uploaded back into the event, where every other member
 * imports it as a photo they have never seen.
 *
 * These assert both halves, because only the second is the reported harm: **one** asset in the library, and
 * **no upload job and no object** for the orphan. A fix that stopped the duplicate but left the first copy
 * unsuppressed would still send someone else's photo back into the event.
 *
 * The "next pass" that must adjudicate an inherited row is a **relaunch** (`/device/relaunch`): the startup
 * interrupted-import sweep runs at host assembly, which the relaunched app performs when its host is first
 * touched — the next `/device/state` read.
 */
class InterruptedImportIntegrationTest {

    private val foreignDevice = "DEV-F"
    private val foreignAsset = "FQ"
    @Test
    fun an_interrupted_import_is_not_repeated_on_the_next_pass() = rigTest {
        val before = stageWithAbandonedImport()

        // Precondition: the asset really was created, and the import really is unconfirmed. Without both,
        // this test would pass for the wrong reason.
        assertEquals(before + 1, libraryTotal(), "the asset was created")
        assertUnsettled(downloadProgress(), "but the import was never confirmed")

        // The next pass — a relaunch, then a foreground and a download wake. It must adjudicate the row,
        // not import it again.
        relaunchAndAssemble()
        os("app", "onForeground")
        downloadAll()

        awaitDownloadSettled() // the row settles against the asset that exists
        assertEquals(before + 1, libraryTotal(), "exactly one asset created for this photo — no second copy")
    }
    @Test
    fun the_first_copy_is_never_uploaded_back_into_the_event() = rigTest {
        stageWithAbandonedImport()

        relaunchAndAssemble()
        awaitDownloadSettled()
        cycle()

        // The reported harm. `SNAPSYNC-6` uploaded key `BB4F7765-…-primary.heic` — the orphaned first copy —
        // and every other member imported it as a photo they had never seen.
        assertEquals(0, jobs().created, "the first copy stays suppressed and creates no upload job")
        assertTrue(objects().isEmpty(), "and nothing of it lands on the backend")
    }

    /**
     * Staged bytes are the **only** source for a retry — a resource already recorded as staged is never
     * re-downloaded. So releasing them before a row is settled does not cost a retry; it loses the photo
     * permanently and silently. These pin the ordering from both sides.
     */
    @Test
    fun staged_bytes_survive_an_unconfirmed_import_and_are_released_once_it_settles() = rigTest {
        stageWithAbandonedImport()

        val staged = stagedFiles()
        assertTrue(staged.isNotEmpty(), "the transfer staged bytes")
        // A full trigger cycle against the unconfirmed row leaves them where they are.
        reconcile()
        os("app", "onBackgroundTask", HEARTBEAT)
        assertEquals(staged, stagedFiles(), "unconfirmed → the bytes stay; the retry needs them")

        relaunchAndAssemble() // the startup sweep adjudicates → present → settles the row

        eventually(read = { stagedFiles() }) { files -> files.none { it in staged } }
        assertTrue(downloadProgress().settled(), "confirmed → the bytes are redundant and released")
    }

    @Test
    fun a_failed_import_keeps_its_bytes_for_the_retry() = rigTest {
        createAndJoin()
        foreignDevice(foreignDevice, foreignAsset)
        val before = libraryTotal()
        device("import/fail-next")
        downloadAll()

        // The first import fails. Whether its retry has already run is a race between the tail pass the staging
        // requested and the one a later request joins (each pass's ① retries what is importable), so the state right
        // after the failure is not asserted here. What the bytes are for is: every retry — here the next wake's tail,
        // whose first unit is the import drain — imports off those same bytes, and would fail had the failure taken
        // them (DownloadControllerTest pins the failure keeping them, step by step).
        os("app", "onBackgroundTask", HEARTBEAT)
        eventually(read = { libraryTotal() }) { it == before + 1 } // the photo still arrives
        awaitDownloadSettled()
    }

    /** The full leave/switch shape: the row survives, adjudicates correctly afterwards, and never echoes. */
    @Test
    fun after_a_leave_the_interrupted_import_still_settles_without_a_duplicate() = rigTest {
        val before = stageWithAbandonedImport()
        val staged = stagedFiles()

        leave()
        createAndJoin(name = "Second Event")
        relaunchAndAssemble()
        // The sweep settled the inherited row: its now-redundant bytes are released.
        eventually(read = { stagedFiles() }) { files -> files.none { it in staged } }
        cycle()

        assertEquals(before + 1, libraryTotal(), "still exactly one asset created for this photo")
        assertEquals(0, jobs().created, "and nothing echoed into the new event either")
        assertTrue(objects().isEmpty())
    }

    /**
     * Download progress has to *settle*. An unconfirmed row counts toward the denominator but not the
     * numerator, so a guard that merely stopped the duplicate — without settling the row — would peg the
     * status line below 100% forever, which reads as a broken app and would ship silently.
     */
    @Test
    fun download_progress_settles_after_an_interrupted_import() = rigTest {
        stageWithAbandonedImport()
        assertUnsettled(downloadProgress(), "precondition: unconfirmed, so progress is genuinely short")

        relaunchAndAssemble()

        awaitDownloadSettled() // imported reaches total — not left stuck
    }

    /**
     * The whole change, end to end: a transaction held OPEN while a full trigger cycle runs against it
     * (capability `photo-download`).
     *
     * This is the state the field defect was adjudicated in — the library answering *absent* about an
     * asset whose change block has committed nothing yet. Every trigger must complete without waiting on
     * it, nothing may act on that *absent* answer, and when the import finally lands there must be exactly
     * ONE asset, and it must never echo back into the event.
     *
     * Asserted on how many assets EXIST: creating the second asset is the harm.
     */
    @Test
    fun a_live_transaction_survives_a_full_trigger_cycle_without_a_duplicate() = rigTest {
        createAndJoin()
        foreignDevice(foreignDevice, foreignAsset)
        val galleryBefore = libraryTotal()
        // Park BEFORE the commit: the library will answer *absent* about a transaction that is alive.
        device("import/suspend-next")
        reconcile()
        stage(wait = false)
        device("import/await-parked")
        assertEquals(galleryBefore, libraryTotal(), "precondition: the commit has not landed")

        // A full cycle of triggers against the live transaction. None may block, and none may act on the
        // library's honest "absent" about it.
        reconcile()
        os("app", "onBackgroundTask", HEARTBEAT)

        assertEquals(
            galleryBefore, libraryTotal(),
            "no second asset was created while the first transaction was still open",
        )

        // The commit finally lands and reports.
        device("import/resume", "succeeded" to "true")

        awaitDownloadSettled() // the row settles
        assertEquals(galleryBefore + 1, libraryTotal(), "exactly one asset for this photo")

        // The REPORTED harm, asserted rather than inferred: run the real upload cycle and require that the
        // downloaded photo produces no upload job. A suppression handle that exists but is not consulted
        // would still send someone else's photo back into their event.
        cycle()
        assertEquals(0, jobs().created, "the downloaded photo creates no upload job")
        assertTrue(objects().isEmpty())
    }

    /**
     * THE ORDERING GUARANTEE, asserted directly because nothing enforces it at compile time (capability
     * `photo-download`): adjudication is no longer any trigger's business, so the only thing that settles a
     * row a dead process left behind is the composition's own startup sweep. If a future edit drops that
     * call, every interrupted import stalls forever and no other test notices — the rows simply sit there,
     * correct and unimported.
     */
    @Test
    fun the_composition_startup_sweep_settles_what_a_dead_process_left() = rigTest {
        val before = stageWithAbandonedImport()
        // Exactly what a killed process leaves: an asset in the library, a row that does not know it. No
        // trigger will settle this — that is the point.
        reconcile()
        os("app", "onBackgroundTask", HEARTBEAT)
        assertUnsettled(
            downloadProgress(),
            "a full trigger cycle leaves it alone — triggers do not adjudicate any more",
        )

        // Host assembly is what runs the sweep: the relaunched app assembles its host when first touched.
        relaunchAndAssemble()

        awaitDownloadSettled() // the startup sweep settled it
        assertEquals(before + 1, libraryTotal(), "against the asset that exists — no second copy")
        cycle()
        assertEquals(0, jobs().created, "and the FIRST copy is suppressed")
    }

    /**
     * D3's SECOND ordering requirement, and the one whose failure would be systematic rather than racy
     * (capability `photo-download`).
     *
     * Under a partial grant the presence source answers from the held selection snapshot, which is `null`
     * until the observer's first emission. A sweep that ran before it would answer *unknown* for every
     * inherited row — and with one sweep per process and no re-arm, that row waits for the NEXT LAUNCH.
     * On a relaunch driven by a `URLSession` staging callback, which may never foreground, the same
     * ordering could repeat every launch and the photo would never arrive.
     *
     * So the sweep waits for a snapshot rather than asking a question the source cannot answer yet. The
     * settlement is observed through the staging directory: a settled row releases its bytes.
     */
    @Test
    fun under_a_partial_grant_the_sweep_waits_for_the_selection_snapshot() = rigTest {
        createAndJoin()
        val ownBefore = libraryIds()
        stageWithAbandonedImport(joined = true)
        val created = (libraryIds() - ownBefore).single()
        val staged = stagedFiles()
        assertTrue(staged.isNotEmpty(), "precondition: the unconfirmed import holds its bytes")

        permission("LIMITED")
        relaunchAndAssemble()

        // No emission yet: the snapshot is null, so an eager sweep would read UNKNOWN and settle nothing —
        // permanently, because nothing re-arms it. Give it room to do the wrong thing.
        stagingNeverChangesWithin(staged, what = "the sweep must NOT have settled the row before a snapshot")

        // The observer emits, and the created asset is in the member's hand-picked selection.
        device("selection/change", "assets" to created)

        eventually(read = { stagedFiles() }) { files -> files.none { it in staged } } // once the snapshot exists, the sweep settles
        awaitDownloadSettled()
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Drive a foreign asset all the way to staged, with its import abandoned after the library commit —
     * the state a killed process leaves: the asset exists, the row does not know it. The import stays parked
     * inside the library, so the transaction is genuinely open while the rest of the test runs against it;
     * awaiting the park rather than assuming a delay keeps that deterministic. Answers the library's size
     * before the import.
     */
    private suspend fun Rig.stageWithAbandonedImport(joined: Boolean = false): Long {
        if (!joined) createAndJoin()
        foreignDevice(foreignDevice, foreignAsset)
        val before = libraryTotal()
        device("import/suspend-next", "afterCommit" to "true")
        reconcile()
        // The import fires from the staged-resource callback, so THAT is what parks.
        stage(wait = false)
        device("import/await-parked")
        return before
    }

    /** Process death and a cold launch, then the first touch of the new app's host — which assembles it. */
    private suspend fun Rig.relaunchAndAssemble() {
        device("relaunch")
        state()
    }

    /** The joined screen's download progress, freshly read. */
    private suspend fun Rig.downloadProgress(): DownloadView {
        refresh()
        return state().download
    }

    private fun DownloadView.settled() = total > 0 && downloaded == total

    /** Wait until every planned foreign photo is imported: the download progress reaches its total. */
    private suspend fun Rig.awaitDownloadSettled() {
        eventually(read = { downloadProgress() }) { it.settled() }
    }

    private fun assertUnsettled(progress: DownloadView, what: String) =
        assertTrue(progress.total > 0 && progress.downloaded < progress.total, "$what: $progress")

    /** The files in the download staging directory. */
    private suspend fun Rig.stagedFiles(): Set<String> =
        deviceJson("staging").getValue("files").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }

    /** Every asset id the library holds. */
    private suspend fun Rig.libraryIds(): Set<String> = gallery().policy!!.assets.mapTo(mutableSetOf()) { it.assetId }

    /** The staging directory still holds every one of [staged] for the whole window — a true negative. */
    private suspend fun Rig.stagingNeverChangesWithin(staged: Set<String>, what: String) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 500.milliseconds
        while (deadline.hasNotPassedNow()) {
            assertTrue(stagedFiles().containsAll(staged), what)
            kotlinx.coroutines.delay(50.milliseconds)
        }
    }

    private companion object {
        /** Any wake runs the tail, whose first unit is the import drain; the heartbeat is the one a test can force. */
        const val HEARTBEAT = "app.snapsync.upload.heartbeat"
    }
}
