package app.snapsync.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerStateTest {

    @Test
    fun `the stored names are the ledger column's values`() {
        // A ledger row stores its state by name; renaming one strands every stored row.
        assertEquals(listOf("DISCOVERED", "REQUESTED", "COMPLETED"), LedgerState.entries.map { it.name })
    }
}
