package app.snapsync.contracts

import app.snapsync.model.ResourceRole
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.model.StoredResource
import kotlin.test.assertEquals

/** The backend states a [DeviceFilesSource] clause needs. */
enum class DeviceFilesSourceState {
    /** A device that has uploaded nothing. */
    NO_UPLOADS,

    /** A device that has uploaded one resource. */
    UPLOADED,
}

/**
 * What the per-device listing promises (`docs/architecture.md` — this list IS the port's specification):
 * the read a join-time share-set load and the extension's reconcile settle uploads against. A key that does not
 * match what the uploader stored under re-uploads the library.
 */
object DeviceFilesSourceContract : Contract<DeviceFilesSourceState, EdgeSubject<DeviceFilesSource>>("DeviceFilesSource") {

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(state: DeviceFilesSourceState, clauseId: String, setup: EdgeSetup): Seeded {
        val device = setup.freshId()
        val asset = SeededAsset("stored-1", listOf(ResourceRole.PRIMARY)).takeIf { state == DeviceFilesSourceState.UPLOADED }
        asset?.let { setup.upload(device, it, ResourceRole.PRIMARY) }
        return Seeded(eventId = setup.freshId(), deviceId = device, asset = asset)
    }

    override val clauses = clauses {

        clause("A_FRESH_DEVICE_HOLDS_NOTHING", DeviceFilesSourceState.NO_UPLOADS) { s ->
            assertEquals(emptyList(), s.port.list(s.seeded.deviceId).getOrThrow())
        }

        clause("AN_UPLOAD_IS_LISTED_UNDER_ITS_KEY", DeviceFilesSourceState.UPLOADED) { s ->
            val asset = s.seeded.asset!!
            assertEquals(
                listOf(StoredResource(asset.key(ResourceRole.PRIMARY), asset.assetId)),
                s.port.list(s.seeded.deviceId).getOrThrow(),
            )
        }
    }
}
