package app.snapsync.services.backend

import app.snapsync.model.Reply
import app.snapsync.model.StoredResource
import app.snapsync.model.UnionAsset
import app.snapsync.model.toResult
import app.snapsync.model.uploadKey

/**
 * What a **device** has already stored. Bytes are device-partitioned and event-independent, so this is the dedup
 * source the join-time load seeds the ledger's `COMPLETED` rows from (capability `photo-sharing`): whatever the
 * backend already holds for this device is not uploaded again. Failures are a failed [Result] (never thrown), so a
 * failed load can fall back to an empty ledger rather than crash the join.
 */
fun interface DeviceFilesSource {
    suspend fun list(deviceId: String): Result<List<StoredResource>>
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
 */
fun interface EventUnionSource {
    suspend fun union(eventId: String): Result<List<UnionAsset>>
}

/**
 * [DeviceFilesSource] over the backend's per-device listing, which answers in **identity terms** — `assetId`,
 * `role`, and the resource's **capture filename** — and mints no key. The key is therefore **recomposed** here
 * through the shared [uploadKey] builder, so the one definition of the storage layout stays in `model/` and this
 * service cannot invent a key the client would not have composed. The recomposition is exact even when the capture
 * name is unavailable or is itself a storage key, because only its extension is consumed.
 *
 * A listing the port could not decode ([Reply.Malformed]) is a [DeviceListingShapeException], distinguishable from a
 * transport failure. The port decodes STRICTLY for exactly this reason: see [DeviceListingShapeException].
 */
class BackendDeviceFilesSource(private val backend: AuthenticatedBackend) : DeviceFilesSource {

    override suspend fun list(deviceId: String): Result<List<StoredResource>> =
        when (val reply = backend.deviceFiles(deviceId)) {
            is Reply.Ok -> Result.success(reply.value.map { StoredResource(uploadKey(it.assetId, it.role, it.filename), it.assetId) })
            is Reply.Malformed -> Result.failure(
                DeviceListingShapeException("the per-device listing did not decode into {assetId, role, filename}: ${reply.detail}"),
            )
            else -> reply.toResult("list device $deviceId").map { emptyList() }
        }
}

/** [EventUnionSource] over the backend's public union read: any answer but a served one is a failed [Result]. */
class BackendEventUnionSource(private val backend: AuthenticatedBackend) : EventUnionSource {

    override suspend fun union(eventId: String): Result<List<UnionAsset>> =
        backend.eventFiles(eventId).toResult("union $eventId")
}
