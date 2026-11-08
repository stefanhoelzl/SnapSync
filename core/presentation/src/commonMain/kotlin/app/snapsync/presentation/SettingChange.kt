package app.snapsync.presentation

import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction

/**
 * One settings change, as the reconfigure command takes it: the membership in effect
 * with one thing changed. The bounds are the membership's own unless the change is to the range, so a switch never
 * moves the range it does not touch.
 */
internal data class SettingChange(
    val direction: Direction,
    val from: CaptureCutoff,
    val until: CaptureCeiling,
    val saveToAlbum: Boolean,
) {
    /**
     * Whether this change withdraws photos from the event, and so is asked about first: sharing turned off, or a
     * range that starts later or ends earlier than the one [inEffect]. Widening, and everything else, applies at once.
     * Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
     */
    fun withdrawsFrom(inEffect: SettingChange): Boolean = inEffect.direction.includesUpload &&
        (!direction.includesUpload || from.at > inEffect.from.at || until.at < inEffect.until.at)

    companion object {
        /** The settings [joined] holds. */
        fun of(joined: Membership) = joined.share.let {
            SettingChange(it.direction, it.range.from, it.range.until, AlbumSwitch.isOn(it.album))
        }
    }
}
