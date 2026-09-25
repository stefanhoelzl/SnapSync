package app.snapsync.files

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.ConfigPorts
import app.snapsync.contracts.ConfigStoreContract
import app.snapsync.contracts.ConfigStoreState
import app.snapsync.contracts.DeviceLogSourceContract
import app.snapsync.contracts.DeviceLogSourceState
import app.snapsync.contracts.DeviceManifestStoreContract
import app.snapsync.contracts.DeviceManifestStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.Host
import app.snapsync.contracts.StagedBytesContract
import app.snapsync.contracts.StagedBytesState
import app.snapsync.contracts.verify
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Files
import app.snapsync.ports.StagedBytes
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.config.ConfigService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.staging.StagingService
import java.io.File
import java.nio.file.Files as Nio
import kotlin.test.Test

/**
 * The `Files` contract against the real [JvmFiles], and the file-backed storage services' contracts through the
 * services over it — so every `./gradlew build` runs them, beside their iOS bindings (which only CI's simulator
 * job runs). Each clause gets its own two directories.
 */
class JvmFileServicesContractsTest {

    private class Areas {
        val shared: File = Nio.createTempDirectory("shared").toFile()
        val private: File = Nio.createTempDirectory("private").toFile()
        val files = JvmFiles(shared, private)
        fun dispose() {
            shared.walkTopDown().forEach { it.setReadable(true) }
            shared.deleteRecursively()
            private.deleteRecursively()
        }
    }

    /** The unentitled case: no shared area at all. */
    private fun unavailable(): Files = JvmFiles(shared = null, private = Nio.createTempDirectory("private").toFile())

    private val files = object : Binding<FilesState, Files> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(FilesState.EMPTY, FilesState.HOLDING, FilesState.DENIED, FilesState.UNAVAILABLE)
        override fun create(state: FilesState, clauseId: String): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) return Entered.Ready(unavailable())
            val areas = Areas()
            val path = FilesContract.path(clauseId)
            when (state) {
                FilesState.HOLDING -> areas.files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                FilesState.DENIED -> {
                    areas.files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                    File(areas.shared, path).setReadable(false)
                }
                FilesState.EMPTY, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(areas.files, areas::dispose)
        }
    }

    private val config = object : Binding<ConfigStoreState, ConfigPorts> {
        override val host = Host.JVM
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
            if (state == ConfigStoreState.INACCESSIBLE) return Entered.Ready(ports(ConfigService(unavailable())))
            val areas = Areas()
            val file = File(areas.shared, CONFIG_FILE_NAME)
            when (state) {
                ConfigStoreState.JOINED -> file.writeText(ConfigStoreContract.seedFile(clauseId))
                ConfigStoreState.FOREIGN -> file.writeText(ConfigStoreContract.FOREIGN_FILE)
                ConfigStoreState.UNUSABLE -> file.writeText(ConfigStoreContract.UNUSABLE_FILE)
                ConfigStoreState.FILE_UNREADABLE -> {
                    file.writeText(ConfigStoreContract.seedFile(clauseId))
                    file.setReadable(false)
                }
                ConfigStoreState.ABSENT, ConfigStoreState.INACCESSIBLE -> Unit
            }
            return Entered.Ready(ports(ConfigService(areas.files)), areas::dispose)
        }

        private fun ports(service: ConfigService) = ConfigPorts(source = service, store = service, reader = service)
    }

    private val manifest = object : Binding<DeviceManifestStoreState, DeviceManifestStore> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DeviceManifestStoreState.UNAVAILABLE,
            DeviceManifestStoreState.EMPTY,
            DeviceManifestStoreState.HOLDING,
        )
        override fun create(state: DeviceManifestStoreState, clauseId: String): Entered<DeviceManifestStore> {
            if (state == DeviceManifestStoreState.UNAVAILABLE) return Entered.Ready(DeviceManifestService(unavailable()))
            val areas = Areas()
            val service = DeviceManifestService(areas.files)
            if (state == DeviceManifestStoreState.HOLDING) service.saveLastUploaded(DeviceManifestStoreContract.seedJson(clauseId))
            return Entered.Ready(service, areas::dispose)
        }
    }

    private val staging = object : Binding<StagedBytesState, StagedBytes> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(StagedBytesState.UNAVAILABLE, StagedBytesState.EMPTY, StagedBytesState.STAGED)
        override fun create(state: StagedBytesState, clauseId: String): Entered<StagedBytes> {
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Ready(StagingService(unavailable()))
            val areas = Areas()
            val service = StagingService(areas.files)
            if (state == StagedBytesState.STAGED) {
                StagedBytesContract.stagedNames(clauseId).forEach {
                    areas.files.write(FileArea.SHARED, "${service.stagingRoot()}/$it", "bytes:$it".encodeToByteArray())
                }
            }
            return Entered.Ready(service, areas::dispose)
        }
    }

    private val logs = object : Binding<DeviceLogSourceState, DeviceLogSource> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DeviceLogSourceState.NO_LOG,
            DeviceLogSourceState.EMPTY_LOG,
            DeviceLogSourceState.HOLDING,
            DeviceLogSourceState.ROLLED_ONLY,
        )
        override fun create(state: DeviceLogSourceState, clauseId: String): Entered<DeviceLogSource> {
            val areas = Areas()
            val paths = mapOf(
                DeviceLogSource.Process.APP to File(areas.private, APP_LOG_FILE_NAME),
                DeviceLogSource.Process.EXTENSION to File(areas.shared, EXTENSION_LOG_FILE_NAME),
            )
            paths.forEach { (process, file) ->
                when (state) {
                    DeviceLogSourceState.NO_LOG -> Unit
                    DeviceLogSourceState.EMPTY_LOG -> file.writeText("")
                    DeviceLogSourceState.HOLDING -> file.writeText(DeviceLogSourceContract.seedLog(process, clauseId))
                    DeviceLogSourceState.ROLLED_ONLY ->
                        File(file.path + ".1").writeText(DeviceLogSourceContract.seedLog(process, clauseId))
                }
            }
            return Entered.Ready(LogTailService(areas.files), areas::dispose)
        }
    }

    @Test
    fun `the JVM file system satisfies the Files contract`() = verify(FilesContract, files)

    @Test
    fun `the config service over it satisfies the ConfigStore contract`() = verify(ConfigStoreContract, config)

    @Test
    fun `the manifest service over it satisfies the DeviceManifestStore contract`() =
        verify(DeviceManifestStoreContract, manifest)

    @Test
    fun `the staging service over it satisfies the StagedBytes contract`() = verify(StagedBytesContract, staging)

    @Test
    fun `the log-tail service over it satisfies the DeviceLogSource contract`() = verify(DeviceLogSourceContract, logs)
}
