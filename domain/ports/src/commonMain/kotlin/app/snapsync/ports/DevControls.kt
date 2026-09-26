package app.snapsync.ports

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UploaderPin

/**
 * **The build's development controls** — the inputs only a test build can carry (`docs/architecture.md`, "A
 * build-time-only module is contained by compilation"). A production build's adapter answers the inert values
 * forever and never delivers: the only adapter that does anything is the control channel's, compiled into a build
 * made with `-Psnapsync.rig=true` and into no other. So a shipped process cannot be switched, structurally rather
 * than by a runtime check.
 *
 * An event port for the one command it delivers ([DevHandlers.onReset]); the two reads are read at every use.
 */
interface DevControls : Listenable<DevHandlers> {
    /**
     * The per-uploader switch, read at every use (the channel changes it live). **Always `null` in a production
     * build**, so what a shipped process uploads with is a function of the device and its grant (decision record
     * `changes/both-uploaders-active`, D8).
     */
    fun uploaderPin(): UploaderPin?

    /**
     * Whether the join gate acts on an invite link's dev/test hints (`autoJoin` + its overrides). **Always
     * [InviteLinkHints.Ignored] in a production build**, so no crafted link can join, switch or start sharing
     * without the member confirming (capability `join-event`, "Joining happens only on confirmation").
     */
    fun inviteLinkHints(): InviteLinkHints
}

/** What the development controls tell the core. Built only by a composition. */
class DevHandlers(
    /** Void this device's durable sync state without telling any backend (the channel's `POST /device/reset`). */
    val onReset: suspend () -> Unit,
)
