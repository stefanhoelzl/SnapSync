package app.snapsync.integration

import app.snapsync.ports.CycleResult
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backlog larger than the platform's job limit drains, each cycle creating until the platform refuses — over
 * the **real** stack (the same `snapSyncApp` core the device shells call).
 *
 * Resolving a ledger row to an uploadable resource is a synchronous platform round-trip that nothing can
 * interrupt (measured on device, SE2 / iOS 26.6, 2026-09-22: ~4.5 ms per request + ~3.45 ms per photo). The
 * cycle therefore resolves admitted rows one at a time and stops at the first refusal, so a refusal wastes the
 * one resolve of the row it refused — never the backlog. No capacity is asked of the platform: its refusal is
 * the only signal (decision records `changes/both-uploaders-active` D9, `changes/selection-is-the-walk` D5).
 */
class BoundedTopUpIntegrationTest {

    @Test
    fun a_backlog_drains_across_cycles_stopping_at_each_refusal() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 2 // far fewer slots than there is work
        val assets = listOf("A", "B", "C", "D", "E", "F")
        for (id in assets) w.addOwnAsset(id)

        // Drain the whole backlog two at a time, completing whatever the platform accepted each round.
        var completed = 0
        var rounds = 0
        while (w.runUploadCycle() == CycleResult.PROCESSING && rounds++ < assets.size * 2) {
            w.platform.created.drop(completed).forEach { w.platform.completeJob(it.filename) }
            completed = w.platform.created.size
        }

        assertEquals(
            assets.map { "$it-primary.jpg" },
            w.platform.created.map { it.filename },
            "every asset was eventually enqueued",
        )
        assertTrue(rounds > 1, "the backlog really did span several cycles")
        assertTrue(
            w.discovery.resolvedKeyCount - w.platform.created.size <= rounds + 1,
            "each cycle wastes at most the one resolve of the row it was refused",
        )
    }

    @Test
    fun a_cycle_with_every_slot_busy_resolves_one_row_and_reports_work_remaining() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 1
        for (id in listOf("A", "B", "C", "D", "E", "F")) w.addOwnAsset(id)

        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        val resolvedWhileFilling = w.discovery.resolvedKeyCount

        // The one slot is occupied and nothing has completed. The ledger still holds the rest.
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle(), "backpressure, not an absence of work")
        assertTrue(
            w.discovery.resolvedKeyCount - resolvedWhileFilling <= 1,
            "a full platform costs one resolve, never the backlog",
        )
    }
}
