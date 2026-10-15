package app.snapsync.domain.membership

import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventId
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction

/**
 * What a member confirmed on the join screen: [event] as the server served it when the screen loaded, the [invite] that
 * opened it, and their choices on it — carried raw: the share-range rule is applied by the join itself, so no screen
 * can reach a membership by forgetting it.
 */
data class JoinChoice(
    val event: Event,
    val invite: InviteLink,
    val from: CaptureCutoff,
    val until: CaptureCeiling,
    val direction: Direction,
    val saveToAlbum: Boolean,
)

/** What a member chose in [event]'s settings — carried raw, like a [JoinChoice]'s. */
data class ShareChoice(
    val event: EventId,
    val from: CaptureCutoff,
    val until: CaptureCeiling,
    val direction: Direction,
    val saveToAlbum: Boolean,
)
