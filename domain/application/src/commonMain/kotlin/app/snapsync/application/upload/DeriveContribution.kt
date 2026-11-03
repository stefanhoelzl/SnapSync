package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.upload.ReceivedPhotos
import app.snapsync.domain.membership.Membership

/**
 * Derive what [Membership.Active] contributes on this pass — the membership's ONE policy, paired with the read the
 * grant allows — once per pass, with echo suppression: a photo this device received is never shared back. Derived
 * when the pass asks, not at its gate: a pass that crosses the event's start shares what the start admits from then
 * on.
 */
class DeriveContribution(private val photos: OwnPhotos, private val received: ReceivedPhotos) {
    @EntryPointCall
    suspend operator fun invoke(membership: Membership.Active): Contribution =
        photos.contribution(membership, received.suppressed())
}
