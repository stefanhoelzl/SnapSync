package app.snapsync.ios.upload

import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger

/**
 * The device target's binding: the real PhotoKit upload-job queue, exactly as before this seam existed
 * (capability `ios-photokit-upload`).
 *
 * Every shipped binary — TestFlight, App Store, and every sideloaded dev build — compiles this actual and
 * only this one. The simulator substitute is not merely unused here; it is absent from the binary.
 */
actual fun uploadJobQueue(
    log: Logger,
    discovery: IosDiscovery,
    ledger: LedgerStore,
): BackgroundTransfer = IosPhotoKitUploadPlatform(log, discovery, ledger)
