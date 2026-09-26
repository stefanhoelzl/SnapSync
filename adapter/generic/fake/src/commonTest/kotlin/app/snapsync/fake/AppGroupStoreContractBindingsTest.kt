package app.snapsync.fake

import app.snapsync.contracts.AlbumMapStoreContract
import app.snapsync.contracts.AlbumMapStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceLogSourceContract
import app.snapsync.contracts.DeviceLogSourceState
import app.snapsync.contracts.DeviceManifestStoreContract
import app.snapsync.contracts.DeviceManifestStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.PushRegistrationRecordContract
import app.snapsync.contracts.PushRegistrationRecordState
import app.snapsync.contracts.StagedBytesContract
import app.snapsync.contracts.StagedBytesState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.push.PushRegistrationRecord
import app.snapsync.services.staging.StagingService
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.model.FileArea
import kotlin.test.Test

/**
 * The honest doubles of the App-Group-backed stores, held to the contracts their iOS adapters satisfy.
 * States about storage an in-memory double does not have — an unreachable container, a corrupt record, a
 * rolled log sibling — answer `Unreachable`; the live bindings cover them.
 */
class AppGroupStoreContractBindingsTest {

    // The App-Group file services' contracts over [inMemoryFiles] — the in-memory areas every feature test and the
    // world stand on, held to the clauses the platform file systems are (`:adapter:generic:app`, `:adapter:ios:ext-safe`).

    private val manifest = object : Binding<DeviceManifestStoreState, DeviceManifestService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            DeviceManifestStoreState.UNAVAILABLE,
            DeviceManifestStoreState.EMPTY,
            DeviceManifestStoreState.HOLDING,
        )

        override fun create(state: DeviceManifestStoreState, clauseId: String): Entered<DeviceManifestService> {
            if (state == DeviceManifestStoreState.UNAVAILABLE) return Entered.Ready(DeviceManifestService(inMemoryFiles(shared = null)))
            val service = DeviceManifestService(inMemoryFiles())
            if (state == DeviceManifestStoreState.HOLDING) service.saveLastUploaded(DeviceManifestStoreContract.seedJson(clauseId))
            return Entered.Ready(service)
        }
    }

    private val pushRecord = object : Binding<PushRegistrationRecordState, PushRegistrationRecord> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            PushRegistrationRecordState.UNAVAILABLE,
            PushRegistrationRecordState.EMPTY,
            PushRegistrationRecordState.HOLDING,
        )

        override fun create(state: PushRegistrationRecordState, clauseId: String): Entered<PushRegistrationRecord> {
            if (state == PushRegistrationRecordState.UNAVAILABLE) return Entered.Ready(PushRegistrationRecord(inMemoryFiles(shared = null)))
            val record = PushRegistrationRecord(inMemoryFiles())
            if (state == PushRegistrationRecordState.HOLDING) record.saveLastRegistered(PushRegistrationRecordContract.seed(clauseId))
            return Entered.Ready(record)
        }
    }

    private val staged = object : Binding<StagedBytesState, StagingService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(StagedBytesState.UNAVAILABLE, StagedBytesState.EMPTY, StagedBytesState.STAGED)

        override fun create(state: StagedBytesState, clauseId: String): Entered<StagingService> {
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Ready(StagingService(inMemoryFiles(shared = null)))
            val files = inMemoryFiles()
            val service = StagingService(files)
            if (state == StagedBytesState.STAGED) {
                StagedBytesContract.stagedNames(clauseId).forEach {
                    files.write(FileArea.SHARED, "${service.stagingRoot()}/$it", "bytes:$it".encodeToByteArray())
                }
            }
            return Entered.Ready(service)
        }
    }

    private val logs = object : Binding<DeviceLogSourceState, LogTailService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceLogSourceState.NO_LOG, DeviceLogSourceState.EMPTY_LOG, DeviceLogSourceState.HOLDING)

        override fun create(state: DeviceLogSourceState, clauseId: String): Entered<LogTailService> {
            val text: (LogTailService.Process) -> String = when (state) {
                DeviceLogSourceState.NO_LOG -> return Entered.Ready(LogTailService(inMemoryFiles()))
                DeviceLogSourceState.ROLLED_ONLY -> return Entered.Unreachable("the in-memory areas hold no rolled sibling")
                DeviceLogSourceState.EMPTY_LOG -> { _ -> "" }
                DeviceLogSourceState.HOLDING -> { p -> DeviceLogSourceContract.seedLog(p, clauseId) }
            }
            val files = inMemoryFiles(
                shared = mutableMapOf(EXTENSION_LOG_FILE_NAME to text(LogTailService.Process.EXTENSION).encodeToByteArray()),
                private = mutableMapOf(APP_LOG_FILE_NAME to text(LogTailService.Process.APP).encodeToByteArray()),
            )
            return Entered.Ready(LogTailService(files))
        }
    }

    private val albums = object : Binding<AlbumMapStoreState, AlbumMapService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(AlbumMapStoreState.EMPTY, AlbumMapStoreState.HOLDING)

        override fun create(state: AlbumMapStoreState, clauseId: String): Entered<AlbumMapService> {
            val service = AlbumMapService(inMemoryPreferences(), inMemorySecureStore())
            return when (state) {
                AlbumMapStoreState.EMPTY -> Entered.Ready(service)
                AlbumMapStoreState.HOLDING -> Entered.Ready(
                    service.apply { put(AlbumMapStoreContract.seedEvent(clauseId), AlbumMapStoreContract.seedAlbum(clauseId)) },
                )
                AlbumMapStoreState.CORRUPT -> Entered.Unreachable("reached by the platform bindings, over a real encoding")
            }
        }
    }

    @Test
    fun `the manifest service over in-memory files satisfies the DeviceManifestStore contract`() =
        verify(DeviceManifestStoreContract, manifest)

    @Test
    fun `the push registration record over in-memory files satisfies the PushRegistrationRecord contract`() =
        verify(PushRegistrationRecordContract, pushRecord)

    @Test
    fun `the staging service over in-memory files satisfies the StagedBytes contract`() = verify(StagedBytesContract, staged)

    @Test
    fun `the log-tail service over in-memory files satisfies the DeviceLogSource contract`() = verify(DeviceLogSourceContract, logs)

    @Test
    fun `the album map over in-memory preferences satisfies the AlbumMapStore contract`() = verify(AlbumMapStoreContract, albums)
}
