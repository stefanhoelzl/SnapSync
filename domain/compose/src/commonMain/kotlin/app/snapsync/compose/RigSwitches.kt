package app.snapsync.compose

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UploaderPin

/**
 * The runtime inputs a **shipped build cannot carry**. Each one's only
 * non-inert writer is the control channel (the rig's boot hook on a device, its JVM host over a world), whose
 * source is compiled only into a build made with `-Psnapsync.rig=true`. So on a production build they hold
 * their inert answers forever — structurally, not by a runtime check.
 *
 * [uploaderPin] is read fresh at every use (the channel changes it live); [inviteLinkHints] is a value, fixed
 * when the root composes — the rig's boot hook assigns it at image load, before anything forces the graph.
 *
 * Bundled because they are one kind of thing, and because a root states them together.
 */
class RigSwitches(
    /** The per-uploader switch. **Always `null` in a production build**, so what a shipped process uploads with
     *  is a function of the device and its grant (decision record `changes/both-uploaders-active`, D8). */
    val uploaderPin: () -> UploaderPin?,
    /** Whether the join gate acts on an invite link's dev/test hints (`autoJoin` + its overrides). **Always
     *  [InviteLinkHints.Ignored] in a production build**, so no crafted link can join, switch or start sharing
     *  without the member confirming (capability `join-event`, "Joining happens only on confirmation"). */
    val inviteLinkHints: InviteLinkHints,
)
