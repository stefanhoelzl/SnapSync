@file:OptIn(AppProcessOnly::class)

package app.snapsync.feature.support

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendTarget
import app.snapsync.data.backend.VersionObserver
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.data.receiving.DownloadStore
import app.snapsync.data.receiving.DownloadTransfers
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.receiving.StagingArea
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork

/**
 * What the download store says of how far this device received, over [databases] — the store as the app composes it,
 * for the tests whose subject reads its counts. Nothing here starts a transfer: the session starts nothing.
 */
fun receivingCounts(databases: Databases): ReceivingCounts = ReceivingCounts(
    DownloadStore(
        DownloadDatabaseFile(databases),
        StagingArea(inMemoryFiles()),
        DownloadTransfers(
            NoDownloads,
            MobileDataStore(inMemoryPreferences()),
            BackendExchange(HttpTransport { HttpResult.Unreachable("no backend in this test") }, NoTarget, NoVersions),
        ),
    ),
)

/**
 * [device]'s photo [asset], received and imported into this device's library as [created] — tagged as [event]'s when
 * one is given — written as the download store records an import. Straight to the rows: how a photo comes to be
 * imported is receiving's to test, and these tests read only what it left.
 */
fun Databases.receivedImported(device: DeviceId, asset: AssetId, created: AssetId, event: EventId?) {
    val opened = open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, readOnly = false)
    val database = (opened as? DbOpen.Opened)?.database ?: error("downloads.db cannot be opened ($opened)")
    database.execute { driver ->
        driver.execute(null, IMPORTED_ROW, 3) {
            bindString(0, device.value)
            bindString(1, asset.value)
            bindString(2, created.value)
        }
        event?.let {
            driver.execute(null, EVENT_REF, 3) {
                bindString(0, it.value)
                bindString(1, device.value)
                bindString(2, asset.value)
            }
        }
    }
}

private const val IMPORTED_ROW = "INSERT INTO downloadAsset (sourceDeviceId, sourceAssetId, state, creationDate, " +
    "createdLocalId) VALUES (?, ?, 'IMPORTED', '2026-09-10T00:00:00Z', ?)"

private const val EVENT_REF = "INSERT INTO eventRef (eventId, sourceDeviceId, sourceAssetId) VALUES (?, ?, ?)"

/** A download session that starts nothing: these tests read what the store holds, never what it fetches. */
private object NoDownloads : Download {
    @CompositionOnly
    override fun inbound(handlers: DownloadHandlers) = Unit

    override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork) =
        StartResult.NotStarted

    override suspend fun cancelAll() = Unit
}

private object NoTarget : BackendTarget {
    override val base = "https://backend.test/api/v2"
    override val declaredVersion = ""
}

private object NoVersions : VersionObserver {
    override suspend fun observe(answer: app.snapsync.domain.trust.BackendAnswer) = Unit
}
