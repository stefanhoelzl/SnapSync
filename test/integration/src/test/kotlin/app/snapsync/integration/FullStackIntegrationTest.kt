package app.snapsync.integration

import app.snapsync.model.Arrow
import app.snapsync.presentation.Layer
import app.snapsync.presentation.SyncHealth
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration over the real stack, driven through the control protocol: `UiState` AND observable
 * outcomes — objects the backend lists, the photo library's contents, the operating system's upload jobs.
 */
class FullStackIntegrationTest {

    @Test
    fun a_future_start_event_uploads_nothing_and_reads_not_started() = rigTest {
        // THE THEOREM the whole design rests on (capability `photo-selection-policy`).
        //
        // Nothing syncs before the event starts — and NOT because a gate refuses. There is no gate. The join-time
        // clamp makes the effective cutoff `max(chosen, startsAt)`, and a photo's capture date cannot lie in the
        // future, so while `minPhotoDate >= startsAt > now` NO asset can satisfy `creationDate >= minPhotoDate`.
        createAndJoin(startsAt = "2099-12-01T00:00:00", endsAt = "2099-12-31T00:00:00")
        addPhoto("A") // captured in 2026 — long before the event begins
        refresh()
        awaitHealth { it is SyncHealth.NotStarted }

        // Run a cycle anyway: the real stack, the real upload cycle, no special-casing.
        cycle()
        refresh()

        assertEquals(0, jobs().created, "no upload job may be created before the event starts")
        assertTrue(objects().isEmpty(), "no object may land before the event starts")
        awaitHealth { it is SyncHealth.NotStarted }
    }

    @Test
    fun the_same_event_uploads_normally_once_its_start_is_in_the_past() = rigTest {
        // The mirror of the theorem: with the start in the past the floor binds nothing, and the very same stack
        // uploads exactly as it did before start dates existed.
        createAndJoin()
        addPhoto("A")
        refresh()
        awaitHealth { it is SyncHealth.Syncing } // NOT NotStarted

        uploadAll()
        refresh()

        assertTrue(primaryKey("A") in objects())
        awaitInSync()
    }

    @Test
    fun upload_completion_advances_uistate_and_world_outcomes() = rigTest {
        createAndJoin()
        addPhoto("A")
        refresh()
        // total = 1, nothing uploaded yet, no job created → Syncing with a static up arrow.
        assertEquals(SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN), awaitHealth { it is SyncHealth.Syncing })

        // One cycle: the job is created → the upload is in flight → the up arrow pulses.
        cycle()
        awaitHealth { (it as? SyncHealth.Syncing)?.upload == Arrow.PULSING }

