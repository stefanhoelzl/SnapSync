package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.UpdateResult
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.Share
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict

/**
 * Change what the member shares of the joined event — the range, the direction, the album — without leaving: saved
 * all or nothing, with the manifest version, so nothing acts on settings that never landed. The range obeys the
 * share-range rule, as at the join; one that breaks it is refused, never clamped.
 */
class ReconfigureEvent(private val membership: MembershipRepository) {
    @EntryPointCall
    operator fun invoke(active: Membership.Active, choice: ShareChoice): ReconfigureOutcome =
        when (choice.event == active.event.id) {
            true -> ranged(active, choice)
            // The settings were opened for a membership a switch replaced meanwhile.
            false -> ReconfigureOutcome.NotCurrent
        }

    private fun ranged(active: Membership.Active, choice: ShareChoice): ReconfigureOutcome = when (
        val range = CaptureRange.of(choice.from, choice.until, active.event.range.start, active.event.range.end)
    ) {
        is CaptureRangeVerdict.Ok -> saved(
            membership.reconfigure(active, Share(range.range, choice.direction, choice.saveToAlbum)),
        )
        CaptureRangeVerdict.Inverted, CaptureRangeVerdict.TooLong, CaptureRangeVerdict.OutsideEvent ->
            ReconfigureOutcome.Refused
    }

    private fun saved(saved: UpdateResult): ReconfigureOutcome = when (saved) {
        is UpdateResult.Updated -> ReconfigureOutcome.Saved(saved.active)
        UpdateResult.Superseded -> ReconfigureOutcome.NotCurrent
        is UpdateResult.Failed -> ReconfigureOutcome.SaveFailed
    }
}

/** What a settings change came to. */
sealed interface ReconfigureOutcome {
    /** Saved: [active] is the membership with its new share. */
    data class Saved(val active: Membership.Active) : ReconfigureOutcome

    /** Nothing changed; [answer] is what the member is told. */
    sealed interface NotSaved : ReconfigureOutcome {
        val answer: SettingsResult
    }

    /** The range breaks the share-range rule: nothing changed. */
    data object Refused : NotSaved {
        override val answer = SettingsResult.Refused
    }

    /** It could not be saved: nothing changed. */
    data object SaveFailed : NotSaved {
        override val answer = SettingsResult.SaveFailed
    }

    /** The membership the settings were for is no longer the active one: nothing changed. */
    data object NotCurrent : NotSaved {
        override val answer = SettingsResult.NotCurrent
    }
}
