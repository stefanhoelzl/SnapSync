package app.snapsync.model

/**
 * What a join commits (capability `join-event`): the loaded event's facts and the member's choices on it.
 *
 * [minPhotoDate] and [maxPhotoDate] are the member's CHOSEN range, carried raw: the clamp to the event window
 * (`startsAt`..`endsAt`) is applied on the far side, inside the join use-case, so no entry path can reach a
 * provision without the floor by forgetting to clamp. [deletesAt] is the loaded retention deadline, persisted
 * verbatim.
 */
data class JoinChoice(
    val eventId: String,
    val name: String,
    val startsAt: EventStart,
    val endsAt: EventEnd,
    val deletesAt: DeletesAt,
    val minPhotoDate: CaptureCutoff,
    val maxPhotoDate: CaptureCeiling,
    val direction: Direction,
    val saveToAlbum: Boolean,
    /** Whether the membership's photos may use mobile data (capability `mobile-data`). */
    val mobileData: Boolean = true,
    /** The key the invite link carried (base64url), `null` when it carried none. Never persisted in the config. */
    val linkKey: String? = null,
    /** The loaded event's key id, `null` for a plain event — what [linkKey] must name. */
    val eventKeyId: String? = null,
)