        // The OS lands the transfer → the next cycle acknowledges it → settled.
        completeJobs()
        cycle()
        refresh()
        awaitInSync()
        assertTrue(primaryKey("A") in objects())
    }

    @Test
    fun create_event_lifts_the_setup_gate() = rigTest {
        assertEquals(Layer.CreateEvent(), state().ui.layer)

        create(name = "Party")
        join()
        // Await the SETTLED health, not merely "left the create layer": the snapshot's first read is itself
        // asynchronous, so `Joined(Loading)` is a legitimate frame between the gate lifting and the counts landing.
        assertEquals(SyncHealth.InSync, awaitInSync()) // no photos in the library → settled
        assertTrue(state().ready.configResolved)
    }

    @Test
    fun foreign_download_imports_and_own_status_excludes_it() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        val before = libraryTotal()

        downloadAll()
        assertEquals(before + 1, libraryTotal(), "the foreign photo was imported into the library")

        refresh()
        // The imported foreign asset is suppressed from the OWN upload universe → own status settled.
        awaitInSync()
    }

    @Test
    fun an_imported_foreign_photo_carries_the_capturing_devices_filename() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ", filename = "IMG_4471.HEIC")

        downloadAll()

        // What lands in the library is what the capturing device called it, NOT the storage object key — which
        // carries the assetId and the `-primary` role token and was what PhotoKit picked up off the staged file
        // when nobody named the resource.
        val names = gallery(resources = true).policy!!.assets.mapNotNull { it.originalFilenames }
        assertTrue(listOf("IMG_4471.HEIC") in names, "the imported photo is named IMG_4471.HEIC: $names")
    }

    @Test
    fun a_limited_grant_receives_foreign_photos_and_never_reads_needs_access() = rigTest {
        // Receive-only under a LIMITED grant is a valid resting state (capability `limited-photo-access`): imports
        // work, no upload work is created, and the screen shows the ordinary health line — never NeedsAccess.
        permission("LIMITED")
        createAndJoin()
        addPhoto("A") // present in the library — must NOT be enumerated or uploaded
        foreignDevice("DEV-F", "FQ")

        // The baseline snapshot lands, and it is EMPTY — the member selected nothing. A real `.limited` device
        // always emits one when observation begins, and only that emission makes the total a counted zero.
        device("selection/change", "assets" to "")

        val before = libraryTotal()
        downloadAll()
        assertEquals(before + 1, libraryTotal(), "the foreign photo was imported under LIMITED")

        refresh()
        cycle()
        assertEquals(0, jobs().created, "no upload work for the unselected own photo")
        assertTrue(objects().isEmpty())
        awaitInSync()
    }

    @Test
    fun a_selection_change_under_limited_raises_n_and_uploads_the_selected_photos() = rigTest {
        // One selection-change emission serves N and the cycle's discovery; the cycle under LIMITED reads the
        // snapshot (never the library) and uploads through the ordinary engine (capability `limited-photo-access`).
        permission("LIMITED")
        createAndJoin()
        addPhoto("A")
        addPhoto("B")
        addPhoto("UNSELECTED") // in the library, never selected — must not upload

        device("selection/change", "assets" to "A,B")
        // N counts the selection: two to upload. Awaiting it also sequences the cycle below.
        awaitHealth { it is SyncHealth.Syncing }

        uploadAll()

        val landed = objects()
        assertTrue(primaryKey("A") in landed && primaryKey("B") in landed, "$landed")
        assertTrue(primaryKey("UNSELECTED") !in landed, "the unselected photo never entered the pipeline")
        assertEquals(2, jobs().created)

        refresh()
        val settled = awaitState { it.health == SyncHealth.InSync }.joined!!
        assertTrue(settled.canChoosePhotos, "the joined layer under LIMITED offers choosing more photos")
    }

    @Test
    fun the_policy_applies_unchanged_to_a_limited_selection() = rigTest {
        // Cutoff and origin exclusions filter hand-picked photos exactly as a full-library walk: picking a
        // pre-cutoff photo or a screenshot does not smuggle it past the policy.
        permission("LIMITED")
        createAndJoin()
        addPhoto("OK")
        addPhoto("OLD", date = "2001-01-01T00:00:00Z") // pre-cutoff
        addPhoto("SHOT", kind = "screenshot") // origin-excluded by subtype

        device("selection/change", "assets" to "OK,OLD,SHOT")
        awaitHealth { it is SyncHealth.Syncing }

        uploadAll()

        val landed = objects()
        assertEquals(setOf(primaryKey("OK")), landed, "only the policy-admitted pick uploads")
        assertEquals(1, jobs().created)
    }

    @Test
    fun an_imported_foreign_asset_in_the_selection_never_reuploads() = rigTest {
        // The app's own import auto-joins the platform selection (measured); the snapshot then carries it, and
        // echo-suppression drops it at the cycle (capability `limited-photo-access`).
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        // The imported photo's library id, read under the full grant — under LIMITED the app sees only the selection.
        val imported = gallery().policy!!.assets.single().assetId

        permission("LIMITED")
        addPhoto("MINE")
        device("selection/change", "assets" to "MINE,$imported")
        awaitHealth { it is SyncHealth.Syncing }

        uploadAll()

        // The foreign import never re-uploaded under this device's id.
        assertEquals(setOf(primaryKey("MINE")), objects())
    }

    @Test
    fun upload_completeness_is_ledger_local_and_backend_independent() = rigTest {
        createAndJoin()
        addPhoto("A")
        uploadAll()
        refresh()
        awaitInSync()

        // Upload completeness is the local ledger, not a storage LIST — backend-offline changes nothing (the read
        // never touches the network).
        device("backend/offline", "on" to "true")
        refresh()
        awaitInSync()
        // The download union read fails offline, without failing the reconcile (it keeps its last state).
        reconcile()
        awaitInSync()
    }

    @Test
    fun leaving_as_the_last_member_returns_to_the_setup_gate_and_keeps_the_event() = rigTest {
        val event = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())

        // The production leave: cancel downloads, stop the producer, clear the membership, then notify the backend
        // FIRE-AND-FORGET.
        user("leave")

        // UiState reduces to the setup gate the instant the membership clears...
        awaitState { it.ui.layer is Layer.CreateEvent }
        assertTrue(!state().ready.configResolved)
        // ...and the backend outcome lands when the fire-and-forget DELETE does. Leaving is RENAME-ONLY
        // (capability `event-leave-endpoint`): the device is departed, but the event and its bytes are RETAINED
        // until the nightly sweep reclaims them (capability `scheduled-cleanup`).
        eventually(read = { deviceJson("backend/departed", "event" to event) }) {
            it.getValue("departed").jsonPrimitive.boolean
        }
        assertTrue(deviceJson("backend/event", "event" to event).getValue("registered").jsonPrimitive.boolean)
        assertTrue(objects().isNotEmpty(), "bytes are not collected by a leave")
    }

    @Test
    fun upload_only_uploads_own_but_imports_no_foreign() = rigTest {
        createAndJoin("direction" to "upload")
        addPhoto("A")
        foreignDevice("DEV-F", "FQ")

        uploadAll()
        assertTrue(primaryKey("A") in objects(), "upload-only still uploads own photos")

        // The download arm is gated off: reconcile is a no-op, so nothing foreign is enqueued or imported.
        val before = libraryTotal()
        downloadAll()
        assertEquals(before, libraryTotal(), "upload-only imports no foreign photos")
        assertEquals(0, state().download.total)

        // Both arrows hidden → In sync; the zero download total flows through the download gate.
        refresh()
        awaitInSync()
    }

    @Test
    fun download_only_imports_foreign_and_reads_in_sync_through_a_zero_total() = rigTest {
        createAndJoin("direction" to "download")
        addPhoto("A") // an un-uploaded own photo remains in the library
        foreignDevice("DEV-F", "FQ")

        val before = libraryTotal()
        downloadAll()
        assertEquals(before + 1, libraryTotal(), "download-only imports foreign photos")

        // DRIVE the upload arm: an assertion with no cycle above it would pass whether or not the gate existed.
        cycle()
        assertTrue(objects().isEmpty(), "download-only uploads nothing")

        // "In sync" because N is 0 — NOT because an arrow is masked: were the total to count the un-uploaded
        // photo, the upload arrow would show and this would fail.
        refresh()
        awaitInSync()
    }

    @Test
    fun download_only_uploads_nothing_when_the_cycle_actually_runs() = rigTest {
        // THE PRIVACY INVARIANT (capability `upload-lifecycle`). The join gate promises "Only receive the event's
        // photos — you won't share yours". On the app-driven tier the APP invokes the cycle — foreground entry, the
        // heartbeat, a silent push — and every one of those reaches exactly this call.
        val event = createAndJoin("direction" to "download")
        addPhoto("A")

        cycle()
        completeJobs() // a no-op once the gate holds: no such job exists
        cycle()

        assertEquals(0, jobs().created, "download-only must create no upload job — the member was promised they would share nothing")
        assertTrue(objects().isEmpty(), "download-only must upload no bytes")
        // The union leak, distinct from the bytes: a manifest listing the member's assets offers them to every
        // other member (capability `photo-selection-policy`, "One policy gates both byte upload and manifest listing").
        assertTrue(manifest(event).isNullOrEmpty(), "download-only must list no asset in its device manifest")
    }

    @Test
    fun leaving_flips_the_screen_before_the_backend_delete_completes() = rigTest {
        val event = createAndJoin()
        // The backend holds the leave's DELETE open: the backend that has not answered yet.
        device("backend/hold-leave")

        user("leave")

        // The screen leaves the joined layer immediately — even though the DELETE is still pending.
        awaitState { it.ui.layer is Layer.CreateEvent }
        assertTrue(!state().ready.configResolved)
        assertTrue(
            !deviceJson("backend/departed", "event" to event).getValue("departed").jsonPrimitive.boolean,
            "the backend has not answered, and the screen did not wait for it",
        )

        // Once the backend answers, the departure lands.
        device("backend/release-leave")
        eventually(read = { deviceJson("backend/departed", "event" to event) }) {
            it.getValue("departed").jsonPrimitive.boolean
        }
        assertIs<Layer.CreateEvent>(state().ui.layer)
    }
}
