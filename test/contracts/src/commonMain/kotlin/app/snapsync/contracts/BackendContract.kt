package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ResourceRole
import app.snapsync.ports.Backend

/** The backend states a [BackendContract] clause needs, each entered through the backend's public surface. */
enum class BackendState {
    /** A backend serving this build, and fresh ids nothing was ever created under. */
    SERVING,

    /** An event the backend created, and a device that has not joined it. */
    EVENT_EXISTS,

    /** An event every seat of which is taken. */
    EVENT_FULL,

    /** An id no event was ever created under. */
    NO_SUCH_EVENT,

    /** An event, and a device that has joined it. */
    MEMBER,

    /**
     * A member whose two assets' bytes have both landed ([BackendContract.FIRST], [BackendContract.SECOND]), so which
     * one the union serves is decided by the manifest alone.
     */
    MEMBER_WITH_TWO_UPLOADED_ASSETS,

    /** An event with one member, who published one asset and uploaded every resource it declares. */
    UNION_COMPLETE_ASSET,

    /** An event with one member, who published one asset and uploaded only some of its resources. */
    UNION_INCOMPLETE_ASSET,

    /** An event, and a device that joined it and uploaded one resource into it. */
    DEVICE_UPLOADED,

    /** An event that exists, and a build older than the backend's minimum. */
    VERSION_REFUSED,

    /** An event that exists, and a credential the backend never issued. */
    FOREIGN_TOKEN,

    /** An event whose range has ENDED, and the one device that joined it — nobody has settled yet. */
    ENDED_MEMBER,

    /**
     * An event whose range has ENDED, the device that joined it and has not settled, and a second member that has
     * settled — so the event waits for the device alone.
     */
    ENDED_BESIDE_A_SETTLED_MEMBER,
}

/**
 * What the backend promises the app (`docs/testing.md` — this list IS the `Backend` port's specification): each
 * route's answers, in the backend's own vocabulary. A status is part of the promise wherever a service above the
 * port decides on it — `404` "gone", `409` "full", `401` "rejected credential", `426` "refused build", `400`
 * "refused input" — and nowhere else: a refusal the app only needs to tell from success is asserted as a refusal.
 *
 * The clauses are split by route area for size only (the parts beside this file); every binding runs all of them.
 */
object BackendContract : Contract<BackendState, EdgeSubject<Backend>>("Backend") {

    /** The two assets [BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS] holds bytes for. */
    val FIRST = SeededAsset(AssetId("asset-first"), listOf(ResourceRole.PRIMARY))
    val SECOND = SeededAsset(AssetId("asset-second"), listOf(ResourceRole.PRIMARY))

    /** The asset [BackendState.UNION_COMPLETE_ASSET] publishes, every resource uploaded. */
    val COMPLETE = SeededAsset(AssetId("complete-1"), listOf(ResourceRole.PRIMARY))

    /** The asset [BackendState.UNION_INCOMPLETE_ASSET] publishes, only its primary uploaded. */
    val INCOMPLETE = SeededAsset(AssetId("incomplete-1"), listOf(ResourceRole.PRIMARY, ResourceRole.LIVE))

    /** The resource [BackendState.DEVICE_UPLOADED] holds. */
    val STORED = SeededAsset(AssetId("stored-1"), listOf(ResourceRole.PRIMARY))

    /** Enters [state] on the backend [setup] drives. Bindings call exactly this. */
    suspend fun seed(state: BackendState, clauseId: String, setup: BackendSetup): Seeded = when (state) {
        BackendState.SERVING, BackendState.NO_SUCH_EVENT -> Seeded(
            eventId = setup.freshId(),
            deviceId = setup.freshId(),
        )
        else -> seedEvent(state, clauseId, setup)
    }

    private suspend fun seedEvent(state: BackendState, clauseId: String, setup: BackendSetup): Seeded {
        val ended = state == BackendState.ENDED_MEMBER || state == BackendState.ENDED_BESIDE_A_SETTLED_MEMBER
        val event = setup.createEvent("contract $clauseId", ended = ended)
        val device = setup.freshId()
        val identity = when (state) {
            BackendState.VERSION_REFUSED -> ClientIdentity(REFUSED_APP_VERSION, token = null)
            BackendState.FOREIGN_TOKEN -> ClientIdentity(SERVED_APP_VERSION, FOREIGN_TOKEN)
            else -> ClientIdentity.SERVED
        }
        val asset = when (state) {
            BackendState.UNION_COMPLETE_ASSET -> COMPLETE
            BackendState.UNION_INCOMPLETE_ASSET -> INCOMPLETE
            BackendState.DEVICE_UPLOADED -> STORED
            else -> null
        }
        when (state) {
            BackendState.EVENT_FULL -> setup.fillToCapacity(event.eventId)
            BackendState.MEMBER, BackendState.ENDED_MEMBER -> setup.join(event.eventId, device)
            BackendState.ENDED_BESIDE_A_SETTLED_MEMBER -> {
                setup.join(event.eventId, device)
                val settled = setup.freshId()
                setup.join(event.eventId, settled)
                setup.publish(event.eventId, settled, emptyList(), final = true)
            }
            BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS -> {
                setup.join(event.eventId, device)
                setup.upload(event.eventId, device, FIRST, ResourceRole.PRIMARY)
                setup.upload(event.eventId, device, SECOND, ResourceRole.PRIMARY)
            }
            BackendState.UNION_COMPLETE_ASSET, BackendState.UNION_INCOMPLETE_ASSET -> {
                setup.join(event.eventId, device)
                setup.publish(event.eventId, device, listOfNotNull(asset))
                setup.upload(event.eventId, device, asset!!, ResourceRole.PRIMARY)
            }
            BackendState.DEVICE_UPLOADED -> {
                setup.join(event.eventId, device)
                setup.upload(event.eventId, device, asset!!, ResourceRole.PRIMARY)
            }
            else -> Unit
        }
        return Seeded(event.eventId, device, event, identity, asset)
    }

    override val clauses = clauses {
        eventClauses()
        membershipClauses()
        listingClauses()
        attestClauses()
        completionClauses()
    }
}
