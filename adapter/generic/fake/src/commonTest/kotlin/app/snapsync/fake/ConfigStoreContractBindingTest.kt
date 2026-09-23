package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ConfigPorts
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.contracts.ConfigStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.EventConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The honest config ports, held to the contract the App-Group file store satisfies. The fake holds a
 * membership, not a file, so the states that are about a record's *text* — another build's format, an
 * undecodable payload, a read that fails on a present file — have no meaning here.
 *
 * Its [ConfigStoreState.INACCESSIBLE] holds a membership it cannot read: the device before first unlock,
 * the state where answering `None` would be a false leave.
 */
class ConfigStoreContractBindingTest {

    private val binding = object : Binding<ConfigStoreState, ConfigPorts> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(ConfigStoreState.INACCESSIBLE, ConfigStoreState.ABSENT, ConfigStoreState.JOINED)

        override fun create(state: ConfigStoreState, clauseId: String): Entered<ConfigPorts> {
            val seed = ConfigStoreContract.seedConfig(clauseId)
            val (persisted: EventConfig?, readable) = when (state) {
                ConfigStoreState.INACCESSIBLE -> seed to false
                ConfigStoreState.ABSENT -> null to true
                ConfigStoreState.JOINED -> seed to true
                ConfigStoreState.FOREIGN, ConfigStoreState.UNUSABLE, ConfigStoreState.FILE_UNREADABLE ->
                    return Entered.Unreachable("the fake holds a membership, not a record's text")
            }
            val store = InMemoryConfigStore(MutableStateFlow(persisted), MutableStateFlow(readable))
            return Entered.Ready(ConfigPorts(source = store, store = store, reader = store))
        }
    }

    @Test
    fun `the in-memory config ports satisfy the ConfigStore contract`() = verify(ConfigStoreContract, binding)
}
