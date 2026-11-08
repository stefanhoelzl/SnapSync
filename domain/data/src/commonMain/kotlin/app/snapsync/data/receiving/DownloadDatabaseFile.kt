package app.snapsync.data.receiving

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.downloadDatabase
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.held
import app.snapsync.data.receiving.RowStatements.refsOf
import app.snapsync.data.receiving.db.DownloadAsset
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.data.receiving.db.DownloadResource
import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.data.receiving.db.EventRef
import app.snapsync.data.receiving.db.Recognised
import app.snapsync.data.receiving.db.UnionCursor
import app.snapsync.data.store.AssetIdColumnAdapter
import app.snapsync.data.store.DeviceIdColumnAdapter
import app.snapsync.data.store.EventIdColumnAdapter
import app.snapsync.data.store.OwnedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.Databases
import app.snapsync.ports.DbResult

/** The download store's database file — runtime identity (`docs/architecture.md`, "Storage"). */
const val DOWNLOADS_DB_NAME: String = "downloads.db"

/**
 * `downloads.db` as the APP opens it: read-write, created if missing and migrated if old, on first use and never at
 * construction (`OwnedDatabase`). The app is the store's one writer and the one process that migrates it — the upload
 * extension may run before an updated app has, and a migration the app did not run is one nobody sequenced with the
 * app's own writes — so the extension cannot build one: it reads through [ReceivedPhotosReader].
 */
class DownloadDatabaseFile @AppProcessOnly constructor(databases: Databases) :
    OwnedDatabase<DownloadStoreQueries>(databases, DOWNLOADS_DB_NAME, DownloadDatabase.Schema) {
    override fun over(driver: SqlDriver): DownloadStoreQueries = downloadDatabase(driver).downloadStoreQueries

    companion object {
        /**
         * The generated database over [driver], its column adapters wired — the one place each column's encoding is
         * named.
         */
        internal fun downloadDatabase(driver: SqlDriver): DownloadDatabase = DownloadDatabase(
            driver,
            DownloadAsset.Adapter(
                sourceDeviceIdAdapter = DeviceIdColumnAdapter,
                sourceAssetIdAdapter = AssetIdColumnAdapter,
                stateAdapter = EnumColumnAdapter(),
                createdLocalIdAdapter = AssetIdColumnAdapter,
            ),
            DownloadResource.Adapter(
                sourceDeviceIdAdapter = DeviceIdColumnAdapter,
                sourceAssetIdAdapter = AssetIdColumnAdapter,
            ),
            EventRef.Adapter(
                eventIdAdapter = EventIdColumnAdapter,
                sourceDeviceIdAdapter = DeviceIdColumnAdapter,
                sourceAssetIdAdapter = AssetIdColumnAdapter,
            ),
            Recognised.Adapter(eventIdAdapter = EventIdColumnAdapter),
            UnionCursor.Adapter(eventIdAdapter = EventIdColumnAdapter),
        )

        /** [this] port answer as a download read. */
        internal fun <T> DbResult<T>.read(): DownloadRead<T> = when (this) {
            is DbResult.Ok -> DownloadRead.Ok(value)
            is DbResult.Failed -> DownloadRead.Unavailable(detail)
        }

        /** [next] of this read's value, or this failure. */
        internal inline fun <A, B> DownloadRead<A>.then(next: (A) -> DownloadRead<B>): DownloadRead<B> = when (this) {
            is DownloadRead.Ok -> next(value)
            is DownloadRead.Unavailable -> this
        }

        /**
         * The id a column holds, as a list of one — or of none when it spells no id. A row an earlier build wrote whose
         * id is
         * no id names no photo the union serves or the library holds, so it plans, imports and suppresses nothing.
         */
        internal fun <T : Any> Stored<T>.held(): List<T> = when (this) {
            is Stored.Valid -> listOf(value)
            is Stored.Invalid -> emptyList()
        }

        /**
         * The ref the texts [device] and [asset] spell — a tag's, a correlation's, a reply's — read as a stored id is:
         * a list
         * of one, or of none when either spells no id.
         */
        internal fun refOf(device: String, asset: String): List<AssetRef> =
            refsOf(DeviceIdColumnAdapter.decode(device).held() to AssetIdColumnAdapter.decode(asset).held())
    }
}

/**
 * What a read of the download store came to: the value, or that the store could not answer — never mistaken for
 * "nothing": a failed read of what was received is not "nothing received".
 */
sealed interface DownloadRead<out T> {
    class Ok<T>(val value: T) : DownloadRead<T>

    /** The store could not be read. [detail] is diagnostics only. */
    class Unavailable(val detail: String) : DownloadRead<Nothing>
}
