package app.snapsync.data.sharing

import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.DeviceManifest
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.testids.testDeviceId

// The device manifest's test values: a manifest of no rows, as the record and the gateway take it.

/** [device]'s manifest of no photos, under [version], declaring [declaration]. */
internal fun emptyManifest(
    version: Long = 1,
    declaration: ShareDeclaration = ShareDeclaration.Sharing,
    device: DeviceId = testDeviceId("D"),
): DeviceManifest = DeviceManifest.of(device, emptyList(), emptyList(), ManifestVersion(version), declaration)

/** Record that this device declared [assets] photos to [event] — what the union's netting reads. */
internal fun ManifestRecord.declared(event: EventId, assets: Int, version: Long = 1): StoreWrite {
    val manifest = emptyManifest(version)
    return record(event, manifest.version, manifest.declaration, assets, ManifestBody.of(manifest))
}
