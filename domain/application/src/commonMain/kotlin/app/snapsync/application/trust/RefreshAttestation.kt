package app.snapsync.application.trust

import app.snapsync.application.EntryPointCall
import app.snapsync.data.trust.AttestingCredential
import app.snapsync.data.trust.RefreshRead
import app.snapsync.domain.trust.DeviceRefusal

/**
 * Refresh the device's credential when it is due, and publish what that says about the device — at every wake the app
 * process gets (launch, foreground, a silent push, each scheduled wake), not on a schedule: the OS budgets wakes, and
 * every one the app already has is a chance to renew that no schedule could add.
 */
class RefreshAttestation(private val credential: AttestingCredential) {
    @EntryPointCall
    suspend operator fun invoke(): RefreshOutcome = when (val read = credential.refresh()) {
        RefreshRead.Fresh -> RefreshOutcome.Fresh
        RefreshRead.Obtained -> RefreshOutcome.Obtained
        is RefreshRead.NotObtained -> RefreshOutcome.NotObtained(read.refusal)
    }
}

/** What a wake's refresh came to. */
sealed interface RefreshOutcome {
    data object Fresh : RefreshOutcome

    data object Obtained : RefreshOutcome

    data class NotObtained(val refusal: DeviceRefusal?) : RefreshOutcome
}
