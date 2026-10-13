package app.snapsync.data.backend

import app.snapsync.domain.trust.BackendAnswer
import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.ServerError
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.models.AppTooOldError
import app.snapsync.wire.models.AppTooOldErrorError
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one reading of a route's reply the credential and the version gate need: its category. */
class BackendAnswersTest {
    @Test
    fun each_reply_is_read_as_its_category() {
        assertEquals(BackendAnswer.Served, BackendAnswers.of(JoinEventReply.Joined))
        assertEquals(BackendAnswer.Rejected, BackendAnswers.of(Unauthorized))
        assertEquals(BackendAnswer.Refused, BackendAnswers.of(EventNotFound))
        assertEquals(
            BackendAnswer.TooOld("0.4"),
            BackendAnswers.of(AppTooOld(AppTooOldError(AppTooOldErrorError.APP_TOO_OLD, "0.4"))),
        )
        assertEquals(BackendAnswer.ServerIssue, BackendAnswers.of(ServerError("write failed")))
        assertEquals(BackendAnswer.NoAnswer, BackendAnswers.of(Unreachable("offline")))
        assertEquals(BackendAnswer.NoAnswer, BackendAnswers.of(Malformed("not JSON")), "an answer outside the contract")
    }
}
