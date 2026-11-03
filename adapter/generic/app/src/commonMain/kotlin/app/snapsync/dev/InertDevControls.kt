package app.snapsync.dev

import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.ports.DevControls
import app.snapsync.ports.UploaderPin

/**
 * A production build's [DevControls]: inert (`docs/architecture.md`, "A build-time-only module
 * is contained by compilation"). No uploader is pinned, on every platform and for
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
    Never(member = "uploaderPin", answer = "returns"),
)
object InertDevControls : DevControls {
    override fun uploaderPin(): UploaderPin? = null

    override fun createsPlainEvents(): Boolean = true
}
