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
    fun an_answer_from_past_the_version_check_clears() {
        for (past in listOf(BackendAnswer.Served, BackendAnswer.Rejected, BackendAnswer.Refused)) {
            assertEquals(VersionVerdict.Clear, VersionVerdict.of(past), "$past")
        }
    }

    @Test
    fun a_server_issue_or_no_answer_says_nothing() {
        for (other in listOf(BackendAnswer.ServerIssue, BackendAnswer.Updating, BackendAnswer.NoAnswer)) {
            assertEquals(VersionVerdict.NoVerdict, VersionVerdict.of(other), "$other")
        }
    }
}
