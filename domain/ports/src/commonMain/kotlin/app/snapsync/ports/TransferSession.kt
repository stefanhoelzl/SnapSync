package app.snapsync.ports

/**
 * The app's background transfer session — a background `URLSession`, Android's in-process uploads — which sends a
 * file (or, where [takesResources], a library resource) to a destination and tells the app when each transfer ended.
 * Thin: what an end means for the ledger, and what is staged for it, are the upload services'.
 */
interface TransferSession : Port, Listenable<TransferHandlers> {
    /** Whether [create] also takes a library resource directly; otherwise it sends files only. */
    val takesResources: Boolean

    /** Start a transfer of [source] to [target], tagged [tag] — the tag every later report carries. */
    suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome

    /** The transfers still in flight, a relaunch's inherited ones included. */
    suspend fun live(): List<Transfer>

    /** Stop [transfer]; its end is reported as any other. */
    suspend fun cancel(transfer: Transfer)
}

/** The bytes a transfer sends. */
sealed interface TransferSource {
    /**
     * A file at the platform [path], sent from byte [from] on: a resumed upload sends the file's rest
     * (`docs/architecture.md`, "Resumable uploads"), however the platform manages to send part of a file.
     */
    class File(val path: String, val from: Long = 0) : TransferSource

    /** A library resource's own bytes. */
    class Resource(val handle: GalleryResourceHandle) : TransferSource
}

/** One transfer in flight: the [tag] it was made with, where the platform kept one. */
class Transfer(val handle: TransferHandle, val tag: String?)

/** The platform's own transfer, opaque above the adapter that reported it. */
interface TransferHandle

/** What the session tells the app. Each runs on the session's delivering thread and must return promptly. */
class TransferHandlers(
    /**
     * A transfer ended — once, so it is recorded inline, before this returns. [tag] is what it was made with (an end
     * the platform tells without one is the adapter's to report and drop); [failure] is `null` for a success.
     */
    val onFinished: (tag: String, failure: TransferFailure?) -> Unit,
    /** The OS relaunched the app for the session's events; [completion] is released when they are drained. */
    val onBackgroundEvents: (completion: Completion) -> Unit,
    /** Every event the OS held for the session has been delivered. */
    val onEventsDrained: () -> Unit,
)
