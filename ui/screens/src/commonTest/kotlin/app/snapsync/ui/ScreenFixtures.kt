package app.snapsync.ui

import app.snapsync.model.AlbumStyle
import app.snapsync.model.JoinedEvent
import app.snapsync.model.RangeChoice
import app.snapsync.model.RangeView
import app.snapsync.model.ShareCount
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * What a member chose on a participation surface — a screen test's input to [over], which states the range the
 * reduction would show for it. Stated, never re-derived: `RangeResolutionTest` owns whether the rules are right, and a
 * screen test asserts what is drawn from the answer.
 */
internal data class Choices(
    val shareOn: Boolean = true,
    val receiveOn: Boolean = true,
    val saveToAlbum: Boolean = true,
    val albumStyle: AlbumStyle = AlbumStyle.COLLECTION,
    val preset: RangeChoice = RangeChoice.WHOLE_EVENT,
    val customFrom: LocalDateTime? = null,
    val customUntil: LocalDateTime? = null,
) {
    /** These choices over the window `[start, end]`, at [now]. */
    fun over(
        start: LocalDateTime,
        end: LocalDateTime,
        now: LocalDateTime,
        count: ShareCount = ShareCount.Unavailable,
        today: LocalDate = now.date,
    ): RangeView = RangeView(
        shareOn = shareOn,
        receiveOn = receiveOn,
        saveToAlbum = saveToAlbum,
        albumStyle = albumStyle,
        preset = preset,
        from = when (preset) {
            RangeChoice.WHOLE_EVENT -> start
            RangeChoice.FROM_NOW -> now
            RangeChoice.CUSTOM -> customFrom ?: start
        },
        until = if (preset == RangeChoice.CUSTOM) customUntil ?: end else end,
        windowStart = start,
        windowEnd = end,
        nowAvailable = now in start..end,
        today = today,
        commitEnabled = shareOn || receiveOn,
        shareCount = count,
    )
}

/** A membership as the joined screen states it; its own range is the event's unless a test says otherwise. */
internal fun joinedEvent(
    name: String,
    startsAt: LocalDateTime,
    endsAt: LocalDateTime,
    eventId: String = "11111111-1111-4111-8111-111111111111",
    sharesFrom: LocalDateTime = startsAt,
    sharesUntil: LocalDateTime = endsAt,
    shares: Boolean = true,
    receives: Boolean = true,
    savesToAlbum: Boolean = false,
) = JoinedEvent(eventId, name, startsAt, endsAt, sharesFrom, sharesUntil, shares, receives, savesToAlbum)
