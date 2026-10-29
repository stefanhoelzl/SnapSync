package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.BackendCall
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam ↔ UI-state integration over the real stack, driven through the control protocol: `UiState` AND observable
 * outcomes — what the backend stores, the photo library's contents, the operating system's upload jobs.
 */
class FullStackIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Nothing is shared before the event starts",
        scenario = "A joined member of a future event shares nothing yet",
    )
    fun a_future_start_event_uploads_nothing_and_reads_not_started() = behaviour {
        // THE THEOREM the whole design rests on.
        //
        // Nothing syncs before the event starts — and NOT because a gate refuses. There is no gate. The join-time
        // clamp makes the effective cutoff `max(chosen, startsAt)`, and a photo's capture date cannot lie in the
        // future, so while `minPhotoDate >= startsAt > now` NO asset can satisfy `creationDate >= minPhotoDate`.
        given {
            extensionUploadsOnly()
            createdEvent(startsAt = FUTURE_START, endsAt = FUTURE_END)
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined> { it.health == SyncHealth.NotStarted }
                settled { true }
            })
            photos("A") // captured in 2026 — long before the event begins
        }

        // Run a cycle anyway: the real stack, the real upload cycle, no special-casing.
        whenever {
            cycle()
            os.foreground()
        }
        then {
            never<WorldEvent.UploadJobCreated>() // no upload job may be created before the event starts
            never<WorldEvent.BytesStored>() // no object may land before the event starts
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
            neverScreen<Layer.Joined> { it.health != SyncHealth.NotStarted }
            settled { true }
        }
    }

    @Test
    fun the_same_event_uploads_normally_once_its_start_is_in_the_past() = behaviour {
        // The mirror of the theorem: with the start in the past the floor binds nothing, and the very same stack
        // uploads exactly as it did before start dates existed.
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("A taken", act = {
                takePhoto("A")
                os.foreground()
            }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.Syncing } // NOT NotStarted
                settled { true }
            })
        }

        whenever {
            uploadAll()
            os.foreground()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" }
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
        }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "The joined screen counts the photos shared and received")
    fun the_counts_line_tracks_what_was_shared_and_received() = behaviour {
        // The same numbers the arrows derive from, through the real stack — own photos out, the other members' photos
        // in.
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("A and B taken", act = {
                takePhoto("A")
                takePhoto("B")
                os.foreground()
            }, until = {
                screen<Layer.Joined> { it.counts == SyncCounts(progress(0, 2), progress(0, 0)) }
                settled { true }
            })
            step("both uploaded, and a fellow member's three on their way", act = {
                uploadAll()
                publicBackend.foreignDevice(GUEST_DEVICE, listOf("G-1", "G-2", "G-3"))
                os.foreground()
            }, until = {
                event<WorldEvent.BytesStored> { it.asset == "A" }
                event<WorldEvent.BytesStored> { it.asset == "B" }
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        lateinit var synced: Seen<Layer.Joined>
        whenever {
            downloadSession.finish()
            os.foreground()
            advance(STATUS_POLL)
        }
        then { synced = screen<Layer.Joined> { it.health == SyncHealth.InSync } }
        assertEquals(SyncCounts(progress(2, 2), progress(3, 3)), synced.value.counts)

        // Without access the numbers are unknown: only the status line, which asks for access.
        lateinit var blocked: Seen<Layer.Joined>
        whenever {
            library.permission(PhotoGrant.DENIED)
            os.foreground()
        }
        then { blocked = screen<Layer.Joined> { it.health is SyncHealth.NeedsAccess } }
        assertNull(blocked.value.counts)
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "A not-yet-started event says when it starts")
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "No counts before the start or before the app has looked",
    )
    fun a_future_event_says_how_long_until_it_starts_and_shows_no_counts() = behaviour {
        given { createdEvent(startsAt = FUTURE_START, endsAt = FUTURE_END) }

        lateinit var waiting: Seen<Layer.Joined>
        whenever { confirmJoin() }
        then { waiting = screen<Layer.Joined> { it.health == SyncHealth.NotStarted } }
        assertIs<EventTiming.Upcoming>(waiting.value.timing)
        assertNull(waiting.value.counts)
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Direction arrows show the work left and the transfer running")
    fun upload_completion_advances_uistate_and_world_outcomes() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
        }

        // total = 1, nothing uploaded yet, no job created → Syncing with a static up arrow.
        lateinit var waiting: Seen<Layer.Joined>
        whenever {
            takePhoto("A")
            os.foreground()
        }
        then { waiting = screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
        assertEquals(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), waiting.value.health)

        // One cycle: the job is created → the upload is in flight → the up arrow pulses (the screen's status poll, two
        // seconds on, reads it).
        whenever {
            cycle()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
            screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.upload == Arrow.PULSING }
        }

        // The OS lands the transfer → the next cycle acknowledges it → settled.
        whenever {
            uploadQueue.completeJob()
            cycle()
            os.foreground()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" }
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "\"Up to date\" is never claimed before the app has looked",
        scenario = "Nothing to share still settles",
    )
    fun create_event_lifts_the_setup_gate() = behaviour {
        given {
            step("the app on its setup gate", act = { os.foreground() }, until = {
                never<WorldEvent.UiChanged> { it.ui.layer !is Layer.CreateEvent }
                settled { true }
            })
            createdEvent(name = "Party")
        }

        // Await the SETTLED health, not merely "left the create layer": the snapshot's first read is itself
        // asynchronous, so `Joined(Loading)` is a legitimate frame between the gate lifting and the counts landing.
        whenever { confirmJoin() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } } // no photos in the library → settled
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "Progress counts only what this membership shares")
    fun foreign_download_imports_and_own_status_excludes_it() = behaviour {
        val before = given {
            joinedEvent()
            downloadStarted(DEV_F, "FQ").state.libraryPhotos.orEmpty().size
        }

        // The foreground's status read may run before the import lands; the status poll is what catches the line up.
        whenever {
            downloadSession.finish()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ", DEV_F) } // the foreign photo imported
            // The imported foreign asset is suppressed from the OWN upload universe → own status settled.
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { it.state.libraryPhotos?.size == before + 1 }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "The sender's filename is kept",
    )
    fun an_imported_foreign_photo_carries_the_capturing_devices_filename() = behaviour {
        given {
            joinedEvent()
            step("FQ, named IMG_4471.HEIC, on its way", act = {
                publicBackend.foreignDevice(DEV_F, listOf("FQ"), filename = "IMG_4471.HEIC")
                os.foreground()
            }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        // What lands in the library is what the capturing device called it with SnapSync's mark added — NOT the
        // storage object key, which carries the assetId and the `-primary` role token and was what PhotoKit picked
        // up off the staged file when nobody named the resource. The mark is what a reinstalled app recognises it by.
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { created ->
                created.names.size == 1 && Regex("""IMG_4471\.snapsync-[a-z2-7]{10}\.HEIC""").matches(created.names.single())
            }
        }
    }

    @Test
    @Verifies(spec = "photo-access", requirement = "Receive-only with limited access is never nagged")
    fun a_limited_grant_receives_foreign_photos_and_never_reads_needs_access() = behaviour {
        // Receive-only under a LIMITED grant is a valid resting state: imports work, no upload work is created, and
        // the screen shows the ordinary health line — never NeedsAccess.
        val before = given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            photos("A") // present in the library — must NOT be enumerated or uploaded
            // The baseline snapshot lands, and it is EMPTY — the member selected nothing. A real `.limited` device
            // always emits one when observation begins, and only that emission makes the total a counted zero.
            step("nothing selected", act = { library.changeSelection(emptyList()) }, until = { settled { true } })
            downloadStarted(DEV_F, "FQ").state.libraryPhotos.orEmpty().size
        }

        whenever {
            downloadSession.finish()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ", DEV_F) } // imported under LIMITED
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            neverScreen<Layer.Joined> { it.health is SyncHealth.NeedsAccess }
            settled { it.state.libraryPhotos?.size == before + 1 }
        }

        whenever {
            os.foreground()
            cycle()
        }
        then {
            never<WorldEvent.UploadJobCreated>() // no upload work for the unselected own photo
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.BytesStored>()
            neverScreen<Layer.Joined> { it.health != SyncHealth.InSync }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
    )
    fun a_selection_change_under_limited_raises_n_and_uploads_the_selected_photos() = behaviour {
        // One selection-change emission serves N and the cycle's discovery; the cycle under LIMITED reads the
        // snapshot (never the library) and uploads through the ordinary engine.
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            photos("A", "B", "UNSELECTED") // UNSELECTED: in the library, never selected — must not upload
        }

        // N counts the selection: two to upload. Under a partial grant only the app's uploader creates (the extension
        // withholds by its own admission): its transfers are created from the selection change's tail.
        whenever { library.changeSelection(listOf("A", "B")) }
        then {
            screen<Layer.Joined> { it.health is SyncHealth.Syncing }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            times(2) { event<WorldEvent.SessionTransferCreated>() }
            never<WorldEvent.UploadJobCreated>()
        }

        // The operating system lands them.
        lateinit var synced: Seen<Layer.Joined>
        whenever {
            uploadSession.complete()
            os.foreground()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" }
            event<WorldEvent.BytesStored> { it.asset == "B" }
            never<WorldEvent.BytesStored> { it.asset == "UNSELECTED" } // never entered the pipeline
            never<WorldEvent.SessionTransferCreated>()
            synced = screen<Layer.Joined> { it.health == SyncHealth.InSync }
        }
        assertTrue(synced.value.canChoosePhotos, "the joined layer under LIMITED offers choosing more photos")
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "Under limited access the member's selection decides what a photo is",
    )
    fun the_range_filters_a_limited_selection_and_the_origin_rules_do_not() = behaviour {
        // The cutoff filters hand-picked photos as it filters a library walk — picking a pre-cutoff photo does not
        // smuggle it past the range — while the rules about what a photo is stand down: the member picked the
        // screenshot (decision record `changes/archive/2026-10-15-limited-selection-decides`).
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            photoTaken("OK")
            photoTaken("OLD", date = "2001-01-01T00:00:00Z") // pre-cutoff
            photoTaken("SHOT", kind = LibraryPhotoKind.SCREENSHOT) // a screenshot, which a library walk excludes
        }

        // Under a partial grant only the app's uploader creates (the extension withholds by its own admission): its
        // transfers are created from the selection change's tail, and the operating system lands them.
        whenever { library.changeSelection(listOf("OK", "OLD", "SHOT")) }
        then {
            screen<Layer.Joined> { it.health is SyncHealth.Syncing }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("OK") }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("SHOT") }
            times(2) { event<WorldEvent.SessionTransferCreated>() }
        }

        // Every pick inside the range uploads, and only those.
        whenever { uploadSession.complete() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "OK" }
            event<WorldEvent.BytesStored> { it.asset == "SHOT" }
            never<WorldEvent.BytesStored> { it.asset != "OK" && it.asset != "SHOT" }
            never<WorldEvent.SessionTransferCreated>()
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun an_imported_foreign_asset_in_the_selection_never_reuploads() = behaviour {
        // The app's own import auto-joins the platform selection (measured); the snapshot then carries it, and
        // echo-suppression drops it at the cycle.
        given {
            joinedEvent()
            downloadStarted(DEV_F, "FQ")
            step("FQ imported", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ", DEV_F) }
                settled { true }
            })
            photoAccess(PhotoGrant.LIMITED)
            photos("MINE")
        }

        // Under a partial grant only the app's uploader creates (the extension withholds by its own admission): its
        // transfers are created from the selection change's tail, and the operating system lands them.
        whenever { library.changeSelection(listOf("MINE", imported("FQ", DEV_F))) }
        then {
            screen<Layer.Joined> { it.health is SyncHealth.Syncing }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("MINE") }
            never<WorldEvent.SessionTransferCreated> { it.key != primaryKey("MINE") }
            settled { true }
        }

        // The foreign import never re-uploaded under this device's id.
        whenever { uploadSession.complete() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "MINE" }
            never<WorldEvent.BytesStored> { it.asset != "MINE" }
            never<WorldEvent.SessionTransferCreated>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The status stays current while the app is open",
        scenario = "A failed read does not flip the status",
    )
    fun upload_completeness_is_ledger_local_and_backend_independent() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            uploaded("A")
            step("in sync", act = { os.foreground() }, until = {
                screen<Layer.Joined> { it.health == SyncHealth.InSync }
                settled { true }
            })
        }

        // Upload completeness is the local ledger, not a storage LIST — backend-offline changes nothing (the read
        // never touches the network).
        whenever {
            backend.offline(true)
            os.foreground()
        }
        then { staysInSync() }

        // The download union read fails offline, without failing the reconcile (it keeps its last state).
        whenever {
            advance(STATUS_POLL)
            os.foreground()
        }
        then { staysInSync() }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving deletes no one's photos",
        scenario = "Shared photos outlive the member's leave",
    )
    fun leaving_as_the_last_member_returns_to_the_setup_gate_and_keeps_the_event() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                uploaded("A")
            }
        }

        // The production leave: cancel downloads, stop the producer, clear the membership, then notify the backend
        // FIRE-AND-FORGET.
        whenever { tap(UiIntent.LeaveEvent) }
        then {
            // UiState reduces to the setup gate the instant the membership clears...
            screen<Layer.CreateEvent>()
            // ...and the backend outcome lands when the fire-and-forget DELETE does. Leaving is RENAME-ONLY: the
            // device is departed, but the event and its bytes are RETAINED until the nightly sweep reclaims them.
            event<WorldEvent.MemberLeft> { it.event == event }
            never<WorldEvent.EventDeleted>() // bytes are not collected by a leave
            settled { it.state.backendEvents?.containsKey(event) == true }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Each direction runs only if the member chose it",
        scenario = "Share-only receives nothing",
    )
    fun upload_only_uploads_own_but_imports_no_foreign() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.UploadOnly))
            photos("A")
            fellowMember(DEV_F, "FQ")
        }

        whenever { uploadAll() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } } // upload-only still uploads own photos

        // The download arm is gated off: reconcile is a no-op, so nothing foreign is enqueued or imported. Both arrows
        // hidden → In sync; the zero download total flows through the download gate.
        whenever {
            os.foreground()
            downloadSession.finish()
        }
        then {
            never<WorldEvent.DownloadStarted>()
            never<WorldEvent.LibraryAssetCreated>() // upload-only imports no foreign photos
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { it.state.libraryPhotos?.size == 1 }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Each direction runs only if the member chose it",
        scenario = "Receive-only uploads nothing",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Receive-only ignores the member's own gallery",
    )
    fun download_only_imports_foreign_and_reads_in_sync_through_a_zero_total() = behaviour {
        val before = given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.DownloadOnly))
            photos("A") // an un-uploaded own photo remains in the library
            downloadStarted(DEV_F, "FQ").state.libraryPhotos.orEmpty().size
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ", DEV_F) } // download-only imports
            settled { it.state.libraryPhotos?.size == before + 1 }
        }

        // DRIVE the upload arm: an assertion with no cycle above it would pass whether or not the gate existed.
        // "In sync" because N is 0 — NOT because an arrow is masked: were the total to count the un-uploaded photo, the
        // upload arrow would show and this would fail.
        whenever {
            cycle()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>() // download-only uploads nothing
            neverScreen<Layer.Joined> { it.health != SyncHealth.InSync }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Each direction runs only if the member chose it",
        scenario = "Receive-only uploads nothing",
    )
    fun download_only_uploads_nothing_when_the_cycle_actually_runs() = behaviour {
        // THE PRIVACY INVARIANT. The join gate promises "Only receive the event's
        // photos — you won't share yours". On the app-driven tier the APP invokes the cycle — foreground entry, the
        // heartbeat, a silent push — and every one of those reaches exactly this call.
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.DownloadOnly))
            step("A taken", act = { takePhoto("A") }, until = {
                never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
                settled { true }
            })
        }

        whenever {
            cycle()
            uploadQueue.completeJob() // a no-op once the gate holds: no such job exists
            cycle()
        }
        then {
            // Download-only must create no upload job — the member was promised they would share nothing…
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>() // …and upload no bytes.
            // The union leak, distinct from the bytes: a manifest listing the member's assets offers them to every
            // other member: one policy gates both the byte upload and the manifest listing.
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "manage-membership", requirement = "Leaving asks first, then is instant and works offline")
    fun leaving_flips_the_screen_before_the_backend_delete_completes() = behaviour {
        val event = given {
            joinedEvent().also {
                // The backend holds the leave's DELETE open: the backend that has not answered yet.
                step("the leave held", act = { backend.hold(BackendCall.LEAVE) }, until = {})
            }
        }

        // The screen leaves the joined layer immediately — even though the DELETE is still pending.
        whenever { tap(UiIntent.LeaveEvent) }
        then {
            screen<Layer.CreateEvent>()
            event<WorldEvent.BackendHeld> { it.call == BackendCall.LEAVE }
            never<WorldEvent.MemberLeft>() // the backend has not answered, and the screen did not wait for it
        }

        // Once the backend answers, the departure lands.
        whenever { backend.release(BackendCall.LEAVE) }
        then {
            event<WorldEvent.MemberLeft> { it.event == event }
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.CreateEvent }
            settled { true }
        }
    }

    /** The status read nothing new, and nothing on the screen moved off "In sync". */
    private fun Expectations.staysInSync() {
        never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined }
        neverScreen<Layer.Joined> { it.health != SyncHealth.InSync }
        settled { true }
    }

    private companion object {
        const val FUTURE_START = "2099-12-01T00:00:00"
        const val FUTURE_END = "2099-12-31T00:00:00"

        /** Past the screen's status poll, which reads the ledger every two seconds of mocked time. */
        val STATUS_POLL = 5.seconds
    }
}

private fun progress(done: Int, total: Int) = DirectionCount.Progress(done, total)
