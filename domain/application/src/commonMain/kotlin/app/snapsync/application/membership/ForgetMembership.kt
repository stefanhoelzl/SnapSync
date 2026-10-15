package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.MembershipRepository

/**
 * Forget the membership locally, telling no backend — a device starting from nothing, its membership belonging to a
 * backend it no longer talks to (the control channel's reset, a rig build only).
 */
class ForgetMembership(private val membership: MembershipRepository) {
    @EntryPointCall
    operator fun invoke() = membership.forget()
}
