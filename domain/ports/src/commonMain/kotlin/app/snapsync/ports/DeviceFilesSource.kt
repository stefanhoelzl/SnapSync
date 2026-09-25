package app.snapsync.ports

/**
 * The seam that fetches what a **device** has already stored (`GET /files/devices/<deviceId>`,
 * `bunny-list-endpoint`). Bytes are device-partitioned and event-independent, so this is the dedup
 * source the join-time load seeds the ledger's `COMPLETED` rows from (capability
 * `photo-sharing`): whatever the backend already holds for this device is not uploaded
 * again. Failures are a failed [Result] (never thrown), so a failed load can fall back to an empty
 * ledger rather than crash the join.
 */
interface DeviceFilesSource {
    suspend fun list(deviceId: String): Result<List<StoredResource>>
}

/**
 * One resource the backend holds for this device: its recomposed storage [key] and the [assetId] the
 * backend **reported** for it.
 *
 * The `assetId` travels beside the key rather than being parsed back out of it. The backend states
 * identity, so a caller seeding a ledger row takes that statement instead of recovering it from a string
 * the seam has just composed — the direction that cannot drift.
 */
data class StoredResource(val key: String, val assetId: String)

/**
 * The listing did not have the shape this build understands.
 *
 * A **permanent** failure, and that is the whole reason it has a type. A transport failure is transient
 * and is answered by deferring the cycle and retrying; a shape mismatch will never heal by retrying, so
 * collapsing the two leaves a device deferring uploads forever behind a warning that reads exactly like
 * a slow network (`docs/architecture.md`, "Absence is never silent" — "'nothing' and 'couldn't tell' are
 * different answers wherever their consequences differ").
 *
 * It is a real hazard rather than a hypothetical one: both listing shapes carry a field named `filename`
 * and mean different things by it — the storage key in one, the capture name in the other — so a lenient
 * decode accepts either and silently seeds nonsense.
 */
class DeviceListingShapeException(message: String) : Exception(message)
