package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **A terminal upload outcome survives the process that learned it** (decision record
 * `changes/fix-lost-upload-acks`).
 *
 * The defect these pin is Bugsink `SNAPSYNC-11`. iOS delivers a background-`URLSession` completion
 * exactly once — `URLSessionTask.State.completed` is documented as *"the task has completed (without
 * being canceled), and the task's delegate receives no further callbacks"* — and the adapter used to
 * park that outcome in an `ArrayList` for a later `UploadCycle` to drain. The drain is gated on a
 * single-flight cycle measured in the field at 27 minutes, 65 minutes and 4h49m, so a process death in
 * between lost the fact outright: the row still read `REQUESTED` with no live task, the stranded repair of
 * the time demoted it, and bytes that had already landed were sent again. One device uploaded the same
 * two photos three times over two days and its status screen said "uploading" throughout.
 *
 * **What makes this assertable without a device** is that the host can simulate the one thing that
 * matters — a process boundary. `host.relaunch` ends the app and composes a new one over the same durable
 * state: every in-memory registry the adapter held is gone, and only what reached durable storage survives. If a
 * completion is ever parked in memory again, the relaunched app cannot see it and these fail.
 */
class LostUploadAckIntegrationTest {

    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    @Verifies(spec = "delivery", requirement = "A finished upload is recognised no later than the next opening")
    fun a_completion_learned_by_a_dead_process_is_settled_and_never_re_uploaded() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
        }

        // Cycle 1 creates the upload job; the "OS" then finishes the transfer.
        whenever { cycle() }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() } // precondition: one upload job
            settled { true }
        }
        whenever { uploadQueue.completeJob() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } } // the bytes landed

        // ── the process dies here. Everything the adapter held in memory goes with it. ──
        whenever { host.relaunch() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }

        whenever {
            cycle()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            // Nothing is left outstanding: the screen settles over the photo it uploaded.
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            // And its bytes are NEVER sent again — this is the whole defect: a lost acknowledgement used to read as a
            // lost upload, and the photo was re-uploaded on every relaunch. The object landed once.
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn }
        }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "The album holds the event as this device holds it")
    fun a_platform_that_reports_within_the_cycle_settles_in_that_same_cycle_and_places_once() = behaviour {
        // The PhotoKit tier's shape: that tier has no callback outside the cycle — its adapter fetches the
        // finished jobs, records them settled and acknowledges in place. The album is not waiting for that: the photo
        // is placed when its upload is first enqueued, so the completion changes nothing about the album.
        val event = given {
            extensionUploadsOnly()
            joinedSavingToAlbum().first.also { photos("A") } // a cycle places only into an album that exists
        }

        // The cycle places, then creates the job.
        whenever { cycle() }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == "A" }
            times(1) { event<WorldEvent.UploadJobCreated>() }
            event<WorldEvent.ManifestPublished> { it.event == event && "A" in it.added }
            never<WorldEvent.BytesStored>() // precondition: no byte has reached the backend yet
            // in the album as soon as its upload is queued, before any byte moved
            settled { it.state.albums.orEmpty().flatMap { album -> album.photos } == listOf("A") }
        }

        // The "OS" finished it while we were away; ONE cycle: the drain records the completion, and the manifest
        // publishes it.
        whenever {
            uploadQueue.completeJob()
            cycle()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" }
            // The announcement IS the manifest write: on the versioned device API there is no notify route, so
            // publishing the device's asset set is the only thing that tells the event anything happened.
            event<WorldEvent.ManifestPublished> { it.event == event }
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn } // the settled upload stays published
            never<WorldEvent.UploadJobCreated>()
            // What the album HOLDS, never how often the app asked: a repeated add is a no-op on the platform (the
            // GalleryReader contract's A_REPEATED_ADD_IS_A_NO_OP), and the join's album gather may place A again.
            settled { it.state.albums.orEmpty().flatMap { album -> album.photos } == listOf("A") } // held once
        }
    }

    private companion object {
        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
