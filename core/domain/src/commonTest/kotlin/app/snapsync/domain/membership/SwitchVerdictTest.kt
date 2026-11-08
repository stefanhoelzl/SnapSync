package app.snapsync.domain.membership

import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.anEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opening another event's invite (decision record `changes/back-dated-events`, D8; capability `manage-membership`). */
class SwitchVerdictTest {

    @Test
    fun `an invite that cannot be joined never leaves, whatever the joined event`() {
        listOf(SwitchFacts.Deleted, SwitchFacts.Undecided, SwitchFacts.EveryoneShared).forEach { current ->
            assertEquals(SwitchVerdict.Stay, SwitchVerdict.of(current, holdsEverything = true, InviteFact.NotJoinable))
        }
    }

    @Test
    fun `an invite the read could not tell about asks`() {
        assertEquals(SwitchVerdict.Ask, SwitchVerdict.of(SwitchFacts.Deleted, true, InviteFact.Unknown))
    }

    @Test
    fun `a joinable invite leaves quietly only when leaving loses nothing`() {
        assertEquals(SwitchVerdict.Quiet, SwitchVerdict.of(SwitchFacts.Deleted, null, InviteFact.Joinable))
        assertEquals(SwitchVerdict.Quiet, SwitchVerdict.of(SwitchFacts.EveryoneShared, true, InviteFact.Joinable))
        assertEquals(SwitchVerdict.Ask, SwitchVerdict.of(SwitchFacts.EveryoneShared, false, InviteFact.Joinable))
        assertEquals(SwitchVerdict.Ask, SwitchVerdict.of(SwitchFacts.EveryoneShared, null, InviteFact.Joinable))
        assertEquals(SwitchVerdict.Ask, SwitchVerdict.of(SwitchFacts.Undecided, true, InviteFact.Joinable))
    }

    @Test
    fun `only an ended event everyone shared asks what this device holds`() {
        assertEquals(
            listOf(false, false, true),
            listOf(
                SwitchFacts.Deleted,
                SwitchFacts.Undecided,
                SwitchFacts.EveryoneShared,
            ).map { it.needsReceivedCheck },
        )
    }

    @Test
    fun `the server's read decides deleted, everyone shared, or nothing`() {
        val event = anEvent()
        assertEquals(
            SwitchFacts.Deleted,
            SwitchFacts.served(event.copy(members = MemberCounts(3, 3, 0)), completed = true),
        )
        assertEquals(SwitchFacts.EveryoneShared, SwitchFacts.served(event.copy(members = MemberCounts(3, 3, 0)), false))
        assertEquals(SwitchFacts.Undecided, SwitchFacts.served(event.copy(members = MemberCounts(3, 2, 1)), false))
        assertEquals(SwitchFacts.Undecided, SwitchFacts.served(event.copy(members = MemberCounts(3, 3)), false))
        assertEquals(SwitchFacts.Undecided, SwitchFacts.served(event.copy(members = null), false), "no counts")
    }
}
