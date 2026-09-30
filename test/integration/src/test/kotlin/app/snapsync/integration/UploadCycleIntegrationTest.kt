package app.snapsync.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload cycle over the real stack, driven through the control protocol and read back off the systems outside the
 * app — the operating system's upload jobs, the backend's objects, manifest and union (capabilities `photo-sharing`,
 * `background-upload`): the upload extension's answers, the grant's admission, the capture-date cutoff, the job's
 * retry, the presence walk, and a storage reset healed by the next join.
 *
 * What each ledger row holds on the way is the app's own bookkeeping; `UploadCycleTest` (`:test:feature`) pins it.
 */
class UploadCycleIntegrationTest {

    // ---- the upload extension's answers ---------------------------------------------------------------------------

    @Test
    fun process_starts_a_new_upload_and_asks_to_be_called_again() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")

        assertEquals("processing", cycle(), "the system is asked to call again while the upload is in flight")
        assertEquals(listOf(primaryKey("A")), jobs().live, "the new photo's upload is started")
    }

    @Test
    fun process_with_nothing_new_completes() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        assertEquals("completed", cycle())
        assertEquals(0, jobs().created, "nothing is uploaded")
    }

    @Test
    fun process_without_a_membership_declines() = rigTest {
        extensionUploadsOnly()
        addPhoto("A")
        assertEquals("skipped", cycle())
        assertEquals(0, jobs().created, "nothing is uploaded")
    }

    // ---- the grant's admission (capability `background-upload`, "The upload cycle owns its entry decision") -----

    @Test
    fun a_revoked_grant_withholds_the_cycle_and_leaves_the_published_union_intact() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue("A" in union(), "precondition: A is shared")

        permission("NOT_DETERMINED")
        addPhoto("B")

        assertEquals("skipped", cycle(), "no usable access: the cycle is withheld")
        assertTrue(primaryKey("B") !in jobs().live, "nothing created without a grant")
        assertTrue("A" in union(), "a temporary grant state publishes nothing — never the empty manifest that blanks the union")
    }

    @Test
    fun a_restored_grant_resumes_where_it_left_off() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        permission("DENIED")
        addPhoto("A")
        assertEquals("skipped", cycle())

        permission("GRANTED")

        cycle()
        assertTrue(primaryKey("A") in jobs().live)
    }

    /**
     * A process that may not create never opens the download store (capability `receiving-photos`): its suppression
     * read is the gate's last step, taken only for an admitted cycle — so a withheld cycle never pauses on it either.
     */
    @Test
    fun a_withheld_cycle_never_opens_the_download_store() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        permission("DENIED")
        device("relaunch", "scene" to "false")
        val opened = osRecord().databasesOpened.size

        assertEquals("skipped", cycle())

        assertTrue("downloads.db" !in osRecord().databasesOpened.drop(opened), "admission is decided before the store is opened")
    }

    // ---- the capture-date cutoff (capability `photo-sharing`) -----------------------------------------------------

    /** One cutoff drives BOTH the byte upload and the manifest projection. */
    @Test
    fun a_cutoff_keeps_a_pre_cutoff_photo_out_of_the_upload_and_the_union() = rigTest {
        extensionUploadsOnly()
        createAndJoin("cutoff" to "2026-06-01T00:00:00Z")
        addPhoto("OLD", date = "2026-05-20T10:00:00Z")
        addPhoto("NEW", date = "2026-06-05T10:00:00Z")

        uploadAll()

        assertTrue("NEW" in union(), "the post-cutoff photo is uploaded and shared into the event union")
        assertTrue("OLD" !in union(), "the pre-cutoff photo is never shared")
        assertTrue(primaryKey("OLD") !in objects(), "and its bytes are never uploaded to the device partition")
    }

    // ---- the job's retry ------------------------------------------------------------------------------------------

    @Test
    fun a_failed_job_is_retried_once_in_place_then_re_created() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        cycle()
        assertEquals(1, jobs().created)

        device("jobs/fail", "key" to primaryKey("A"), "error" to "network")
        cycle() // the first failure: the single free retry re-points the job
        assertEquals(1, jobs().created, "the first retry creates no new job")

        device("jobs/fail", "key" to primaryKey("A"), "error" to "network")
        cycle() // retry spent: re-created in the same cycle
        assertEquals(2, jobs().created, "the spent retry re-creates the job")
        assertTrue(primaryKey("A") in jobs().live)
    }

    // ---- the presence walk (capability `photo-sharing`, "Deletion is a presence diff over an authoritative walk") --

    @Test
    fun an_unreadable_walk_retracts_nothing_and_the_next_readable_walk_retracts_the_deleted_photo() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertEquals(setOf("A"), manifest(event)?.keys)

        device("gallery/remove", "id" to "A")
        device("gallery/fail-next-enumeration")
        cycle()
        assertEquals(setOf("A"), manifest(event)?.keys, "an empty answer that is not authoritative is no evidence")

        cycle()
        assertEquals(emptySet(), manifest(event)?.keys, "the next readable walk is the evidence — no removal signal needed")
    }

    // ---- a storage reset ------------------------------------------------------------------------------------------

    /**
     * A storage wipe followed by a join re-uploads everything instead of hanging (capability `photo-sharing`): the
     * join's reconcile sees the empty listing and re-baselines, so the walk re-discovers what the bytes lost.
     */
    @Test
    fun a_storage_reset_then_a_new_event_re_uploads_everything() = rigTest {
        extensionUploadsOnly()
        val first = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        assertTrue("A" in union(first))

        device("backend/wipe-bytes")
        assertTrue(objects().isEmpty())
        assertTrue("A" !in union(first), "the union drops what has no bytes")

        leave()
        val second = createAndJoin(name = "Trip")
        cycle()
        assertTrue(primaryKey("A") in jobs().live, "A is re-enqueued for upload")
        completeJobs()
        cycle()

        assertTrue(primaryKey("A") in objects())
        assertTrue("A" in union(second), "healed: the new event's union serves it")
    }

    // ---- the composition opens nothing it is not asked for --------------------------------------------------------

    /**
     * **Forcing the composition opens no database** (`docs/architecture.md`): the storage services open on first use,
     * so a process the operating system starts and wakes for something that needs no store opens none — a locked
     * background launch is never forced into an open by composition — and the first cycle is what opens the ledger.
     */
    @Test
    fun a_wake_that_needs_no_store_opens_no_database_and_the_first_cycle_opens_the_ledger() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        device("relaunch", "scene" to "false")
        val opened = osRecord().databasesOpened.size

        os("app", "onPushToken", "0a1b2c3d")
        eventually(read = { deviceConfig()?.first }) { it == "0a1b2c3d" }
        assertEquals(opened, osRecord().databasesOpened.size, "composing and waking the app opened a database")

        cycle()
        assertTrue("ledger.db" in osRecord().databasesOpened.drop(opened), "the first cycle is what opens the ledger")
    }
}
