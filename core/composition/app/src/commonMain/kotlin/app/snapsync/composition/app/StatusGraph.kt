package app.snapsync.composition.app

import app.snapsync.application.mobiledata.SetMobileData
import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.network.NetworkStatus
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.store.ChangedElsewhere
import app.snapsync.entrypoints.ForegroundNetwork
import app.snapsync.ports.NetworkMonitor
import app.snapsync.query.mobiledata.MobileDataLookup
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.mobiledata.MobileDataStatuses
import app.snapsync.query.network.NetworkStatusLookup
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.query.sharing.SharingProgressQuery
import app.snapsync.scheduling.GatedWatch
import app.snapsync.scheduling.Runtime

/**
 * The joined screen's status (`docs/patterns.md`): the member's admitted photos and the use case that counts them, the
 * sharing progress followed from the shared database, and what another process's commit is announced on.
 */
class StatusGraph(
    services: ServicesGraph,
    receiving: ReceivingGraph,
    ownPhotos: OwnPhotos,
    foreground: AppForeground,
    uploader: UploaderGraph,
    runtime: Runtime,
) {
    /** The total `N`, as last counted or walked: the upload's own, which every walk updates. */
    val admitted: AdmittedPhotos = uploader.layers.admitted

    /** The count of the total, a step of the opening, a join, a settings change and a selection change. */
    val count: CountOwnPhotos = CountOwnPhotos(services.membership, receiving.received, ownPhotos, admitted)

    /** How far the member's photos are shared. */
    val sharing: SharingProgressQuery = SharingProgressQuery(
        foreground,
        services.sharedDatabase.changes,
        services.membership,
        uploader.progress,
        admitted,
        runtime.scope,
    )

    /** The app's stores, as the `Databases` port names them when the upload extension committed to one. */
    val changedElsewhere: ChangedElsewhere = ChangedElsewhere(services.sharedDatabase, receiving.file)
}

/** The mobile-data choice's layers: the change the menu's switch runs, the status it is answered on, and the read. */
class MobileDataGraph(services: ServicesGraph) {
    val statuses: MobileDataStatuses = MobileDataStatuses()
    val set: SetMobileData = SetMobileData(services.mobileData)
    val query: MobileDataQuery = MobileDataLookup(services.mobileData, statuses)
}

/**
 * The app's network watch — the app says when it cannot reach the network — over the [NetworkMonitor] port: what it
 * tells, the foreground's watch the lifecycle's entry points start and stop, and the screen's read of it.
 */
class NetworkGraph(runtime: Runtime, monitor: NetworkMonitor) {
    /** What the member is told about the network, and when a missing one returns. */
    val status: NetworkStatus = NetworkStatus()

    /** Followed only while the app is in front — the platform's monitor runs only then. */
    val foreground: ForegroundNetwork = ForegroundNetwork(GatedWatch(runtime), NetworkReadings(monitor), status)

    /** The screen's read of [status]. */
    val query: NetworkStatusQuery = NetworkStatusLookup(status)
}
