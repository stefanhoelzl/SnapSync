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
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.ports.StagedBytes
import kotlin.test.Test

/**
 * The honest doubles of the App-Group-backed stores, held to the contracts their iOS adapters satisfy.
 * States about storage an in-memory double does not have — an unreachable container, a corrupt record, a
 * rolled log sibling — answer `Unreachable`; the live bindings cover them.
 */
class AppGroupStoreContractBindingsTest {

    private val manifest = object : Binding<DeviceManifestStoreState, DeviceManifestStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceManifestStoreState.EMPTY, DeviceManifestStoreState.HOLDING)

        override fun create(state: DeviceManifestStoreState, clauseId: String): Entered<DeviceManifestStore> =
            when (state) {
                DeviceManifestStoreState.UNAVAILABLE -> Entered.Unreachable("an in-memory record is always reachable")
                DeviceManifestStoreState.EMPTY -> Entered.Ready(InMemoryDeviceManifestStore())
                DeviceManifestStoreState.HOLDING ->
                    Entered.Ready(InMemoryDeviceManifestStore(DeviceManifestStoreContract.seedJson(clauseId)))
            }
    }

    private val pushRecord = object : Binding<PushRegistrationRecordState, PushRegistrationRecord> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PushRegistrationRecordState.EMPTY, PushRegistrationRecordState.HOLDING)

        override fun create(state: PushRegistrationRecordState, clauseId: String): Entered<PushRegistrationRecord> =
            when (state) {
                PushRegistrationRecordState.UNAVAILABLE -> Entered.Unreachable("an in-memory record is always reachable")
                PushRegistrationRecordState.EMPTY -> Entered.Ready(InMemoryPushRegistrationRecord())
                PushRegistrationRecordState.HOLDING ->
                    Entered.Ready(InMemoryPushRegistrationRecord(PushRegistrationRecordContract.seed(clauseId)))
            }
    }

    private val staged = object : Binding<StagedBytesState, StagedBytes> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(StagedBytesState.EMPTY, StagedBytesState.STAGED)

        override fun create(state: StagedBytesState, clauseId: String): Entered<StagedBytes> {
            val files = mutableSetOf<String>()
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Unreachable("the fake is always given a root")
            if (state == StagedBytesState.STAGED) files += StagedBytesContract.stagedNames(clauseId).map { "$ROOT/$it" }
            return Entered.Ready(InMemoryStagedBytes(files, ROOT))
        }
    }

    private val logs = object : Binding<DeviceLogSourceState, DeviceLogSource> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceLogSourceState.NO_LOG, DeviceLogSourceState.EMPTY_LOG, DeviceLogSourceState.HOLDING)

        override fun create(state: DeviceLogSourceState, clauseId: String): Entered<DeviceLogSource> {
            val text: (DeviceLogSource.Process) -> String = when (state) {
                DeviceLogSourceState.NO_LOG -> return Entered.Ready(InMemoryDeviceLogSource())
                DeviceLogSourceState.ROLLED_ONLY -> return Entered.Unreachable("the fake has no rolled sibling")
                DeviceLogSourceState.EMPTY_LOG -> { _ -> "" }
                DeviceLogSourceState.HOLDING -> { p -> DeviceLogSourceContract.seedLog(p, clauseId) }
            }
            return Entered.Ready(InMemoryDeviceLogSource(DeviceLogSource.Process.entries.associateWith(text)))
        }
    }

    private val albums = object : Binding<AlbumMapStoreState, AlbumMapStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(AlbumMapStoreState.EMPTY, AlbumMapStoreState.HOLDING)

        override fun create(state: AlbumMapStoreState, clauseId: String): Entered<AlbumMapStore> = when (state) {
            AlbumMapStoreState.EMPTY -> Entered.Ready(InMemoryAlbumMapStore())
            AlbumMapStoreState.HOLDING -> Entered.Ready(
                InMemoryAlbumMapStore(
                    mapOf(AlbumMapStoreContract.seedEvent(clauseId) to AlbumMapStoreContract.seedAlbum(clauseId)),
                ),
            )
            AlbumMapStoreState.CORRUPT -> Entered.Unreachable("the fake holds a map, not an encoding of one")
        }
    }

    @Test
    fun `the in-memory manifest record satisfies the DeviceManifestStore contract`() =
        verify(DeviceManifestStoreContract, manifest)

    @Test
    fun `the in-memory push registration record satisfies the PushRegistrationRecord contract`() =
        verify(PushRegistrationRecordContract, pushRecord)

    @Test
    fun `the in-memory staged bytes satisfy the StagedBytes contract`() = verify(StagedBytesContract, staged)

    @Test
    fun `the in-memory device logs satisfy the DeviceLogSource contract`() = verify(DeviceLogSourceContract, logs)

    @Test
    fun `the in-memory album map satisfies the AlbumMapStore contract`() = verify(AlbumMapStoreContract, albums)

    private companion object {
        const val ROOT = "staged:"
    }
}
