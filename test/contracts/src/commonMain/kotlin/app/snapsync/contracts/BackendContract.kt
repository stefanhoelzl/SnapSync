package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ResourceRole

/** The backend states a [BackendContract] clause needs, each entered through the backend's public surface. */
enum class BackendState {
    /** A backend serving this build, and fresh ids nothing was ever created under. */
    SERVING,

    /** An event the backend created, and a device that has not joined it. */
    EVENT_EXISTS,

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

    /** An event, and a device that joined it whose upload of one resource ended short — part of it kept. */
    DEVICE_UPLOAD_BROKE_OFF,

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

    /** No backend at the address: nothing listens there, so no request completes. */
    NO_BACKEND,

    /**
     * A server that answers every route with success and bytes no backend version this build speaks would send — a
     * wire fixture, standing in for a backend of another version; what is judged is only what the client's decoding
     * makes of the bytes (`docs/testing.md`, "Hosts").
     */
    UNREADABLE_SUCCESS,

    /** A server whose store or upstream fails every request — `502`, a wire fixture standing in for the failure. */
    SERVER_FAILING,

    /** A deploy's migration window: every request is answered `503 maintenance` with a `Retry-After`. */
    IN_MAINTENANCE,

    /**
     * An event that ended for good — completed by the sweep, or closed before events stopped closing — whose every write
     * is answered `410 closed`: a wire fixture, since the sweep has no route and nothing closes any more.
     */
    ENDED_FOR_GOOD,

    /**
     * An event closed before events stopped closing, whose member's changed asset set is answered `409 closed` — a wire
     * fixture, since nothing closes an event any more (`PENDING_CLEANUPS.md`).
     */
    CLOSED_BEFORE_OPEN_UNTIL,

    /**
     * A backend that accepts the binding's attestations, and a key the binding can attest and sign with
     * ([EdgeSubject.attester]): a software Android key for the real `api/` (the local rig's `any` policy), the in-memory
     * integrity's proofs for the backend mock.
     */
    ATTESTABLE,
}

/**
 * What the backend promises the app (`docs/testing.md`; the backend boundary: `docs/architecture.md`): each operation's
 * outcomes, as `api/openapi.yaml` names them and the app's generated replies read them, through the production
 * gateway. An outcome is part of the promise wherever a service above the gateway decides on it — `EventNotFound`
 * "gone", `Full`, `Unauthorized` "rejected credential", `AppTooOld` "refused build", `InvalidName` "refused input".
 * Each outcome a clause sees is a grid cell (`Backend.<operationId> → <Outcome>`, or `Backend.* → <Outcome>` for one
 * shared by several operations), recorded by the [BackendClient].
 *
 * The clauses are split by area for size only (the parts beside this file); every binding runs all of them.
 */
object BackendContract : Contract<BackendState, EdgeSubject<BackendClient>>("Backend") {

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
        BackendState.SERVING,
        BackendState.NO_SUCH_EVENT,
        BackendState.NO_BACKEND,
        BackendState.UNREADABLE_SUCCESS,
        BackendState.SERVER_FAILING,
        BackendState.IN_MAINTENANCE,
        BackendState.ENDED_FOR_GOOD,
        BackendState.CLOSED_BEFORE_OPEN_UNTIL,
        BackendState.ATTESTABLE,
        -> Seeded(
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
            BackendState.DEVICE_UPLOADED, BackendState.DEVICE_UPLOAD_BROKE_OFF -> STORED
            else -> null
        }
        when (state) {
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
            BackendState.DEVICE_UPLOAD_BROKE_OFF -> {
                setup.join(event.eventId, device)
                setup.uploadPart(event.eventId, device, asset!!, ResourceRole.PRIMARY)
            }
            else -> Unit
        }
        return Seeded(event.eventId, device, event, identity, asset)
    }

    override val clauses = clauses {
        eventClauses()
        changeClauses()
        membershipClauses()
        listingClauses()
        uploadStateClauses()
        attestClauses()
        completionClauses()
        transportClauses()
    }
}
