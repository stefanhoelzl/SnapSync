package app.snapsync.push

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PushRegistrationRecordContract
import app.snapsync.contracts.PushRegistrationRecordState
import app.snapsync.contracts.verify
import app.snapsync.files.IosFiles
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * The push registration record over the App-Group files (capability `receiving-photos`): the record service over the
 * real iOS file system — the area unreachable (no App-Group entitlement in a test executable), empty, and holding.
 */
class IosPushRegistrationRecordContractTest {

    private val binding = object : Binding<PushRegistrationRecordState, PushRegistrationRecord> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            PushRegistrationRecordState.UNAVAILABLE,
            PushRegistrationRecordState.EMPTY,
            PushRegistrationRecordState.HOLDING,
        )

        override fun create(state: PushRegistrationRecordState, clauseId: String): Entered<PushRegistrationRecord> {
            if (state == PushRegistrationRecordState.UNAVAILABLE) return Entered.Ready(PushRegistrationRecord(IosFiles()))
            val dir = newTempDirectory()
            val record = PushRegistrationRecord(IosFiles(dir, null))
            if (state == PushRegistrationRecordState.HOLDING) record.saveLastRegistered(PushRegistrationRecordContract.seed(clauseId))
            return Entered.Ready(record) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the App-Group push registration record satisfies the PushRegistrationRecord contract`() =
        verify(PushRegistrationRecordContract, binding)
}
