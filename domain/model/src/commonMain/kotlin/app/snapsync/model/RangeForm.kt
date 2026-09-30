package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * A member's **uncommitted choices** on a decision surface — the join gate and the in-place reconfigure
 * ask for the same six (capability `photo-sharing`, `join-event`, `manage-membership`).
 *
 * These used to be seven `mutableStateOf`s held by each screen, declared twice with different seeds. That
 * gave them Compose's lifetime rather than the surface's, which is the wrong one: the join gate advances
 * `Ready → Committing → CommitFailed` beneath them, and a value tied to composition has already caused a
 * seeding bug there. Reduced state gives them a lifetime the type states.
 *
 * What is remembered is the PRESET, and the picked wall-clock values behind a `CUSTOM` pick — never a
 * resolved instant. Resolution happens against the event window on every reduction ([resolve]), so a
 * window that arrives late (the details fetch resolving after the surface mounts) is picked up rather
 * than baked in.
 *
 * The defaults are what an UNTOUCHED gate commits, so each is a stance. All three participation values
 * start on — including [saveToAlbum], because the album is the only on-device statement that a set of
 * photos belongs to this event, and a member who decides nothing should get that grouping (capability
 * `event-album`). The headless `autoJoin` path does NOT read these seeds and deliberately keeps its own
 * album default off; see `StatusContainerHost.autoConfirm`.
 *
 * [albumOffered] is not a choice but the platform's answer: whether this phone can hold an event album at all
 * (capability `event-album` — an Android phone cannot). Where it is `false` the surfaces show no album choice and
 * [saveToAlbum] is `false`, so what is committed says what happens.
 */
@Serializable
data class RangeForm(
    val shareOn: Boolean = true,
    val receiveOn: Boolean = true,
    val saveToAlbum: Boolean = true,
    val albumOffered: Boolean = true,
    val preset: RangeChoice = RangeChoice.WHOLE_EVENT,
    /** The picked start behind [RangeChoice.CUSTOM]; `null` resolves to the window's start. */
    val customFrom: LocalDateTime? = null,
    /** The picked end behind [RangeChoice.CUSTOM]; `null` resolves to the window's end. */
    val customUntil: LocalDateTime? = null,
)

/**
 * What a [RangeForm] resolves to once the event window is known: what would be committed, and what the
 * surface renders.
 *
 * Wall-clock values rather than formatted strings — the design system owns how a date reads (capability
 * `docs/architecture.md`), and the reduction owns what the date IS. The reduction applies the device's zone when
 * producing these, so a consumer rendering a transported state shows the device's own wall clock.
 */
@Serializable
data class ResolvedRange(
    val windowStart: LocalDateTime,
    val windowEnd: LocalDateTime,
    val from: LocalDateTime,
    val until: LocalDateTime,
    /** The same bounds in the canonical `…Z` domain — what a commit actually carries. */
    val chosenFrom: CaptureCutoff,
    val chosenUntil: CaptureCeiling,
    val direction: Direction,
    /** Both switches off is representable and does nothing, so the commit action is disabled with a reason. */
    val commitEnabled: Boolean,
    /** "From now" is offered only while the present is inside the event window. */
    val nowAvailable: Boolean,
    /**
     * How many of the member's own photos the chosen range would share (capability `join-event`).
     * Computed by the container over the user-query bundle — never by the screen — and carried here so the
     * row renders reduced state. Unavailable and zero mean different things: `Ready(0)` says the chosen
     * range admits none of their photos.
     */
    val shareCount: ShareCount = ShareCount.Counting,
)

/** The live shareable count (capability `join-event`), as the row renders it. */
@Serializable
sealed interface ShareCount {
    /** Being (re)computed — the row shows `counting…`. */
    @Serializable
    data object Counting : ShareCount

    /** No count is available (no usable grant, or the read failed) — the row is omitted entirely. */
    @Serializable
    data object Unavailable : ShareCount

    /** The chosen range would share [count] of the member's photos. */
    @Serializable
    data class Ready(val count: Int) : ShareCount
}
