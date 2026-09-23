package app.snapsync.contracts

import app.snapsync.model.DeviceManifest
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.ports.ManifestPublisher
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
}

/**
 * What publishing a device's manifest promises (capability `port-contracts` — this list IS the port's
 * specification). The manifest is contribution only: it never enrolls, so a non-member's publish is refused.
 */
object ManifestPublisherContract : Contract<ManifestPublisherState, EdgeSubject<ManifestPublisher>>("ManifestPublisher") {

    suspend fun seed(state: ManifestPublisherState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == ManifestPublisherState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("manifest $clauseId")
        val device = setup.freshId()
        if (state == ManifestPublisherState.MEMBER) setup.join(event.eventId, device)
        return Seeded(event.eventId, device, event)
    }

    private fun manifest(deviceId: String) =
        DeviceManifest(deviceId, listOf(SeededAsset("asset-1", listOf(ResourceRole.PRIMARY)).manifestEntry())).encodeToJson()

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
    }
}
