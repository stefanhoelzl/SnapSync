package app.snapsync.model

/**
 * What a join commits: the loaded event's facts and the member's choices on it.
 *
 * [minPhotoDate] and [maxPhotoDate] are the member's CHOSEN range, carried raw: the share-range rule against the
 * event's `startsAt`..`endsAt` (`shareRangeFault`) is applied on the far side, inside the join use-case, so no entry
 * path can reach a provision by forgetting to check it. [deletesAt] is the loaded retention deadline, persisted
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
    /** The key the invite link carried (base64url), `null` when it carried none. Never persisted in the config. */
    val linkKey: String? = null,
    /** The loaded event's key id, `null` for a plain event — what [linkKey] must name. */
    val eventKeyId: String? = null,
    /** The day guests may join until, as the loaded details served it; `null` when not served. */
    val openUntil: EventEnd? = null,
)
