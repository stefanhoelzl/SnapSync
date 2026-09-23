package app.snapsync.gallery

import app.snapsync.compose.PermissionAwareCandidateSource
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CandidateSourceContract
import app.snapsync.contracts.CandidateSourceState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Seeded
import app.snapsync.contracts.UploadDiscoveryContract
import app.snapsync.contracts.UploadDiscoveryState
import app.snapsync.contracts.verify
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The real PhotoKit reads, live, in the simulator's Kotlin/Native test executable (capability
 * `port-contracts`). That process has no bundle identifier, so no photo grant can reach it, and the only
 * state it presents is `NO_GRANT`. Every granted state runs in the simulator app instead.
 *
 * A grant here is not assumed. Each binding reads the process's real grant, and a process that turned out to
 * hold one would answer `Unreachable` for the state it declares, which the runner reports as `Failed`.
 */
class PhotoKitNoGrantContractTest {

    private val unreachable = "the Kotlin/Native test executable has no bundle identifier, so no photo grant reaches it"

    /** Whether this process holds no grant, as the adapters' own read reports it. */
    private fun holdsNoGrant(): Boolean = currentPhotoPermission().let {
        it == PermissionStatus.NOT_DETERMINED || it == PermissionStatus.DENIED
    }

    private val candidateSource = object : Binding<CandidateSourceState, Seeded<CandidateSource>> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(CandidateSourceState.NO_GRANT)

        override fun create(state: CandidateSourceState, clauseId: String): Entered<Seeded<CandidateSource>> {
            if (state != CandidateSourceState.NO_GRANT || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            return Entered.Ready(
                Seeded(
                    PermissionAwareCandidateSource(
                        permission = MutableStateFlow(currentPhotoPermission()),
                        walk = PhotoKitCandidateSource(),
                        selection = MutableStateFlow<List<Resource>?>(null),
                    ),
                ),
            )
        }
    }

    private val uploadDiscovery = object : Binding<UploadDiscoveryState, Seeded<UploadDiscovery>> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(UploadDiscoveryState.NO_GRANT)

        override fun create(state: UploadDiscoveryState, clauseId: String): Entered<Seeded<UploadDiscovery>> {
            if (state != UploadDiscoveryState.NO_GRANT || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            return Entered.Ready(Seeded(IosDiscovery(Logger.withTag("contract"), PhotoKitCandidateSource())))
        }
    }

    @Test
    fun `the grant-aware PhotoKit read satisfies the CandidateSource contract on this host`() =
        verify(CandidateSourceContract, candidateSource)

    @Test
    fun `IosDiscovery satisfies the UploadDiscovery contract on this host`() =
        verify(UploadDiscoveryContract, uploadDiscovery)
}
