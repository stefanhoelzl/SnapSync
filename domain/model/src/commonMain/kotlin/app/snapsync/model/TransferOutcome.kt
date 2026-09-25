package app.snapsync.model

/**
 * What a finished transfer turned out to be — the facts [DownloadTransportHost.accepts] judges it on
 * (capability `receiving-photos`). Data only: the platform edge reads these off its response object, and
 * nothing platform-shaped crosses the seam.
 *
 * @param statusCode the HTTP status, or `null` if the response carried none (should be unreachable —
 *   transfers are restricted to `http`/`https` — and is treated as unknown, not bad).
 * @param expectedBytes the response's declared length, or a negative value if it declared none.
 * @param receivedBytes the length actually delivered.
 */
data class TransferOutcome(
    val statusCode: Int?,
    val expectedBytes: Long,
    val receivedBytes: Long,
)
