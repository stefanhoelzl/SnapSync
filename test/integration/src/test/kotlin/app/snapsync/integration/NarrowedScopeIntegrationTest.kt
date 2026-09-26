package app.snapsync.integration

import app.snapsync.model.AssetId
import app.snapsync.model.assetIdFromUploadKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A narrowing takes effect on the bytes, not only on the manifest** (capability `photo-sharing`), over
 * the real stack: the composed core, the real `UploadCycle`, the real `ReconfigureEvent` behind `/user/reconfigure`,
 * with only PhotoKit faked.
 *
 * Every other selection test reasons about a policy that was already in force when a resource was discovered. This
 * one changes the policy *after* the rows exist, which is the hole the defect shipped through: a ledger row records
 * that the policy admitted its asset **when the row was written**, and `enqueue` used to treat that read as the
 * admitted set. A member who joined wide, let the walk run, and then raised their cutoff kept uploading the photos
 * they had just excluded — at 16 rows a cycle, for as long as the backlog took to drain, against a surface that had
 * shown them a smaller count.
 *
 * The fixture forges the backlog the way a device does: the OS refuses jobs for one cycle (`jobs/limit?n=0`), so
 * the walk records its rows — declared in the manifest — and enqueues nothing. Raising the cutoff then leaves those
 * rows needing a job under a policy that no longer admits them.
 *
 * The event window (the default, 2026-05-15..06-14) holds both capture dates; joining at the event start is wide,
 * and the narrowed cutoff falls between them.
 */
class NarrowedScopeIntegrationTest {

    private val early = "2026-05-20T10:00:00Z"
    private val late = "2026-06-10T10:00:00Z"
    private val narrow = "2026-06-01T00:00:00Z"

    /** Raise the cutoff on the joined membership through the settings surface, as a member does. */
    private suspend fun Rig.narrow() {
        user("reconfigure", "cutoff" to narrow)
        awaitState { it.ready.minPhotoDate?.let { d -> d >= narrow } == true }
    }

    @Test
    fun raising_the_cutoff_stops_uploading_the_rows_the_wider_one_recorded() = rigTest {
        val event = createAndJoin()
        addPhoto("EARLY", date = early)
        addPhoto("LATE", date = late)

        // A backlog the wide policy recorded and no cycle has drained: the OS accepted no job.
        device("jobs/limit", "n" to "0")
        cycle()
        assertEquals(setOf("EARLY", "LATE"), manifest(event)?.keys, "both were recorded under the wide cutoff")
        assertEquals(0, jobs().created, "nothing was enqueued yet")

        // The member narrows. EARLY is now outside the membership's scope.
        device("jobs/limit", "n" to UNLIMITED)
        narrow()

        cycle()
        assertEquals(
            listOf(primaryKey("LATE")),
            jobs().live,
            "the excluded row costs no job — the narrowing reaches the bytes, not only the manifest",
        )

        // …and it keeps not uploading, cycle after cycle.
        completeJobs(primaryKey("LATE"))
        cycle()
        cycle()
        assertEquals(setOf(primaryKey("LATE")), objects(), "no excluded asset's bytes ever reach the backend")
        assertEquals(1, jobs().created)
    }

    @Test
    fun excluded_rows_do_not_starve_admitted_work() = rigTest {
        // The one way to make this worse than the bug: admit AFTER a bounded read. Rows needing a job come back in
        // a stable key order, so an excluded backlog at the front would fill a bounded read on every cycle and the
        // admitted row further down would never be reached — a permanent, silent stall.
        val event = createAndJoin()
        // More excluded rows than several resolve chunks, all sorting AHEAD of the admitted one by key.
        repeat(20) { i -> addPhoto("A${(i + 1).toString().padStart(2, '0')}", date = early) }
        addPhoto("Z01", date = late)

        device("jobs/limit", "n" to "0")
        cycle()
        assertEquals(21, manifest(event)?.size, "every asset was recorded under the wide cutoff")

        device("jobs/limit", "n" to UNLIMITED)
        narrow()
        cycle()

        assertEquals(
            listOf(primaryKey("Z01")),
            jobs().live,
            "the admitted row is enqueued even though 20 excluded rows sort ahead of it",
        )
        assertEquals(1, jobs().created)
    }

    @Test
    fun after_a_narrowing_the_manifest_and_the_uploaded_set_are_the_same_set() = rigTest {
        // Not "each is individually correct" — the SAME set. One policy gates both, so a narrowing that reached one
        // consumer and not the other is the defect, whatever each looks like alone.
        val event = createAndJoin()
        addPhoto("EARLY", date = early)
        addPhoto("LATE", date = late)

        device("jobs/limit", "n" to "0")
        cycle()
        device("jobs/limit", "n" to UNLIMITED)
        narrow()

        uploadAll()

        val listed = manifest(event)?.keys.orEmpty()
        val uploaded = objects().mapTo(mutableSetOf()) { assetIdFromUploadKey(it).value }
        assertEquals(setOf("LATE"), listed, "the manifest declares only what the narrowed policy admits")
        assertEquals(listed, uploaded, "what is declared and what is uploaded are one set")
    }

    private companion object {
        const val UNLIMITED = "2147483647"
    }
}
