package app.snapsync.domain.membership

import app.snapsync.domain.event.Event
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.Direction

/**
 * This device's membership of an event, as stored. Whether its photos are deleted is the type, never a flag: renaming,
 * changing the share, uploading and receiving take an [Active] one. Nothing closes, so there is no third case; and
 * "not joined" is no membership at all.
 */
sealed interface Membership {
    /** The event, as the server last served it. */
    val event: Event

    /** What this member chose for the event. */
    val share: Share

    /** The event's photos are still on the server: everything a member does with an event, it does with this. */
    data class Active(override val event: Event, override val share: Share) : Membership

    /**
     * The server confirmed the event's photos deleted. The membership stays, shown as over, until the member leaves.
     */
    data class Deleted(override val event: Event, override val share: Share) : Membership
}

/**
 * What this member chose for the event: the capture dates they share ([range] — checked against the event when it was
 * chosen), whether they share and receive ([direction]), and whether received photos go into an album. Only a settings
 * change replaces it.
 */
data class Share(val range: CaptureRange, val direction: Direction, val saveToAlbum: Boolean)
