package app.snapsync.ports

/**
 * The seam that fetches a **device's** already-stored object filenames (`GET /files/devices/<deviceId>`,
 * `bunny-list-endpoint`). Bytes are device-partitioned and event-independent, so this is the dedup
 * source the extension reconciler seeds `COMPLETED` from — a reinstall (empty ledger) is restored by
 * it, and it preserves dedup across an event switch. Failures are a failed [Result] (never thrown), so
 * the reconciler can defer the cycle rather than crash.
 */
interface DeviceFilesSource {
    suspend fun list(deviceId: String): Result<List<String>>
}

/**
 * The listing did not have the shape this build understands.
 *
 * A **permanent** failure, and that is the whole reason it has a type. A transport failure is transient
 * and is answered by deferring the cycle and retrying; a shape mismatch will never heal by retrying, so
 * collapsing the two leaves a device deferring uploads forever behind a warning that reads exactly like
 * a slow network (`module-architecture`, "Absence is never silent" — "'nothing' and 'couldn't tell' are
 * different answers wherever their consequences differ").
 *
 * It is a real hazard rather than a hypothetical one: both listing shapes carry a field named `filename`
 * and mean different things by it — the storage key in one, the capture name in the other — so a lenient
 * decode accepts either and silently seeds nonsense.
 */
class DeviceListingShapeException(message: String) : Exception(message)
