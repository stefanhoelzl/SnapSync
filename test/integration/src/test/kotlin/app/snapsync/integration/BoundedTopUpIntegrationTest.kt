package app.snapsync.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
class BoundedTopUpIntegrationTest {

    @Test
    fun a_backlog_drains_across_cycles_stopping_at_each_refusal() = rigTest {
        createAndJoin()
        device("jobs/limit", "n" to "2") // far fewer slots than there is work
        val assets = listOf("A", "B", "C", "D", "E", "F")
        for (id in assets) addPhoto(id)

        // Drain the whole backlog two at a time, completing whatever the platform accepted each round.
        var rounds = 0
        while (cycle() == "processing" && rounds++ < assets.size * 2) {
            assertTrue(jobs().live.size <= 2, "a cycle never creates past the platform's refusal")
            completeJobs()
        }

        assertEquals(assets.size, jobs().created, "every asset was eventually enqueued, once")
        assertEquals(assets.map(::primaryKey).toSet(), objects(), "and every asset landed")
        assertTrue(rounds > 1, "the backlog really did span several cycles")
    }
}
