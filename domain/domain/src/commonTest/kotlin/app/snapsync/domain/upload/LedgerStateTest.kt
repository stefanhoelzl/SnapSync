package app.snapsync.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerStateTest {

    @Test
    fun `the stored names are the ledger column's values`() {
        // A ledger row stores its state by name; renaming one strands every stored row.
        assertEquals(listOf("DISCOVERED", "REQUESTED", "COMPLETED"), LedgerState.entries.map { it.name })
    }

    @Test
    fun `only a discovered row needs a job, and only a completed one is done`() {
        assertEquals(listOf(LedgerState.DISCOVERED), LedgerState.needingJob)
        assertEquals(listOf(LedgerState.COMPLETED), LedgerState.done)
    }
}
