package app.snapsync.download

/**
 * A submitted byte transfer. [cancel] is the **only** way to stop it — see [DownloadTransport].
 */
interface DownloadTask {
    /** Stop this transfer. The transport stays usable; other transfers are untouched. */
    fun cancel()
}

/**
 * What a finished transfer turned out to be — the facts [DownloadTransportHost.accepts] judges it on
 * (capability `photo-download`). Data only: the platform edge reads these off its response object, and
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

/**
 * The owner a [DownloadTransport] reports back to. Every call carries the opaque `description` the owner
 * tagged the transfer with at [DownloadTransport.start] — which is how a completion delivered in a *later
 * process* (a background relaunch) is re-attributed to its asset with no in-memory state to consult.
 *
 * That is also why [destinationFor] is a query rather than an argument to `start`: after a relaunch the
 * transport is handed completions for transfers it never started, so the staging path must be derivable
 * from the description alone.
 */
interface DownloadTransportHost {
    /**
     * May [description]'s bytes be staged, given how the transfer turned out? Asked **before** the bytes
     * are moved, because staging is what makes them the store's truth: a rejected body that reached
     * staging would be imported, fail, and be retried forever against the same file — the download is
     * never re-run once a resource is recorded as staged (capability `photo-download`).
     *
     * The judgement lives on this side of the seam so it is covered by `commonTest`; the transport only
     * reports the facts. Answering `false` leaves the transfer's bytes untouched, so the resource stays
     * un-staged and the next reconcile re-downloads it.
     */
    fun accepts(description: String, outcome: TransferOutcome): Boolean

    /** Where [description]'s finished bytes must be moved; `null` if the description is unattributable. */
    fun destinationFor(description: String): String?

    /** [description]'s bytes finished and were moved to durable staging at [stagedPath]. */
    fun onStaged(description: String, stagedPath: String)

    /** [description] reached a terminal state (finished, failed, or cancelled) — its slot is free. */
    fun onCompleted(description: String, error: String?)

    /**
     * The transport became permanently unusable — the *system* invalidated it (we never do; see
     * [DownloadTransport]). The owner MUST discard it; the next transfer builds a fresh one.
     */
    fun onInvalidated()

    /** The OS's background-relaunch events have all been delivered (`didFinishEventsForBackgroundURLSession`). */
    fun onBackgroundEventsFinished()
}

/**
 * The byte-transfer edge of the download client (capability `photo-download`) — on iOS a background
 * `URLSession`, in tests a fake.
 *
 * **This seam deliberately offers no way to invalidate or destroy the underlying session.** Invalidation
 * is terminal: creating a task on an invalidated `NSURLSession` throws an Objective-C `NSException`, which
 * Kotlin/Native cannot catch and which aborts the process. Since a cancel is always followed by a later
 * download (a re-join, a foreground reconcile, a push), a session destroyed as a *means of cancelling* is
 * a crash waiting for the next reconcile — exactly the crash this seam exists to make unrepresentable.
 * Cancellation is expressed only as [DownloadTask.cancel]; the session is a process-lifetime singleton
 * that outlives every leave, switch, and re-provision.
 */
interface DownloadTransport {
    /**
     * Begin fetching [url], tagging the transfer with [description]. Returns the handle, or `null` if the
     * transfer could not be started. It MUST NOT throw — an unusable [url] is the caller's to filter (it
     * does), and anything else is a `null`.
     */
    fun start(url: String, description: String): DownloadTask?
}
