package app.snapsync.dev

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers

/**
 * A production build's [DevControls]: inert, and never delivering (`docs/architecture.md`, "A build-time-only module
 * is contained by compilation"). No uploader is pinned and no invite-link hint is honoured, on every platform and for
 * the process's whole life — the only [DevControls] that does anything is the control channel's, which a production
 * build does not contain.
 */
object InertDevControls : DevControls {
    /** Registers nothing: this adapter never delivers, so there is no handler to hold. */
    override fun listen(handlers: DevHandlers) = Unit

    override fun uploaderPin(): UploaderPin? = null

    override fun inviteLinkHints(): InviteLinkHints = InviteLinkHints.Ignored
}
