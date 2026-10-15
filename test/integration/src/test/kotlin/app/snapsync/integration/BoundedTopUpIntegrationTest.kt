package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A backlog larger than the platform's job limit drains, each cycle creating until the platform refuses — over the
 * **real** stack (the same `snapSyncApp` core the device shells call), driven through the control protocol.
 *
 * Resolving a ledger row to an uploadable resource is a synchronous platform round-trip that nothing can interrupt
 * (measured on device, SE2 / iOS 26.6, 2026-09-22: ~4.5 ms per request + ~3.45 ms per photo). The cycle therefore
 * resolves admitted rows one at a time and stops at the first refusal, so a refusal wastes the one resolve of the
 * row it refused — never the backlog. No capacity is asked of the platform: its refusal is the only signal
 * (decision records `changes/both-uploaders-active` D9, `changes/selection-is-the-walk` D5).
 *
 * The resolve counts themselves — at most one wasted resolve per refusal, and one resolve for a cycle whose every
 * slot is busy — are PhotoKit call counts with nothing outside the app to observe them; they are `UploadCycleTest`'s
 * `a_refusal_stops_the_pass_with_no_further_resolve` and `a_full_platform_reports_work_remaining`.
 */
@Verifies(
    spec = "delivery",
    requirement = "A photo is never lost on the way",
    scenario = "A large backlog drains in steps",
)
class BoundedTopUpIntegrationTest {

    @Test
    fun a_backlog_drains_across_cycles_stopping_at_each_refusal() = behaviour {
        val assets = listOf("A", "B", "C", "D", "E", "F")
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("two slots", act = { uploadQueue.jobLimit(2) }, until = {}) // far fewer slots than there is work
            photos(*assets.toTypedArray())
        }

        // The backlog drains two at a time, the OS completing whatever the platform accepted each round.
        val enqueued = mutableListOf<String>()
        repeat(assets.size / 2) { round ->
            var answer = ""
            lateinit var cycled: Seen<WorldEvent.Settled>
            whenever { answer = cycle() }
            then {
                // A cycle never creates past the platform's refusal.
                times(2) { event<WorldEvent.UploadJobCreated>() }
                cycled = settled { it.state.inFlight?.jobs?.size == 2 }
            }
            assertEquals("processing", answer, "round ${round + 1} leaves work in flight")
            enqueued += cycled.value.state.inFlight!!.jobs

            whenever { uploadQueue.completeJob() }
            then { times(2) { event<WorldEvent.BytesStored>() } }
        }

        // The backlog really did span several cycles, and the one after it has nothing left to start.
        var answer = ""
        whenever { answer = cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
        assertEquals("completed", answer)
        assertEquals(
            assets.map(::primaryJob),
            enqueued.sorted(),
            "every asset was enqueued, once — and every one landed",
        )
    }
}
