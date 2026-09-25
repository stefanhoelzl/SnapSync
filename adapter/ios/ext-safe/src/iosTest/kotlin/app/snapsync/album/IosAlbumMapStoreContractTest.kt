package app.snapsync.album

import app.snapsync.contracts.AlbumMapStoreContract
import app.snapsync.contracts.AlbumMapStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.model.SecureStoreRead
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.SecureStore
import app.snapsync.preferences.IosPreferences
import app.snapsync.services.album.AlbumMapService
import platform.Foundation.NSUserDefaults
import kotlin.test.Test

/**
 * The event-album map in its `NSUserDefaults` suite, live (`docs/architecture.md`). Each clause gets its
 * own suite, named from the clause id and removed afterwards — measured in this executable (2026-09-23): a
 * named suite round-trips and `removePersistentDomainForName` empties it.
 *
 * The legacy Keychain the adapter migrates from is its production default. Here it answers unavailable (the
 * executable is unentitled), which sends the adapter down its "defer the migration" branch to an empty map —
 * the honest answer for a store holding nothing, and the one it gives on any host without a Keychain. The
 * migration itself is not this contract's: its decision is `albumMapSource`, covered in `commonTest`.
 *
 * Seeding goes through the adapter's own `put`, so the stored encoding is the adapter's; only the corrupt
 * state writes the suite directly, because no `put` can produce it.
 */
class IosAlbumMapStoreContractTest {

    private val binding = object : Binding<AlbumMapStoreState, AlbumMapStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(AlbumMapStoreState.EMPTY, AlbumMapStoreState.HOLDING, AlbumMapStoreState.CORRUPT)

        override fun create(state: AlbumMapStoreState, clauseId: String): Entered<AlbumMapStore> {
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

    /** The service over the real suite, with no legacy Keychain map — the migration is the service's own test. */
    private fun service(suite: String) = AlbumMapService(IosPreferences(suite), NoLegacyMap)

    private object NoLegacyMap : SecureStore {
        override fun read(): SecureStoreRead = SecureStoreRead.Absent
        override fun write(value: String) = error("the contract never writes a legacy map")
        override fun migrateProtection() = Unit
        override fun delete() = Unit
    }

    @Test
    fun `the App-Group album map satisfies the AlbumMapStore contract`() = verify(AlbumMapStoreContract, binding)

    private companion object {
        /** The adapter's own key — a runtime-identity pin, restated here only to corrupt it. */
        const val MAP_KEY = "app.snapsync.album.map"
    }
}
