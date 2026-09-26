package app.snapsync.ports

import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome

/**
 * **The platform's background downloads** (capability `receiving-photos`): on iOS a background `URLSession`, which
 * keeps downloading while the app is suspended and relaunches it when a transfer ends. One external system, deciding
 * nothing: whether a finished body may be staged, where it goes and what it means are the download feature's.
 *
 * An event port. What the operating system reports arrives through the [DownloadHandlers] a composition registers
 * once — including for transfers a **previous process** started, which a relaunched process is handed with no memory
 * of them; the [start]ing tag is how each is attributed.
 *
 * **It offers no way to destroy its session.** Creating a task on an invalidated `NSURLSession` raises an
 * Objective-C exception Kotlin/Native cannot catch, so a session destroyed as a means of cancelling would be a crash
 * waiting for the next reconcile. A session the *system* invalidates is reported ([DownloadHandlers.onInvalidated])
 * and rebuilt by the adapter on the next [start].
 */
interface Download : Listenable<DownloadHandlers> {

    /**
     * Begin fetching [url], tagging the transfer with [tag] — the one field the platform keeps across a relaunch. Never
     * throws: an unusable [url] is the caller's to filter (it does), and anything else is [StartResult.NotStarted],
     * which converges with a started-then-failed transfer (the resource stays pending until the next reconcile).
     */
    fun start(url: String, tag: String): StartResult

    /**
     * Cancel **every** transfer the session holds when called — this process's and any a relaunched process inherited —
     * and return once each is cancelled. A transfer started after it returns is not affected. Each cancelled transfer
     * still reports [DownloadHandlers.onCompleted], with an error.
     */
    suspend fun cancelAll()
}

/** What the platform tells the core about its downloads. Built only by a composition. */
class DownloadHandlers(
    /**
     * [tag]'s body finished arriving, with the [facts] to judge it by, at [tempPath] — a file the platform deletes when
     * this returns. **Inline**: whatever keeps the bytes (a move to staging) happens before returning; a store write it
     * causes may be launched, and is joined before the wake's handler is released.
     */
    val onFinished: (tag: String, facts: TransferOutcome, tempPath: String) -> Unit,
    /** [tag]'s transfer ended — finished, failed or cancelled; [error] describes a failure. Its slot is free. */
    val onCompleted: (tag: String, error: String?) -> Unit,
    /** The **system** invalidated the session (never the app): the next [Download.start] runs on a fresh one. */
    val onInvalidated: () -> Unit,
    /**
     * The operating system relaunched (or woke) the app to deliver this session's events, handing [completion] — held
     * by the core across the wake's own work (staging what is delivered) and released after the drain report.
     */
    val onBackgroundEvents: (completion: Completion) -> Unit,
    /** The session delivered every event it held for the relaunch (`urlSessionDidFinishEvents`). */
    val onEventsDrained: () -> Unit,
)
