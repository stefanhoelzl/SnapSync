package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import app.snapsync.model.JoinResult

/**
 * The vocabulary the join split introduced.
 *
 * Enumerating [JoinResult] looks like testing the language until you ask what it is FOR: the whole point
 * of the split is that "the event is full" and "the network blipped" stop being the same answer, so the
 * set of answers is the contract. A member silently added or removed here changes what the join surface
 * can say (capability `join-event`).
 */
class JoinSeamsTest {

    @Test
    fun a_join_has_exactly_four_answers() {
        assertEquals(
            listOf("JOINED", "EVENT_FULL", "EVENT_NOT_FOUND", "FAILED"),
            JoinResult.entries.map { it.name },
            "the join surface renders one screen per answer; adding one silently leaves it unrendered",
        )
    }
}
