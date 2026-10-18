package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
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
    fun a_revoked_grant_withholds_the_cycle_and_leaves_the_published_union_intact() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            sharedInTheUnion("A")
            photoAccess(PhotoGrant.NOT_DETERMINED)
            photos("B")
        }
        var answer = ""
        whenever { answer = cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // nothing created without a grant
            // a temporary grant state publishes nothing — never the empty manifest that blanks the union
            never<WorldEvent.ManifestPublished>()
            never<WorldEvent.UnionChanged> { it.asset == "A" && !it.gained }
        }
        assertEquals("completed", answer, "no usable access: the cycle is withheld, and the system rests")
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Losing photo access stops new uploads but lets in-flight ones finish",
        scenario = "Access returns",
    )
    fun a_restored_grant_resumes_where_it_left_off() = behaviour {
        val withheld = given {
            extensionUploadsOnly()
            joinedEvent()
            photoAccess(PhotoGrant.DENIED)
            photos("A")
            step("a cycle without access", act = { cycle() }, until = {
                never<WorldEvent.UploadJobCreated>()
                settled { true }
            }).also { photoAccess(PhotoGrant.GRANTED) }
        }
        assertEquals("completed", withheld)

        whenever { cycle() }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
            settled { primaryJob("A") in it.state.inFlight?.jobs.orEmpty() }
        }
    }

    // Still read the old way: what it pins — which of the app's databases a process opens — is the app's own storage,
    // and nothing outside the app records an open.
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
        host.relaunch(scene = false)
        val opened = databases.opened().size

        assertEquals("completed", cycle())

        assertTrue(
            "downloads.db" !in databases.opened().drop(opened),
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
    fun a_cutoff_keeps_a_pre_cutoff_photo_out_of_the_upload_and_the_union() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(cutoff = "2026-06-01T00:00:00Z"))
            photoTaken("OLD", date = "2026-05-20T10:00:00Z")
            photoTaken("NEW", date = "2026-06-05T10:00:00Z")
        }

        whenever { uploadAll() }
        then {
            // the post-cutoff photo is uploaded and shared into the event union
            event<WorldEvent.UnionChanged> { it.asset == "NEW" && it.gained }
            // the pre-cutoff photo is never shared, and its bytes are never uploaded to the device partition
            never<WorldEvent.ManifestPublished> { "OLD" in it.added }
            never<WorldEvent.UnionChanged> { it.asset == "OLD" }
            never<WorldEvent.BytesStored> { it.asset == "OLD" }
        }
    }

    // ---- the job's retry ------------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "A failed upload is retried",
    )
    fun a_failed_job_is_retried_once_in_place_then_re_created() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            step("A's job created", act = { cycle() }, until = {
                times(1) { event<WorldEvent.UploadJobCreated>() }
                settled { true }
            })
        }

        // the first failure: the single free retry re-points the job
        whenever {
            uploadQueue.failJob(primaryJob("A"), "network")
            cycle()
        }
        then { never<WorldEvent.UploadJobCreated>() } // the first retry creates no new job

        // retry spent: re-created in the same cycle
        whenever {
            uploadQueue.failJob(primaryJob("A"), "network")
            cycle()
        }
        then {
            // the spent retry re-creates the job
            times(1) { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } }
            settled { primaryJob("A") in it.state.inFlight?.jobs.orEmpty() }
        }
    }

    // ---- the presence walk ----------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Deleting or no longer sharing a photo withdraws it")
    fun an_unreadable_walk_retracts_nothing_and_the_next_readable_walk_retracts_the_deleted_photo() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            uploaded("A")
        }

        whenever {
            library.remove("A")
            library.failNextEnumeration()
            cycle()
        }
        // an empty answer that is not authoritative is no evidence
        then { never<WorldEvent.ManifestPublished> { it.withdrawn.isNotEmpty() } }

        // the next readable walk is the evidence — no removal signal needed
        whenever { cycle() }
        then {
            event<WorldEvent.ManifestPublished> { "A" in it.withdrawn }
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
        }
    }

    // ---- a storage reset ------------------------------------------------------------------------------------------

    /**
     * A storage wipe followed by a join re-uploads everything instead of hanging: the join's reconcile sees the empty
     * listing and re-baselines, so the walk re-discovers what the bytes lost.
     */
    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun a_storage_reset_then_a_new_event_re_uploads_everything() = behaviour {
        val first = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                sharedInTheUnion("A")
            }
        }

        whenever { backend.wipeBytes() }
        then { event<WorldEvent.UnionChanged> { it.event == first && it.asset == "A" && !it.gained } } // nothing to serve

        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }

        lateinit var joined: Seen<Layer.Joined>
        whenever {
            createEvent("Trip", Rig.WINDOW_START, Rig.WINDOW_END)
            awaitScreen("the join gate") { (it.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
            confirmJoin()
        }
        then {
            joined = screen<Layer.Joined> { it.membership.name == "Trip" }
            settled { true }
        }
        val second = joined.value.membership.eventId

        whenever { cycle() }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } } // A is re-enqueued for upload

        whenever {
            uploadQueue.completeJob()
            cycle()
        }
        then {
            event<WorldEvent.BytesStored> { it.event == second && it.asset == "A" }
            // healed: the new event's union serves it
            event<WorldEvent.UnionChanged> { it.event == second && it.asset == "A" && it.gained }
        }
    }

    // ---- the composition opens nothing it is not asked for --------------------------------------------------------

    // Still read the old way: what it pins — which of the app's databases a process opens — is the app's own storage,
    // and nothing outside the app records an open.
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
        host.relaunch(scene = false)
        val opened = databases.opened().size

        os.pushToken("0a1b2c3d")
        eventually(read = { deviceConfig()?.first }) { it == "0a1b2c3d" }
        assertEquals(opened, databases.opened().size, "composing and waking the app opened a database")

        cycle()
        assertTrue("ledger.db" in databases.opened().drop(opened), "the first cycle is what opens the ledger")
    }

    /** The own photo [id] uploaded through the extension, and served in the event's union. */
    private suspend fun Given.sharedInTheUnion(id: String) = step("$id shared", act = { uploadAll() }, until = {
        event<WorldEvent.UnionChanged> { it.asset == id && it.gained }
        settled { true }
    })
}
