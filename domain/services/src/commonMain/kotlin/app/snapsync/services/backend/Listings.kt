package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.model.StoredResource
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionResource
import app.snapsync.model.UnionTrigger
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.uploadKey
import app.snapsync.wire.api.DeviceFilesReply
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.Routes

/**
 * What a **device** has already stored **in one event**. Each event holds its own bytes (change
 * `per-event-storage-layout`), so this is the dedup source the join-time load seeds the ledger's `COMPLETED` rows
 * from: whatever the backend already holds for this device in this event is not
 * uploaded again — and nothing it holds for another event is mistaken for this one's. Failures are a failed
 * [Result] (never thrown), so a failed load can fall back to an empty ledger rather than crash the join.
 */
fun interface DeviceFilesSource {
    suspend fun list(eventId: EventId, deviceId: DeviceId): Result<List<StoredResource>>
}

/**
 * The listing did not have the shape this build understands.
 *
 * A **permanent** failure, and that is the whole reason it has a type. A transport failure is transient and is
 * answered by deferring the cycle and retrying; a shape mismatch will never heal by retrying, so collapsing the two
 * leaves a device deferring uploads forever behind a warning that reads exactly like a slow network
 * (`docs/architecture.md`, "Absence is never silent" — "'nothing' and 'couldn't tell' are different answers
 * wherever their consequences differ").
 *
 * It is a real hazard rather than a hypothetical one: both listing shapes carry a field named `filename` and mean
 * different things by it — the storage key in one, the capture name in the other — so a lenient decode accepts
 * either and silently seeds nonsense.
 */
class DeviceListingShapeException(message: String) : Exception(message)

/**
 * The event-wide union: every contributing device's **complete** assets, each tagged with its `deviceId` and
 * carrying per-resource download `url`s. Failures surface as a failed [Result] (never thrown) so the download
 * controller can keep its last good state rather than crash. Own-vs-foreign selection is the caller's concern.
 *
 * Read from a [cursor] it answers only what was gained since, all of it for `null` (decision record
 * `changes/incremental-union`, D3); [trigger] says why, and the backend records it.
 */
fun interface EventUnionSource {
    suspend fun union(eventId: EventId, cursor: Long?, trigger: UnionTrigger): Result<UnionPage>
}

/**
 * [DeviceFilesSource] over the backend's per-device listing, which answers in **identity terms** — `assetId`,
 * `role`, and the resource's **capture filename** — and mints no key. The key is therefore **recomposed** here
 * through the shared [uploadKey] builder, so the one definition of the storage layout stays in `model/` and this
 * service cannot invent a key the client would not have composed. The recomposition is exact even when the capture
 * name is unavailable or is itself a storage key, because only its extension is consumed.
 *
 * A listing that decodes but names what this build cannot read is a [DeviceListingShapeException], distinguishable
 * from a transport failure. Decoding is STRICT for exactly this reason (`StoredResource`'s every field is required,
 * its role a closed vocabulary): see [DeviceListingShapeException]. An answer that does not decode at all
 * ([Malformed]) is no answer — most often a page a Wi-Fi network or a proxy put in the backend's place — and fails
 * like an unreachable one, retried, never reported as a permanent fault (decision record
 * `changes/tell-service-faults`, D2).
 */
class BackendDeviceFilesSource(private val gateway: BackendGateway) : DeviceFilesSource {

    override suspend fun list(eventId: EventId, deviceId: DeviceId): Result<List<StoredResource>> =
        when (val reply = gateway.send(Routes.DeviceFiles.request(eventId.value, deviceId.value))) {
            is DeviceFilesReply.Listed -> runCatchingCancellable {
                // A non-canonical id fails the constructor: a listing this build cannot read, never an invented row.
                reply.items.map {
                    val assetId = requireNotNull(AssetId.parse(it.assetId)) { "not a canonical asset id" }
                    StoredResource(uploadKey(assetId, it.role.inApp(), it.filename), assetId)
                }
            }.recoverCatching {
                throw DeviceListingShapeException(
                    "the per-device listing named a non-canonical asset: $it",
                )
            }
            is Malformed -> Result.failure(
                IllegalStateException(
                    "list device $deviceId in $eventId: an answer this build cannot read (${reply.detail})",
                ),
            )
            else -> Result.failure(IllegalStateException("list device $deviceId in $eventId: $reply"))
        }
}

/**
 * [EventUnionSource] over the backend's union read: any answer but a [EventFilesReply.Page] is a failed [Result].
 *
 * It reads with `urls=false` and builds each resource's download address itself (decision record
 * `changes/incremental-union`, D1): the route `downloadResource` on this build's backend, which redirects to the bytes.
 */
class BackendEventUnionSource(private val gateway: BackendGateway) : EventUnionSource {

    override suspend fun union(eventId: EventId, cursor: Long?, trigger: UnionTrigger): Result<UnionPage> {
        val request = Routes.EventFiles.request(
            eventId.value,
            cursor?.toString(),
            urls = false,
            snapSyncTrigger = trigger.wire,
        )
        val what = "union $eventId (${trigger.wire}, from ${cursor ?: "start"})"
        return when (val reply = gateway.send(request)) {
            // A non-canonical asset id, or a position that is not a number, is a page the app could not continue from.
            is EventFilesReply.Page -> runCatchingCancellable {
                UnionPage(
                    reply.items.map { asset(eventId, it) },
                    reply.snapSyncCursor.toLong(),
                    // A value that is no count is the absence of one: the screen reads as it did before the header.
                    reply.snapSyncDeclared?.toLongOrNull(),
                )
            }
            else -> Result.failure(IllegalStateException("$what: $reply"))
        }
    }

    private fun asset(eventId: EventId, dto: app.snapsync.wire.models.UnionAsset): UnionAsset {
        val assetId = requireNotNull(AssetId.parse(dto.assetId)) { "not a canonical asset id" }
        // A malformed id refuses the asset, as a malformed asset id always has.
        val deviceId = requireNotNull(DeviceId.parse(dto.deviceId)) { "not a device id: '${dto.deviceId}'" }
        return UnionAsset(
            deviceId = deviceId,
            assetId = assetId,
            creationDate = dto.creationDate,
            resources = dto.resources.map {
                val path = Routes.DownloadResource.path(eventId.value, dto.deviceId, assetId.value, it.role.value)
                UnionResource(it.key, gateway.open.url(path), it.role.value, it.contentType, it.filename)
            },
        )
    }
}
