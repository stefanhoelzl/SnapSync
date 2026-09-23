package app.snapsync.contracts

import app.snapsync.model.ResourceRole
import app.snapsync.ports.EventUnionSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The backend states an [EventUnionSource] clause needs. */
enum class EventUnionSourceState {
    /** An id no event was ever created under. */
    NO_SUCH_EVENT,

    /** An event no member has contributed to. */
    EMPTY_EVENT,

    /** A member declared one single-resource asset and uploaded its bytes. */
    COMPLETE_ASSET,

    /** A member declared a two-resource asset and uploaded only one of them. */
    INCOMPLETE_ASSET,
}

/**
 * What the event-wide union promises (capability `port-contracts` — this list IS the port's specification):
 * the read every download is planned from. An asset appears only once every resource it declares has landed,
 * because a half-present asset would be imported as a broken photo.
 */
object EventUnionSourceContract : Contract<EventUnionSourceState, EdgeSubject<EventUnionSource>>("EventUnionSource") {

    suspend fun seed(state: EventUnionSourceState, clauseId: String, setup: EdgeSetup): Seeded {
        if (state == EventUnionSourceState.NO_SUCH_EVENT) return Seeded(eventId = setup.freshId(), deviceId = setup.freshId())
        val event = setup.createEvent("union $clauseId")
        val device = setup.freshId()
        val asset = when (state) {
            EventUnionSourceState.COMPLETE_ASSET -> SeededAsset("complete-1", listOf(ResourceRole.PRIMARY))
            EventUnionSourceState.INCOMPLETE_ASSET -> SeededAsset("incomplete-1", listOf(ResourceRole.PRIMARY, ResourceRole.LIVE))
            else -> null
        }
        if (asset != null) {
            setup.join(event.eventId, device)
            setup.publish(event.eventId, device, listOf(asset))
            setup.upload(device, asset, ResourceRole.PRIMARY)
        }
        return Seeded(event.eventId, device, event, asset = asset)
    }

    override val clauses = clauses {

        clause("AN_UNKNOWN_EVENT_IS_A_FAILURE", EventUnionSourceState.NO_SUCH_EVENT) { s ->
            assertTrue(s.port.union(s.seeded.eventId).isFailure, "absent is a failure, never an empty union")
        }

        clause("AN_EMPTY_EVENT_IS_AN_EMPTY_UNION", EventUnionSourceState.EMPTY_EVENT) { s ->
            assertEquals(emptyList(), s.port.union(s.seeded.eventId).getOrThrow())
        }

        clause("A_COMPLETE_ASSET_IS_LISTED_UNDER_ITS_DEVICE", EventUnionSourceState.COMPLETE_ASSET) { s ->
            val asset = s.seeded.asset!!
            val listed = s.port.union(s.seeded.eventId).getOrThrow().single()
            assertEquals(s.seeded.deviceId, listed.deviceId)
            assertEquals(asset.assetId, listed.assetId)
            assertEquals(asset.creationDate, listed.creationDate)
            val resource = listed.resources.single()
            assertEquals(ResourceRole.PRIMARY.wire, resource.role)
            assertEquals(asset.key(ResourceRole.PRIMARY), resource.key, "the key the uploader stored it under")
            assertEquals(asset.filename, resource.originalFilename)
            assertEquals("image/jpeg", resource.contentType)
            assertTrue(resource.url.isNotBlank(), "a fetchable url")
        }

        clause("AN_INCOMPLETE_ASSET_IS_OMITTED", EventUnionSourceState.INCOMPLETE_ASSET) { s ->
            assertEquals(emptyList(), s.port.union(s.seeded.eventId).getOrThrow(), "never half a photo")
        }
    }
}
