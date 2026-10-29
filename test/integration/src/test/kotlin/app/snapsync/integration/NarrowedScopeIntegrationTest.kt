package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

/**
 * **A narrowing takes effect on the bytes, not only on the manifest**, over the real stack: the composed core, the real
 * `UploadCycle`, the real `ReconfigureEvent` behind the settings' taps (`Person.reconfigure`), with only PhotoKit faked.
 *
 * Every other selection test reasons about a policy that was already in force when a resource was discovered. This
 * one changes the policy *after* the rows exist, which is the hole the defect shipped through: a ledger row records
 * that the policy admitted its asset **when the row was written**, and `enqueue` used to treat that read as the
 * admitted set. A member who joined wide, let the walk run, and then raised their cutoff kept uploading the photos
 * they had just excluded — at 16 rows a cycle, for as long as the backlog took to drain, against a surface that had
 * shown them a smaller count.
 *
 * The fixture forges the backlog the way a device does: the OS refuses jobs for one cycle (a job limit of 0), so
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

    /** The member raises the cutoff on the joined membership through the settings surface, and the screen shows it. */
    private suspend fun Acts.narrow() {
        reconfigure(Choices(cutoff = narrow))
        val from = Instant.parse(narrow).toLocalDateTime(zone)
        awaitScreen("the narrowed range") { ui -> (ui.layer as? Layer.Joined)?.membership?.sharesFrom == from }
    }

    /**
     * A backlog the wide policy recorded and no cycle has drained: the OS accepts no job, so a cycle records what its
     * walk found — declared in the manifest — and enqueues nothing. Then the OS takes jobs again.
     */
    private suspend fun Given.backlogRecorded(vararg ids: String) {
        step("no slot", act = { uploadQueue.jobLimit(0) }, until = {})
        step("the backlog recorded", act = { cycle() }, until = {
            ids.forEach { id -> event<WorldEvent.ManifestPublished> { id in it.added } } // recorded under the wide cutoff
            never<WorldEvent.UploadJobCreated>() // nothing was enqueued yet
        })
        step("slots again", act = { uploadQueue.jobLimit() }, until = {})
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Only photos inside the range the member chose are shared")
    fun raising_the_cutoff_stops_uploading_the_rows_the_wider_one_recorded() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("EARLY", date = early)
            photoTaken("LATE", date = late)
            backlogRecorded("EARLY", "LATE")
            // The member narrows. EARLY is now outside the membership's scope.
            step("narrowed", act = { narrow() }, until = { settled { true } })
        }

        // The excluded row costs no job — the narrowing reaches the bytes, not only the manifest.
        whenever { cycle() }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { it.state.inFlight?.jobs == listOf(primaryJob("LATE")) }
        }

        // …and it keeps not uploading, cycle after cycle: no excluded asset's bytes ever reach the backend.
        whenever {
            uploadQueue.completeJob(primaryJob("LATE"))
            cycle()
            cycle()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "LATE" }
            never<WorldEvent.BytesStored> { it.asset != "LATE" }
            never<WorldEvent.UploadJobCreated>()
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Only photos inside the range the member chose are shared")
    fun excluded_rows_do_not_starve_admitted_work() = behaviour {
        // The one way to make this worse than the bug: admit AFTER a bounded read. Rows needing a job come back in
        // a stable key order, so an excluded backlog at the front would fill a bounded read on every cycle and the
        // admitted row further down would never be reached — a permanent, silent stall.
        // More excluded rows than several resolve chunks, all sorting AHEAD of the admitted one by key.
        val excluded = (1..20).map { i -> "A${i.toString().padStart(2, '0')}" }
        given {
            extensionUploadsOnly()
            joinedEvent()
            excluded.forEach { photoTaken(it, date = early) }
            photoTaken("Z01", date = late)
            backlogRecorded(*(excluded + "Z01").toTypedArray()) // every asset was recorded under the wide cutoff
            step("narrowed", act = { narrow() }, until = { settled { true } })
        }

        // The admitted row is enqueued even though 20 excluded rows sort ahead of it.
        whenever { cycle() }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { it.state.inFlight?.jobs == listOf(primaryJob("Z01")) }
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "What the member is told is shared is exactly what is shared")
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change takes effect as soon as it applies",
        scenario = "Narrowing withdraws listings but not received copies",
    )
    fun after_a_narrowing_the_manifest_and_the_uploaded_set_are_the_same_set() = behaviour {
        // Not "each is individually correct" — the SAME set. One policy gates both, so a narrowing that reached one
        // consumer and not the other is the defect, whatever each looks like alone.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("EARLY", date = early)
            photoTaken("LATE", date = late)
            backlogRecorded("EARLY", "LATE")
        }

        // Both were declared; from the narrowing on, the manifest declares only what the narrowed policy admits — and
        // what is declared and what is uploaded are one set.
        whenever {
            narrow()
            uploadAll()
        }
        then {
            event<WorldEvent.ManifestPublished> { "EARLY" in it.withdrawn }
            never<WorldEvent.ManifestPublished> { "EARLY" in it.added || "LATE" in it.withdrawn }
            event<WorldEvent.BytesStored> { it.asset == "LATE" }
            never<WorldEvent.BytesStored> { it.asset != "LATE" }
        }
    }
}
