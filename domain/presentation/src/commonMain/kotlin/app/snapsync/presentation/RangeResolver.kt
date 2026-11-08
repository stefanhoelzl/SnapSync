package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.presentation.RangeResolution.nowWithinWindow
import app.snapsync.presentation.RangeResolution.resolve
import app.snapsync.ui.state.ShareCount

/**
 * Resolve a form against an event window, in the DEVICE's zone ([cutoffFormatter]), and seed a fresh one.
 *
 * The join gate and the settings surface reach a window by different roads — one off the loaded phase, one off the
 * persisted membership — and those roads genuinely differ. From here down the rules are identical, and a range rule
 * that held on one surface and not the other would be a bug nobody would ever see.
 */
internal class RangeResolver(private val cutoffFormatter: CutoffFormatter, private val albumContents: AlbumContents) {

    /** An untouched surface's choices on this phone: all on, the album included. */
    val freshForm: RangeForm get() = RangeForm(albumContents = albumContents)

    /** [form] over `[startsAt, endsAt]`, the count it shows being [shareCount]. */
    fun resolve(form: RangeForm, startsAt: EventStart, endsAt: EventEnd, shareCount: ShareCount): ResolvedRange {
        val resolved = form.resolve(
            windowStart = cutoffFormatter.toLocal(startsAt),
            windowEnd = cutoffFormatter.toLocal(endsAt),
            nowLocal = cutoffFormatter.nowLocal(),
            nowAvailable = nowWithinWindow(cutoffFormatter.nowCutoff(), startsAt.at, endsAt.at),
            toCutoff = cutoffFormatter::toCutoff,
            shareCount = shareCount,
        )
        val chosen = CaptureRange.of(resolved.chosenFrom, resolved.chosenUntil, startsAt, endsAt)
        return resolved.copy(chosen = (chosen as? CaptureRangeVerdict.Ok)?.range)
    }
}
