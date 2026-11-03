package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.gallery.FullGrantRead
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.OsUploader
import app.snapsync.domain.upload.Registration
import app.snapsync.domain.upload.UploaderArming
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.inbound.Log

private val log = Log("ReconcileUploader")

/**
 * **What a membership transition, or the app's launch, does to the two uploaders** ([UploaderChange]): the extension
 * registered where the change calls for it — with a full grant in hand, read now — and whether the app's own uploader
 * is armed. Holds nothing: every decision is derived afresh. A change that needs a membership while none can be read
 * defers — acting on "could not read it" as "not joined" is a false leave, and as "joined" arms for an event it cannot
 * name — and the next transition reads it again.
 */
class ReconcileUploader(
    private val membership: MembershipRepository,
    private val osUploader: OsUploader,
    private val grant: GrantRead,
    private val registrar: ExtensionRegistrar,
) {
    @EntryPointCall
    suspend operator fun invoke(change: UploaderChange): UploaderArming? = when (change.needsMembership && !joined()) {
        true -> null
        false -> reconciled(change)
    }

    private suspend fun reconciled(change: UploaderChange): UploaderArming {
        when (change.registration(osUploader.admission(), osUploader.standing())) {
            Registration.REGISTER -> registered()
            Registration.LEAVE -> Unit
        }
        return UploaderArming.of(grant.access())
    }

    private suspend fun registered() = when (val full = grant.full()) {
        is FullGrantRead.Full -> registrar.register(full.grant)
        FullGrantRead.NotFull -> log.i("the grant narrowed before the extension could be registered")
    }

    private suspend fun joined(): Boolean = when (membership.read()) {
        is MembershipRead.Joined -> true
        is MembershipRead.NotJoined -> false
        is MembershipRead.Unavailable -> false.also {
            log.w(
                "the membership is unreadable — deferring this transition",
            )
        }
    }
}
