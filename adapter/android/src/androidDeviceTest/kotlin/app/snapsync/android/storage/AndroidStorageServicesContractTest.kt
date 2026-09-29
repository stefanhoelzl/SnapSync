package app.snapsync.android.storage

import app.snapsync.services.config.ConfigService
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.contracts.ConfigStoreState
import app.snapsync.contracts.DownloadStoreContract
import app.snapsync.contracts.DownloadStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LedgerStoreContract
import app.snapsync.contracts.LedgerStoreState
import app.snapsync.contracts.StagedBytesContract
import app.snapsync.contracts.StagedBytesState
import app.snapsync.contracts.verify
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.model.FileArea
import app.snapsync.ports.Clock
import app.snapsync.services.staging.StagingService
import java.io.File
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The storage services' contracts through the services over the real Android adapters on ART — what the iOS
 * simulator's executable runs over the iOS adapters: the leave decision over [AndroidFiles]' own error mapping, and
 * both SQLite services (schema creation, migrations, the enum column adapters) over [AndroidDatabases].
 */
class AndroidStorageServicesContractTest {

    private val config = object : Binding<ConfigStoreState, ConfigService> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            ConfigStoreState.ABSENT,
            ConfigStoreState.JOINED,
            ConfigStoreState.FOREIGN,
            ConfigStoreState.UNUSABLE,
            ConfigStoreState.FILE_UNREADABLE,
        )

        override fun create(state: ConfigStoreState, clauseId: String): Entered<ConfigService> {
            if (state == ConfigStoreState.INACCESSIBLE) return Entered.Unreachable(ALWAYS_REACHABLE)
            val dir = newTempDirectory()
            val file = File(dir, CONFIG_FILE)
            when (state) {
                ConfigStoreState.JOINED -> file.writeText(ConfigStoreContract.seedFile(clauseId))
                ConfigStoreState.FOREIGN -> file.writeText(ConfigStoreContract.FOREIGN_FILE)
                ConfigStoreState.UNUSABLE -> file.writeText(ConfigStoreContract.UNUSABLE_FILE)
                // A present file this process may not open: the case `java.io` reports as a missing file — a false leave.
                ConfigStoreState.FILE_UNREADABLE -> {
                    file.writeText(ConfigStoreContract.seedFile(clauseId))
                    revokeAllAccess(file)
                }
                ConfigStoreState.ABSENT, ConfigStoreState.INACCESSIBLE -> Unit
            }
            return Entered.Ready(ConfigService(AndroidFiles(sharedRoot = dir, privateRoot = newTempDirectory()), CLOCK)) {
                restoreOwnerAccess(file)
                dir.deleteRecursively()
            }
        }
    }

    private val staged = object : Binding<StagedBytesState, StagingService> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(StagedBytesState.EMPTY, StagedBytesState.STAGED)

        override fun create(state: StagedBytesState, clauseId: String): Entered<StagingService> {
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Unreachable(ALWAYS_REACHABLE)
            val shared = newTempDirectory()
            val private = newTempDirectory()
            val files = AndroidFiles(sharedRoot = shared, privateRoot = private)
            val staging = StagingService(files)
            if (state == StagedBytesState.STAGED) {
                StagedBytesContract.stagedNames(clauseId).forEach {
                    files.write(FileArea.SHARED, "${staging.stagingRoot()}/$it", "bytes:$it".encodeToByteArray())
                }
            }
            return Entered.Ready(staging) {
                shared.deleteRecursively()
                private.deleteRecursively()
            }
        }
    }

    private val ledger = object : Binding<LedgerStoreState, LedgerService> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LedgerStoreState.EMPTY)
        override fun create(state: LedgerStoreState, clauseId: String): Entered<LedgerService> {
            val dir = newTempDirectory()
            return Entered.Ready(LedgerService(AndroidDatabases(context, dir))) { dir.deleteRecursively() }
        }
    }

    private val downloads = object : Binding<DownloadStoreState, DownloadService> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String): Entered<DownloadService> {
            val dir = newTempDirectory()
            return Entered.Ready(DownloadService(AndroidDatabases(context, dir))) { dir.deleteRecursively() }
        }
    }

    @Test
    fun `the config file satisfies the ConfigStore contract`() = verify(ConfigStoreContract, config)

    @Test
    fun `the staging service satisfies the StagedBytes contract`() = verify(StagedBytesContract, staged)

    @Test
    fun `the ledger satisfies the LedgerService contract`() = verify(LedgerStoreContract, ledger)

    @Test
    fun `the download store satisfies the DownloadService contract`() = verify(DownloadStoreContract, downloads)

    private companion object {
        const val ALWAYS_REACHABLE = "both file areas are app-private directories, always reachable"

        /** The config service's own file name — a runtime-identity pin, restated here only to seed it. */
        const val CONFIG_FILE = "eventconfig.json"

        /** No clause reads "now", so any instant serves. */
        val CLOCK = object : Clock {
            override fun now() = Instant.fromEpochSeconds(0)
            override fun timeZone() = TimeZone.UTC
        }
    }
}
