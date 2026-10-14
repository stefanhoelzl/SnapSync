package app.snapsync.ports

import app.snapsync.model.JoinResult
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun a_join_has_exactly_five_answers() {
        // Unverified is the join refused for this phone's credential — its own screen, the refused step (`join-event`).
        val answers: List<JoinResult> = listOf(
            JoinResult.Joined,
            JoinResult.EventClosed,
            JoinResult.EventNotFound,
            JoinResult.Unverified,
            JoinResult.Failed(),
        )
        assertEquals(
            listOf("Joined", "EventClosed", "EventNotFound", "Unverified", "Failed"),
            answers.map {
                when (it) {
                    JoinResult.Joined -> "Joined"
                    JoinResult.EventClosed -> "EventClosed"
                    JoinResult.EventNotFound -> "EventNotFound"
                    JoinResult.Unverified -> "Unverified"
                    is JoinResult.Failed -> "Failed"
                }
            },
            "the join surface renders one screen per answer; adding one silently leaves it unrendered",
        )
    }
}
