package app.snapsync.world

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ExtensionEntriesContract
import app.snapsync.contracts.ExtensionEntriesState
import app.snapsync.contracts.ExtensionEntriesSubject
import app.snapsync.contracts.Host
import app.snapsync.contracts.PlatformEntriesContract
import app.snapsync.contracts.PlatformEntriesState
import app.snapsync.contracts.PlatformEntriesSubject
import app.snapsync.contracts.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The inbound ports' contracts, bound on this host (IOS_SIM_KEXE) over the core's real implementations and the world
 * (capability `port-contracts`). `Live`: the implementation under contract is the one the shells delegate to.
 */
class EntryContractsSimulatorTest {

    private val platformEntries = object : Binding<PlatformEntriesState, PlatformEntriesSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(PlatformEntriesState.UNJOINED, PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO)
        override fun create(state: PlatformEntriesState, clauseId: String) =
            runBlocking { EntryContractFixtures.enter(state) }
    }

    private val extensionEntries = object : Binding<ExtensionEntriesState, ExtensionEntriesSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            ExtensionEntriesState.UNJOINED,
            ExtensionEntriesState.JOINED_WITH_A_NEW_PHOTO,
            ExtensionEntriesState.JOINED_WITH_NOTHING_NEW,
        )
        override fun create(state: ExtensionEntriesState, clauseId: String) =
            runBlocking { EntryContractFixtures.enter(state) }
    }

    @Test
    fun `satisfies the PlatformEntries contract`() = verify(PlatformEntriesContract, platformEntries)

    @Test
    fun `satisfies the ExtensionEntries contract`() = verify(ExtensionEntriesContract, extensionEntries)
}
