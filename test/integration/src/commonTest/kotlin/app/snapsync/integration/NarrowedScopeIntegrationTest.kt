package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A narrowing takes effect on the bytes, not only on the manifest** (capability
 * `photo-selection-policy`), over the real stack: the composed core, the real `UploadCycle`, the real
 * `ReconfigureEvent` behind `UserCommands.reconfigure`, with only PhotoKit faked.
 *
 * Every other selection test reasons about a policy that was already in force when a resource was
 * discovered. This one changes the policy *after* the rows exist, which is the hole the defect shipped
 * through: a ledger row records that the policy admitted its asset **when the row was written**, and
 * `enqueue` used to treat that read as the admitted set. A member who joined wide, let the walk run, and
 * then raised their cutoff kept uploading the photos they had just excluded — at 16 rows a cycle, for as
 * long as the backlog took to drain, against a surface that had shown them a smaller count.
 *
 * The fixture forges the backlog the way a device does: the OS refuses jobs for one cycle
 * ([FakeBackgroundTransfer.jobLimit] `= 0`), so the walk records its rows and enqueues nothing. Raising
 * the cutoff then leaves those rows needing a job under a policy that no longer admits them.
 */
class NarrowedScopeIntegrationTest {

    private val wide = captureCutoff("2026-01-01T00:00:00Z")
    private val narrow = captureCutoff("2026-06-10T00:00:00Z")
    private val ceiling = captureCeiling(World.DEFAULT_FAR_CEILING)

    private val early = "2026-02-01T10:00:00Z"
    private val late = "2026-06-15T10:00:00Z"

    /** Raise the cutoff on the joined membership through the composed command, as the surface does. */
    private suspend fun World.narrowTo(eventId: String) =
        userCommands.reconfigure(eventId, Direction.Both, narrow, ceiling, false)

    @Test
    fun raising_the_cutoff_stops_uploading_the_rows_the_wider_one_recorded() = worldTest {
        val w = World(this)
        w.provision("E", minPhotoDate = wide)
        w.addOwnAsset("EARLY", creationDate = early)
        w.addOwnAsset("LATE", creationDate = late)

        // A backlog the wide policy recorded and no cycle has drained: the OS accepted no job.
        w.platform.jobLimit = 0
        w.runUploadCycle()
        assertEquals(
            listOf("EARLY-primary.jpg", "LATE-primary.jpg"),
            w.ledgerBackend.rowsNeedingJob().map { it.key },
            "both rows were recorded under the wide cutoff and still need a job",
        )
        assertTrue(w.platform.created.isEmpty(), "nothing was enqueued yet")

        // The member narrows. EARLY is now outside the membership's scope.
        w.platform.jobLimit = Int.MAX_VALUE
        w.narrowTo("E")

        w.runUploadCycle()
        assertEquals(
            listOf("LATE-primary.jpg"),
            w.platform.created.map { it.filename },
            "the excluded row costs no job — the narrowing reaches the bytes, not only the manifest",
        )

        // …and it keeps not uploading, cycle after cycle: the row stays, and stays excluded.
        w.platform.completeJob("LATE-primary.jpg")
        w.runUploadCycle()
        w.runUploadCycle()
        assertEquals(
            setOf("LATE-primary.jpg"),
            w.store.objectsOf(w.ownDeviceId),
            "no excluded asset's bytes ever reach the backend store",
        )
        assertEquals(
            listOf("EARLY-primary.jpg"),
            w.ledgerBackend.rowsNeedingJob().map { it.key },
            "the excluded row is retained, not pruned — widening again re-admits it (capability `sync-ledger`)",
        )
    }

    @Test
    fun excluded_rows_do_not_starve_admitted_work() = worldTest {
        // The one way to make this worse than the bug: admit AFTER a bounded read. `rowsNeedingJob`
        // returns a stable key order, so an excluded backlog at the front would fill the batch on every
        // cycle and the admitted row further down would never be reached — a permanent, silent stall.
        val w = World(this)
        w.provision("E", minPhotoDate = wide)
        // More excluded rows than one cycle enqueues (`enqueueBatchSize` is 16), all sorting AHEAD of the
        // admitted one by key.
        repeat(20) { i -> w.addOwnAsset("A${(i + 1).toString().padStart(2, '0')}", creationDate = early) }
        w.addOwnAsset("Z01", creationDate = late)

        w.platform.jobLimit = 0
        w.runUploadCycle()
        assertEquals(21, w.ledgerBackend.rowsNeedingJob().size, "every asset earned a row under the wide cutoff")

        w.platform.jobLimit = Int.MAX_VALUE
        w.narrowTo("E")
        w.runUploadCycle()

        assertEquals(
            listOf("Z01-primary.jpg"),
            w.platform.created.map { it.filename },
            "the admitted row is enqueued even though 20 excluded rows sort ahead of it",
        )
    }

    @Test
    fun after_a_narrowing_the_manifest_and_the_uploaded_set_are_the_same_set() = worldTest {
        // Not "each is individually correct" — the SAME set. One policy gates both, so a narrowing that
        // reached one consumer and not the other is the defect, whatever each looks like alone.
        val w = World(this)
        w.provision("E", minPhotoDate = wide)
        w.addOwnAsset("EARLY", creationDate = early)
        w.addOwnAsset("LATE", creationDate = late)

        w.platform.jobLimit = 0
        w.runUploadCycle()
        w.platform.jobLimit = Int.MAX_VALUE
        w.narrowTo("E")

        w.runUploadCycle()
        w.platform.created.forEach { w.platform.completeJob(it.filename) }
        w.runUploadCycle()

        val listed = w.store.manifestOf("E", w.ownDeviceId)?.assets?.map { it.assetId }?.toSet().orEmpty()
        val uploaded = w.store.objectsOf(w.ownDeviceId).mapTo(mutableSetOf()) { assetIdFromUploadKey(it) }
        assertEquals(setOf("LATE"), listed, "the manifest declares only what the narrowed policy admits")
        assertEquals(listed, uploaded, "what is declared and what is uploaded are one set")
    }
}
