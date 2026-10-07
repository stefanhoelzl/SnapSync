package app.snapsync.compose

import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.model.AppFacts
import app.snapsync.model.Fact
import app.snapsync.model.asFact
import app.snapsync.model.inMegabytes
import app.snapsync.services.device.DeviceConditionsReadings
import app.snapsync.services.network.NetworkReadings

/**
 * The diagnostic dump assembly (capability `privacy-security`) — reads only: this graph's state and the device's,
 * never written to. Built on first use by [AppCore.collectDiagnosticDump], so an unconfigured build (which never fires
 * the command) never builds it.
 *
 * Its own file rather than `AppCore`'s body for the reason [AppNetwork] gives: `AppCore` is measured, and the `compose`
 * tier's `LargeClass` ceiling is what keeps it from absorbing every composition in the graph.
 */
internal fun AppCore.diagnosticDumpFor(): CollectDiagnosticDump = CollectDiagnosticDump(
    environment = ports.process.build.diagnostics,
    logs = services.deviceLogs,
    ledger = services.ledger,
    downloads = services.downloadStore,
    config = services.config,
    permission = galleryAccess,
    network = NetworkReadings(ports.network),
    mobileData = services.mobileData,
    conditions = DeviceConditionsReadings(ports.deviceConditions),
    appFacts = {
        AppFacts(
            // Never minted: a report must not create the identity it reports.
            deviceId = services.deviceIdentity.current().asFact(),
            timeZone = Fact.Known(ports.process.clock.timeZone().id),
            memoryFootprintMb = ports.processInfo.memoryFootprint().inMegabytes(),
            selectionPhotos = selectionPhotosNow(),
        )
    },
    uploadFacts = {
        mapOf(
            "extension_registrable" to extensionRegistrableNow().toString(),
            "app_admission" to appUploadAdmission().name,
        )
    },
    refusalFacts = { attestation.refusalFacts() },
)
