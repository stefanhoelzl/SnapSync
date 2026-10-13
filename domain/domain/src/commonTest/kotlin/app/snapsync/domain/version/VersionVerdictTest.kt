package app.snapsync.domain.version

import app.snapsync.domain.trust.BackendAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

/** What one backend answer says about this build (capability `app-update-required`). */
class VersionVerdictTest {
    @Test
    fun a_426_refuses_naming_the_minimum_or_none() {
        assertEquals(VersionVerdict.Refuse(VersionRefusal("0.4")), VersionVerdict.of(BackendAnswer.TooOld("0.4")))
        assertEquals(VersionVerdict.Refuse(VersionRefusal(null)), VersionVerdict.of(BackendAnswer.TooOld(null)))
    }

    @Test
    fun a_served_request_clears_and_any_other_answer_says_nothing() {
        assertEquals(VersionVerdict.Clear, VersionVerdict.of(BackendAnswer.Served))
        for (other in listOf(
            BackendAnswer.Rejected,
            BackendAnswer.Refused,
            BackendAnswer.ServerIssue,
            BackendAnswer.NoAnswer,
        )) {
            assertEquals(VersionVerdict.NoVerdict, VersionVerdict.of(other), "$other")
        }
    }
}
