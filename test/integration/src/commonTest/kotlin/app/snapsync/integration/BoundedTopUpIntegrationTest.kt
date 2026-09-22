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
 * interrupt (measured on device, iPhone12,8 / iOS 26.6, 2026-09-09: ~11 ms per key). The cycle therefore
 * resolves admitted rows a chunk at a time and stops at the first refusal, so the resolves a refusal wastes are
 * bounded by one chunk per cycle — never the whole backlog. No capacity is asked of the platform: its refusal is
 * the only signal (decision record `changes/both-uploaders-active`, D9).
 */
class BoundedTopUpIntegrationTest {

    /** The cycle's resolve chunk (`RESOLVE_CHUNK`, internal to `:domain:feature`). */
    private val chunk = 4

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
            w.discovery.resolvedKeyCount - w.platform.created.size <= (rounds + 1) * (chunk - 1),
            "each cycle wastes at most one chunk of resolves at its refusal",
        )
    }

    @Test
    fun a_cycle_with_every_slot_busy_resolves_at_most_one_chunk_and_reports_work_remaining() = worldTest {
        val w = World(this)
        w.provision("E")
        w.platform.jobLimit = 1
        for (id in listOf("A", "B", "C", "D", "E", "F")) w.addOwnAsset(id)

        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        val resolvedWhileFilling = w.discovery.resolvedKeyCount

        // The one slot is occupied and nothing has completed. The ledger still holds the rest.
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle(), "backpressure, not an absence of work")
        assertTrue(
            w.discovery.resolvedKeyCount - resolvedWhileFilling <= chunk,
            "a full platform costs one chunk of resolves, never the backlog",
        )
    }
}
