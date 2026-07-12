package app.snapsync.download

/**
 * A submitted byte transfer. [cancel] is the **only** way to stop it — see [DownloadTransport].
 */
interface DownloadTask {
    /** Stop this transfer. The transport stays usable; other transfers are untouched. */
    fun cancel()
}

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
