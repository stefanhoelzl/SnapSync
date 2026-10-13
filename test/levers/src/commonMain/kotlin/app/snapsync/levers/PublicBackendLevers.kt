package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The backend through its public surface** — the device API's routes, as another member or the operator's seeding
 * reaches them, credential free. Because nothing here is the mock's, it holds against the real `api/` too; each method
 * names the operator members it stands in for ([StandsInFor]).
 *
 * An omitted `event` is the joined event and an omitted `device` this device ([JoinedEvent], [ThisDevice]); any other
 * omitted argument is `null`, as [BackendLevers] says.
 */
@Rpc
interface PublicBackendLevers {
    /** The event's union: every member's complete assets, with the roles each is served with. */
    @StandsInFor("unionOf")
    suspend fun union(@JoinedEvent event: String? = null): List<UnionEntry>

    /** What the backend serves for [event] — whether it holds it at all, its name, and the `closedAt` it still serves. */
    @StandsInFor("isRegistered", "eventNameOf")
    suspend fun event(@JoinedEvent event: String? = null): EventRecord

    /** The object keys the backend lists for [device] in [event], sorted. */
    @StandsInFor("objectsOf")
    suspend fun objects(@ThisDevice device: String? = null, @JoinedEvent event: String? = null): List<String>

    /**
     * A fellow member, [device], with complete photos [assets] (asset ids) in [event] — the joined one, or one the
     * backend mints when the device is joined to none; answers the event. [kind] [PhotoKind.MOTION_PHOTO] makes each a
     * real Google motion photo, as an Android member's camera shares it (omitted: a placeholder photo); [filename] is
     * the capturing device's own name for every asset. [final] `true` declares the member's share settled; [uploading]
     * (a subset of [assets]) are declared but their bytes not sent — still on that member's phone; a later call without
     * them lands them.
     */
    @StandsInFor("seedMember", "deposit")
    suspend fun foreignDevice(
        device: String,
        assets: List<String>,
        event: String? = null,
        kind: PhotoKind? = null,
        filename: String? = null,
        final: Boolean? = null,
        uploading: List<String>? = null,
    ): String

    /** A fellow member leaving [event], as any build's leave reaches the backend. */
    suspend fun foreignDeviceLeave(device: String, @JoinedEvent event: String? = null)

    /**
     * A fellow member saying it holds every photo of the others up to where [event]'s union stands now — what a current
     * build reports once its downloads are in its library.
     */
    suspend fun foreignDeviceReceived(device: String, @JoinedEvent event: String? = null)

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<PublicBackendLevers>(
        "public-backend",
        setOf(LeverNeed.BACKEND_REACHABLE),
        { serviceDescriptorOf() },
    )
}

/** One asset of an event's union: whose it is, and the roles it is served with. */
@Serializable
data class UnionEntry(val device: String, val asset: String, val roles: List<String>)

/** What the backend serves for an event: whether it holds it, its name, and the `closedAt` an older build acts on. */
@Serializable
data class EventRecord(val registered: Boolean, val name: String?, val closedAt: String?)

/** What a fellow member's photo is. */
@Serializable
enum class PhotoKind {
    /** A placeholder photo. */
    @SerialName("photo")
    PHOTO,

    /** A real Google motion photo. */
    @SerialName("motion-photo")
    MOTION_PHOTO,
}
