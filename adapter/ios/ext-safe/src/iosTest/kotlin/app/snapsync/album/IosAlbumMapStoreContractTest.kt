package app.snapsync.album

import app.snapsync.contracts.AlbumMapStoreContract
import app.snapsync.contracts.AlbumMapStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.preferences.IosPreferences
import app.snapsync.services.album.AlbumMapService
import platform.Foundation.NSUserDefaults
import kotlin.test.Test

/**
 * The event-album map in its `NSUserDefaults` suite, live (`docs/architecture.md`). Each clause gets its
 * own suite, named from the clause id and removed afterwards — measured in this executable (2026-09-23): a
 * named suite round-trips and `removePersistentDomainForName` empties it.
 *
 * Seeding goes through the adapter's own `put`, so the stored encoding is the adapter's; only the corrupt
 * state writes the suite directly, because no `put` can produce it.
 */
class IosAlbumMapStoreContractTest {

    private val binding = object : Binding<AlbumMapStoreState, AlbumMapService> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(AlbumMapStoreState.EMPTY, AlbumMapStoreState.HOLDING, AlbumMapStoreState.CORRUPT)

        override fun create(state: AlbumMapStoreState, clauseId: String): Entered<AlbumMapService> {
            val suite = "contract.albummap.$clauseId"
            NSUserDefaults(suiteName = suite).removePersistentDomainForName(suite)
            when (state) {
                AlbumMapStoreState.EMPTY -> Unit
                AlbumMapStoreState.HOLDING -> service(suite).put(
                    AlbumMapStoreContract.seedEvent(clauseId),
                    AlbumMapStoreContract.seedAlbum(clauseId),
                )
                AlbumMapStoreState.CORRUPT -> NSUserDefaults(suiteName = suite).setObject("{not json", forKey = MAP_KEY)
            }
            return Entered.Ready(service(suite)) {
                NSUserDefaults(suiteName = suite).removePersistentDomainForName(suite)
            }
        }
    }

    /** The service over the real suite. */
    private fun service(suite: String) = AlbumMapService(IosPreferences(suite))

    @Test
    fun `the App-Group album map satisfies the AlbumMapService contract`() = verify(AlbumMapStoreContract, binding)

    private companion object {
        /** The adapter's own key — a runtime-identity pin, restated here only to corrupt it. */
        const val MAP_KEY = "app.snapsync.album.map"
    }
}
