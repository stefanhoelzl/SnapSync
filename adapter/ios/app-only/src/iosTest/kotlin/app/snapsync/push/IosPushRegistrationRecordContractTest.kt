package app.snapsync.push

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PushRegistrationRecordContract
import app.snapsync.contracts.PushRegistrationRecordState
import app.snapsync.contracts.verify
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * The App-Group push registration record, live (`docs/architecture.md`). Readable states get a fresh container
 * directory, seeded through the adapter's own write so the layout is the adapter's, not this test's.
 * [PushRegistrationRecordState.UNAVAILABLE] is the DEFAULT container, which this unentitled executable's App-Group
 * lookup answers with `nil` — the degraded, never-raising record the port promises.
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
            if (state == PushRegistrationRecordState.UNAVAILABLE) return Entered.Ready(IosPushRegistrationRecord())
            val dir = newTempDirectory()
            if (state == PushRegistrationRecordState.HOLDING) {
                IosPushRegistrationRecord(dir).saveLastRegistered(PushRegistrationRecordContract.seed(clauseId))
            }
            return Entered.Ready(IosPushRegistrationRecord(dir)) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the App-Group push registration record satisfies the PushRegistrationRecord contract`() =
        verify(PushRegistrationRecordContract, binding)
}
