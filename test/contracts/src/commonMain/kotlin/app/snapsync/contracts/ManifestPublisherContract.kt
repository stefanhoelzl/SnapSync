package app.snapsync.contracts

import app.snapsync.model.DeviceManifest
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.ports.ManifestPublisher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backend states a [ManifestPublisher] clause needs. */
enum class ManifestPublisherState {
    /** An event, and a device that has joined it. */
    MEMBER,

    /** An event, and a device that never joined it. */
    NON_MEMBER,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,

    /**
     * A member whose two assets' bytes have both landed ([ManifestPublisherContract.FIRST], [ManifestPublisherContract.SECOND]),
     * so which one the union serves is decided by the manifest alone.
     */
    MEMBER_WITH_TWO_UPLOADED_ASSETS,
}

/**
 * What publishing a device's manifest promises (capability `port-contracts` — this list IS the port's
 * specification). The manifest is contribution only: it never enrolls, so a non-member's publish is refused.
 */
object ManifestPublisherContract : Contract<ManifestPublisherState, EdgeSubject<ManifestPublisher>>("ManifestPublisher") {

    /** The two assets [ManifestPublisherState.MEMBER_WITH_TWO_UPLOADED_ASSETS] holds bytes for. */
    val FIRST = SeededAsset("asset-first", listOf(ResourceRole.PRIMARY))
    val SECOND = SeededAsset("asset-second", listOf(ResourceRole.PRIMARY))

    suspend fun seed(state: ManifestPublisherState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == ManifestPublisherState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("manifest $clauseId")
        val device = setup.freshId()
        if (state == ManifestPublisherState.MEMBER || state == ManifestPublisherState.MEMBER_WITH_TWO_UPLOADED_ASSETS) {
            setup.join(event.eventId, device)
        }
        if (state == ManifestPublisherState.MEMBER_WITH_TWO_UPLOADED_ASSETS) {
            setup.upload(device, FIRST, ResourceRole.PRIMARY)
            setup.upload(device, SECOND, ResourceRole.PRIMARY)
        }
        return Seeded(event.eventId, device, event)
    }

    private fun manifest(deviceId: String) =
        DeviceManifest(deviceId, listOf(SeededAsset("asset-1", listOf(ResourceRole.PRIMARY)).manifestEntry())).encodeToJson()

    /** A manifest declaring exactly [asset], projected under manifest [version]. */
    private fun versioned(deviceId: String, asset: SeededAsset, version: Long) =
        DeviceManifest(deviceId, listOf(asset.manifestEntry()), version = version).encodeToJson()

    override val clauses = clauses {

        clause("A_MEMBER_PUBLISH_IS_ACCEPTED", ManifestPublisherState.MEMBER) { s ->
            assertTrue(s.port.publish(s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)))
        }

        clause("A_NON_MEMBER_PUBLISH_IS_REFUSED", ManifestPublisherState.NON_MEMBER) { s ->
            assertFalse(s.port.publish(s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)), "publishing never enrolls")
        }

        clause("AN_UNKNOWN_EVENT_PUBLISH_IS_REFUSED", ManifestPublisherState.NO_SUCH_EVENT) { s ->
            assertFalse(s.port.publish(s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)))
        }

        // Two processes' publishes can cross in the network (capability `device-manifest`, "A publish carries the
        // manifest version"): the one landing LAST may be the older snapshot. It is answered as a success — a
        // snapshot at least as new is already there — and changes nothing the union serves.
        clause("AN_OLDER_PUBLISH_LANDING_LAST_CHANGES_NOTHING", ManifestPublisherState.MEMBER_WITH_TWO_UPLOADED_ASSETS) { s ->
            val (event, device) = s.seeded.eventId to s.seeded.deviceId
            assertTrue(s.port.publish(event, device, versioned(device, SECOND, version = 2)))
            assertTrue(s.port.publish(event, device, versioned(device, FIRST, version = 1)), "an older publish is still answered")
            assertEquals(setOf(SECOND.assetId), s.setup.unionAssetIds(event), "the older snapshot changed nothing")
        }

        // One version names one snapshot, so an equal one is applied — which is what lets a republish of an unchanged
        // version land at all.
        clause("AN_EQUAL_VERSION_PUBLISH_IS_APPLIED", ManifestPublisherState.MEMBER_WITH_TWO_UPLOADED_ASSETS) { s ->
            val (event, device) = s.seeded.eventId to s.seeded.deviceId
            assertTrue(s.port.publish(event, device, versioned(device, SECOND, version = 2)))
            assertTrue(s.port.publish(event, device, versioned(device, FIRST, version = 2)))
            assertEquals(setOf(FIRST.assetId), s.setup.unionAssetIds(event), "the equal-version snapshot replaced it")
        }
    }
}
