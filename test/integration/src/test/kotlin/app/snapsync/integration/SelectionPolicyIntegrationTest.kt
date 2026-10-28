package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.WorldEvent
import app.snapsync.testids.testAssetId
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * The selection policy over the **real** stack: the real `UploadCycle`, engine, ledger and device-manifest producer
 * over the backend mock, with only PhotoKit faked — driven through the control protocol.
 *
 * Each test asserts the consequences an exclusion must have, because getting one and missing another is exactly
 * the failure mode this change exists to close:
 *
 *  1. **No bytes.** No upload job, no object on the backend.
 *  2. **Not in the manifest** — so it never enters the event union and no other member downloads it. This is the
 *     one that used to leak: the manifest hook was fed the raw discovery.
 */
class SelectionPolicyIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A screenshot is never shared",
    )
    fun a_screenshot_is_neither_uploaded_nor_shared() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM") // an ordinary camera photo
            photoTaken("SHOT", LibraryPhotoKind.SCREENSHOT) // …and a screenshot taken at the same event
        }

        // 1. No bytes: only the camera photo got a job.
        whenever { cycle() }
        then { onlyCameraJob(excluded = "SHOT") }

        // 2. Not in the manifest → never in the event union, so no other member ever sees it. The manifest lists what
        //    landed once the cycle that records the completion re-projects: complete the admitted job and invoke again.
        whenever { cameraPhotoLanded() }
        then { onlyCameraShared(excluded = "SHOT") } // only the camera photo reaches the device manifest and the union
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A photo in a WhatsApp album is excluded",
    )
    fun a_whatsapp_album_photo_is_neither_uploaded_nor_shared() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM")
            photoTaken("WA")
            step("WA saved by WhatsApp, not taken here", act = { library.placeIn("WhatsApp", "WA") }, until = {
                settled { true }
            })
        }

        whenever { cycle() }
        then { onlyCameraJob(excluded = "WA") }

        whenever { cameraPhotoLanded() }
        then { onlyCameraShared(excluded = "WA") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A screen recording is never shared",
    )
    fun a_screen_recording_is_neither_uploaded_nor_shared() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM")
            photoTaken("REC", LibraryPhotoKind.SCREEN_RECORDING)
        }

        whenever { cycle() }
        then { onlyCameraJob(excluded = "REC") }

        whenever { cameraPhotoLanded() }
        then { onlyCameraShared(excluded = "REC") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "An ordinary animated GIF is excluded",
    )
    fun an_ordinary_gif_falls_below_the_image_floor() = behaviour {
        // Excluded by its size, not its type: the library reports a messenger's GIF at 480×270, under the 3 MP floor.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM")
            photoTaken("GIF", LibraryPhotoKind.GIF)
        }

        whenever { cycle() }
        then { onlyCameraJob(excluded = "GIF") }

        whenever { cameraPhotoLanded() }
        then { onlyCameraShared(excluded = "GIF") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A photo in a user album named \"Screenshots\" is excluded",
    )
    fun a_photo_in_an_album_the_member_named_screenshots_is_neither_uploaded_nor_shared() = behaviour {
        // An iPhone's album is a collection: the member filing a camera photo in one of their own does not make it a
        // screenshot — the album's title alone excludes it.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM")
            photoTaken("FILED")
            step("FILED in an album named Screenshots", act = { library.placeIn("Screenshots", "FILED") }, until = {
                settled { true }
            })
        }

        whenever { cycle() }
        then { onlyCameraJob(excluded = "FILED") }

        whenever { cameraPhotoLanded() }
        then { onlyCameraShared(excluded = "FILED") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A compressed image received from a messenger is excluded",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A 1080p video is shared",
    )
    fun a_compressed_received_image_is_excluded_but_a_1080p_recording_is_not() = behaviour {
        // The single most dangerous line in this policy: 1080p video is 2.07 MP, BELOW the 3 MP image floor.
        // If the floors were shared, every video anyone recorded at the event would silently disappear.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("WA", LibraryPhotoKind.LOW_RES) // 1600x1200 = 1.9 MP → excluded
            photoTaken("CLIP", LibraryPhotoKind.HD_VIDEO) // 1920x1080 = 2.07 MP → ADMITTED
            photoTaken("CAM")
        }

        whenever { cycle() }
        then {
            times(2) { event<WorldEvent.UploadJobCreated>() }
            settled {
                it.state.inFlight?.jobs?.mapTo(mutableSetOf(), ::assetIdOfJob) == setOf(testAssetId("CAM"), testAssetId("CLIP"))
            }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Media that is certainly not an event photo is excluded",
        scenario = "A video just under the floor's pixel count is shared",
    )
    fun a_narrow_video_with_more_pixels_than_720p_is_shared() = behaviour {
        // The video floor counts pixels: 1100×900 is narrower than 1280 but holds more pixels than 1280×720.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("NARROW", LibraryPhotoKind.NARROW_VIDEO)
        }

        whenever { cycle() }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { it.state.inFlight?.jobs?.mapTo(mutableSetOf(), ::assetIdOfJob) == setOf(testAssetId("NARROW")) }
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "What the member is told is shared is exactly what is shared")
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "An excluded screenshot does not hold the status open",
    )
    fun the_status_total_excludes_what_the_cycle_refuses_so_the_screen_reaches_in_sync() = behaviour {
        // The two components enumerate INDEPENDENTLY. If the total counted the screenshot the cycle will never
        // upload, completeness would peg below 100% and the joined screen would say "pending" forever — so the
        // screen reaching In sync once the one admitted photo lands IS the total excluding the other two.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("CAM")
            photoTaken("SHOT", LibraryPhotoKind.SCREENSHOT)
            photoTaken("WA", LibraryPhotoKind.LOW_RES)
        }

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } } // the admitted photo is outstanding

        // Drive the admitted photo all the way to landed, and the screen reports fully in sync.
        whenever {
            cycle()
            uploadQueue.completeJob()
            cycle()
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            event<WorldEvent.ManifestPublished> { "CAM" in it.added }
            onlyCameraShared(excluded = "SHOT")
            never<WorldEvent.ManifestPublished> { "WA" in it.added }
            never<WorldEvent.BytesStored> { it.asset == "WA" }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * The camera photo's is the one job created, and the device manifest declares it as it goes — [excluded] is
     * declared nowhere.
     */
    private fun Expectations.onlyCameraJob(excluded: String) {
        times(1) { event<WorldEvent.UploadJobCreated>() }
        event<WorldEvent.ManifestPublished> { "CAM" in it.added }
        never<WorldEvent.ManifestPublished> { excluded in it.added }
        settled { it.state.inFlight?.jobs == listOf(primaryJob("CAM")) }
    }

    /** The OS lands the camera photo's job, and the next cycle records the completion and publishes. */
    private suspend fun Acts.cameraPhotoLanded() {
        uploadQueue.completeJob(primaryJob("CAM"))
        cycle()
    }

    /**
     * The camera photo's bytes are stored and the device manifest keeps declaring it, so the event's union serves it;
     * [excluded] reaches neither.
     */
    private fun Expectations.onlyCameraShared(excluded: String) {
        event<WorldEvent.BytesStored> { it.asset == "CAM" }
        never<WorldEvent.ManifestPublished> { "CAM" in it.withdrawn }
        never<WorldEvent.BytesStored> { it.asset == excluded }
        never<WorldEvent.ManifestPublished> { excluded in it.added }
    }
}
