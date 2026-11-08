package app.snapsync.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Settling a share (capability `event-lifetime`, change `settling-needs-a-real-look`): when the member's own range has
 * ended, what a publish declares after each kind of look, whether it goes out after an earlier settlement, and whether
 * the end of a wake owes a look.
 */
class SettlingTest {

    // The member's range is the event's own: 1 to 7 June.
    private val ended = SharePhase.Ended(RANGE.until.at)

    private fun lookAt(takenAt: String) = SettlingLook.Looked(
        Discovery(emptyList(), ts(takenAt), DiscoverySource.LIBRARY, 0),
    )

    @Test
    fun `the range stands at sharing until its end has passed, then ended at its end`() {
        assertEquals(SharePhase.Sharing, SharePhase.of(RANGE, ts("2026-06-06T23:00:00Z")))
        assertEquals(SharePhase.Sharing, SharePhase.of(RANGE, RANGE.until.at), "at the end itself")
        val after = assertIs<SharePhase.Ended>(SharePhase.of(RANGE, ts("2026-06-08T09:00:00Z")))
        assertEquals(RANGE.until.at, after.at)
    }

    @Test
    fun `before the end nothing settles, whatever the look`() {
        listOf(lookAt("2026-06-08T09:00:00Z"), SettlingLook.CouldNotLook, SettlingLook.NothingToLook).forEach {
            assertEquals(ShareDeclaration.Sharing, ShareDeclaration.of(SharePhase.Sharing, it))
        }
    }

    @Test
    fun `a look begun after the end settles the share on its evidence`() {
        val settled = assertIs<ShareDeclaration.Settled>(ShareDeclaration.of(ended, lookAt("2026-06-07T00:00:01Z")))
        assertEquals(RANGE.until.at, settled.evidence.ended)
    }

    @Test
    fun `a look begun at or before the end settles nothing — it may have missed the last moments`() {
        assertEquals(ShareDeclaration.Sharing, ShareDeclaration.of(ended, lookAt("2026-06-07T00:00:00Z")))
        assertEquals(ShareDeclaration.Sharing, ShareDeclaration.of(ended, lookAt("2026-06-06T22:00:00Z")))
    }

    @Test
    fun `a look that could not be made settles nothing`() {
        assertEquals(ShareDeclaration.Sharing, ShareDeclaration.of(ended, SettlingLook.CouldNotLook))
    }

    @Test
    fun `a member who shares nothing settles at the end with nothing to look at`() {
        assertIs<ShareDeclaration.Settled>(ShareDeclaration.of(ended, SettlingLook.NothingToLook))
    }

    @Test
    fun `a settled declaration always goes out`() {
        val settled = ShareDeclaration.of(ended, SettlingLook.NothingToLook)
        for (last in PublishedDeclaration.entries) {
            assertEquals(PublishVerdict.SEND, PublishVerdict.of(ended, settled, last))
        }
    }

    @Test
    fun `after the end an unproven publish never takes back a settlement sent before`() {
        val sharing = ShareDeclaration.Sharing
        assertEquals(PublishVerdict.KEEP_SETTLED, PublishVerdict.of(ended, sharing, PublishedDeclaration.SETTLED))
        assertEquals(PublishVerdict.SEND, PublishVerdict.of(ended, sharing, PublishedDeclaration.SHARING))
        assertEquals(PublishVerdict.SEND, PublishVerdict.of(ended, sharing, PublishedDeclaration.NONE))
    }

    @Test
    fun `a range that is open again sends sharing, taking a settlement back`() {
        val verdict = PublishVerdict.of(SharePhase.Sharing, ShareDeclaration.Sharing, PublishedDeclaration.SETTLED)
        assertEquals(PublishVerdict.SEND, verdict)
    }

    @Test
    fun `the end of a wake owes a look once the range has ended, until a settled share was sent`() {
        assertEquals(SettleNeed.DUE, SettleNeed.of(ended, PublishedDeclaration.NONE))
        assertEquals(SettleNeed.DUE, SettleNeed.of(ended, PublishedDeclaration.SHARING))
        assertEquals(SettleNeed.NOT_DUE, SettleNeed.of(ended, PublishedDeclaration.SETTLED))
        PublishedDeclaration.entries.forEach { assertEquals(SettleNeed.NOT_DUE, SettleNeed.of(SharePhase.Sharing, it)) }
    }
}
