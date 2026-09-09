package app.snapsync.integration

import app.snapsync.ports.CycleResult
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backlog larger than the platform's capacity drains without ever resolving a row the platform would
 * have refused — over the **real** stack (the same `snapSyncApp` core the device shells call).
 *
 * Resolving a ledger row to an uploadable resource is a synchronous platform round-trip that nothing can
 * interrupt, so a row read past what the platform will accept is uninterruptible time spent on a job that
 * is never created. Measured on device (iPhone12,8 / iOS 26.6, 2026-09-09): 54 ms to resolve sixteen keys
 * against a cap of four, where one key costs 11 ms — and a cycle with every slot busy resolved sixteen to
 * create none.
 *
 * The assertion is deliberately a **count with repeats**, not a set: the waste this removes is repeated
 * work on the same rows across successive cycles, which a set of distinct keys hides completely.
 */
class BoundedTopUpIntegrationTest {

    @Test
    fun a_backlog_drains_without_resolving_a_row_the_platform_would_refuse() = worldTest {
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
        // THE POINT. One resolve per creation, across the whole drain. Before the bound, each cycle read up
        // to the fixed batch and resolved every row in it to create at most `jobLimit` of them, paying the
        // platform round-trip again for the same leftovers on the next cycle, and again on the one after.
        assertEquals(
            w.platform.created.size,
            w.platform.resolvedKeyCount,
            "no row was resolved for a job the platform would not take",
        )
        assertTrue(rounds > 1, "the backlog really did span several cycles")
    }

    @Test
    fun a_cycle_with_every_slot_busy_makes_no_platform_read_and_still_reports_work_remaining() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 1
        w.addOwnAsset("A")
        w.addOwnAsset("B")

        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        val resolvedWhileFilling = w.platform.resolvedKeyCount

        // The one slot is occupied and nothing has completed. The ledger still holds B.
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle(), "backpressure, not an absence of work")
        assertEquals(
            resolvedWhileFilling,
            w.platform.resolvedKeyCount,
            "a full platform costs no platform round-trip at all",
        )
    }
}
