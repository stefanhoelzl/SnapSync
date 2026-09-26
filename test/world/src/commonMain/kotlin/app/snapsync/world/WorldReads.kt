package app.snapsync.world

import app.cash.sqldelight.db.QueryResult
import app.snapsync.ports.DbOpen
import app.snapsync.services.downloads.DOWNLOADS_DB_NAME
import app.snapsync.services.downloads.db.DownloadDatabase

// ---- download store operator reads ---------------------------------------------------------
//
// Read straight from the download database, as an inspector of the App-Group file would: the store's own API is
// the app's, and it offers no "what is in flight" read, which only the operator needs.

/** The foreign resources whose downloads were sent to the OS and have not landed — (device, asset, resource key). */
fun World.downloadsInFlight(): List<Triple<String, String, String>> = downloads(
    "SELECT sourceDeviceId, sourceAssetId, resourceKey FROM downloadResource WHERE enqueued = 1 AND stagedPath IS NULL",
) { device, asset, key -> Triple(device, asset, key) }

/** How many foreign assets the store holds as landed: every resource staged, or already imported. */
fun World.downloadsLanded(): Int = downloads(
    "SELECT sourceDeviceId, sourceAssetId, state FROM downloadAsset a WHERE a.state = 'IMPORTED' OR NOT EXISTS " +
        "(SELECT 1 FROM downloadResource r WHERE r.sourceDeviceId = a.sourceDeviceId " +
        "AND r.sourceAssetId = a.sourceAssetId AND r.stagedPath IS NULL)",
) { _, _, _ -> Unit }.size

private fun <T> World.downloads(sql: String, row: (String, String, String) -> T): List<T> {
    val driver = (databases.open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, readOnly = true) as? DbOpen.Opened)?.driver
        ?: return emptyList()
    return driver.executeQuery(null, sql, { cursor ->
        val out = mutableListOf<T>()
        while (cursor.next().value) out += row(cursor.getString(0)!!, cursor.getString(1)!!, cursor.getString(2)!!)
        QueryResult.Value(out)
    }, 0).value
}


/** Operator lever: bytes appear on the world's staging disk at [path] (relative to the shared area). */
fun World.stageFile(path: String) {
    sharedFiles[path] = STAGED_BYTES
}
