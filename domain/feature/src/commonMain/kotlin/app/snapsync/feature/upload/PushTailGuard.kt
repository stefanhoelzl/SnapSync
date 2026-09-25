package app.snapsync.feature.upload

import app.snapsync.ports.ConfigSource
import app.snapsync.ports.MembershipRead
import co.touchlab.kermit.Logger

/**
 * Whether a silent push's wake joins the tail — the upload arm's **active-event guard**, carried into the tail when the
 * upload arm stopped being a receiver of the push (capability `receiving-photos`, "Silent-push receive seam";
 * decision record `changes/own-work-per-wake`, D1).
 *
 * A push's own work is the download arm's; its upload work — the top-up, and under a full grant the walk and the
 * manifest publish — reaches the wake only through the tail that follows the released handler. That tail acts on the
 * device's active membership read fresh by every unit, so no push can name the event it works on; what the push can
 * still do wrong is **wake it for another event**. A locally-left event is the one that matters: leave is local-only
 * (capability `manage-membership`), so its backend membership persists and keeps pushing this device.
 *
 * So the wake joins the tail only when the pushed event is the active one, read from the membership the flow has just
 * re-read. No event configured, another event, or an **unreadable** membership each join nothing — unreadable is not
 * "no event", and it is logged as its own answer, because only it is a reason to look at the device's lock state.
 *
 * The other two guards stay where they were, orthogonal to this one: the **limited-grant read discipline** is the tail's
 * own (its walk runs only under a full grant, and its top-up resolves from the selection snapshot — capability
 * `photo-access`, "The read discipline is enforced at the mechanism, not at the trigger fan-out"), and the
 * **direction gate** is the upload cycle's entry decision (capability `background-upload`).
 */
class PushTailGuard(
    /** The membership: the active event id is read fresh at every push. */
    private val configSource: ConfigSource,
    private val log: Logger = Logger.withTag("PushTailGuard"),
) {
    /** `true` when the push for [eventId] names this device's active event, so its wake joins the tail. */
    fun joinsTail(eventId: String): Boolean {
        val active = when (val membership = configSource.membership) {
            is MembershipRead.Member -> membership.config.eventId
            MembershipRead.NotMember -> null
            MembershipRead.Unreadable -> {
                log.w { "silent push for $eventId joins no tail — the membership is unreadable right now" }
                return false
            }
        }
        val joins = eventId == active
        log.i {
            if (joins) "silent push for active event $eventId — its wake joins the tail"
            else "silent push for $eventId joins no tail (active event = $active)"
        }
        return joins
    }
}
