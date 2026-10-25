package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Behaviour
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.WorldEvent
import app.snapsync.testids.testAssetId
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **One admitted set, over the real stack**.
 *
 * The unit tests assert the property over one policy value. This asserts it over the composed core the device
 * shells actually run — the same `snapSyncApp`/`uploadCore`, the real `UploadCycle`, the real status source, the
 * real preview — with only PhotoKit faked, driven through the control protocol. That distinction is the whole
 * reason the original bug survived: each consumer's own unit tests passed while the four disagreed in production,
 * because nothing composed them and compared.
 *
 * The fixture is the shape that surfaced it on device: a **closed** capture window (an event whose end has passed)
 * holding a photo taken after the ceiling.
 */
@Verifies(
    spec = "photo-sharing",
    requirement = "What the member is told is shared is exactly what is shared",
)
class AdmittedSetIntegrationTest {

    private val inWindow = "2026-06-15T12:00:00Z"
    private val postCeiling = "2026-07-05T12:00:00Z"

    /** The closed window: it ends before [postCeiling]. */
    private val closedStart = "2026-06-01T00:00:00"
    private val closedEnd = "2026-06-30T00:00:00"

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A photo from after the range's end stays private",
    )
    fun a_post_ceiling_photo_reaches_no_consumer() = behaviour {
        given {
            extensionUploadsOnly()
            photoTaken("IN", date = inWindow)
            photoTaken("AFTER", date = postCeiling)
        }

        // ① the join preview
        val preview = previewOf(closedStart, closedEnd)
        assertEquals(1, preview.count(), "the preview counts only the in-window photo")
        joined(excluded = listOf("AFTER"))

        // ③ the byte upload: only the in-window photo's bytes are uploaded.
        whenever { cycle() }
        then { onlyJobs("IN", excluded = listOf("AFTER")) }

        // ④ the device manifest — the consumer that leaked. A post-ceiling asset listed here enters the event union
        //    and is offered to every other member as bytes that were never uploaded: a 404 for everyone, and
        //    invisible on the device that caused it. The manifest lists what landed once the cycle that records the
        //    completion re-projects, so complete the job, then invoke again.
        whenever { landedAndRecorded() }
        then { declared("IN", excluded = listOf("AFTER")) } // the manifest lists only the in-window photo

        // ② the status total N — the half the user can see. Counting AFTER here is what pegged the screen below
        //    100% forever, because its bytes were never going to arrive: In sync once IN has landed is N = 1.
        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    fun an_open_window_admits_the_same_photo_at_every_consumer() = behaviour {
        // The control. Without it the assertions above would pass just as well against a stack that dropped AFTER
        // for some unrelated reason — which is exactly how the original bug hid behind four green suites. An event
        // is at most 30 days long, so "open" is a window whose end lies past AFTER.
        given {
            extensionUploadsOnly()
            photoTaken("IN", date = inWindow)
            photoTaken("AFTER", date = postCeiling)
            step("the event is running, both photos taken", act = { advanceTo("2026-07-06T12:00:00Z") }, until = {
                settled { true }
            })
        }

        val preview = previewOf("2026-06-10T00:00:00", "2026-07-09T00:00:00")
        assertEquals(2, preview.count())
        joined()

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }

        whenever { cycle() }
        then {
            times(2) { event<WorldEvent.UploadJobCreated>() }
            event<WorldEvent.ManifestPublished> { "IN" in it.added }
            event<WorldEvent.ManifestPublished> { "AFTER" in it.added }
            settled {
                it.state.inFlight?.jobs?.mapTo(mutableSetOf(), ::assetIdOfJob) == setOf(testAssetId("IN"), testAssetId("AFTER"))
            }
        }

        whenever { landedAndRecorded() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "IN" }
            event<WorldEvent.BytesStored> { it.asset == "AFTER" }
            never<WorldEvent.ManifestPublished> { it.withdrawn.isNotEmpty() }
        }

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Media that is certainly not an event photo is excluded")
    fun the_origin_exclusions_reach_every_consumer_too() = behaviour {
        // The ceiling is the bound that drifted, but the property is about the SET, not about one rule: a
        // screenshot and a sub-floor image must be absent from all four answers just as firmly.
        val excluded = listOf("SHOT", "WA")
        given {
            extensionUploadsOnly()
            photoTaken("CAM", date = inWindow)
            photoTaken("SHOT", LibraryPhotoKind.SCREENSHOT, inWindow)
            photoTaken("WA", LibraryPhotoKind.LOW_RES, inWindow)
        }

        val preview = previewOf(closedStart, closedEnd)
        assertEquals(1, preview.count())
        joined(excluded)

        whenever { cycle() }
        then { onlyJobs("CAM", excluded) }

        // No excluded asset reaches the event union.
        whenever { landedAndRecorded() }
        then { declared("CAM", excluded) }

        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The create form for an event running [startsAt]..[endsAt], confirmed: the join gate's preview, once counted. */
    private suspend fun Behaviour.previewOf(
        startsAt: String,
        endsAt: String,
    ): Seen<Layer.JoiningEvent> {
        lateinit var preview: Seen<Layer.JoiningEvent>
        whenever { createEvent(Rig.EVENT_NAME, startsAt, endsAt) }
        then { preview = screen<Layer.JoiningEvent> { it.range?.shareCount is ShareCount.Ready } }
        return preview
    }

    /** The open join gate confirmed — and from the join on, none of [excluded] is ever declared. */
    private suspend fun Behaviour.joined(excluded: List<String> = emptyList()) {
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            neverDeclared(excluded)
            settled { true }
        }
    }

    /** The one live job is [photo]'s, and the device manifest declares it as it goes — and nothing of [excluded]. */
    private fun Expectations.onlyJobs(photo: String, excluded: List<String>) {
        times(1) { event<WorldEvent.UploadJobCreated>() }
        event<WorldEvent.ManifestPublished> { photo in it.added }
        neverDeclared(excluded)
        settled { it.state.inFlight?.jobs == listOf(primaryJob(photo)) }
    }

    /** [photo]'s bytes landed, and the device manifest keeps declaring it — and none of [excluded]. */
    private fun Expectations.declared(photo: String, excluded: List<String>) {
        event<WorldEvent.BytesStored> { it.asset == photo }
        never<WorldEvent.ManifestPublished> { photo in it.withdrawn }
        neverDeclared(excluded)
    }

    private fun Expectations.neverDeclared(excluded: List<String>) {
        if (excluded.isNotEmpty()) never<WorldEvent.ManifestPublished> { published -> excluded.any { it in published.added } }
    }

    /** The OS lands every live job, and the next cycle records the completions and publishes. */
    private suspend fun Acts.landedAndRecorded() {
        uploadQueue.completeJob()
        cycle()
    }
}

/** What the open join gate's preview counted. */
private fun Seen<Layer.JoiningEvent>.count(): Int = (value.range!!.shareCount as ShareCount.Ready).count
