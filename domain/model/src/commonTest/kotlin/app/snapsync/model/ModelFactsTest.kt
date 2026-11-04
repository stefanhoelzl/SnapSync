package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The facts the model derives: how a refusal the service sent is read and how many members an ended event waits for.
 */
class ModelFactsTest {

    @Test
    fun `an ended event waits for its active members that have not settled never fewer than none`() {
        assertEquals(2, MemberCounts(active = 3, settled = 1).waitingFor)
        assertEquals(0, MemberCounts(active = 1, settled = 2).waitingFor)
    }
}
