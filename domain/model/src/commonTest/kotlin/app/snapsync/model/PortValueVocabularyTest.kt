package app.snapsync.model

import kotlin.test.Test

/**
 * The small values the ports and the commands carry, pinned as their consumers read them: a sealed outcome's cases
 * are values a `when` can tell apart, and a payload case compares by its payload, because a feature decides on the
 * case and a test asserts the answer by equality.
 */
class PortValueVocabularyTest {

    @Test
    fun `a handoff to the platform was accepted or refused with a reason`() {
        assertEveryCaseDistinct(listOf(Handoff.Accepted, Handoff.Refused("no window"))) {
            when (it) {
                Handoff.Accepted -> "Accepted"
                is Handoff.Refused -> "Refused"
            }
        }
        assertValueEquality({ Handoff.Refused("no window") }, Handoff.Refused("no scene"))
    }

    @Test
    fun `a platform error compares by what it carries`() {
        assertValueEquality({ PlatformError("denied") }, PlatformError("restricted"))
    }
}
