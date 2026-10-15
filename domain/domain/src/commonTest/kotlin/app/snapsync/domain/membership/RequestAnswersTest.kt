package app.snapsync.domain.membership

import app.snapsync.domain.service.ServiceFault
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a member can be told about a request: each answer they can act on differently is its own, so a new one is a
 * deliberate change to what the screen must tell apart — the exhaustive readings below stop compiling until it is.
 */
class RequestAnswersTest {

    @Test
    fun `each request's answers are the ones a member acts on differently`() {
        assertEquals(
            listOf("Committed", "EventOver", "Failed", "Unverified", "WrongLink"),
            listOf(
                JoinCommit.Committed,
                JoinCommit.EventOver,
                JoinCommit.Failed(),
                JoinCommit.Unverified,
                JoinCommit.WrongLink,
            ).map { it.kind() },
        )
        assertEquals(listOf("Saved", "SaveFailed", "NotCurrent", "Refused"), SettingsResult.entries.map { it.name })
        assertEquals(
            listOf("Renamed", "InvalidName", "Failed", "EventOver"),
            listOf(RenameResult.Renamed, RenameResult.InvalidName, RenameResult.Failed(), RenameResult.EventOver)
                .map { it.kind() },
        )
    }

    @Test
    fun `a failure carries how the service failed, and none when it was not the cause`() {
        assertEquals(null, JoinCommit.Failed().fault)
        assertEquals(ServiceFault.UPDATING, JoinCommit.Failed(ServiceFault.UPDATING).fault)
        assertEquals(null, RenameResult.Failed().fault)
        assertEquals(ServiceFault.TROUBLE, RenameResult.Failed(ServiceFault.TROUBLE).fault)
    }

    private fun JoinCommit.kind(): String = when (this) {
        JoinCommit.Committed -> "Committed"
        JoinCommit.EventOver -> "EventOver"
        is JoinCommit.Failed -> "Failed"
        JoinCommit.Unverified -> "Unverified"
        JoinCommit.WrongLink -> "WrongLink"
    }

    private fun RenameResult.kind(): String = when (this) {
        RenameResult.Renamed -> "Renamed"
        RenameResult.InvalidName -> "InvalidName"
        is RenameResult.Failed -> "Failed"
        RenameResult.EventOver -> "EventOver"
    }
}
