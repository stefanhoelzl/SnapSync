package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.contracts.ConfigStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.FileArea
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.config.ConfigService
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The config service's contract over [inMemoryFiles]: every state a record's text or the area can be in is reachable
 * here, as it is over the platform file systems — the membership the world and the feature tests stand on.
 */
class ConfigStoreContractBindingTest {

    private val binding = object : Binding<ConfigStoreState, ConfigService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            ConfigStoreState.INACCESSIBLE,
            ConfigStoreState.ABSENT,
            ConfigStoreState.JOINED,
            ConfigStoreState.FOREIGN,
            ConfigStoreState.UNUSABLE,
            ConfigStoreState.FILE_UNREADABLE,
        )

        override fun create(state: ConfigStoreState, clauseId: String): Entered<ConfigService> {
            val clock = fixedClock(Instant.fromEpochSeconds(0))
            if (state == ConfigStoreState.INACCESSIBLE) return Entered.Ready(ConfigService(inMemoryFiles(shared = null), clock))
            val record = when (state) {
                ConfigStoreState.JOINED, ConfigStoreState.FILE_UNREADABLE -> ConfigStoreContract.seedFile(clauseId)
                ConfigStoreState.FOREIGN -> ConfigStoreContract.FOREIGN_FILE
                ConfigStoreState.UNUSABLE -> ConfigStoreContract.UNUSABLE_FILE
                ConfigStoreState.ABSENT, ConfigStoreState.INACCESSIBLE -> null
            }
            val shared = mutableMapOf<String, ByteArray>()
            record?.let { shared[CONFIG_FILE_NAME] = it.encodeToByteArray() }
            val denied = if (state == ConfigStoreState.FILE_UNREADABLE) setOf(FileArea.SHARED to CONFIG_FILE_NAME) else emptySet()
            return Entered.Ready(ConfigService(inMemoryFiles(shared = shared, denied = denied), clock))
        }
    }

    @Test
    fun `the config service over in-memory files satisfies the ConfigStore contract`() = verify(ConfigStoreContract, binding)
}
