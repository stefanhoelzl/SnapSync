package app.snapsync.status

import app.snapsync.engine.LedgerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ObservedCompletionOverlayTest {

    private val t1 = Instant.fromEpochMilliseconds(2_000_000)

    private fun snapshot(
        completed: Int = 0,
        newestCompletionAt: Instant? = null,
        pendingByAsset: Map<String, Set<String>> = emptyMap(),
    ) = LedgerSnapshot(completed, newestCompletionAt, pendingByAsset)

    @Test
    fun `empty observation is the identity`() {
        val snap = snapshot(completed = 2, newestCompletionAt = t1, pendingByAsset = mapOf("P" to setOf("P-1", "P-2")))

        assertEquals(Overlaid(completed = 2, pending = 1, newestCompletionAt = t1), overlay(snap, emptySet()))
    }

    @Test
    fun `a photo promotes only when all its outstanding resources are observed`() {
        val snap = snapshot(completed = 2, pendingByAsset = mapOf("P" to setOf("P-photo", "P-video")))

        assertEquals(Overlaid(2, 1, null), overlay(snap, setOf("P-photo")), "partial does not promote")
        assertEquals(Overlaid(3, 0, null), overlay(snap, setOf("P-photo", "P-video")), "all observed promotes")
    }

    @Test
    fun `a stale FAILED key promotes like a REQUESTED one`() {
        // pendingByAsset holds outstanding rows regardless of REQUESTED vs FAILED — observation wins.
        val snap = snapshot(completed = 0, pendingByAsset = mapOf("F" to setOf("F-photo")))

        assertEquals(Overlaid(1, 0, null), overlay(snap, setOf("F-photo")))
    }

    @Test
    fun `the overlay never fabricates a completion timestamp`() {
        val snap = snapshot(completed = 0, newestCompletionAt = null, pendingByAsset = mapOf("P" to setOf("P-1")))

        assertEquals(null, overlay(snap, setOf("P-1")).newestCompletionAt)
    }

    @Test
    fun `sticky unions fresh and retains a released key still in the backlog`() {
        val snap = snapshot(pendingByAsset = mapOf("P" to setOf("P-1", "P-2")))

        val afterFirst = stickyRetain(previous = emptySet(), fresh = setOf("P-1"), snapshot = snap)
        assertEquals(setOf("P-1"), afterFirst)

        // P-1 released by the platform, but still outstanding in the ledger → retained.
        val afterRelease = stickyRetain(previous = afterFirst, fresh = setOf("P-2"), snapshot = snap)
        assertEquals(setOf("P-1", "P-2"), afterRelease)
    }

    @Test
    fun `sticky drops a key once it leaves the backlog`() {
        // P-1 recorded COMPLETED (gone from pendingByAsset); only the still-outstanding P-2 remains.
        val snap = snapshot(completed = 0, pendingByAsset = mapOf("P" to setOf("P-2")))

        assertEquals(setOf("P-2"), stickyRetain(previous = setOf("P-1", "P-2"), fresh = emptySet(), snapshot = snap))
    }

    @Test
    fun `sticky is empty when nothing has been observed`() {
        assertEquals(emptySet(), stickyRetain(emptySet(), emptySet(), snapshot(pendingByAsset = mapOf("P" to setOf("P-1")))))
    }
}
