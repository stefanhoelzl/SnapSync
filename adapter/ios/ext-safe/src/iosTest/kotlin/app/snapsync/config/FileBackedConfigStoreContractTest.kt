@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.config

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ConfigPorts
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.contracts.ConfigStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.writeTextFile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import kotlin.test.Test

/**
 * The App-Group config file store, live (capability `port-contracts`) — the first run of the leave
 * decision through the real adapter's own file IO and `NSError` mapping, rather than through the
 * classifier and the read algorithm separately.
 *
 * Readable states get a fresh directory with the record written where the adapter looks for it.
 * [ConfigStoreState.INACCESSIBLE] is the adapter's DEFAULT container: this unentitled executable's
 * App-Group lookup answers `nil`, so the unavailable branch runs on the platform's own answer.
 */
class FileBackedConfigStoreContractTest {

    private val binding = object : Binding<ConfigStoreState, ConfigPorts> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            ConfigStoreState.INACCESSIBLE,
            ConfigStoreState.ABSENT,
            ConfigStoreState.JOINED,
            ConfigStoreState.FOREIGN,
            ConfigStoreState.UNUSABLE,
            ConfigStoreState.FILE_UNREADABLE,
        )

        override fun create(state: ConfigStoreState, clauseId: String): Entered<ConfigPorts> {
            if (state == ConfigStoreState.INACCESSIBLE) return Entered.Ready(ports(FileBackedConfigStore()))
            val dir = newTempDirectory()
            val file = "$dir/$FILE"
            when (state) {
                ConfigStoreState.JOINED -> writeTextFile(file, ConfigStoreContract.seedFile(clauseId))
                ConfigStoreState.FOREIGN -> writeTextFile(file, ConfigStoreContract.FOREIGN_FILE)
                ConfigStoreState.UNUSABLE -> writeTextFile(file, ConfigStoreContract.UNUSABLE_FILE)
                // A present file the process may not read. Measured in this executable (2026-09-23): the read
                // fails `NSCocoaErrorDomain` 257 (`NSFileReadNoPermissionError`, underlying `EACCES`) — the
                // Cocoa class Apple documents for a protected file before first unlock — so the classifier's
                // else-branch runs on an error a real filesystem raised. The dispose restores the mode.
                ConfigStoreState.FILE_UNREADABLE -> {
                    writeTextFile(file, ConfigStoreContract.seedFile(clauseId))
                    chmod(file, NO_PERMISSIONS)
                }
                ConfigStoreState.ABSENT, ConfigStoreState.INACCESSIBLE -> Unit
            }
            return Entered.Ready(ports(FileBackedConfigStore(containerPath = dir))) {
                chmod(file, OWNER_READ_WRITE)
                removeDirectory(dir)
            }
        }
    }

    private fun ports(store: FileBackedConfigStore) = ConfigPorts(source = store, store = store, reader = store)

    @Test
    fun `the App-Group config file satisfies the ConfigStore contract`() = verify(ConfigStoreContract, binding)

    private companion object {
        /** The adapter's own file name — a runtime-identity pin, restated here only to seed it. */
        const val FILE = "eventconfig.json"

        const val NO_PERMISSIONS: UShort = 0u
        const val OWNER_READ_WRITE: UShort = 0x180u // 0600
    }
}
