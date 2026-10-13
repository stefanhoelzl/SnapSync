package app.snapsync.dev

import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers

/**
 * A production build's [DevControls]: inert, and never delivering (`docs/architecture.md`, "A build-time-only module
 * is contained by compilation"). No uploader is pinned and no invite-link hint is honoured, on every platform and for
 * the process's whole life — the only [DevControls] that does anything is the control channel's, which a production
 * build does not contain.
 *
 * Every event a shipped build creates is PLAIN for now, so its invite is the form every app version in use opens: an
 * encrypted event's invite is unopenable on 0.4, and the api's minimum app version cannot rise to 0.5 while 0.4 devices
 * are members of open events. Re-enabling encrypted creation flips [createsPlainEvents] back to `false`
 * (`PENDING_CLEANUPS.md`, "Re-enable encrypted creation"; decision record
 * `openspec/changes/archive/2026-10-12-defer-encrypt-by-default`).
 */
@NeverAnswers(
    Never(member = "createsPlainEvents", answer = "false"),
    Never(member = "inviteLinkHints", answer = "InviteLinkHints.Honoured"),
    Never(member = "uploaderPin", answer = "returns"),
    Never(handler = "onReset"),
)
object InertDevControls : DevControls {
    /** Registers nothing: this adapter never delivers, so there is no handler to hold. */
    @CompositionOnly
    override fun inbound(handlers: DevHandlers) = Unit

    override fun uploaderPin(): UploaderPin? = null

    override fun inviteLinkHints(): InviteLinkHints = InviteLinkHints.Ignored

    override fun createsPlainEvents(): Boolean = true

    override fun networkGraceMillis(): Long? = null
}
