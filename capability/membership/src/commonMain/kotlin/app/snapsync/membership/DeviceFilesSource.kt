package app.snapsync.membership

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
