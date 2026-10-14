package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload cycle over the real stack, driven through the control protocol and read back off the systems outside the
 * app — the operating system's upload jobs, the backend's objects, manifest and union: the upload extension's
 * answers, the grant's admission, the capture-date cutoff, the job's retry, the presence walk, and a storage reset
 * healed by the next join.
 *
 * What each ledger row holds on the way is the app's own bookkeeping; `UploadCycleTest` (`:test:feature`) pins it.
 */
class UploadCycleIntegrationTest {

    // ---- the upload extension's answers ---------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A photo taken with the app closed reaches the event",
    )
    fun process_starts_a_new_upload_and_asks_to_be_called_again() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
        }
        var answer = ""
        whenever { answer = cycle() }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } }
        assertEquals("processing", answer, "the system is asked to call again while the upload is in flight")
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Photos travel without the app being opened")
    fun process_with_nothing_new_completes() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
        }
        var answer = ""
        whenever { answer = cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
        assertEquals("completed", answer)
    }

    @Test
    @Verifies(spec = "join-event", requirement = "Joining happens only on confirmation and needs a connection")
    fun process_without_a_membership_declines() = behaviour {
        given {
            extensionUploadsOnly()
            photos("A")
        }
        var answer = ""
        whenever { answer = cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
        assertEquals("completed", answer)
    }

    // ---- the grant's admission ------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "delivery", requirement = "Losing photo access stops new uploads but lets in-flight ones finish")
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "Revoked access withdraws nothing",
    )
    fun a_revoked_grant_withholds_the_cycle_and_leaves_the_published_union_intact() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue("A" in union(), "precondition: A is shared")

        permission(PhotoGrant.NOT_DETERMINED)
        addPhoto("B")

        assertEquals("completed", cycle(), "no usable access: the cycle is withheld, and the system rests")
        assertTrue(primaryJob("B") !in jobs().live, "nothing created without a grant")
        assertTrue(
            "A" in union(),
            "a temporary grant state publishes nothing — never the empty manifest that blanks the union",
        )
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Losing photo access stops new uploads but lets in-flight ones finish",
        scenario = "Access returns",
    )
    fun a_restored_grant_resumes_where_it_left_off() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        permission(PhotoGrant.DENIED)
        addPhoto("A")
        assertEquals("completed", cycle())

        permission(PhotoGrant.GRANTED)

        cycle()
        assertTrue(primaryJob("A") in jobs().live)
    }

    /**
     * A process that may not create never opens the download store: its suppression read is the gate's last step,
     * taken only for an admitted cycle — so a withheld cycle never pauses on it either.
     */
    @Test
    @Verifies(spec = "delivery", requirement = "Losing photo access stops new uploads but lets in-flight ones finish")
    fun a_withheld_cycle_never_opens_the_download_store() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        permission(PhotoGrant.DENIED)
        device("relaunch", "scene" to "false")
        val opened = osRecord().databasesOpened.size

        assertEquals("completed", cycle())

        assertTrue(
            "downloads.db" !in osRecord().databasesOpened.drop(opened),
            "admission is decided before the store is opened",
        )
    }

    // ---- the capture-date cutoff ----------------------------------------------------------------------------------

    /** One cutoff drives BOTH the byte upload and the manifest projection. */
    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A photo from before the range stays private",
    )
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
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "A failed upload is retried",
    )
    fun a_failed_job_is_retried_once_in_place_then_re_created() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        cycle()
        assertEquals(1, jobs().created)

        uploadQueue.failJob(primaryJob("A"), "network")
        cycle() // the first failure: the single free retry re-points the job
        assertEquals(1, jobs().created, "the first retry creates no new job")

        uploadQueue.failJob(primaryJob("A"), "network")
        cycle() // retry spent: re-created in the same cycle
        assertEquals(2, jobs().created, "the spent retry re-creates the job")
        assertTrue(primaryJob("A") in jobs().live)
    }

    // ---- the presence walk ----------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Deleting or no longer sharing a photo withdraws it")
    fun an_unreadable_walk_retracts_nothing_and_the_next_readable_walk_retracts_the_deleted_photo() = rigTest {
        extensionUploadsOnly()
        val event = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertEquals(setOf("A"), manifest(event)?.keys)

        library.remove("A")
        library.failNextEnumeration()
        cycle()
        assertEquals(setOf("A"), manifest(event)?.keys, "an empty answer that is not authoritative is no evidence")

        cycle()
        assertEquals(
            emptySet(),
            manifest(event)?.keys,
            "the next readable walk is the evidence — no removal signal needed",
        )
    }

    // ---- a storage reset ------------------------------------------------------------------------------------------

    /**
     * A storage wipe followed by a join re-uploads everything instead of hanging: the join's reconcile sees the empty
     * listing and re-baselines, so the walk re-discovers what the bytes lost.
     */
    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun a_storage_reset_then_a_new_event_re_uploads_everything() = rigTest {
        extensionUploadsOnly()
        val first = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        assertTrue("A" in union(first))

        backend.wipeBytes()
        assertTrue(objects().isEmpty())
        assertTrue("A" !in union(first), "the union drops what has no bytes")

        leave()
        val second = createAndJoin(name = "Trip")
        cycle()
        assertTrue(primaryJob("A") in jobs().live, "A is re-enqueued for upload")
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
    @Verifies(
        spec = "delivery",
        requirement = "A locked phone delays uploads and never ends the membership",
        scenario = "A wake before the first unlock after a restart",
    )
    fun a_wake_that_needs_no_store_opens_no_database_and_the_first_cycle_opens_the_ledger() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        device("relaunch", "scene" to "false")
        val opened = osRecord().databasesOpened.size

        os.pushToken("0a1b2c3d")
        eventually(read = { deviceConfig()?.first }) { it == "0a1b2c3d" }
        assertEquals(opened, osRecord().databasesOpened.size, "composing and waking the app opened a database")

        cycle()
        assertTrue("ledger.db" in osRecord().databasesOpened.drop(opened), "the first cycle is what opens the ledger")
    }
}
